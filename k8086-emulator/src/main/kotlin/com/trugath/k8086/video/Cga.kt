package com.trugath.k8086.video

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.chipset.XtCharScanCodes
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.REG_CS

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.Cursor
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

// IBM CGA adapter: an MC6845 CRTC plus the CGA mode/color/status registers,
// decoded at 0x3D0-0x3DF. The 5155's built-in display is CGA.
//
//   0x3D4 index / 0x3D5 data : 6845 CRTC register file
//   0x3D8 mode control       : text/graphics, 40/80 cols, video enable, blink
//   0x3D9 color select        : border/background/palette
//   0x3DA status              : bit0 = display-enable off (h/v blank), bit3 = vsync
//
// POST spins on 0x3DA waiting for retrace transitions, so a free-running "beam"
// (advanced by CPU time) drives those two bits. Text mode is rendered from the
// 80x25 buffer at 0xB8000 using the full IBM CGA 8x8 character ROM (CP437).
internal class Cga(
    private val cpu: Emulator8086,
    private val showWindow: Boolean = true,
    /** When true (single-instance CLI), closing the window exits the JVM. */
    private val exitOnClose: Boolean = true,
) : IoDevice {

    /**
     * Optional host toolbar actions (Ctrl+Alt+Del, change floppies). Set by [Machine]
     * before the window is created.
     */
    data class HostControls(
        val floppyDriveCount: Int = 0,
        val onCtrlAltDelete: () -> Unit = {},
        /** Swap image for drive 0–3; [path] null ejects. */
        val onChangeFloppy: (drive: Int, path: String?) -> Unit = { _, _ -> },
        /** Current image path for tooltips, or null if empty. */
        val floppyPath: (drive: Int) -> String? = { null },
        val onToggleAudio: (() -> Unit)? = null,
        val isAudioMuted: (() -> Boolean)? = null,
        val onTogglePause: (() -> Unit)? = null,
        val isPaused: (() -> Boolean)? = null,
        val onToggleTurbo: (() -> Unit)? = null,
        val isTurbo: (() -> Boolean)? = null,
    )

    var hostControls: HostControls? = null

    /**
     * When false, [tickCpuCycles] advances timing but skips composing/presenting
     * frames (turbo). [copyFramebuffer] still composes on demand for host consoles.
     */
    @Volatile
    var presentEnabled: Boolean = true

    private val crtc = IntArray(32)
    private var crtcIndex = 0
    private var modeControl = 0
    private var colorSelect = 0

    // Composite monitor emulation (New CGA NTSC artifact color).
    // AUTO: on for mode 6 with color burst enabled (DOSBox Staging default).
    // ON: force for any graphics mode. OFF: always RGBI.
    var compositeMode: CgaComposite.Mode = CgaComposite.Mode.AUTO
        set(value) {
            field = value
            onVideoConfigChanged()
        }
    var hueOffsetDeg: Double = 0.0
        set(value) {
            field = value
            rebuildCompositeLuts()
        }
    private var compositeLuts = CgaComposite.rebuild(0, 0)
    private var compositeActiveCached = false

    // Free-running position within a frame, measured in CPU cycles.
    private var frameCycle = 0
    private var framesRendered = 0L

    private var window: JFrame? = null
    private var panel: CgaPanel? = null
    private var image: BufferedImage? = null
    /** Steady EDT paint so live-resize cannot starve frame updates. */
    private var refreshTimer: Timer? = null
    /** Composite colour-mix toggle; visible only in APA graphics modes. */
    private var compositeButton: JButton? = null

    // Set by the machine to receive XT scan codes (make code, then break = make|0x80)
    // for host key presses on the display window.
    var onKeyScanCode: ((Int) -> Unit)? = null
    /** Relative mouse when the CGA window has pointer grab (bit0=left, bit1=right). */
    var onMouseEvent: ((dx: Int, dy: Int, buttons: Int) -> Unit)? = null

    override fun ioReadByte(port: Int): Int = when (port) {
        0x3DA, 0x3DE -> statusRegister()
        0x3DB, 0x3DC -> 0xFF // light-pen strobes: no state
        else -> if (port in 0x3D0..0x3D7 && (port and 1) == 1) {
            crtc[crtcIndex and 0x1F] and 0xFF // CRTC data read-back
        } else 0xFF
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val v = value and 0xFF
        when (port) {
            0x3D8 -> {
                modeControl = v
                onVideoConfigChanged()
            }
            0x3D9 -> {
                colorSelect = v
                onVideoConfigChanged()
            }
            0x3DB, 0x3DC -> { /* light-pen strobes */ }
            else -> if (port in 0x3D0..0x3D7) {
                if ((port and 1) == 0) crtcIndex = v else crtc[crtcIndex and 0x1F] = v
            }
        }
    }

    private fun onVideoConfigChanged() {
        rebuildCompositeLuts()
        compositeActiveCached = computeCompositeActive()
        syncBdaTextRows()
        refreshCompositeButton()
    }

    private fun rebuildCompositeLuts() {
        compositeLuts = CgaComposite.rebuild(colorSelect, modeControl, hueOffsetDeg)
    }

    private fun colorBurstEnabled(): Boolean = (modeControl and 0x04) == 0

    private fun computeCompositeActive(): Boolean = when (compositeMode) {
        CgaComposite.Mode.OFF -> false
        CgaComposite.Mode.ON -> graphicsMode()
        CgaComposite.Mode.AUTO -> graphicsMode() && highResGraphics() && colorBurstEnabled()
    }

    fun isCompositeActive(): Boolean = compositeActiveCached

    /** True in CGA APA graphics (modes 4/5/6). */
    fun isGraphicsMode(): Boolean = graphicsMode()

    /**
     * Enable/disable NTSC composite colour mixing.
     * [enabled]=true forces ON (artefact colours); false forces RGBI OFF.
     */
    fun setCompositeEnabled(enabled: Boolean) {
        compositeMode = if (enabled) CgaComposite.Mode.ON else CgaComposite.Mode.OFF
        onVideoConfigChanged()
        renderFrame()
        updateWindowTitle()
        refreshCompositeButton()
    }

    /** Cycle AUTO → ON → OFF → AUTO (F12). */
    fun cycleCompositeMode() {
        compositeMode = when (compositeMode) {
            CgaComposite.Mode.AUTO -> CgaComposite.Mode.ON
            CgaComposite.Mode.ON -> CgaComposite.Mode.OFF
            CgaComposite.Mode.OFF -> CgaComposite.Mode.AUTO
        }
        onVideoConfigChanged()
        renderFrame()
        updateWindowTitle()
        refreshCompositeButton()
    }

    /** Nudge NTSC hue / tint dial by [delta] degrees (F11 / Shift+F11). */
    fun adjustHue(delta: Double) {
        hueOffsetDeg += delta
        renderFrame()
        updateWindowTitle()
    }

    // Many DOS games leave the adapter in APA graphics mode when they terminate.
    // COMMAND.COM then writes character/attribute cells into what is still being
    // scanned as pixels (CLS looks like static). Call this from the DOS-terminate
    // path to put the screen back to 80x25 colour text the same way MODE CO80 /
    // INT 10h AH=00 AL=03 would.
    fun restoreTextModeIfGraphics() {
        if (!graphicsMode()) return
        // Standard IBM CGA 80x25 CRTC register file (mode 3), matching what the 5160
        // BIOS programs on INT 10h set-mode.
        val regs = intArrayOf(
            0x71, 0x50, 0x5A, 0x0A, 0x1F, 0x06, 0x19, 0x1C,
            0x02, 0x07, 0x06, 0x07, 0x00, 0x00, 0x00, 0x00,
        )
        for (i in regs.indices) crtc[i] = regs[i]
        // 0x29: 80-col + video-enable + blink (bit0|bit3|bit5), graphics bit clear.
        modeControl = 0x29
        colorSelect = 0x07
        onVideoConfigChanged()

        // BIOS data area video state (segment 0040).
        cpu.writePhysByte(0x449, 0x03)                         // current mode
        cpu.writePhysByte(0x44A, 80); cpu.writePhysByte(0x44B, 0) // columns
        cpu.writePhysByte(0x44C, 0x00); cpu.writePhysByte(0x44D, 0x10) // page size 0x1000
        cpu.writePhysByte(0x44E, 0); cpu.writePhysByte(0x44F, 0) // page start
        for (i in 0 until 16) cpu.writePhysByte(0x450 + i, 0)  // cursor pos per page
        cpu.writePhysByte(0x460, 0x07); cpu.writePhysByte(0x461, 0x06) // cursor shape
        cpu.writePhysByte(0x462, 0)                            // active page
        cpu.writePhysByte(0x463, 0xD4); cpu.writePhysByte(0x464, 0x03) // CRTC base 0x3D4
        cpu.writePhysByte(0x465, modeControl)                 // current mode-select reg
        cpu.writePhysByte(0x466, colorSelect)                 // current color-select reg
        syncBdaTextRows(force = true)
        for (i in 0 until 80 * 25) {
            cpu.writePhysByte(0xB8000 + i * 2, 0x20)
            cpu.writePhysByte(0xB8000 + i * 2 + 1, 0x07)
        }
        renderFrame()
    }

    /**
     * XT BIOS never writes BDA 0040:0084 (rows−1). Some DOS shells use that byte as
     * MAX_Y with no zero-fallback, so DIR /P pauses after every line when it is left
     * at 0.
     *
     * Stamp 24 at most once after leaving BIOS (or on [force]). CheckIt's base-memory
     * test writes patterns through the BDA — including a deliberate 00h at 000484h —
     * so a per-frame "heal zeros" path fails bits 3–4 (00h → 18h).
     */
    private var bdaRowsStamped = false

    private fun syncBdaTextRows(force: Boolean = false) {
        if (graphicsMode()) return
        // Leave the 5160 BIOS alone during POST.
        if (!force && cpu.getReg16(REG_CS) >= 0xF000) return
        if (force) {
            cpu.writePhysByte(0x484, 24)
            bdaRowsStamped = true
            return
        }
        if (bdaRowsStamped) return
        if (cpu.readPhysByte(0x484) != 0) {
            bdaRowsStamped = true
            return
        }
        cpu.writePhysByte(0x484, 24)
        bdaRowsStamped = true
    }

    /** Allow [syncBdaTextRows] to run again after a warm boot clears BDA state. */
    fun allowBdaRowsRestamp() {
        bdaRowsStamped = false
    }

    // CGA status register (0x3DA). Bit 0 is set during horizontal/vertical blanking
    // (safe-to-access-VRAM), bit 3 during vertical sync.
    private fun statusRegister(): Int {
        val line = frameCycle / CYCLES_PER_LINE
        val lineCycle = frameCycle % CYCLES_PER_LINE
        var s = 0
        if (lineCycle >= ACTIVE_CYCLES_PER_LINE || line >= ACTIVE_LINES) s = s or 0x01
        if (line in VSYNC_START until (VSYNC_START + VSYNC_LINES)) s = s or 0x08
        return s
    }

    // Advance the CRT beam with elapsed CPU time; render one frame per vsync.
    fun tickCpuCycles(cpuCycles: Int) {
        var next = frameCycle + cpuCycles
        if (next >= CYCLES_PER_FRAME) {
            do {
                framesRendered++
                // POST may leave BDA 40:84 at 0; stamp rows−1 once so DIR /P works.
                syncBdaTextRows()
                // Full-rate presents when enabled; in turbo (presentEnabled=false) still
                // refresh sparsely so a Swing window is created and stays visible.
                if (presentEnabled || (showWindow && framesRendered % 30L == 0L)) {
                    renderFrame()
                }
                next -= CYCLES_PER_FRAME
            } while (next >= CYCLES_PER_FRAME)
        }
        frameCycle = next
    }

    private fun textColumns(): Int = if ((modeControl and 0x01) != 0) 80 else 40
    private fun videoEnabled(): Boolean = (modeControl and 0x08) != 0
    private fun graphicsMode(): Boolean = (modeControl and 0x02) != 0
    private fun highResGraphics(): Boolean = (modeControl and 0x10) != 0
    /** Mode-control bit 5: attribute bit 7 blinks instead of selecting bright background. */
    private fun blinkEnabled(): Boolean = (modeControl and 0x20) != 0

    /** CGA blink ~1.875 Hz (16 frames on / 16 off at 60 Hz). */
    private fun blinkVisible(): Boolean = ((framesRendered / 16) and 1L) == 0L

    fun renderFrame() {
        // Mutate the backing image on the emulator thread; the EDT refresh timer blits it.
        composeFrame()
    }

    // Composes the current video RAM into the backing image (text or graphics). Works
    // headless so tests can snapshot the framebuffer; the Swing window, when present, is
    // (re)created lazily by ensureImage as the active resolution changes.
    private fun composeFrame(): BufferedImage? {
        val composite = computeCompositeActive().also { compositeActiveCached = it }
        val (w, h) = when {
            graphicsMode() && highResGraphics() -> 640 to 200
            graphicsMode() -> 320 to 200
            else -> textColumns() * 8 to 200
        }
        val img = ensureImage(w, h)
        when {
            graphicsMode() && composite && highResGraphics() -> renderCompositeMode6(img)
            graphicsMode() && composite -> renderCompositeMode4(img)
            graphicsMode() -> renderGraphics(img)
            else -> renderText(img)
        }
        return img
    }

    private fun renderText(img: BufferedImage) {
        val cols = img.width / 8
        val base = 0xB8000
        for (row in 0 until 25) {
            for (col in 0 until cols) {
                val cell = base + (row * cols + col) * 2
                val ch = cpu.readPhysByte(cell)
                val attr = cpu.readPhysByte(cell + 1)
                drawGlyph(img, col * 8, row * 8, ch, attr)
            }
        }
    }

    // Mode 6 composite: each 4-pixel nibble is one NTSC color clock → one artifact color,
    // drawn 4 pixels wide so the framebuffer stays 640×200.
    private fun renderCompositeMode6(img: BufferedImage) {
        val lut = compositeLuts.mode6
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            for (byteX in 0 until 80) {
                val b = cpu.readPhysByte(rowBase + byteX)
                val c0 = lut[(b shr 4) and 0x0F]
                val c1 = lut[b and 0x0F]
                val x0 = byteX * 8
                for (i in 0 until 4) img.setRGB(x0 + i, y, c0)
                for (i in 0 until 4) img.setRGB(x0 + 4 + i, y, c1)
            }
        }
    }

    // Mode 4/5 composite: each pair of 2-bit pixels forms one color clock → one artifact
    // color, drawn 2 pixels wide (320×200).
    private fun renderCompositeMode4(img: BufferedImage) {
        val lut = compositeLuts.mode4
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            for (byteX in 0 until 80) {
                val b = cpu.readPhysByte(rowBase + byteX)
                // Four 2-bit pixels → two color clocks: (p0,p1) and (p2,p3).
                val p0 = (b shr 6) and 3
                val p1 = (b shr 4) and 3
                val p2 = (b shr 2) and 3
                val p3 = b and 3
                val c0 = lut[(p0 shl 2) or p1]
                val c1 = lut[(p2 shl 2) or p3]
                val x0 = byteX * 4
                img.setRGB(x0, y, c0)
                img.setRGB(x0 + 1, y, c0)
                img.setRGB(x0 + 2, y, c1)
                img.setRGB(x0 + 3, y, c1)
            }
        }
    }

    // CGA all-points-addressable graphics (RGBI). Scanlines are interleaved between two
    // 8 KB banks (even lines at 0xB8000, odd at 0xBA000); mode 4/5 pack four 2-bit pixels
    // per byte (320x200), mode 6 packs eight 1-bit pixels per byte (640x200).
    private fun renderGraphics(img: BufferedImage) {
        val hi = highResGraphics()
        val colors = graphicsPalette()
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            if (hi) {
                for (x in 0 until 640) {
                    val b = cpu.readPhysByte(rowBase + (x shr 3))
                    val on = (b shr (7 - (x and 7))) and 1
                    img.setRGB(x, y, if (on != 0) colors[1] else colors[0])
                }
            } else {
                for (x in 0 until 320) {
                    val b = cpu.readPhysByte(rowBase + (x shr 2))
                    val px = (b shr (6 - 2 * (x and 3))) and 3
                    img.setRGB(x, y, colors[px])
                }
            }
        }
    }

    // The four displayable colors in mode 4/5, selected by the 0x3D9 color-select
    // register: colour 0 is the background, colours 1-3 come from one of two palettes
    // (green/red/brown or cyan/magenta/white) at low or high intensity.
    private fun graphicsPalette(): IntArray {
        val bg = CGA_PALETTE[colorSelect and 0x0F]
        if (highResGraphics()) return intArrayOf(CGA_PALETTE[0], bg)
        val intensity = if ((colorSelect and 0x10) != 0) 8 else 0
        return if ((colorSelect and 0x20) != 0) {
            intArrayOf(bg, CGA_PALETTE[3 + intensity], CGA_PALETTE[5 + intensity], CGA_PALETTE[7 + intensity])
        } else {
            intArrayOf(bg, CGA_PALETTE[2 + intensity], CGA_PALETTE[4 + intensity], CGA_PALETTE[6 + intensity])
        }
    }

    private fun drawGlyph(img: BufferedImage, x: Int, y: Int, ch: Int, attr: Int) {
        // Bit7 = blink when mode-control bit5 set; otherwise bright background (bits 4–7).
        val bgIndex = if (blinkEnabled()) (attr shr 4) and 0x07 else (attr shr 4) and 0x0F
        val bg = CGA_PALETTE[bgIndex]
        val hideFg = blinkEnabled() && (attr and 0x80) != 0 && !blinkVisible()
        val fg = if (hideFg) bg else CGA_PALETTE[attr and 0x0F]
        for (yy in 0 until 8) {
            val bits = CgaFont.row(ch, yy)
            for (xx in 0 until 8) {
                val on = (bits and (0x80 shr xx)) != 0
                img.setRGB(x + xx, y + yy, if (on) fg else bg)
            }
        }
    }

    // Composes the current frame and writes it as a PNG (used by tests/tools).
    fun writeFramePng(file: java.io.File) {
        val img = composeFrame() ?: return
        javax.imageio.ImageIO.write(img, "png", file)
    }

    private fun ensureImage(w: Int, h: Int): BufferedImage {
        var img = image
        if (img == null || img.width != w || img.height != h) {
            img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            image = img
            if (showWindow && !GraphicsEnvironment.isHeadless()) createWindow(img)
        }
        return img
    }

    private fun createWindow(img: BufferedImage) {
        SwingUtilities.invokeLater {
            if (window == null) {
                val p = CgaPanel(img)
                panel = p
                p.isFocusable = true
                // Keep Tab as a guest scancode (do not focus-traverse onto Ctrl+Alt+Del).
                p.focusTraversalKeysEnabled = false
                p.addKeyListener(object : KeyAdapter() {
                    private val pressedScanCodes = HashSet<Int>()
                    override fun keyPressed(e: KeyEvent) {
                        when (e.keyCode) {
                            KeyEvent.VK_ESCAPE -> {
                                if (mouseGrabbed) {
                                    setMouseGrabbed(p, false)
                                    suppressEscBreak = true
                                    return
                                }
                            }
                            KeyEvent.VK_F12 -> {
                                cycleCompositeMode()
                                return
                            }
                            KeyEvent.VK_F11 -> {
                                adjustHue(if (e.isShiftDown) -5.0 else 5.0)
                                return
                            }
                        }
                        val sc = scanCodeFor(e) ?: return
                        if (!pressedScanCodes.add(sc)) return
                        onKeyScanCode?.invoke(sc)
                    }
                    override fun keyReleased(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_F11 || e.keyCode == KeyEvent.VK_F12) return
                        if (e.keyCode == KeyEvent.VK_ESCAPE && suppressEscBreak) {
                            suppressEscBreak = false
                            return
                        }
                        val sc = scanCodeFor(e) ?: return
                        if (!pressedScanCodes.remove(sc)) return
                        onKeyScanCode?.invoke(sc or 0x80)
                    }
                })
                p.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (!mouseGrabbed) {
                            if (SwingUtilities.isRightMouseButton(e)) {
                                pasteClipboard(p)
                                return
                            }
                            if (SwingUtilities.isLeftMouseButton(e)) {
                                setMouseGrabbed(p, true)
                                lastMouseX = e.x
                                lastMouseY = e.y
                            }
                            return
                        }
                        emitMouse(0, 0, mouseButtonsFrom(e.modifiersEx))
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        if (!mouseGrabbed) return
                        emitMouse(0, 0, mouseButtonsFrom(e.modifiersEx))
                    }
                })
                p.addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseDragged(e: MouseEvent) = mouseMoved(e)
                    override fun mouseMoved(e: MouseEvent) {
                        if (!mouseGrabbed) return
                        val dx = e.x - lastMouseX
                        val dy = e.y - lastMouseY
                        lastMouseX = e.x
                        lastMouseY = e.y
                        if (dx == 0 && dy == 0) return
                        // Swing Y grows down; Microsoft serial mouse Y grows up.
                        emitMouse(dx, -dy, mouseButtonsFrom(e.modifiersEx))
                    }
                })
                p.toolTipText = "Click to grab mouse (Esc release); right-click pastes when ungrabbed"
                val toolbar = buildToolbar(p)
                val root = JPanel(BorderLayout()).apply {
                    add(p, BorderLayout.CENTER)
                    add(toolbar, BorderLayout.SOUTH)
                }
                window = JFrame(windowTitle()).apply {
                    defaultCloseOperation = if (exitOnClose) {
                        WindowConstants.EXIT_ON_CLOSE
                    } else {
                        WindowConstants.DISPOSE_ON_CLOSE
                    }
                    contentPane = root
                    isResizable = true
                    // Size from the video panel (toolbar must not widen the frame).
                    packToVideoAspect(this, p, toolbar)
                    // Prefer a fixed corner — setLocationRelativeTo(null) can place the
                    // frame under a maximized IDE on large/multi-head desktops.
                    setLocation(64, 64)
                    isAlwaysOnTop = true
                    isVisible = true
                    // Insets are reliable after the peer exists; re-apply size once shown.
                    packToVideoAspect(this, p, toolbar)
                    toFront()
                    // Drop always-on-top after first show so it doesn't steal focus forever.
                    Timer(1500) { isAlwaysOnTop = false }.apply {
                        isRepeats = false
                        start()
                    }
                    addWindowListener(object : WindowAdapter() {
                        override fun windowClosed(e: WindowEvent?) {
                            refreshTimer?.stop()
                            refreshTimer = null
                        }
                    })
                }
                // Paint on a fixed cadence so Windows live-resize cannot freeze updates.
                refreshTimer?.stop()
                refreshTimer = Timer(16) { p.repaint() }.also { it.start() }
                p.requestFocusInWindow()
            } else {
                // Keep the user's window size; only swap the backing image.
                panel?.setImage(img)
                updateWindowTitle()
            }
        }
    }

    private var mouseGrabbed = false
    private var suppressEscBreak = false
    private var lastMouseX = 0
    private var lastMouseY = 0

    private fun setMouseGrabbed(panel: JPanel, grabbed: Boolean) {
        mouseGrabbed = grabbed
        panel.cursor = if (grabbed) blankCursor else Cursor.getDefaultCursor()
        panel.toolTipText = if (grabbed) {
            "Mouse grabbed — Esc to release"
        } else {
            "Click to grab mouse (Esc release); right-click pastes when ungrabbed"
        }
        if (grabbed) panel.requestFocusInWindow()
    }

    private val blankCursor: Cursor by lazy {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), "blank")
    }

    private fun emitMouse(dx: Int, dy: Int, buttons: Int) {
        onMouseEvent?.invoke(dx, dy, buttons)
    }

    private fun mouseButtonsFrom(modifiersEx: Int): Int {
        var b = 0
        if ((modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) != 0) b = b or 1
        if ((modifiersEx and MouseEvent.BUTTON3_DOWN_MASK) != 0) b = b or 2
        return b
    }

    /** Initial size: video preferred size + toolbar height, not toolbar width. */
    private fun packToVideoAspect(frame: JFrame, video: CgaPanel, toolbar: JPanel) {
        frame.pack()
        val videoPref = video.preferredSize
        val toolbarH = toolbar.preferredSize.height
        val insets = frame.insets
        val w = videoPref.width + insets.left + insets.right
        val h = videoPref.height + toolbarH + insets.top + insets.bottom
        frame.setSize(w, h)
        frame.minimumSize = Dimension(
            (videoPref.width / 4).coerceAtLeast(160) + insets.left + insets.right,
            (videoPref.height / 4).coerceAtLeast(100) + toolbarH + insets.top + insets.bottom,
        )
    }
    private fun buildToolbar(focusTarget: JPanel): JPanel {
        val controls = hostControls
        val bar = JPanel(BorderLayout())
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        val cad = JButton("Ctrl+Alt+Del").apply {
            isFocusable = false
            isFocusPainted = false
            toolTipText = "Send Ctrl+Alt+Delete to the emulated PC"
            addActionListener {
                controls?.onCtrlAltDelete?.invoke()
                focusTarget.requestFocusInWindow()
            }
        }
        left.add(cad)
        val driveCount = controls?.floppyDriveCount ?: 0
        for (drive in 0 until driveCount) {
            val label = "${'A' + drive}:"
            val btn = JButton("Change $label").apply {
                isFocusable = false
                isFocusPainted = false
                toolTipText = floppyTooltip(drive, controls)
                addActionListener {
                    promptChangeFloppy(drive, this, controls)
                    focusTarget.requestFocusInWindow()
                }
            }
            left.add(btn)
        }
        bar.add(left, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4))
        val mixBtn = JButton().apply {
            isFocusable = false
            isFocusPainted = false
            addActionListener {
                // Toggle colour mixing: active → OFF, inactive → ON.
                setCompositeEnabled(!isCompositeActive())
                focusTarget.requestFocusInWindow()
            }
        }
        compositeButton = mixBtn
        right.add(mixBtn)
        refreshCompositeButton()
        if (controls?.onTogglePause != null) {
            val pauseBtn = JButton().apply {
                isFocusable = false
                isFocusPainted = false
                fun refresh() {
                    val paused = controls.isPaused?.invoke() == true
                    text = if (paused) PLAY_LABEL else PAUSE_LABEL
                    toolTipText = if (paused) "Resume" else "Pause"
                }
                refresh()
                addActionListener {
                    controls.onTogglePause.invoke()
                    refresh()
                    focusTarget.requestFocusInWindow()
                }
            }
            right.add(pauseBtn)
        }
        if (controls?.onToggleTurbo != null) {
            val turboBtn = JButton().apply {
                isFocusable = false
                isFocusPainted = false
                fun refresh() {
                    val on = controls.isTurbo?.invoke() == true
                    text = TURBO_LABEL
                    toolTipText = if (on) "Turbo on (click for realtime)" else "Turbo off (click for max speed)"
                    foreground = if (on) java.awt.Color(0xC06000) else javax.swing.UIManager.getColor("Button.foreground")
                }
                refresh()
                addActionListener {
                    controls.onToggleTurbo.invoke()
                    refresh()
                    focusTarget.requestFocusInWindow()
                }
            }
            right.add(turboBtn)
        }
        if (controls?.onToggleAudio != null) {
            val audioBtn = JButton().apply {
                isFocusable = false
                isFocusPainted = false
                fun refresh() {
                    val muted = controls.isAudioMuted?.invoke() == true
                    text = if (muted) AUDIO_MUTED_LABEL else AUDIO_ON_LABEL
                    toolTipText = if (muted) "Unmute audio" else "Mute audio"
                }
                refresh()
                addActionListener {
                    controls.onToggleAudio.invoke()
                    refresh()
                    focusTarget.requestFocusInWindow()
                }
            }
            right.add(audioBtn)
        }
        if (right.componentCount > 0) {
            bar.add(right, BorderLayout.EAST)
        }
        return bar
    }

    private fun floppyTooltip(drive: Int, controls: HostControls?): String {
        val path = controls?.floppyPath?.invoke(drive)
        return if (path != null) "Mounted: $path" else "No disk in ${'A' + drive}:"
    }

    private fun promptChangeFloppy(drive: Int, button: JButton, controls: HostControls?) {
        if (controls == null) return
        val letter = "${'A' + drive}:"
        val options = arrayOf("Insert image…", "Eject", "Cancel")
        val choice = JOptionPane.showOptionDialog(
            window,
            "Floppy $letter",
            "Change floppy",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0],
        )
        when (choice) {
            0 -> {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Insert floppy image ($letter)"
                    fileFilter = FileNameExtensionFilter(
                        "Disk images (*.img, *.ima, *.dsk)",
                        "img", "ima", "dsk",
                    )
                    isAcceptAllFileFilterUsed = true
                    controls.floppyPath(drive)?.let { current ->
                        val f = File(current)
                        if (f.parentFile?.isDirectory == true) currentDirectory = f.parentFile
                        if (f.exists()) selectedFile = f
                    }
                }
                if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
                    val path = chooser.selectedFile?.absolutePath ?: return
                    controls.onChangeFloppy(drive, path)
                    button.toolTipText = floppyTooltip(drive, controls)
                }
            }
            1 -> {
                controls.onChangeFloppy(drive, null)
                button.toolTipText = floppyTooltip(drive, controls)
            }
        }
    }

    private fun windowTitle(): String {
        val comp = when (compositeMode) {
            CgaComposite.Mode.AUTO -> if (compositeActiveCached) "composite:auto" else "rgb:auto"
            CgaComposite.Mode.ON -> "composite:on"
            CgaComposite.Mode.OFF -> "rgb:off"
        }
        val hue = if (hueOffsetDeg == 0.0) "" else " hue=${hueOffsetDeg.toInt()}"
        return "k8086 - IBM 5155 ($comp$hue)  [F12 composite · F11 hue]"
    }

    private fun updateWindowTitle() {
        val w = window ?: return
        SwingUtilities.invokeLater { w.title = windowTitle() }
    }

    private fun refreshCompositeButton() {
        val btn = compositeButton ?: return
        SwingUtilities.invokeLater {
            val graphics = graphicsMode()
            btn.isVisible = graphics
            if (!graphics) return@invokeLater
            val on = compositeActiveCached
            btn.text = if (on) COMPOSITE_ON_LABEL else COMPOSITE_OFF_LABEL
            btn.toolTipText = if (on) {
                "Composite colour mixing on (click for RGBI)"
            } else {
                "Composite colour mixing off (click for NTSC artefact colours)"
            }
            btn.foreground = if (on) {
                java.awt.Color(0xC06000)
            } else {
                javax.swing.UIManager.getColor("Button.foreground")
            }
        }
    }

    private fun pasteClipboard(focusTarget: JPanel) {
        focusTarget.requestFocusInWindow()
        val emit = onKeyScanCode ?: return
        try {
            val clip = Toolkit.getDefaultToolkit().systemClipboard
            if (!clip.isDataFlavorAvailable(DataFlavor.stringFlavor)) return
            val text = clip.getData(DataFlavor.stringFlavor) as? String ?: return
            if (text.isEmpty()) return
            // Pace injection so the guest INT 16 buffer (15 chars) is not flooded.
            val codes = ArrayList<Int>(text.length * 4)
            XtCharScanCodes.paste(text, codes::add)
            if (codes.isEmpty()) return
            var i = 0
            val t = Timer(8) {
                if (i >= codes.size) {
                    (it.source as Timer).stop()
                    return@Timer
                }
                // Emit one make/break pair per tick when possible.
                emit(codes[i++])
                if (i < codes.size) emit(codes[i++])
            }
            t.start()
        } catch (_: Exception) {
            // Clipboard busy / unsupported flavor — ignore.
        }
    }

    // Map a Swing key event to an IBM XT (scan-code set 1) make code, or null if the
    // key is not represented in the set.
    private fun scanCodeFor(e: KeyEvent): Int? = when (e.keyCode) {
        KeyEvent.VK_ESCAPE -> 0x01
        KeyEvent.VK_1 -> 0x02; KeyEvent.VK_2 -> 0x03; KeyEvent.VK_3 -> 0x04; KeyEvent.VK_4 -> 0x05
        KeyEvent.VK_5 -> 0x06; KeyEvent.VK_6 -> 0x07; KeyEvent.VK_7 -> 0x08; KeyEvent.VK_8 -> 0x09
        KeyEvent.VK_9 -> 0x0A; KeyEvent.VK_0 -> 0x0B; KeyEvent.VK_MINUS -> 0x0C; KeyEvent.VK_EQUALS -> 0x0D
        KeyEvent.VK_BACK_SPACE -> 0x0E; KeyEvent.VK_TAB -> 0x0F
        KeyEvent.VK_Q -> 0x10; KeyEvent.VK_W -> 0x11; KeyEvent.VK_E -> 0x12; KeyEvent.VK_R -> 0x13
        KeyEvent.VK_T -> 0x14; KeyEvent.VK_Y -> 0x15; KeyEvent.VK_U -> 0x16; KeyEvent.VK_I -> 0x17
        KeyEvent.VK_O -> 0x18; KeyEvent.VK_P -> 0x19; KeyEvent.VK_OPEN_BRACKET -> 0x1A; KeyEvent.VK_CLOSE_BRACKET -> 0x1B
        KeyEvent.VK_ENTER -> 0x1C; KeyEvent.VK_CONTROL -> 0x1D
        KeyEvent.VK_A -> 0x1E; KeyEvent.VK_S -> 0x1F; KeyEvent.VK_D -> 0x20; KeyEvent.VK_F -> 0x21
        KeyEvent.VK_G -> 0x22; KeyEvent.VK_H -> 0x23; KeyEvent.VK_J -> 0x24; KeyEvent.VK_K -> 0x25
        KeyEvent.VK_L -> 0x26; KeyEvent.VK_SEMICOLON -> 0x27; KeyEvent.VK_QUOTE -> 0x28; KeyEvent.VK_BACK_QUOTE -> 0x29
        KeyEvent.VK_SHIFT -> if (e.keyLocation == KeyEvent.KEY_LOCATION_RIGHT) 0x36 else 0x2A
        KeyEvent.VK_BACK_SLASH -> 0x2B
        KeyEvent.VK_Z -> 0x2C; KeyEvent.VK_X -> 0x2D; KeyEvent.VK_C -> 0x2E; KeyEvent.VK_V -> 0x2F
        KeyEvent.VK_B -> 0x30; KeyEvent.VK_N -> 0x31; KeyEvent.VK_M -> 0x32; KeyEvent.VK_COMMA -> 0x33
        KeyEvent.VK_PERIOD -> 0x34; KeyEvent.VK_SLASH -> 0x35
        KeyEvent.VK_ALT -> 0x38; KeyEvent.VK_SPACE -> 0x39; KeyEvent.VK_CAPS_LOCK -> 0x3A
        KeyEvent.VK_F1 -> 0x3B; KeyEvent.VK_F2 -> 0x3C; KeyEvent.VK_F3 -> 0x3D; KeyEvent.VK_F4 -> 0x3E
        KeyEvent.VK_F5 -> 0x3F; KeyEvent.VK_F6 -> 0x40; KeyEvent.VK_F7 -> 0x41; KeyEvent.VK_F8 -> 0x42
        KeyEvent.VK_F9 -> 0x43; KeyEvent.VK_F10 -> 0x44
        KeyEvent.VK_HOME -> 0x47; KeyEvent.VK_UP -> 0x48; KeyEvent.VK_PAGE_UP -> 0x49
        KeyEvent.VK_LEFT -> 0x4B; KeyEvent.VK_RIGHT -> 0x4D; KeyEvent.VK_END -> 0x4F
        KeyEvent.VK_DOWN -> 0x50; KeyEvent.VK_PAGE_DOWN -> 0x51; KeyEvent.VK_INSERT -> 0x52; KeyEvent.VK_DELETE -> 0x53
        else -> null
    }

    /**
     * Snapshot of the current composed frame as packed ARGB ints (row-major),
     * or null if nothing has been rendered yet. Safe to call from another thread
     * for host console bridging (copies pixel data).
     */
    @Synchronized
    fun copyFramebuffer(): FramebufferSnapshot? {
        val img = composeFrame() ?: return null
        val w = img.width
        val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        return FramebufferSnapshot(
            width = w,
            height = h,
            argb = pixels,
            graphicsMode = graphicsMode(),
            compositeMode = compositeMode,
            compositeActive = compositeActiveCached,
        )
    }

    fun disposeWindow() {
        SwingUtilities.invokeLater {
            refreshTimer?.stop()
            refreshTimer = null
            window?.dispose()
            window = null
            panel = null
        }
    }

    // Test/inspection helpers.
    fun crtcRegister(i: Int): Int = crtc[i and 0x1F]
    fun modeControlValue(): Int = modeControl
    fun colorSelectValue(): Int = colorSelect
    fun framesRenderedCount(): Long = framesRendered

    // Renders the CGA framebuffer with CRT pixel-aspect correction.
    // All 200-line CGA modes fill the same monitor: 320-wide pixels are
    // double-wide vs 640-wide, so mode 4/40-col share the 640×200 aspect.
    private class CgaPanel(private var img: BufferedImage) : JPanel() {
        private val zoom = 2

        /**
         * Corrected display width/height. Always the CGA CRT aspect — not
         * raw framebuffer width/height (that makes mode 4 look squished).
         */
        fun displayAspect(): Double = CGA_DISPLAY_ASPECT

        private fun preferredDisplaySize(): Dimension {
            val h = ACTIVE_LINES * zoom * PIXEL_ASPECT_Y
            val w = (h * CGA_DISPLAY_ASPECT).toInt()
            return Dimension(w, h)
        }

        init {
            preferredSize = preferredDisplaySize()
            isOpaque = true
            background = Color.BLACK
        }

        fun setImage(newImg: BufferedImage) {
            img = newImg
            preferredSize = preferredDisplaySize()
            // Do not revalidate/pack — that would reset a user-resized window.
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create()
            try {
                if (g2 is java.awt.Graphics2D) {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                    )
                }
                g2.color = Color.BLACK
                g2.fillRect(0, 0, width, height)
                val aspect = displayAspect()
                var drawW = width
                var drawH = (drawW / aspect).toInt()
                if (drawH > height) {
                    drawH = height
                    drawW = (drawH * aspect).toInt()
                }
                if (drawW < 1 || drawH < 1) return
                val x = (width - drawW) / 2
                val y = (height - drawH) / 2
                g2.drawImage(img, x, y, drawW, drawH, null)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        // CGA timing in 4.77 MHz CPU cycles. 80-col character clock is CPU/3, so
        // one scanline is (HTOTAL+1)*8/3 ≈ 114*8/3 = 304. Use an integer number of
        // lines per frame so DE edges do not drift within the line.
        const val CYCLES_PER_LINE = 304
        const val LINES_PER_FRAME = 262
        const val CYCLES_PER_FRAME = CYCLES_PER_LINE * LINES_PER_FRAME // 79648 ≈ 59.9 Hz
        // Display-enable active width ≈ HDISP chars (80) * 8/3 ≈ 213; rest is
        // border + HBlank (safe for snow-wait VRAM access when bit0 is set).
        const val ACTIVE_CYCLES_PER_LINE = 213
        const val ACTIVE_LINES = 200           // 200 active scanlines (25 text rows x 8)
        const val VSYNC_START = 224
        const val VSYNC_LINES = 3

        /**
         * Vertical stretch for 200-line modes (non-square pixels on a CRT).
         * Combined with a fixed 640-dot logical width, display aspect is 8:5 —
         * close to 4:3 and identical for 320×200 and 640×200.
         */
        const val PIXEL_ASPECT_Y = 2

        /** Shared CRT aspect for all CGA 200-line modes (640×400 after correction). */
        const val CGA_DISPLAY_ASPECT = 640.0 / (ACTIVE_LINES * PIXEL_ASPECT_Y)

        /** Toolbar speaker glyphs (unmuted / muted). */
        const val AUDIO_ON_LABEL = "\uD83D\uDD0A"
        const val AUDIO_MUTED_LABEL = "\uD83D\uDD07"
        /** Play / pause / turbo glyphs. */
        const val PLAY_LABEL = "\u25B6"
        const val PAUSE_LABEL = "\u23F8"
        const val TURBO_LABEL = "\u23E9"
        /** Composite colour-mix toggle labels. */
        const val COMPOSITE_ON_LABEL = "NTSC"
        const val COMPOSITE_OFF_LABEL = "RGBI"

        // Standard 16-color CGA/RGBI palette.
        val CGA_PALETTE = intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        )
    }
}
