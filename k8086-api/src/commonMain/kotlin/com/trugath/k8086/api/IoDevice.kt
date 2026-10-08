package com.trugath.k8086.api

/** A device attached to the 8088 I/O port space (byte-wide transfers). */
interface IoDevice {
    fun ioReadByte(port: Int): Int
    fun ioWriteByte(port: Int, value: Int)
}
