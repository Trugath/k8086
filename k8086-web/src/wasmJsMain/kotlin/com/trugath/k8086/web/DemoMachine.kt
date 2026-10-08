@file:OptIn(ExperimentalJsExport::class, ExperimentalWasmJsInterop::class)

package com.trugath.k8086.web

import com.trugath.k8086.Machine
import com.trugath.k8086.MachineOptions
import com.trugath.k8086.platform.mountVirtualFile
import kotlin.js.ExperimentalJsExport
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsExport

private const val ROM_U18 = "/roms/u18.bin"
private const val ROM_U19 = "/roms/u19.bin"
private const val FLOPPY = "/disks/fd.img"

external class Uint8Array : JsAny {
    val length: Int
}

external class Int32Array : JsAny {
    val length: Int
}

@JsFun("(a, i) => a[i]")
external fun uint8Get(a: Uint8Array, i: Int): Int

@JsFun("(a, i, v) => { a[i] = v; }")
external fun int32Set(a: Int32Array, i: Int, v: Int)

private var machine: Machine? = null
private var lastWidth = 0
private var lastHeight = 0

@JsExport
fun createDemoMachine(u18: Uint8Array, u19: Uint8Array, floppy: Uint8Array): String {
    return try {
        machine?.shutdown()
        mountVirtualFile(ROM_U18, u18.toBytes())
        mountVirtualFile(ROM_U19, u19.toBytes())
        mountVirtualFile(FLOPPY, floppy.toBytes())
        val m = Machine(
            ROM_U18,
            ROM_U19,
            MachineOptions(
                showVideo = false,
                enableAudio = false,
                exitOnClose = false,
                realtime = false,
                turbo = true,
            ),
        )
        m.prepareBoot(listOf(FLOPPY))
        machine = m
        lastWidth = 0
        lastHeight = 0
        ""
    } catch (t: Throwable) {
        t.message ?: t.toString()
    }
}

@JsExport
fun runSlice(maxInstructions: Int): Boolean {
    val m = machine ?: return false
    return m.runSlice(maxInstructions.toLong().coerceAtLeast(1L))
}

@JsExport
fun pollFrame(dest: Int32Array): Boolean {
    val snap = machine?.copyFramebuffer() ?: return false
    lastWidth = snap.width
    lastHeight = snap.height
    val n = minOf(dest.length, snap.argb.size)
    var i = 0
    while (i < n) {
        int32Set(dest, i, snap.argb[i])
        i++
    }
    return true
}

@JsExport
fun frameWidth(): Int = lastWidth

@JsExport
fun frameHeight(): Int = lastHeight

@JsExport
fun sendScanCode(code: Int) {
    machine?.enqueueScanCode(code and 0xFF)
}

@JsExport
fun instructionCount(): Double = machine?.instructionCount()?.toDouble() ?: 0.0

@JsExport
fun reset() {
    machine?.prepareBoot(listOf(FLOPPY))
    lastWidth = 0
    lastHeight = 0
}

@JsExport
fun dispose() {
    machine?.shutdown()
    machine = null
}

fun main() {
    // JS loads this module, then calls [createDemoMachine].
}

private fun Uint8Array.toBytes(): ByteArray {
    val n = length
    val out = ByteArray(n)
    var i = 0
    while (i < n) {
        out[i] = uint8Get(this, i).toByte()
        i++
    }
    return out
}
