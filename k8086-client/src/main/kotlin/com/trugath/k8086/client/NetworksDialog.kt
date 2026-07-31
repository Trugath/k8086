package com.trugath.k8086.client

import com.trugath.k8086.protocol.NetworkApi
import com.trugath.k8086.protocol.NetworkDefinition
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

/**
 * Manager dialog for virtual NAT networks (gateway IP, DHCP).
 */
class NetworksDialog(
    owner: JFrame,
    private val networks: NetworkApi,
) : JDialog(owner, "Virtual networks", true) {
    private val model = DefaultListModel<String>()
    private val list = JList(model).also { it.selectionMode = ListSelectionModel.SINGLE_SELECTION }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(560, 360)
        contentPane.layout = BorderLayout(8, 8)
        (contentPane as JPanel).border = EmptyBorder(8, 8, 8, 8)
        add(JScrollPane(list), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).also { bar ->
            bar.add(JButton("New…").also { it.addActionListener { edit(null) } })
            bar.add(JButton("Edit…").also { it.addActionListener { editSelected() } })
            bar.add(JButton("Delete").also { it.addActionListener { deleteSelected() } })
            bar.add(JButton("Refresh").also { it.addActionListener { refresh() } })
            bar.add(JButton("Close").also { it.addActionListener { dispose() } })
        }, BorderLayout.SOUTH)
        refresh()
        pack()
        setLocationRelativeTo(owner)
    }

    private fun refresh() {
        model.clear()
        for (n in networks.listNetworks()) {
            val dhcp = if (n.dhcpEnabled) "DHCP ${n.dhcpStartIp}–${n.dhcpEndIp}" else "DHCP off"
            model.addElement("${n.id}: ${n.name}  gw=${n.gatewayIp}/${n.subnetMask}  $dhcp")
        }
    }

    private fun selectedId(): String? {
        val line = list.selectedValue ?: return null
        return line.substringBefore(':').trim().ifBlank { null }
    }

    private fun editSelected() {
        val id = selectedId() ?: return
        val def = networks.getNetwork(id) ?: return
        edit(def)
    }

    private fun deleteSelected() {
        val id = selectedId() ?: return
        val n = JOptionPane.showConfirmDialog(this, "Delete network '$id'?", "Delete", JOptionPane.YES_NO_OPTION)
        if (n != JOptionPane.YES_OPTION) return
        try {
            networks.deleteNetwork(id)
            refresh()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Delete failed", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun edit(existing: NetworkDefinition?) {
        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(3, 3, 3, 3)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        fun row(i: Int, label: String, field: JComponent) {
            c.gridx = 0; c.gridy = i; c.weightx = 0.0
            form.add(JLabel(label), c)
            c.gridx = 1; c.weightx = 1.0
            form.add(field, c)
        }
        val idField = JTextField(existing?.id ?: "", 16).also { it.isEnabled = existing == null }
        val nameField = JTextField(existing?.name ?: "Network", 16)
        val gwField = JTextField(existing?.gatewayIp ?: "10.0.2.2", 16)
        val maskField = JTextField(existing?.subnetMask ?: "255.255.255.0", 16)
        val dhcpCheck = JCheckBox("Enable DHCP", existing?.dhcpEnabled ?: true)
        val dhcpStart = JTextField(existing?.dhcpStartIp ?: "10.0.2.15", 16)
        val dhcpEnd = JTextField(existing?.dhcpEndIp ?: "10.0.2.31", 16)
        row(0, "Id:", idField)
        row(1, "Name:", nameField)
        row(2, "Gateway IP:", gwField)
        row(3, "Subnet mask:", maskField)
        row(4, "", dhcpCheck)
        row(5, "DHCP start:", dhcpStart)
        row(6, "DHCP end:", dhcpEnd)
        val title = if (existing == null) "New virtual network" else "Edit ${existing.id}"
        val ok = JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return
        val def = NetworkDefinition(
            id = idField.text.trim(),
            name = nameField.text.trim().ifBlank { idField.text.trim() },
            gatewayIp = gwField.text.trim(),
            subnetMask = maskField.text.trim(),
            dhcpEnabled = dhcpCheck.isSelected,
            dhcpStartIp = dhcpStart.text.trim(),
            dhcpEndIp = dhcpEnd.text.trim(),
        )
        try {
            if (existing == null) networks.createNetwork(def) else networks.updateNetwork(def)
            refresh()
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, e.message, "Save failed", JOptionPane.ERROR_MESSAGE)
        }
    }
}
