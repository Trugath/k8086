package com.trugath.k8086.video

import com.trugath.k8086.chipset.XtCharScanCodes
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

internal actual class CgaHostUi actual constructor(
    private val cga: Cga,
    private val showWindow: Boolean,
    private val exitOnClose: Boolean,
) {
    private var window: JFrame? = null
    private var panel: CgaPanel? = null
    private var image: BufferedImage? = null
    private var refreshTimer: Timer? = null
    private var compositeButton: JButton? = null
    private var mouseGrabbed = false
    private var suppressEscBreak = false
    private var lastMouseX = 0
    private var lastMouseY = 0

    actual fun onFramebufferReady(width: Int, height: Int, pixels: IntArray) {
        if (!showWindow || GraphicsEnvironment.isHeadless()) return
        val img = ensureImage(width, height)
        img.setRGB(0, 0, width, height, pixels, 0, width)
        if (window == null) createWindow(img) else panel?.repaint()
    }

    actual fun refreshCompositeChrome() {
        val btn = compositeButton ?: return
        SwingUtilities.invokeLater {
            val graphics = cga.isGraphicsMode()
            btn.isVisible = graphics
            if (!graphics) return@invokeLater
            val on = cga.isCompositeActive()
            btn.text = if (on) Cga.COMPOSITE_ON_LABEL else Cga.COMPOSITE_OFF_LABEL
            btn.toolTipText = if (on) {
                "Composite colour mixing on (click for RGBI)"
            } else {
                "Composite colour mixing off (click for NTSC artefact colours)"
            }
            btn.foreground = if (on) Color(0xC06000) else UIManager.getColor("Button.foreground")
        }
    }

    actual fun updateTitle() {
        val w = window ?: return
        SwingUtilities.invokeLater { w.title = cga.compositeTitle() }
    }

    actual fun dispose() {
        SwingUtilities.invokeLater {
            refreshTimer?.stop()
            refreshTimer = null
            window?.dispose()
            window = null
            panel = null
        }
    }

    actual fun writeFramePng(path: String, width: Int, height: Int, argb: IntArray) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, width, height, argb, 0, width)
        ImageIO.write(img, "png", File(path))
    }

    private fun ensureImage(w: Int, h: Int): BufferedImage {
        var img = image
        if (img == null || img.width != w || img.height != h) {
            img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            image = img
        }
        return img
    }

    private fun createWindow(img: BufferedImage) {
        SwingUtilities.invokeLater {
            if (window != null) {
                panel?.setImage(img)
                updateTitle()
                return@invokeLater
            }
            val p = CgaPanel(img)
            panel = p
            p.isFocusable = true
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
                            cga.cycleCompositeMode()
                            return
                        }
                        KeyEvent.VK_F11 -> {
                            cga.adjustHue(if (e.isShiftDown) -5.0 else 5.0)
                            return
                        }
                    }
                    val sc = scanCodeFor(e) ?: return
                    if (!pressedScanCodes.add(sc)) return
                    cga.onKeyScanCode?.invoke(sc)
                }
                override fun keyReleased(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_F11 || e.keyCode == KeyEvent.VK_F12) return
                    if (e.keyCode == KeyEvent.VK_ESCAPE && suppressEscBreak) {
                        suppressEscBreak = false
                        return
                    }
                    val sc = scanCodeFor(e) ?: return
                    if (!pressedScanCodes.remove(sc)) return
                    cga.onKeyScanCode?.invoke(sc or 0x80)
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
                    emitMouse(dx, -dy, mouseButtonsFrom(e.modifiersEx))
                }
            })
            p.toolTipText = "Click to grab mouse (Esc release); right-click pastes when ungrabbed"
            val toolbar = buildToolbar(p)
            val root = JPanel(BorderLayout()).apply {
                add(p, BorderLayout.CENTER)
                add(toolbar, BorderLayout.SOUTH)
            }
            window = JFrame(cga.compositeTitle()).apply {
                defaultCloseOperation = if (exitOnClose) {
                    WindowConstants.EXIT_ON_CLOSE
                } else {
                    WindowConstants.DISPOSE_ON_CLOSE
                }
                contentPane = root
                isResizable = true
                packToVideoAspect(this, p, toolbar)
                setLocation(64, 64)
                isAlwaysOnTop = true
                isVisible = true
                packToVideoAspect(this, p, toolbar)
                toFront()
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
            refreshTimer?.stop()
            refreshTimer = Timer(16) { p.repaint() }.also { it.start() }
            p.requestFocusInWindow()
        }
    }

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
        cga.onMouseEvent?.invoke(dx, dy, buttons)
    }

    private fun mouseButtonsFrom(modifiersEx: Int): Int {
        var b = 0
        if ((modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) != 0) b = b or 1
        if ((modifiersEx and MouseEvent.BUTTON3_DOWN_MASK) != 0) b = b or 2
        return b
    }

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
        val controls = cga.hostControls
        val bar = JPanel(BorderLayout())
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        left.add(
            JButton("Ctrl+Alt+Del").apply {
                isFocusable = false
                isFocusPainted = false
                toolTipText = "Send Ctrl+Alt+Delete to the emulated PC"
                addActionListener {
                    controls?.onCtrlAltDelete?.invoke()
                    focusTarget.requestFocusInWindow()
                }
            },
        )
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
                cga.setCompositeEnabled(!cga.isCompositeActive())
                focusTarget.requestFocusInWindow()
            }
        }
        compositeButton = mixBtn
        right.add(mixBtn)
        refreshCompositeChrome()
        if (controls?.onTogglePause != null) {
            val pauseBtn = JButton().apply {
                isFocusable = false
                isFocusPainted = false
                fun refresh() {
                    val paused = controls.isPaused?.invoke() == true
                    text = if (paused) Cga.PLAY_LABEL else Cga.PAUSE_LABEL
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
                    text = Cga.TURBO_LABEL
                    toolTipText = if (on) "Turbo on (click for realtime)" else "Turbo off (click for max speed)"
                    foreground = if (on) Color(0xC06000) else UIManager.getColor("Button.foreground")
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
                    text = if (muted) Cga.AUDIO_MUTED_LABEL else Cga.AUDIO_ON_LABEL
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
        if (right.componentCount > 0) bar.add(right, BorderLayout.EAST)
        return bar
    }

    private fun floppyTooltip(drive: Int, controls: Cga.HostControls?): String {
        val path = controls?.floppyPath?.invoke(drive)
        return if (path != null) "Mounted: $path" else "No disk in ${'A' + drive}:"
    }

    private fun promptChangeFloppy(drive: Int, button: JButton, controls: Cga.HostControls?) {
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

    private fun pasteClipboard(focusTarget: JPanel) {
        focusTarget.requestFocusInWindow()
        val emit = cga.onKeyScanCode ?: return
        try {
            val clip = Toolkit.getDefaultToolkit().systemClipboard
            if (!clip.isDataFlavorAvailable(DataFlavor.stringFlavor)) return
            val text = clip.getData(DataFlavor.stringFlavor) as? String ?: return
            if (text.isEmpty()) return
            val codes = ArrayList<Int>(text.length * 4)
            XtCharScanCodes.paste(text, codes::add)
            if (codes.isEmpty()) return
            var i = 0
            val t = Timer(8) {
                if (i >= codes.size) {
                    (it.source as Timer).stop()
                    return@Timer
                }
                emit(codes[i++])
                if (i < codes.size) emit(codes[i++])
            }
            t.start()
        } catch (_: Exception) {
        }
    }

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

    private class CgaPanel(private var img: BufferedImage) : JPanel() {
        private val zoom = 2
        fun setImage(newImg: BufferedImage) {
            img = newImg
            revalidate()
            repaint()
        }
        private fun preferredDisplaySize(): Dimension {
            val h = Cga.ACTIVE_LINES * zoom * Cga.PIXEL_ASPECT_Y
            val w = (h * Cga.CGA_DISPLAY_ASPECT).toInt()
            return Dimension(w, h)
        }
        init {
            preferredSize = preferredDisplaySize()
            isOpaque = true
            background = Color.BLACK
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create()
            if (g2 is java.awt.Graphics2D) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            }
            val aspect = Cga.CGA_DISPLAY_ASPECT
            var drawW = width
            var drawH = (drawW / aspect).toInt()
            if (drawH > height) {
                drawH = height
                drawW = (drawH * aspect).toInt()
            }
            val x = (width - drawW) / 2
            val y = (height - drawH) / 2
            g2.drawImage(img, x, y, drawW, drawH, null)
            g2.dispose()
        }
    }
}

internal fun Cga.writeFramePng(file: File) = writeFramePng(file.absolutePath)
