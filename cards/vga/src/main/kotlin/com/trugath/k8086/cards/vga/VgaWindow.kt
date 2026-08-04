package com.trugath.k8086.cards.vga

import com.trugath.k8086.api.IsaConsoleControls
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
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

/** VGA present window with CGA-parity console toolbar. */
internal class VgaWindow(
    private val title: String,
    private val exitOnClose: Boolean,
    private val controls: IsaConsoleControls?,
) {
    private var frame: JFrame? = null
    private var panel: Panel? = null
    private var toolbar: JPanel? = null
    private var image: BufferedImage? = null
    private var timer: Timer? = null
    private var imgW = 0
    private var imgH = 0
    /** Reused compose buffer — avoid allocating 320×200 ints every present. */
    private var pixels = IntArray(0)

    private var pauseBtn: JButton? = null
    private var turboBtn: JButton? = null
    private var audioBtn: JButton? = null

    /** XT make codes currently held — Swing auto-repeats must not flood IRQ1. */
    private val pressedScanCodes = HashSet<Int>()

    /** Set by [bindCore]; compose runs on the Swing timer, never the emu tick. */
    @Volatile private var coreRef: VgaCore? = null
    @Volatile private var dirty = true

    fun bindCore(core: VgaCore) {
        coreRef = core
        dirty = true
    }

    fun markDirty() {
        dirty = true
    }

    fun ensureOpen() {
        if (frame != null || GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeLater {
            if (frame != null) return@invokeLater
            ensureImage(VgaCore.TEXT_PIXEL_W, VgaCore.TEXT_PIXEL_H)
            val p = Panel()
            panel = p
            p.isFocusable = true
            p.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val sc = scanCodeFor(e) ?: return
                    // Ignore OS auto-repeat: XT boards only send one make until break.
                    if (!pressedScanCodes.add(sc)) return
                    controls?.enqueueKeyScanCode(sc)
                }
                override fun keyReleased(e: KeyEvent) {
                    val sc = scanCodeFor(e) ?: return
                    if (!pressedScanCodes.remove(sc)) return
                    controls?.enqueueKeyScanCode(sc or 0x80)
                }
            })
            val bar = buildToolbar(p)
            toolbar = bar
            val root = JPanel(BorderLayout()).apply {
                add(p, BorderLayout.CENTER)
                add(bar, BorderLayout.SOUTH)
            }
            frame = JFrame(title).apply {
                defaultCloseOperation =
                    if (exitOnClose) WindowConstants.EXIT_ON_CLOSE
                    else WindowConstants.DISPOSE_ON_CLOSE
                contentPane = root
                isResizable = true
                packToVideo(this, p, bar)
                setLocation(64, 64)
                isVisible = true
                packToVideo(this, p, bar)
                addWindowFocusListener(object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        controls?.setConsoleFocused(true)
                    }
                    override fun windowLostFocus(e: WindowEvent?) {
                        // Lost focus mid-hold would leave Keyboard[] stuck in Wolf.
                        releaseAllKeys()
                        controls?.setConsoleFocused(false)
                    }
                })
            }
            // ~60 Hz: compose + blit on the EDT so the emu thread never blocks in
            // composeFrame/setRGB (that starved Wolf3D on turbo and realtime).
            timer = Timer(16) {
                refreshControlButtons()
                val c = coreRef
                if (c != null && dirty) {
                    dirty = false
                    presentOnEdt(c)
                }
                panel?.repaint()
            }.also { it.start() }
            p.requestFocusInWindow()
            controls?.setConsoleFocused(true)
        }
    }

    /**
     * Compose + setRGB on the EDT only. Emulator ticks call [markDirty].
     */
    private fun presentOnEdt(core: VgaCore) {
        try {
            val text = core.isTextMode()
            val w = if (text) VgaCore.TEXT_PIXEL_W else 320
            val h = if (text) VgaCore.TEXT_PIXEL_H else 200
            if (pixels.size != w * h) {
                pixels = IntArray(w * h)
            }
            if (text) core.composeTextFrame(pixels) else core.composeFrame(pixels)

            val img = image
            if (img == null || imgW != w || imgH != h) {
                ensureImage(w, h)
                image?.setRGB(0, 0, w, h, pixels, 0, w)
                return
            }
            img.setRGB(0, 0, w, h, pixels, 0, w)
        } catch (_: Exception) {
            // Never let a present glitch tear down the UI timer.
        }
    }

    /** @deprecated Prefer [markDirty]; kept for any external callers. */
    fun present(core: VgaCore) {
        coreRef = core
        markDirty()
        ensureOpen()
    }

    private fun ensureImage(w: Int, h: Int) {
        if (image != null && imgW == w && imgH == h) return
        imgW = w
        imgH = h
        image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val p = panel
        val bar = toolbar
        val f = frame
        if (p != null) {
            p.preferredSize = Dimension(w * 2, h * 2)
            if (f != null && bar != null) packToVideo(f, p, bar)
            else f?.pack()
        }
    }

    fun close() {
        SwingUtilities.invokeLater {
            releaseAllKeys()
            timer?.stop()
            timer = null
            frame?.dispose()
            frame = null
            panel = null
            toolbar = null
            image = null
            pauseBtn = null
            turboBtn = null
            audioBtn = null
        }
    }

    /** Emit breaks for every held make so guest Keyboard[] cannot stick. */
    private fun releaseAllKeys() {
        if (pressedScanCodes.isEmpty()) return
        val held = pressedScanCodes.toIntArray()
        pressedScanCodes.clear()
        for (sc in held) {
            controls?.enqueueKeyScanCode(sc or 0x80)
        }
    }

    private fun packToVideo(frame: JFrame, video: JPanel, toolbar: JPanel) {
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
        val bar = JPanel(BorderLayout())
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        if (controls != null) {
            left.add(
                JButton("Ctrl+Alt+Del").apply {
                    isFocusable = false
                    isFocusPainted = false
                    toolTipText = "Send Ctrl+Alt+Delete to the emulated PC"
                    addActionListener {
                        controls.sendCtrlAltDelete()
                        focusTarget.requestFocusInWindow()
                    }
                },
            )
            for (drive in 0 until controls.floppyDriveCount()) {
                val label = "${'A' + drive}:"
                val btn = JButton("Change $label").apply {
                    isFocusable = false
                    isFocusPainted = false
                    toolTipText = floppyTooltip(drive)
                    addActionListener {
                        promptChangeFloppy(drive, this)
                        focusTarget.requestFocusInWindow()
                    }
                }
                left.add(btn)
            }
        }
        bar.add(left, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4))
        if (controls != null) {
            pauseBtn = JButton().apply {
                isFocusable = false
                isFocusPainted = false
                addActionListener {
                    controls.togglePause()
                    refreshControlButtons()
                    focusTarget.requestFocusInWindow()
                }
            }
            right.add(pauseBtn)
            turboBtn = JButton(TURBO_LABEL).apply {
                isFocusable = false
                isFocusPainted = false
                addActionListener {
                    controls.toggleTurbo()
                    refreshControlButtons()
                    focusTarget.requestFocusInWindow()
                }
            }
            right.add(turboBtn)
            audioBtn = JButton().apply {
                isFocusable = false
                isFocusPainted = false
                addActionListener {
                    controls.toggleAudioMute()
                    refreshControlButtons()
                    focusTarget.requestFocusInWindow()
                }
            }
            right.add(audioBtn)
            refreshControlButtons()
        }
        if (right.componentCount > 0) {
            bar.add(right, BorderLayout.EAST)
        }
        return bar
    }

    private fun refreshControlButtons() {
        val c = controls ?: return
        pauseBtn?.let { btn ->
            val paused = c.isPaused()
            btn.text = if (paused) PLAY_LABEL else PAUSE_LABEL
            btn.toolTipText = if (paused) "Resume" else "Pause"
        }
        turboBtn?.let { btn ->
            val on = c.isTurbo()
            btn.toolTipText = if (on) "Turbo on (click for realtime)" else "Turbo off (click for max speed)"
            btn.foreground = if (on) Color(0xC06000) else UIManager.getColor("Button.foreground")
        }
        audioBtn?.let { btn ->
            val muted = c.isUserAudioMuted()
            btn.text = if (muted) AUDIO_MUTED_LABEL else AUDIO_ON_LABEL
            btn.toolTipText = if (muted) "Unmute audio" else "Mute audio"
        }
    }

    private fun floppyTooltip(drive: Int): String {
        val path = controls?.floppyPath(drive)
        return if (path != null) "Mounted: $path" else "No disk in ${'A' + drive}:"
    }

    private fun promptChangeFloppy(drive: Int, button: JButton) {
        val c = controls ?: return
        val letter = "${'A' + drive}:"
        val options = arrayOf("Insert image…", "Eject", "Cancel")
        val choice = JOptionPane.showOptionDialog(
            frame,
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
                    c.floppyPath(drive)?.let { current ->
                        val f = File(current)
                        if (f.parentFile?.isDirectory == true) currentDirectory = f.parentFile
                        if (f.exists()) selectedFile = f
                    }
                }
                if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                    val path = chooser.selectedFile?.absolutePath ?: return
                    c.changeFloppy(drive, path)
                    button.toolTipText = floppyTooltip(drive)
                }
            }
            1 -> {
                c.changeFloppy(drive, null)
                button.toolTipText = floppyTooltip(drive)
            }
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

    private inner class Panel : JPanel() {
        init {
            preferredSize = Dimension(640, 400)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image ?: return
            g.drawImage(img, 0, 0, width, height, null)
        }
    }

    companion object {
        private const val AUDIO_ON_LABEL = "\uD83D\uDD0A"
        private const val AUDIO_MUTED_LABEL = "\uD83D\uDD07"
        private const val PLAY_LABEL = "\u25B6"
        private const val PAUSE_LABEL = "\u23F8"
        private const val TURBO_LABEL = "\u23E9"
    }
}
