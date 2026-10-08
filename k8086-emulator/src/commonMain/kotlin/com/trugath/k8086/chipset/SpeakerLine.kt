package com.trugath.k8086.chipset

internal interface SpeakerLine {
    fun available(): Int
    fun write(buf: ByteArray, off: Int, len: Int)
    fun close()
}

internal expect fun openSpeakerLine(enableAudio: Boolean): SpeakerLine?
