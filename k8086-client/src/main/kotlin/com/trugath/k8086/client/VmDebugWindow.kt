package com.trugath.k8086.client

import com.trugath.k8086.protocol.CpuDebugState
import com.trugath.k8086.protocol.HostApi
import com.trugath.k8086.protocol.VmId
import com.trugath.k8086.protocol.VmState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
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
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

/**
 * Debug window for one running VM: registers, memory dump, pause/step, breakpoints.
 */
class VmDebugWindow(
    private val host: HostApi,
    private val vmId: VmId,
    private val vmName: String,
) : JFrame("$vmName — Debug") {
    private val mono = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val regLabels = linkedMapOf<String, JLabel>()
    private val flagsLabel = JLabel(" ")
    private val nextInsnLabel = JLabel(" ")
    private val statusLabel = JLabel(" ")
    private val memAddressField = JTextField("00000", 8)
    private val memDumpArea = JTextArea(16, 68).apply {
        isEditable = false
        font = mono
    }
    private val bpListModel = DefaultListModel<String>()
    private val bpList = JList(bpListModel)
    private val bpAddressField = JTextField(10)
    private var lastState: CpuDebugState? = null
    private var memViewAddress = 0
    private val refreshTimer = Timer(500) { refresh() }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(820, 640)
        layout = BorderLayout(6, 6)
        (contentPane as JPanel).border = EmptyBorder(6, 6, 6, 6)

        add(buildToolbar(), BorderLayout.NORTH)
        add(
            JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                buildLeftPanel(),
                buildRightPanel(),
            ).apply { resizeWeight = 0.45 },
            BorderLayout.CENTER,
        )
        add(statusLabel, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                refreshTimer.start()
                refresh()
            }
            override fun windowClosing(e: WindowEvent?) {
                refreshTimer.stop()
            }
        })
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        bar.add(JButton("Pause").also {
            it.addActionListener {
                host.pauseVm(vmId)
                refresh()
            }
        })
        bar.add(JButton("Resume").also {
            it.addActionListener {
                host.resumeVm(vmId)
                refresh()
            }
        })
        bar.add(JButton("Step").also {
            it.addActionListener {
                if (!host.isPaused(vmId)) host.pauseVm(vmId)
                if (!host.stepVm(vmId)) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Step requires a running paused VM.",
                        "Step",
                        JOptionPane.WARNING_MESSAGE,
                    )
                }
                refresh()
            }
        })
        bar.add(JButton("Refresh").also { it.addActionListener { refresh() } })
        return bar
    }

    private fun buildLeftPanel(): JPanel {
        val panel = JPanel(BorderLayout(4, 4))
        panel.border = BorderFactory.createTitledBorder("CPU")

        val regs = JPanel(GridLayout(0, 4, 8, 2))
        for (name in listOf(
            "AX", "BX", "CX", "DX",
            "SP", "BP", "SI", "DI",
            "ES", "CS", "SS", "DS",
            "IP", "FL",
        )) {
            val label = JLabel("$name: ----", SwingConstants.LEFT).also { it.font = mono }
            regLabels[name] = label
            regs.add(label)
        }
        panel.add(regs, BorderLayout.NORTH)

        flagsLabel.font = mono
        nextInsnLabel.font = mono
        val mid = JPanel(GridLayout(0, 1, 0, 4)).apply {
            border = EmptyBorder(8, 4, 4, 4)
            add(flagsLabel)
            add(nextInsnLabel)
        }
        panel.add(mid, BorderLayout.CENTER)
        return panel
    }

    private fun buildRightPanel(): JPanel {
        val panel = JPanel(BorderLayout(4, 4))

        val memPanel = JPanel(BorderLayout(4, 4))
        memPanel.border = BorderFactory.createTitledBorder("Memory")
        val memBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        memAddressField.font = mono
        memBar.add(JLabel("Addr:"))
        memBar.add(memAddressField)
        memBar.add(JButton("Go").also {
            it.addActionListener {
                parseHex(memAddressField.text)?.let { addr ->
                    memViewAddress = addr and 0xFFFFFF
                    refreshMemory()
                }
            }
        })
        memBar.add(JButton("CS:IP").also {
            it.addActionListener {
                lastState?.let { s ->
                    memViewAddress = s.linearCsIp and 0xFFFFFF
                    memAddressField.text = "%05X".format(memViewAddress)
                    refreshMemory()
                }
            }
        })
        memBar.add(JButton("SS:SP").also {
            it.addActionListener {
                lastState?.let { s ->
                    memViewAddress = ((s.ss shl 4) + s.sp) and 0xFFFFF
                    memAddressField.text = "%05X".format(memViewAddress)
                    refreshMemory()
                }
            }
        })
        memPanel.add(memBar, BorderLayout.NORTH)
        memPanel.add(JScrollPane(memDumpArea), BorderLayout.CENTER)

        val bpPanel = JPanel(BorderLayout(4, 4))
        bpPanel.border = BorderFactory.createTitledBorder("Breakpoints (linear)")
        bpAddressField.font = mono
        val bpBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        bpBar.add(bpAddressField)
        bpBar.add(JButton("Add").also {
            it.addActionListener {
                val addr = parseHex(bpAddressField.text)
                if (addr == null) {
                    JOptionPane.showMessageDialog(this, "Enter a hex linear address.", "Breakpoint", JOptionPane.WARNING_MESSAGE)
                    return@addActionListener
                }
                host.addBreakpoint(vmId, addr)
                refreshBreakpoints()
            }
        })
        bpBar.add(JButton("Remove").also {
            it.addActionListener {
                val selected = bpList.selectedValue ?: return@addActionListener
                parseHex(selected)?.let { host.removeBreakpoint(vmId, it) }
                refreshBreakpoints()
            }
        })
        bpPanel.add(bpBar, BorderLayout.NORTH)
        bpList.font = mono
        bpPanel.add(JScrollPane(bpList), BorderLayout.CENTER)
        bpPanel.preferredSize = Dimension(200, 140)

        panel.add(memPanel, BorderLayout.CENTER)
        panel.add(bpPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun refresh() {
        val summary = host.listVms().find { it.id == vmId }
        if (summary == null || summary.state == VmState.Stopped || summary.state == VmState.Error) {
            refreshTimer.stop()
            title = "$vmName — Debug (stopped)"
            statusLabel.text = "VM stopped"
            return
        }
        val paused = host.isPaused(vmId) || summary.state == VmState.Paused
        title = if (paused) "$vmName — Debug (paused)" else "$vmName — Debug"
        refreshTimer.delay = if (paused) 100 else 500

        val state = host.getCpuDebugState(vmId)
        if (state != null) {
            lastState = state
            setReg("AX", state.ax)
            setReg("BX", state.bx)
            setReg("CX", state.cx)
            setReg("DX", state.dx)
            setReg("SP", state.sp)
            setReg("BP", state.bp)
            setReg("SI", state.si)
            setReg("DI", state.di)
            setReg("ES", state.es)
            setReg("CS", state.cs)
            setReg("SS", state.ss)
            setReg("DS", state.ds)
            setReg("IP", state.ip)
            setReg("FL", state.flags)
            flagsLabel.text = "<html>Flags: ${formatFlags(state.flags)}<br>" +
                "CS:IP=${hex16(state.cs)}:${hex16(state.ip)}&nbsp;&nbsp;linear=${hex20(state.linearCsIp)}</html>"
            val bytes = state.nextBytes.take(state.nextLength).joinToString(" ") { "%02X".format(it) }
            nextInsnLabel.text = "Next (${state.nextLength}): $bytes" + if (state.halted) "  (halted)" else ""
            statusLabel.text = "Instructions: ${state.instructionCount}" + if (paused) "  (paused)" else ""
        } else {
            statusLabel.text = "No CPU state"
        }
        refreshMemory()
        refreshBreakpoints()
    }

    private fun refreshMemory() {
        memAddressField.text = "%05X".format(memViewAddress)
        val dump = host.readGuestMemory(vmId, memViewAddress, 256) ?: run {
            memDumpArea.text = ""
            return
        }
        memDumpArea.text = buildString {
            var i = 0
            while (i < dump.bytes.size) {
                val rowAddr = (dump.address + i) and 0xFFFFFF
                append("%05X  ".format(rowAddr))
                val row = dump.bytes.subList(i, minOf(i + 16, dump.bytes.size))
                for (b in 0 until 16) {
                    if (b < row.size) append("%02X ".format(row[b])) else append("   ")
                    if (b == 7) append(' ')
                }
                append(' ')
                for (b in row) {
                    val ch = b and 0xFF
                    append(if (ch in 32..126) ch.toChar() else '.')
                }
                appendLine()
                i += 16
            }
        }
    }

    private fun refreshBreakpoints() {
        val selected = bpList.selectedValue
        bpListModel.clear()
        host.listBreakpoints(vmId).forEach { bpListModel.addElement("%05X".format(it and 0xFFFFFF)) }
        if (selected != null) {
            val idx = (0 until bpListModel.size()).firstOrNull { bpListModel.get(it) == selected }
            if (idx != null) bpList.selectedIndex = idx
        }
    }

    private fun setReg(name: String, value: Int) {
        regLabels[name]?.text = "$name: ${hex16(value)}"
    }

    private fun formatFlags(flags: Int): String = buildString {
        fun bit(mask: Int, label: String) {
            append(if ((flags and mask) != 0) label else label.lowercase())
            append(' ')
        }
        bit(0x0001, "CF")
        bit(0x0004, "PF")
        bit(0x0010, "AF")
        bit(0x0040, "ZF")
        bit(0x0080, "SF")
        bit(0x0100, "TF")
        bit(0x0200, "IF")
        bit(0x0400, "DF")
        bit(0x0800, "OF")
    }.trimEnd()

    private fun parseHex(text: String): Int? {
        val cleaned = text.trim().removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return null
        return cleaned.toIntOrNull(16)
    }

    private fun hex16(value: Int) = "%04X".format(value and 0xFFFF)
    private fun hex20(value: Int) = "%05X".format(value and 0xFFFFF)
}
