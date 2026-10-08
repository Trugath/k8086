package com.trugath.k8086.platform

import com.trugath.k8086.api.IsaCard

internal actual fun showPrintPreview(title: String, text: String, raw: ByteArray) {}

internal actual fun dumpCardDebug(
    cards: List<IsaCard>,
    dir: String,
    insn: Long,
    cpuDump: () -> String,
) {}

internal actual fun openLogSink(path: String): ByteSink = object : ByteSink {
    override fun write(b: Int) {}
    override fun close() {}
}
