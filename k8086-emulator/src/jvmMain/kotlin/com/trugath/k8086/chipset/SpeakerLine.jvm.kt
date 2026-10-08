package com.trugath.k8086.chipset

import java.awt.GraphicsEnvironment
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

internal actual fun openSpeakerLine(enableAudio: Boolean): SpeakerLine? {
    if (!enableAudio || GraphicsEnvironment.isHeadless()) return null
    return try {
        val format = AudioFormat(PcSpeaker.SAMPLE_RATE.toFloat(), 8, 1, false, false)
        val info = javax.sound.sampled.DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) null
        else {
            val line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format, 4096)
            line.start()
            object : SpeakerLine {
                override fun available(): Int = line.available()
                override fun write(buf: ByteArray, off: Int, len: Int) {
                    line.write(buf, off, len)
                }
                override fun close() {
                    try {
                        line.stop()
                        line.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
