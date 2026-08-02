package com.trugath.k8086.client

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

/** Chosen ROM source paths from [RomPickerDialog]. */
data class RomPickResult(
    val u18: String,
    val u19: String,
    val fdrom: String,
)

/**
 * Choose U18/U19/fdrom source images for VM edit.
 * Defaults are the shipped rmDOS ROMs; Browse overrides for this VM only.
 */
class RomPickerDialog(
    owner: java.awt.Window,
    title: String,
    initialU18: String,
    initialU19: String,
    initialFdrom: String,
    note: String = "These images are copied into the VM as immutable snapshots.",
) : JDialog(owner, title, ModalityType.APPLICATION_MODAL) {
    private val u18Field = JTextField(initialU18, 36)
    private val u19Field = JTextField(initialU19, 36)
    private val fdromField = JTextField(initialFdrom, 36)
    private var result: RomPickResult? = null

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val form = JPanel(GridBagLayout()).apply {
            border = EmptyBorder(12, 12, 8, 12)
            val c = GridBagConstraints().apply {
                insets = Insets(4, 4, 4, 4)
                anchor = GridBagConstraints.WEST
            }
            fun row(y: Int, label: String, field: JTextField) {
                c.gridx = 0; c.gridy = y; c.fill = GridBagConstraints.NONE; c.weightx = 0.0
                add(JLabel(label), c)
                c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0
                add(field, c)
                c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0.0
                add(JButton("Browse…").also { it.addActionListener { browse(field) } }, c)
            }
            row(0, "U18 ROM (32 KB):", u18Field)
            row(1, "U19 ROM (8 KB):", u19Field)
            row(2, "FD ROM (2 KB):", fdromField)

            c.gridx = 0; c.gridy = 3; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL
            add(JLabel("<html><i>$note</i></html>"), c)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(JButton("Cancel").also { it.addActionListener { dispose() } })
            add(JButton("OK").also { it.addActionListener { onOk() } })
        }
        contentPane.layout = BorderLayout()
        contentPane.add(form, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        preferredSize = Dimension(640, 220)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun browse(field: JTextField) {
        val chooser = JFileChooser().apply {
            selectedFile = File(field.text).takeIf { it.parentFile?.isDirectory == true }
                ?: File("roms")
            fileSelectionMode = JFileChooser.FILES_ONLY
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.text = chooser.selectedFile.absolutePath
        }
    }

    private fun onOk() {
        val u18 = u18Field.text.trim()
        val u19 = u19Field.text.trim()
        val fdrom = fdromField.text.trim()
        if (u18.isEmpty() || u19.isEmpty() || fdrom.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "U18, U19, and Fixed Disk ROM paths are required.",
                title,
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        if (!File(u18).isFile || !File(u19).isFile || !File(fdrom).isFile) {
            JOptionPane.showMessageDialog(
                this,
                "ROM file(s) not found:\n  $u18\n  $u19\n  $fdrom",
                title,
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }
        result = RomPickResult(u18, u19, fdrom)
        dispose()
    }

    companion object {
        fun show(
            owner: java.awt.Window,
            title: String,
            initialU18: String,
            initialU19: String,
            initialFdrom: String,
            note: String = "These images are copied into the VM as immutable snapshots.",
        ): RomPickResult? {
            val d = RomPickerDialog(owner, title, initialU18, initialU19, initialFdrom, note)
            d.isVisible = true
            return d.result
        }
    }
}
