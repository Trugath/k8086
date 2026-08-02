package com.trugath.k8086.client

import com.trugath.k8086.host.SetupMapper
import com.trugath.k8086.protocol.HostApi
import com.trugath.k8086.protocol.SystemRomDefaults
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmState
import com.trugath.k8086.protocol.VmSummary
import com.trugath.k8086.ui.PrintPreviewWindow
import com.trugath.k8086.ui.StartWizard
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

/**
 * VirtualBox-style manager: VM list, metrics, start/stop/console.
 */
class ManagerFrame(
    private val host: HostApi,
    private val defaultU18: String,
    private val defaultU19: String,
) : JFrame("k8086 Workstation") {
    private val listModel = DefaultListModel<VmSummary>()
    private val vmList = JList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        fixedCellWidth = 220
        font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val s = value as? VmSummary
                if (s != null) c.text = "${s.name}  [${s.state}]"
                return c
            }
        }
    }
    private val nameLabel = JLabel(" ")
    private val stateLabel = JLabel(" ")
    private val metricsArea = JTextArea(8, 40).apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = true
    }
    private val openConsoles = mutableMapOf<VmId, VmConsoleWindow>()
    private val openDebugWindows = mutableMapOf<VmId, VmDebugWindow>()
    private val printPreviewSeq = mutableMapOf<VmId, Int>()

    private val refreshTimer = Timer(500) { refreshList(preserveSelection = true) }
    private val printPollTimer = Timer(400) { pollPrintJobs() }

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        preferredSize = Dimension(900, 560)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("New…").also { it.addActionListener { onNewVm() } })
            add(JButton("Edit…").also { it.addActionListener { onEditVm() } })
            add(JButton("Networks…").also { it.addActionListener { onNetworks() } })
            add(JButton("Start").also { it.addActionListener { onStart() } })
            add(JButton("Stop").also { it.addActionListener { onStop() } })
            add(JButton("Console").also { it.addActionListener { onConsole() } })
            add(JButton("Debug").also { it.addActionListener { onDebug() } })
            add(JButton("Delete").also { it.addActionListener { onDelete() } })
            add(JButton("Refresh").also { it.addActionListener { refreshList() } })
        }

        val left = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Virtual machines")
            add(JScrollPane(vmList), BorderLayout.CENTER)
        }

        val detailHeader = JPanel(GridLayout(2, 1, 0, 4)).apply {
            border = EmptyBorder(8, 8, 8, 8)
            nameLabel.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
            add(nameLabel)
            add(stateLabel)
        }
        val right = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Details")
            add(detailHeader, BorderLayout.NORTH)
            add(JScrollPane(metricsArea), BorderLayout.CENTER)
        }

        vmList.addListSelectionListener {
            if (!it.valueIsAdjusting) updateDetails()
        }

        contentPane.layout = BorderLayout()
        contentPane.add(toolbar, BorderLayout.NORTH)
        contentPane.add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            resizeWeight = 0.35
        }, BorderLayout.CENTER)

        pack()
        setLocationRelativeTo(null)
        refreshList()
        refreshTimer.start()
        printPollTimer.start()
    }

    private fun pollPrintJobs() {
        for (summary in host.listVms()) {
            if (summary.state != VmState.Running && summary.state != VmState.Paused) continue
            val jobs = host.pollPrintJobs(summary.id)
            for (job in jobs) {
                val seq = (printPreviewSeq[summary.id] ?: 0) + 1
                printPreviewSeq[summary.id] = seq
                val title = "${summary.name} — Print Preview (#$seq)"
                PrintPreviewWindow(title, job.text, job.rawBytes).isVisible = true
            }
        }
    }

    private fun selected(): VmSummary? = vmList.selectedValue

    private fun refreshList(preserveSelection: Boolean = false) {
        val selectedId = if (preserveSelection) selected()?.id else null
        val vms = host.listVms()
        listModel.clear()
        vms.forEach { listModel.addElement(it) }
        if (selectedId != null) {
            val idx = (0 until listModel.size()).firstOrNull { listModel.get(it).id == selectedId }
            if (idx != null) vmList.selectedIndex = idx
        } else if (listModel.size() > 0 && vmList.selectedIndex < 0) {
            vmList.selectedIndex = 0
        }
        updateDetails()
        vmList.repaint()
    }

    private fun updateDetails() {
        val s = selected()
        if (s == null) {
            nameLabel.text = "No VM selected"
            stateLabel.text = " "
            metricsArea.text = ""
            return
        }
        nameLabel.text = s.name
        stateLabel.text = "State: ${s.state}" + (s.errorMessage?.let { " — $it" } ?: "")
        val m = host.metrics(s.id)
        val def = host.getDefinition(s.id)
        metricsArea.text = buildString {
            appendLine("ID: ${s.id}")
            if (m != null) {
                appendLine("Instructions: ${m.instructionCount}")
                appendLine("Uptime: ${m.uptimeMs / 1000}s")
                if (m.floppyPaths.isNotEmpty()) {
                    appendLine("Floppies:")
                    m.floppyPaths.forEachIndexed { i, p ->
                        appendLine("  ${'A' + i}: ${p ?: "(empty)"}")
                    }
                }
            }
            if (def != null) {
                appendLine()
                appendLine("Memory: ${def.motherboard.baseMemoryKb} KB")
                appendLine("Graphics: ${def.graphics}")
                appendLine("COM1: ${def.enableCom1}")
                appendLine("FDC: ${def.floppy.enabled}")
                appendLine("HD: ${def.hardDisk.enabled}")
                if (def.hardDisk.imagePath != null) appendLine("  HD image: ${def.hardDisk.imagePath}")
                appendLine("U18 ROM: ${def.u18RomPath}")
                appendLine("U19 ROM: ${def.u19RomPath}")
                if (def.cards.isNotEmpty()) {
                    appendLine("Cards: ${def.cards.count { it.enabled }}")
                }
            }
        }
    }

    private fun onNewVm() {
        val setup = try {
            StartWizard.show(host.network(), finishButtonLabel = "Create")
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(
                this,
                t.message ?: t.toString(),
                "New virtual machine",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        } ?: return
        val name = JOptionPane.showInputDialog(this, "VM name:", "New virtual machine", JOptionPane.QUESTION_MESSAGE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val roms = RomPickerDialog.show(
            this,
            "System ROMs",
            defaultU18,
            defaultU19,
            note = "Defaults are rmDOS U18/U19. Browse to override. Images are copied into the VM as immutable snapshots.",
        ) ?: return
        val def = SetupMapper.fromMachineSetup(setup, name, roms.first, roms.second)
        try {
            host.createVm(def)
            refreshList()
            val idx = (0 until listModel.size()).firstOrNull { listModel.get(it).id == def.id }
            if (idx != null) vmList.selectedIndex = idx
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(this, ex.message, "Create VM", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun onEditVm() {
        val s = selected() ?: return
        if (s.state != VmState.Stopped && s.state != VmState.Error) {
            JOptionPane.showMessageDialog(
                this,
                "Stop the VM before editing its definition or ROM snapshots.",
                "Edit",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        val def = host.getDefinition(s.id) ?: return
        val name = JOptionPane.showInputDialog(this, "VM name:", def.name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val roms = RomPickerDialog.show(
            this,
            "Edit system ROMs",
            def.u18RomPath,
            def.u19RomPath,
            note = "Browse to replace ROM snapshots (only while the VM is shut down). Unchanged paths keep the existing snapshots.",
        ) ?: return
        try {
            host.updateVm(def.copy(name = name, u18RomPath = roms.first, u19RomPath = roms.second))
            refreshList(preserveSelection = true)
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(this, ex.message, "Edit VM", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun onNetworks() {
        NetworksDialog(this, host.network()).isVisible = true
    }

    private fun onStart() {
        val s = selected() ?: return
        try {
            host.startVm(s.id)
            refreshList(preserveSelection = true)
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(this, ex.message, "Start", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun onStop() {
        val s = selected() ?: return
        try {
            host.stopVm(s.id)
            refreshList(preserveSelection = true)
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(this, ex.message, "Stop", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun onConsole() {
        val s = selected() ?: return
        if (s.state != VmState.Running && s.state != VmState.Starting && s.state != VmState.Paused) {
            JOptionPane.showMessageDialog(this, "Start the VM before opening a console.", "Console", JOptionPane.WARNING_MESSAGE)
            return
        }
        val existing = openConsoles[s.id]
        if (existing != null && existing.isDisplayable) {
            existing.toFront()
            return
        }
        val console = VmConsoleWindow(host, s.id, s.name, onOpenDebug = { openDebugFor(s.id, s.name) })
        openConsoles[s.id] = console
        console.isVisible = true
    }

    private fun onDebug() {
        val s = selected() ?: return
        if (s.state != VmState.Running && s.state != VmState.Starting && s.state != VmState.Paused) {
            JOptionPane.showMessageDialog(this, "Start the VM before opening the debugger.", "Debug", JOptionPane.WARNING_MESSAGE)
            return
        }
        openDebugFor(s.id, s.name)
    }

    private fun openDebugFor(id: VmId, name: String) {
        val existing = openDebugWindows[id]
        if (existing != null && existing.isDisplayable) {
            existing.toFront()
            return
        }
        val debug = VmDebugWindow(host, id, name)
        openDebugWindows[id] = debug
        debug.isVisible = true
    }

    private fun onDelete() {
        val s = selected() ?: return
        val ok = JOptionPane.showConfirmDialog(
            this,
            "Delete VM \"${s.name}\"? Definitions are removed; disk images on disk are kept.",
            "Delete",
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (ok != JOptionPane.OK_OPTION) return
        try {
            openConsoles.remove(s.id)?.dispose()
            openDebugWindows.remove(s.id)?.dispose()
            host.deleteVm(s.id)
            refreshList()
        } catch (ex: Exception) {
            JOptionPane.showMessageDialog(this, ex.message, "Delete", JOptionPane.ERROR_MESSAGE)
        }
    }

    companion object {
        fun launch(host: HostApi, u18: String, u19: String) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (_: Exception) {
            }
            SwingUtilities.invokeLater {
                ManagerFrame(host, u18, u19).isVisible = true
            }
        }
    }
}

/** Resolve default BIOS paths relative to the process working directory. */
fun defaultRomPaths(): Pair<String, String> = SystemRomDefaults.resolve()

fun romsExist(u18: String, u19: String): Boolean =
    File(u18).exists() && File(u19).exists()
