package com.trugath.k8086.video

import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.cpu.Emulator8086
import com.trugath.k8086.cpu.REG_CS
import com.trugath.k8086.platform.platformLock
import kotlin.concurrent.Volatile

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

    private val hostUi = CgaHostUi(this, showWindow, exitOnClose)
    private val fbLock = Any()
    private var fbWidth = 0
    private var fbHeight = 0
    private var pixels: IntArray = IntArray(0)

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
        hostUi.refreshCompositeChrome()
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
        hostUi.updateTitle()
        hostUi.refreshCompositeChrome()
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
        hostUi.updateTitle()
        hostUi.refreshCompositeChrome()
    }

    /** Nudge NTSC hue / tint dial by [delta] degrees (F11 / Shift+F11). */
    fun adjustHue(delta: Double) {
        hueOffsetDeg += delta
        renderFrame()
        hostUi.updateTitle()
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
        composeFrame()
    }

    private fun composeFrame(): Boolean {
        val composite = computeCompositeActive().also { compositeActiveCached = it }
        val (w, h) = when {
            graphicsMode() && highResGraphics() -> 640 to 200
            graphicsMode() -> 320 to 200
            else -> textColumns() * 8 to 200
        }
        ensureFramebuffer(w, h)
        when {
            graphicsMode() && composite && highResGraphics() -> renderCompositeMode6()
            graphicsMode() && composite -> renderCompositeMode4()
            graphicsMode() -> renderGraphics()
            else -> renderText()
        }
        hostUi.onFramebufferReady(fbWidth, fbHeight, pixels)
        return true
    }

    private fun pix(x: Int, y: Int, color: Int) {
        pixels[y * fbWidth + x] = color or 0xFF000000.toInt()
    }

    private fun renderText() {
        val cols = fbWidth / 8
        val base = 0xB8000
        for (row in 0 until 25) {
            for (col in 0 until cols) {
                val cell = base + (row * cols + col) * 2
                val ch = cpu.readPhysByte(cell)
                val attr = cpu.readPhysByte(cell + 1)
                drawGlyph(col * 8, row * 8, ch, attr)
            }
        }
    }

    private fun renderCompositeMode6() {
        val lut = compositeLuts.mode6
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            for (byteX in 0 until 80) {
                val b = cpu.readPhysByte(rowBase + byteX)
                val c0 = lut[(b shr 4) and 0x0F]
                val c1 = lut[b and 0x0F]
                val x0 = byteX * 8
                for (i in 0 until 4) pix(x0 + i, y, c0)
                for (i in 0 until 4) pix(x0 + 4 + i, y, c1)
            }
        }
    }

    private fun renderCompositeMode4() {
        val lut = compositeLuts.mode4
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            for (byteX in 0 until 80) {
                val b = cpu.readPhysByte(rowBase + byteX)
                val p0 = (b shr 6) and 3
                val p1 = (b shr 4) and 3
                val p2 = (b shr 2) and 3
                val p3 = b and 3
                val c0 = lut[(p0 shl 2) or p1]
                val c1 = lut[(p2 shl 2) or p3]
                val x0 = byteX * 4
                pix(x0, y, c0)
                pix(x0 + 1, y, c0)
                pix(x0 + 2, y, c1)
                pix(x0 + 3, y, c1)
            }
        }
    }

    private fun renderGraphics() {
        val hi = highResGraphics()
        val colors = graphicsPalette()
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            if (hi) {
                for (x in 0 until 640) {
                    val b = cpu.readPhysByte(rowBase + (x shr 3))
                    val on = (b shr (7 - (x and 7))) and 1
                    pix(x, y, if (on != 0) colors[1] else colors[0])
                }
            } else {
                for (x in 0 until 320) {
                    val b = cpu.readPhysByte(rowBase + (x shr 2))
                    val px = (b shr (6 - 2 * (x and 3))) and 3
                    pix(x, y, colors[px])
                }
            }
        }
    }

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

    private fun drawGlyph(x: Int, y: Int, ch: Int, attr: Int) {
        val bgIndex = if (blinkEnabled()) (attr shr 4) and 0x07 else (attr shr 4) and 0x0F
        val bg = CGA_PALETTE[bgIndex]
        val hideFg = blinkEnabled() && (attr and 0x80) != 0 && !blinkVisible()
        val fg = if (hideFg) bg else CGA_PALETTE[attr and 0x0F]
        for (yy in 0 until 8) {
            val bits = CgaFont.row(ch, yy)
            for (xx in 0 until 8) {
                val on = (bits and (0x80 shr xx)) != 0
                pix(x + xx, y + yy, if (on) fg else bg)
            }
        }
    }

    private fun ensureFramebuffer(w: Int, h: Int) {
        if (fbWidth != w || fbHeight != h || pixels.size != w * h) {
            fbWidth = w
            fbHeight = h
            pixels = IntArray(w * h)
        }
    }

    fun writeFramePng(path: String) {
        composeFrame()
        if (fbWidth == 0) return
        hostUi.writeFramePng(path, fbWidth, fbHeight, pixels)
    }

    fun copyFramebuffer(): FramebufferSnapshot? = platformLock(fbLock) {
        composeFrame()
        if (fbWidth == 0) return@platformLock null
        FramebufferSnapshot(
            width = fbWidth,
            height = fbHeight,
            argb = pixels.copyOf(),
            graphicsMode = graphicsMode(),
            compositeMode = compositeMode,
            compositeActive = compositeActiveCached,
        )
    }

    fun disposeWindow() = hostUi.dispose()

    internal fun compositeTitle(): String {
        val comp = when (compositeMode) {
            CgaComposite.Mode.AUTO -> if (compositeActiveCached) "composite:auto" else "rgb:auto"
            CgaComposite.Mode.ON -> "composite:on"
            CgaComposite.Mode.OFF -> "rgb:off"
        }
        val hue = if (hueOffsetDeg == 0.0) "" else " hue=${hueOffsetDeg.toInt()}"
        return "k8086 - IBM 5155 ($comp$hue)  [F12 composite · F11 hue]"
    }

    fun crtcRegister(i: Int): Int = crtc[i and 0x1F]
    fun modeControlValue(): Int = modeControl
    fun colorSelectValue(): Int = colorSelect
    fun framesRenderedCount(): Long = framesRendered

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
