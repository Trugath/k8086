package com.trugath.k8086.api

/** Claimed ISA DMA channel (direct byte transfer API, matching XT FDC usage). */
interface DmaChannel {
    val channel: Int
    fun readByte(): Int
    fun writeByte(value: Int)
    fun isMasked(): Boolean
}
