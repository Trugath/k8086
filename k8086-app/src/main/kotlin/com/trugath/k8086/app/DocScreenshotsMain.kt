package com.trugath.k8086.app

import com.trugath.k8086.client.ManagerFrame
import com.trugath.k8086.client.NetworksDialog
import com.trugath.k8086.client.RomPickerDialog
import com.trugath.k8086.client.VmConsoleWindow
import com.trugath.k8086.client.VmDebugWindow
import com.trugath.k8086.client.defaultRomPaths
import com.trugath.k8086.client.romsExist
import com.trugath.k8086.host.LocalHost
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmState
import com.trugath.k8086.ui.StartWizard
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/**
 * Captures workstation UI screenshots into docs/screenshots for the manuals.
 *
 * Run from the repo root:
 *   ./gradlew :k8086-app:docScreenshots
 */
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "docs/screenshots").absoluteFile
    outDir.mkdirs()
    val (u18, u19) = defaultRomPaths()
    if (!romsExist(u18, u19)) {
        System.err.println("ROM BIOS not found — cannot capture screenshots.")
        exitProcess(2)
    }

    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Exception) {
    }

    val host = LocalHost()
    Runtime.getRuntime().addShutdownHook(Thread { host.close() })

    println("Capturing screenshots → ${outDir.path}")

    val manager = edt {
        ManagerFrame(host, u18, u19).also {
            it.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            it.isVisible = true
        }
    }
    settle(600)
    captureWindow(manager, File(outDir, "01-manager.png"))
    println("  01-manager.png")

    StartWizard.captureSteps(host.network(), finishButtonLabel = "Create") { stepName, dialog ->
        settle(250)
        val name = "02-wizard-$stepName.png"
        captureWindow(dialog, File(outDir, name))
        println("  $name")
        true
    }

    val romPicker = edt {
        RomPickerDialog(
            manager,
            "System ROMs",
            u18,
            u19,
            note = "Defaults are rmDOS U18/U19. Browse to override. Images are copied into the VM as immutable snapshots.",
        ).also {
            it.isModal = false
            it.isVisible = true
        }
    }
    settle(300)
    captureWindow(romPicker, File(outDir, "03-rom-picker.png"))
    println("  03-rom-picker.png")
    edt { romPicker.dispose() }

    val networks = edt {
        NetworksDialog(manager, host.network()).also {
            it.isModal = false
            it.isVisible = true
        }
    }
    settle(300)
    captureWindow(networks, File(outDir, "04-networks.png"))
    println("  04-networks.png")
    edt { networks.dispose() }

    val networkEdit = edt { buildNetworkEditCaptureDialog(manager) }
    settle(300)
    captureWindow(networkEdit, File(outDir, "05-network-edit.png"))
    println("  05-network-edit.png")
    edt { networkEdit.dispose() }

    val vm = host.listVms().firstOrNull()
        ?: run {
            System.err.println("No VMs defined under ~/.k8086/vms — skipping console/debug shots.")
            edt { manager.dispose() }
            host.close()
            println("Done (partial).")
            exitProcess(0)
        }

    // Ensure a stopped VM before starting for a clean boot screenshot.
    if (vm.state == VmState.Running || vm.state == VmState.Starting || vm.state == VmState.Paused) {
        host.stopVm(vm.id)
        settle(500)
    }
    host.startVm(vm.id)
    println("  started VM '${vm.name}' — waiting for guest boot…")
    waitForGuestBoot(host, vm.id, timeoutMs = 45_000)

    val console = edt {
        VmConsoleWindow(host, vm.id, vm.name, onOpenDebug = {
            // no-op during capture; Debug window is opened explicitly below
        }).also { it.isVisible = true }
    }
    settle(800)
    captureWindow(console, File(outDir, "06-console.png"))
    println("  06-console.png")

    host.pauseVm(vm.id)
    settle(400)
    val debug = edt {
        VmDebugWindow(host, vm.id, vm.name).also { it.isVisible = true }
    }
    settle(800)
    captureWindow(debug, File(outDir, "07-debug.png"))
    println("  07-debug.png")

    // Manager with a running/paused VM selected for the details pane.
    edt {
        manager.toFront()
        manager.repaint()
    }
    settle(600)
    captureWindow(manager, File(outDir, "08-manager-running.png"))
    println("  08-manager-running.png")

    edt {
        debug.dispose()
        console.dispose()
        manager.dispose()
    }
    try {
        host.stopVm(vm.id)
    } catch (_: Exception) {
    }
    host.close()
    println("Done.")
    // AWT keeps non-daemon threads alive; exit so Gradle can finish.
    exitProcess(0)
}

private fun <T> edt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: T? = null
    var error: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (t: Throwable) {
            error = t
        }
    }
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

private fun settle(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    edt {
        // flush pending paints
    }
}

private fun captureWindow(window: Window, file: File) {
    edt {
        if (!window.isShowing) {
            window.isVisible = true
        }
        window.toFront()
        window.repaint()
    }
    settle(150)
    // Prefer painting the window tree (stable under HiDPI) and fall back to Robot.
    val painted = edt { paintWindow(window) }
    if (painted != null && painted.width > 10 && painted.height > 10) {
        ImageIO.write(painted, "PNG", file)
        return
    }
    val robot = Robot()
    val bounds = edt {
        val loc = window.locationOnScreen
        Rectangle(loc.x, loc.y, window.width, window.height)
    }
    val shot = robot.createScreenCapture(bounds)
    ImageIO.write(shot, "PNG", file)
}

private fun paintWindow(window: Window): BufferedImage? {
    val w = window.width
    val h = window.height
    if (w <= 0 || h <= 0) return null
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
        window.paintAll(g)
    } finally {
        g.dispose()
    }
    return img
}

private fun buildNetworkEditCaptureDialog(owner: Window): JDialog {
    // Mirrors NetworksDialog.edit() fields without blocking on JOptionPane.
    val form = javax.swing.JPanel(java.awt.GridBagLayout())
    val c = java.awt.GridBagConstraints().apply {
        insets = java.awt.Insets(3, 3, 3, 3)
        anchor = java.awt.GridBagConstraints.WEST
        fill = java.awt.GridBagConstraints.HORIZONTAL
    }
    fun row(i: Int, label: String, field: javax.swing.JComponent) {
        c.gridx = 0; c.gridy = i; c.weightx = 0.0
        form.add(javax.swing.JLabel(label), c)
        c.gridx = 1; c.weightx = 1.0
        form.add(field, c)
    }
    row(0, "Id:", javax.swing.JTextField("lan", 16))
    row(1, "Name:", javax.swing.JTextField("LAN", 16))
    row(2, "Gateway IP:", javax.swing.JTextField("10.0.2.2", 16))
    row(3, "Subnet mask:", javax.swing.JTextField("255.255.255.0", 16))
    row(4, "", javax.swing.JCheckBox("Enable DHCP", true))
    row(5, "DHCP start:", javax.swing.JTextField("10.0.2.15", 16))
    row(6, "DHCP end:", javax.swing.JTextField("10.0.2.31", 16))
    return JDialog(null as java.awt.Frame?, "New virtual network", false).also { d ->
        d.contentPane = JOptionPane(
            form,
            JOptionPane.PLAIN_MESSAGE,
            JOptionPane.OK_CANCEL_OPTION,
        )
        d.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        d.pack()
        d.setLocationRelativeTo(owner)
        d.isVisible = true
    }
}

private fun waitForGuestBoot(host: LocalHost, vmId: VmId, timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var firstVideoAt = 0L
    while (System.currentTimeMillis() < deadline) {
        val frame = host.pollConsoleFrame(vmId)
        val metrics = host.metrics(vmId)
        val lit = frame?.argb?.count { (it and 0xFFFFFF) != 0 } ?: 0
        if (lit > 80 && firstVideoAt == 0L) {
            firstVideoAt = System.currentTimeMillis()
        }
        val instructions = metrics?.instructionCount ?: 0L
        // Prefer a post-POST screen: either enough guest work, or several seconds of video.
        val videoAge = if (firstVideoAt == 0L) 0L else System.currentTimeMillis() - firstVideoAt
        if (firstVideoAt != 0L && (instructions > 3_000_000L || videoAge > 12_000L) && lit > 200) {
            return
        }
        settle(250)
    }
}
