package com.trugath.k8086.cards.vga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** I/O + status coverage analogous to [com.trugath.k8086.CgaIoCoverageTest]. */
class VgaIoCoverageTest {
    @Test
    fun seqGcCrtcDacAndStatusRoundTrip() {
        val v = VgaCore()
        v.setMode13h()

        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x05)
        assertEquals(2, v.ioRead(0x3C4))
        assertEquals(0x05, v.ioRead(0x3C5))
        assertEquals(0x05, v.mapMask())

        v.ioWrite(0x3CE, 4)
        v.ioWrite(0x3CF, 0x02)
        assertEquals(4, v.ioRead(0x3CE))
        assertEquals(0x02, v.ioRead(0x3CF))
        assertEquals(0x02, v.readMapSelect())

        v.ioWrite(0x3D4, 0x0C)
        v.ioWrite(0x3D5, 0x01)
        v.ioWrite(0x3D4, 0x0D)
        v.ioWrite(0x3D5, 0x00)
        assertEquals(0x0100, v.startAddress())
        assertEquals(0x0D, v.ioRead(0x3D4))
        assertEquals(0x00, v.ioRead(0x3D5))

        v.ioWrite(0x3C2, 0x63)
        assertEquals(0x63, v.ioRead(0x3CC))

        v.ioWrite(0x3C6, 0xFF)
        assertEquals(0xFF, v.ioRead(0x3C6))

        // DAC write + read-back (6-bit PEL)
        v.ioWrite(0x3C8, 0x10)
        v.ioWrite(0x3C9, 0x20)
        v.ioWrite(0x3C9, 0x10)
        v.ioWrite(0x3C9, 0x08)
        v.ioWrite(0x3C7, 0x10)
        val r = v.ioRead(0x3C9)
        val g = v.ioRead(0x3C9)
        val b = v.ioRead(0x3C9)
        // 6↔8-bit scaling is lossy; require near-match.
        assertTrue(kotlin.math.abs(r - 0x20) <= 1)
        assertTrue(kotlin.math.abs(g - 0x10) <= 1)
        assertTrue(kotlin.math.abs(b - 0x08) <= 1)
        assertEquals(0x20 * 255 / 63, (v.dac[0x10] ushr 16) and 0xFF)

        val s0 = v.ioRead(0x3DA)
        v.tickCpuCycles(100_000)
        val s1 = v.ioRead(0x3DA)
        assertTrue(s0 in 0..0xFF && s1 in 0..0xFF)
    }

    @Test
    fun crtc0to7LockedWhenProtectBitSet() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3D4, 0x11)
        v.ioWrite(0x3D5, 0x80) // protect CRTC 0–7
        v.ioWrite(0x3D4, 0x01)
        v.ioWrite(0x3D5, 0x55)
        assertEquals(0, v.crtc[1], "protected CRTC index 1 must ignore writes")
        // 0C/0D still writable (games page flips)
        v.ioWrite(0x3D4, 0x0C)
        v.ioWrite(0x3D5, 0xAB)
        assertEquals(0xAB, v.crtc[0x0C])
    }

    @Test
    fun atcIndexDataAndFeatureReset() {
        val v = VgaCore()
        v.ioWrite(0x3C0, 0x10) // index
        v.ioWrite(0x3C0, 0x41) // data
        assertEquals(0x41, v.atc[0x10])
        assertFalse(v.atcFlipFlop)
        v.atcFlipFlop = true
        v.ioWrite(0x3DA, 0x00)
        assertFalse(v.atcFlipFlop)
    }

    @Test
    fun graphicsModesComposeWithoutWindow() {
        val v = VgaCore()
        v.setMode13h()
        // Chain-4 pixel at (0,0)
        v.memWrite(0, 0x2A)
        val chain = IntArray(320 * 200)
        v.composeFrame(chain)
        assertEquals(v.dac[0x2A] or 0xFF000000.toInt(), chain[0])

        // Mode Y (unchained): plane-select write then compose
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7)
        assertFalse(v.chain4())
        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x01)
        v.memWrite(0, 0x11)
        v.dac[0x11] = 0x00AABBCC
        val planar = IntArray(320 * 200)
        v.composeFrame(planar)
        assertEquals(0xFFAABBCC.toInt(), planar[0], "x=0 uses plane 0")
        assertEquals(v.dac[0] or 0xFF000000.toInt(), planar[1], "x=1 uses empty plane 1")
    }
}
