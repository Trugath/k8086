package com.trugath.k8086.platform

import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.ui.PrintPreviewWindow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.swing.SwingUtilities

internal actual fun showPrintPreview(title: String, text: String, raw: ByteArray) {
    val decoded = PrintPreviewWindow.decodeCp437(raw)
    SwingUtilities.invokeLater {
        PrintPreviewWindow(title, decoded, raw).isVisible = true
    }
}

internal actual fun dumpCardDebug(
    cards: List<IsaCard>,
    dir: String,
    insn: Long,
    cpuDump: () -> String,
) {
    HostPlatform.stderr(cpuDump())
    for (card in cards) {
        try {
            val snap = card.javaClass.methods.firstOrNull {
                it.name == "debugSnapshot" && it.parameterCount == 0
            }
            if (snap != null) {
                HostPlatform.stderr("  ${snap.invoke(card)}")
            }
            val m = card.javaClass.methods.firstOrNull {
                it.name == "writePng" && it.parameterCount == 1
            } ?: continue
            val path = File(dir, "frame-%09d.png".format(insn)).absolutePath
            val ok = m.invoke(card, path) as? Boolean ?: false
            if (ok) HostPlatform.stderr("VGA_DUMP $path")
        } catch (_: Exception) {
        }
    }
}

internal actual fun openLogSink(path: String): ByteSink {
    val stream = BufferedOutputStream(FileOutputStream(path, false))
    return object : ByteSink {
        override fun write(b: Int) {
            synchronized(stream) {
                stream.write(b)
                stream.flush()
            }
        }

        override fun close() {
            synchronized(stream) { stream.close() }
        }
    }
}
