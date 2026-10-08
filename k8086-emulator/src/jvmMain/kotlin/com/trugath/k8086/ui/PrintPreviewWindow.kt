package com.trugath.k8086.ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.print.PageFormat
import java.awt.print.Printable
import java.awt.print.PrinterException
import java.awt.print.PrinterJob
import java.io.File
import java.nio.charset.Charset
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Host-side preview of an emulated LPT1 print job, with system Print and Save.
 */
class PrintPreviewWindow(
    title: String,
    text: String,
    private val rawBytes: ByteArray,
) : JFrame(title) {
    private val mono = Font(Font.MONOSPACED, Font.PLAIN, 12)
    private val textArea = JTextArea(text).apply {
        isEditable = false
        font = mono
        lineWrap = false
        caretPosition = 0
    }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(720, 560)
        layout = BorderLayout(6, 6)
        (contentPane as JPanel).border = EmptyBorder(8, 8, 8, 8)

        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        bar.add(JButton("Print…").also { it.addActionListener { onPrint() } })
        bar.add(JButton("Save…").also { it.addActionListener { onSave() } })
        bar.add(JButton("Close").also { it.addActionListener { dispose() } })

        add(bar, BorderLayout.NORTH)
        add(JScrollPane(textArea), BorderLayout.CENTER)
        pack()
        setLocationRelativeTo(null)
    }

    private fun onPrint() {
        val job = PrinterJob.getPrinterJob()
        job.setPrintable(TextPrintable(textArea.text, mono))
        job.jobName = title
        if (!job.printDialog()) return
        try {
            job.print()
        } catch (e: PrinterException) {
            JOptionPane.showMessageDialog(
                this,
                e.message ?: "Print failed",
                "Print",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private fun onSave() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save print job"
            fileFilter = FileNameExtensionFilter("Text files (*.txt)", "txt")
            selectedFile = File("lpt1-print.txt")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        try {
            file.writeBytes(rawBytes)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(
                this,
                e.message ?: "Save failed",
                "Save",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private class TextPrintable(
        private val text: String,
        private val font: Font,
    ) : Printable {
        override fun print(graphics: Graphics, pageFormat: PageFormat, pageIndex: Int): Int {
            val g2 = graphics as Graphics2D
            g2.font = font
            val metrics = g2.fontMetrics
            val lines = text.split('\n')
            val lineHeight = metrics.height
            val pageHeight = pageFormat.imageableHeight
            val linesPerPage = maxOf(1, (pageHeight / lineHeight).toInt())
            val start = pageIndex * linesPerPage
            if (start >= lines.size) return Printable.NO_SUCH_PAGE

            var y = pageFormat.imageableY + metrics.ascent
            val x = pageFormat.imageableX
            val end = minOf(lines.size, start + linesPerPage)
            for (i in start until end) {
                g2.drawString(lines[i], x.toFloat(), y.toFloat())
                y += lineHeight
            }
            return Printable.PAGE_EXISTS
        }
    }

    companion object {
        private val CP437: Charset = Charset.forName("IBM437")

        fun decodeCp437(bytes: ByteArray): String = String(bytes, CP437)
    }
}
