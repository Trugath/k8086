package com.trugath.k8086.client

import com.trugath.k8086.chipset.XtCharScanCodes
import com.trugath.k8086.protocol.ConsoleFrame
import com.trugath.k8086.protocol.HostApi
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmState
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
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

/**
 * Console window for one running VM: display + transport / floppy / audio toolbar.
 */
class VmConsoleWindow(
    private val host: HostApi,
    private val vmId: VmId,
    private val vmName: String,
    private val onOpenDebug: (() -> Unit)? = null,
) : JFrame("$vmName — Console") {
    private val display = DisplayPanel()
    private val toolbar = buildToolbar()
    private val refreshTimer = Timer(33) { refreshFrame() }
    private lateinit var pauseButton: JButton
    private lateinit var turboButton: JButton
    private lateinit var audioButton: JButton
    private lateinit var compositeButton: JButton
    private var mouseGrabbed = false
    private var suppressEscBreak = false
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var lastGraphicsMode = false
    private var lastCompositeActive = false

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        layout = BorderLayout()
        display.isFocusable = true
        // Keep Tab/Shift+Tab as guest scancodes — do not move Swing focus onto toolbar
        // (Ctrl+Alt+Del is default-capable; Tab then Enter would warm-boot the guest).
        display.focusTraversalKeysEnabled = false
        display.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE && mouseGrabbed) {
                    setMouseGrabbed(false)
                    suppressEscBreak = true
                    return
                }
                XtScanCodes.makeCode(e)?.let { host.sendScanCode(vmId, it) }
            }
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE && suppressEscBreak) {
                    suppressEscBreak = false
                    return
                }
                XtScanCodes.makeCode(e)?.let { host.sendScanCode(vmId, it or 0x80) }
            }
        })
        display.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!mouseGrabbed) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        pasteClipboard()
                        return
                    }
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        setMouseGrabbed(true)
                        lastMouseX = e.x
                        lastMouseY = e.y
                    }
                    return
                }
                host.sendMouseEvent(vmId, 0, 0, mouseButtonsFrom(e.modifiersEx))
            }
            override fun mouseReleased(e: MouseEvent) {
                if (!mouseGrabbed) return
                host.sendMouseEvent(vmId, 0, 0, mouseButtonsFrom(e.modifiersEx))
            }
        })
        display.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) = mouseMoved(e)
            override fun mouseMoved(e: MouseEvent) {
                if (!mouseGrabbed) return
                val dx = e.x - lastMouseX
                val dy = e.y - lastMouseY
                lastMouseX = e.x
                lastMouseY = e.y
                if (dx == 0 && dy == 0) return
                host.sendMouseEvent(vmId, dx, -dy, mouseButtonsFrom(e.modifiersEx))
            }
        })
        display.toolTipText = "Click to grab mouse (Esc release); right-click pastes when ungrabbed"
        add(display, BorderLayout.CENTER)
        add(toolbar, BorderLayout.SOUTH)
        packToVideoAspect()
        setLocationRelativeTo(null)

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                host.setConsoleFocused(vmId, true)
                refreshTimer.start()
                display.requestFocusInWindow()
                // Insets are reliable after show.
                packToVideoAspect()
            }
            override fun windowClosing(e: WindowEvent?) {
                refreshTimer.stop()
                host.setConsoleFocused(vmId, false)
            }
            override fun windowActivated(e: WindowEvent?) {
                host.setConsoleFocused(vmId, true)
            }
            override fun windowDeactivated(e: WindowEvent?) {
                if (mouseGrabbed) setMouseGrabbed(false)
                host.setConsoleFocused(vmId, false)
            }
        })
    }

    private fun setMouseGrabbed(grabbed: Boolean) {
        mouseGrabbed = grabbed
        display.cursor = if (grabbed) blankCursor else Cursor.getDefaultCursor()
        display.toolTipText = if (grabbed) {
            "Mouse grabbed — Esc to release"
        } else {
            "Click to grab mouse (Esc release); right-click pastes when ungrabbed"
        }
        if (grabbed) display.requestFocusInWindow()
    }

    private val blankCursor: Cursor by lazy {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(img, java.awt.Point(0, 0), "blank")
    }

    private fun mouseButtonsFrom(modifiersEx: Int): Int {
        var b = 0
        if ((modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) != 0) b = b or 1
        if ((modifiersEx and MouseEvent.BUTTON3_DOWN_MASK) != 0) b = b or 2
        return b
    }

    private fun packToVideoAspect() {
        pack()
        val videoPref = display.preferredSize
        val toolbarH = toolbar.preferredSize.height
        val w = videoPref.width + insets.left + insets.right
        val h = videoPref.height + toolbarH + insets.top + insets.bottom
        setSize(w, h)
        minimumSize = Dimension(
            (videoPref.width / 4).coerceAtLeast(160) + insets.left + insets.right,
            (videoPref.height / 4).coerceAtLeast(100) + toolbarH + insets.top + insets.bottom,
        )
    }

    private fun pasteClipboard() {
        display.requestFocusInWindow()
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
                host.sendScanCode(vmId, codes[i++])
                if (i < codes.size) host.sendScanCode(vmId, codes[i++])
            }
            t.start()
        } catch (_: Exception) {
            // Clipboard busy / unsupported flavor — ignore.
        }
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(BorderLayout())
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        left.add(toolbarButton("Ctrl+Alt+Del") {
            host.sendCtrlAltDelete(vmId)
        })
        val metrics = host.metrics(vmId)
        val driveCount = metrics?.floppyPaths?.size
            ?: host.getDefinition(vmId)?.floppy?.let { if (it.enabled) it.driveImages.size.coerceAtLeast(1) else 0 }
            ?: 0
        for (drive in 0 until driveCount) {
            val letter = "${'A' + drive}:"
            left.add(toolbarButton("Change $letter") {
                promptChangeFloppy(drive, letter)
            })
        }
        bar.add(left, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4))
        compositeButton = toolbarButton("") {
            host.setCompositeEnabled(vmId, !host.isCompositeActive(vmId))
            refreshCompositeButton(lastGraphicsMode, host.isCompositeActive(vmId))
        }
        compositeButton.isVisible = false
        pauseButton = toolbarButton("") {
            if (host.isPaused(vmId)) host.resumeVm(vmId) else host.pauseVm(vmId)
            refreshTransportButtons()
        }
        turboButton = toolbarButton(TURBO_LABEL) {
            host.setTurbo(vmId, !host.isTurbo(vmId))
            refreshTurboButton()
        }
        audioButton = toolbarButton("") {
            host.setAudioMuted(vmId, !host.isAudioMuted(vmId))
            refreshAudioButton()
        }
        right.add(compositeButton)
        right.add(pauseButton)
        right.add(turboButton)
        right.add(audioButton)
        if (onOpenDebug != null) {
            right.add(toolbarButton("Debug") {
                onOpenDebug.invoke()
            })
        }
        refreshTransportButtons()
        bar.add(right, BorderLayout.EAST)
        return bar
    }

    /** Toolbar controls stay mouse-only so Tab never leaves the guest display. */
    private fun toolbarButton(text: String, onClick: () -> Unit): JButton =
        JButton(text).apply {
            isFocusable = false
            isFocusPainted = false
            addActionListener {
                onClick()
                display.requestFocusInWindow()
            }
        }

    private fun refreshTransportButtons() {
        refreshPauseButton()
        refreshTurboButton()
        refreshAudioButton()
    }

    private fun refreshPauseButton() {
        val paused = host.isPaused(vmId)
        pauseButton.text = if (paused) PLAY_LABEL else PAUSE_LABEL
        pauseButton.toolTipText = if (paused) "Resume" else "Pause"
    }

    private fun refreshTurboButton() {
        val on = host.isTurbo(vmId)
        turboButton.toolTipText = if (on) "Turbo on (click for realtime)" else "Turbo off (click for max speed)"
        turboButton.foreground = if (on) java.awt.Color(0xC06000) else UIManager.getColor("Button.foreground")
    }

    private fun refreshAudioButton() {
        val muted = host.isAudioMuted(vmId)
        audioButton.text = if (muted) AUDIO_MUTED_LABEL else AUDIO_ON_LABEL
        audioButton.toolTipText = if (muted) "Unmute audio" else "Mute audio"
    }

    private fun refreshCompositeButton(graphicsMode: Boolean, compositeActive: Boolean) {
        lastGraphicsMode = graphicsMode
        lastCompositeActive = compositeActive
        compositeButton.isVisible = graphicsMode
        if (!graphicsMode) return
        compositeButton.text = if (compositeActive) COMPOSITE_ON_LABEL else COMPOSITE_OFF_LABEL
        compositeButton.toolTipText = if (compositeActive) {
            "Composite colour mixing on (click for RGBI)"
        } else {
            "Composite colour mixing off (click for NTSC artefact colours)"
        }
        compositeButton.foreground = if (compositeActive) {
            java.awt.Color(0xC06000)
        } else {
            UIManager.getColor("Button.foreground")
        }
    }

    private fun promptChangeFloppy(drive: Int, letter: String) {
        val options = arrayOf("Insert image…", "Eject", "Cancel")
        when (JOptionPane.showOptionDialog(
            this,
            "Floppy $letter",
            "Change floppy",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0],
        )) {
            0 -> {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Insert floppy image ($letter)"
                    fileFilter = FileNameExtensionFilter("Disk images", "img", "ima", "dsk")
                    isAcceptAllFileFilterUsed = true
                }
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        host.changeFloppy(vmId, drive, chooser.selectedFile.absolutePath)
                    } catch (ex: Exception) {
                        JOptionPane.showMessageDialog(this, ex.message, "Change floppy", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }
            1 -> {
                try {
                    host.changeFloppy(vmId, drive, null)
                } catch (ex: Exception) {
                    JOptionPane.showMessageDialog(this, ex.message, "Eject", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
    }

    private fun refreshFrame() {
        val summary = host.listVms().find { it.id == vmId }
        if (summary == null || summary.state == VmState.Stopped || summary.state == VmState.Error) {
            refreshTimer.stop()
            title = "$vmName — Console (stopped)"
            return
        }
        title = when (summary.state) {
            VmState.Paused -> "$vmName — Console (paused)"
            else -> "$vmName — Console"
        }
        refreshTransportButtons()
        val frame = host.pollConsoleFrame(vmId) ?: return
        if (frame.graphicsMode != lastGraphicsMode || frame.compositeActive != lastCompositeActive) {
            refreshCompositeButton(frame.graphicsMode, frame.compositeActive)
        }
        display.setFrame(frame)
    }

    private class DisplayPanel : JPanel() {
        private var image: BufferedImage? = null
        private val zoom = 2
        /** Same CRT canvas for 320×200 and 640×200 (see Cga.CGA_DISPLAY_ASPECT). */
        private val crtAspect = 640.0 / (200.0 * 2)

        init {
            val h = 200 * zoom * 2
            preferredSize = Dimension((h * crtAspect).toInt(), h)
            background = java.awt.Color.BLACK
            isOpaque = true
        }

        fun setFrame(frame: ConsoleFrame) {
            var img = image
            if (img == null || img.width != frame.width || img.height != frame.height) {
                img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                image = img
                val h = 200 * zoom * 2
                preferredSize = Dimension((h * crtAspect).toInt(), h)
            }
            img.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image ?: return
            val g2 = g.create()
            try {
                if (g2 is java.awt.Graphics2D) {
                    g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                    )
                }
                g2.color = java.awt.Color.BLACK
                g2.fillRect(0, 0, width, height)
                var drawW = width
                var drawH = (drawW / crtAspect).toInt()
                if (drawH > height) {
                    drawH = height
                    drawW = (drawH * crtAspect).toInt()
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

    private companion object {
        const val AUDIO_ON_LABEL = "\uD83D\uDD0A"
        const val AUDIO_MUTED_LABEL = "\uD83D\uDD07"
        const val PLAY_LABEL = "\u25B6"
        const val PAUSE_LABEL = "\u23F8"
        const val TURBO_LABEL = "\u23E9"
        const val COMPOSITE_ON_LABEL = "NTSC"
        const val COMPOSITE_OFF_LABEL = "RGBI"
    }
}
