package com.trugath.k8086.platform

internal expect fun showPrintPreview(title: String, text: String, raw: ByteArray)

internal expect fun dumpCardDebug(
    cards: List<com.trugath.k8086.api.IsaCard>,
    dir: String,
    insn: Long,
    cpuDump: () -> String,
)

internal interface ByteSink {
    fun write(b: Int)
    fun close()
}

internal expect fun openLogSink(path: String): ByteSink
