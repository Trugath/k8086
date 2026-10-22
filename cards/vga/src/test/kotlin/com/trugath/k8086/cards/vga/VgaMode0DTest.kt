package com.trugath.k8086.cards.vga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BIOS mode 0Dh: 320×200, 16 colors, four bit planes, 40 bytes per scanline.
 * Bit 7 of a plane byte is the leftmost pixel. Color bit 0 comes from plane 0.
 */
class VgaMode0DTest {
    @Test
    fun setMode0DhIsPlanar16NotChain4() {
        val v = VgaCore()
        v.setMode0Dh()
        assertEquals(0x0D, v.biosMode)
        assertFalse(v.chain4())
        assertFalse(v.isTextMode())
        assertEquals(0x0F, v.mapMask())
        assertEquals(0, v.writeMode())
        assertEquals(0x05, v.gc[6] and 0xFF)
        assertEquals(0x14, v.crtc[0x13] and 0xFF)
    }

    @Test
    fun planeBit7IsLeftmostPixelAndNextRowIs40BytesOn() {
        val v = VgaCore()
        v.setMode0Dh()
        v.planes[0][0] = 0x80.toByte()
        v.planes[0][40] = 0x80.toByte()
        v.planes[1][1] = 0x80.toByte()
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        assertEquals(VgaCore.CGA_RGB[1], out[0])
        assertEquals(VgaCore.CGA_RGB[0], out[1])
        assertEquals(VgaCore.CGA_RGB[1], out[320])
        assertEquals(VgaCore.CGA_RGB[2], out[8])
    }

    @Test
    fun attributeControllerSelectsDac() {
        val v = VgaCore()
        v.setMode0Dh()
        v.planes[0][0] = 0x80.toByte()
        v.atc[1] = 4
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        assertEquals(VgaCore.CGA_RGB[4], out[0])
    }

    @Test
    fun writeMode1CopiesBackBufferAt4000h() {
        val v = VgaCore()
        v.setMode0Dh()
        v.planes[0][0x4000] = 0x80.toByte()
        v.planes[3][0x4000] = 0x80.toByte()
        v.gc[5] = 0x01
        v.memRead(0x4000)
        v.memWrite(0, 0)
        assertEquals(0x80, v.planes[0][0].toInt() and 0xFF)
        assertEquals(0x80, v.planes[3][0].toInt() and 0xFF)
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        assertEquals(VgaCore.CGA_RGB[9], out[0])
    }

    @Test
    fun mode13ComposeStaysChain4WhenBiosModeIs13h() {
        val v = VgaCore()
        v.setMode13h()
        v.memWrite(0, 0x2A)
        v.dac[0x2A] = 0xFF00FF00.toInt()
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        assertEquals(0xFF00FF00.toInt(), out[0])
    }

    @Test
    fun softMode0DhSetsCardAndBda() {
        val host = FakeIsaHost()
        val card = VgaCardFactory().create(mapOf("window" to "false", "exitOnClose" to "false")) as VgaCard
        card.attach(host)
        host.ioWrite(VgaBiosRom.SOFT_MODE_PORT, 0x0D)
        assertEquals(0x0D, card.core.biosMode)
        assertFalse(card.core.chain4())
        assertEquals(0x0D, host.cpuRead8(0x449))
        assertEquals(40, host.cpuRead8(0x44A))
        assertEquals(0x00, host.cpuRead8(0x44C))
        assertEquals(0x40, host.cpuRead8(0x44D))
        assertTrue(VgaBiosRom.build().size == 2048)
    }
}
