package com.trugath.k8086.cpu

/**
 * Persistent host-side hooks consulted by the INT dispatcher.
 *
 * Created at machine setup; never allocated on the instruction hot path.
 */
internal class CpuHostServices {
    /** Invoked just before DOS terminate (INT 20h / INT 21h AH=00h/4Ch). */
    var onDosTerminate: (() -> Unit)? = null

    /**
     * Optional INT 13h hard-disk handler. When set and DL has bit 7, returning
     * true means the shim fully handled the service and the BIOS vector must not run.
     */
    var onInt13HardDisk: (() -> Boolean)? = null

    /**
     * Optional INT 13h floppy handler. When set and DL &lt; 0x80, returning true
     * means the shim fully handled the service and the BIOS vector must not run.
     */
    var onInt13Floppy: (() -> Boolean)? = null
}
