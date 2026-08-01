package com.trugath.k8086.client

import com.trugath.k8086.chipset.XtCharScanCodes
import com.trugath.k8086.protocol.ConsoleFrame
import com.trugath.k8086.protocol.HostApi
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        layout = BorderLayout()
        display.isFocusable = true
        display.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                XtScanCodes.makeCode(e)?.let { host.sendScanCode(vmId, it) }
            }
            override fun keyReleased(e: KeyEvent) {
                XtScanCodes.makeCode(e)?.let { host.sendScanCode(vmId, it or 0x80) }
            }
        })
        display.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                pasteClipboard()
            }
        })
        display.toolTipText = "Right-click to paste clipboard text"
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
                host.setConsoleFocused(vmId, false)
            }
        })
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
        left.add(JButton("Ctrl+Alt+Del").apply {
            addActionListener {
                host.sendCtrlAltDelete(vmId)
                display.requestFocusInWindow()
            }
        })
        val metrics = host.metrics(vmId)
        val driveCount = metrics?.floppyPaths?.size
            ?: host.getDefinition(vmId)?.floppy?.let { if (it.enabled) it.driveImages.size.coerceAtLeast(1) else 0 }
            ?: 0
        for (drive in 0 until driveCount) {
            val letter = "${'A' + drive}:"
            left.add(JButton("Change $letter").apply {
                addActionListener {
                    promptChangeFloppy(drive, letter)
                    display.requestFocusInWindow()
                }
            })
        }
        bar.add(left, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 4))
        pauseButton = JButton()
        pauseButton.addActionListener {
            if (host.isPaused(vmId)) host.resumeVm(vmId) else host.pauseVm(vmId)
            refreshTransportButtons()
            display.requestFocusInWindow()
        }
        turboButton = JButton(TURBO_LABEL)
        turboButton.addActionListener {
            host.setTurbo(vmId, !host.isTurbo(vmId))
            refreshTurboButton()
            display.requestFocusInWindow()
        }
        audioButton = JButton()
        audioButton.addActionListener {
            host.setAudioMuted(vmId, !host.isAudioMuted(vmId))
            refreshAudioButton()
            display.requestFocusInWindow()
        }
        right.add(pauseButton)
        right.add(turboButton)
        right.add(audioButton)
        if (onOpenDebug != null) {
            right.add(JButton("Debug").also {
                it.addActionListener {
                    onOpenDebug.invoke()
                    display.requestFocusInWindow()
                }
            })
        }
        refreshTransportButtons()
        bar.add(right, BorderLayout.EAST)
        return bar
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
    }
}
