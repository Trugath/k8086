package com.trugath.k8086.cpu

import com.trugath.k8086.api.CpuModel
/**
 * Intel 8088 CPU emulator.
 *
 * The 8088 shares the 8086 instruction engine but exposes deterministic OF
 * results for multi-bit shifts and rotates observed in the 8088 corpus.
 */
internal class Emulator8088 : Emulator8086(DecodeProfiles.I8088) {
    override val model: CpuModel = CpuModel.I8088

    override fun overflowAfterShiftRotate(
        operation: Int,
        count: Int,
        originalValue: Int,
        result: Int,
        carry: Boolean,
        signMask: Int,
    ): Int =
        definedShiftRotateOverflow(operation, count, originalValue, result, carry, signMask)
}
