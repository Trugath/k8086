package com.trugath.k8086.cards.vga

import com.trugath.k8086.api.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class VgaCardTest {
    private fun attachHeadless(config: Map<String, String> = emptyMap()): Pair<FakeIsaHost, VgaCard> {
        val host = FakeIsaHost()
        val merged = mapOf("window" to "false", "exitOnClose" to "false") + config
        val card = VgaCardFactory().create(merged) as VgaCard
        card.attach(host)
        return host to card
    }

    @Test
    fun attachMapsRomMmioAndIoPorts() {
        val (host, _) = attachHeadless()
        assertTrue(host.optionRoms.any { it.first == 0xC0000 })
        val rom = host.optionRoms.first { it.first == 0xC0000 }.second
        assertEquals(0x55, rom[0].toInt() and 0xFF)
        assertEquals(0xAA, rom[1].toInt() and 0xFF)
        assertEquals(0x55, host.cpuRead8(0xC0000))
        assertEquals(0xAA, host.cpuRead8(0xC0001))

        assertTrue(host.hasMmioAt(0xA0000))
        assertTrue(host.hasMmioAt(0xB8000))
        assertTrue(host.hasIoPort(0x3C4))
        assertTrue(host.hasIoPort(0x3DA))
        assertTrue(host.hasIoPort(VgaBiosRom.SOFT_ATTR_PORT))
        assertTrue(host.hasIoPort(VgaBiosRom.SOFT_MODE_PORT))
        assertTrue(host.hasIoPort(VgaBiosRom.SOFT_TTY_PORT))
    }

    @Test
    fun softMode13hSetsCoreAndBda() {
        val (host, card) = attachHeadless()
        host.ioWrite(VgaBiosRom.SOFT_MODE_PORT, 0x13)
        assertEquals(0x13, card.core.biosMode)
        assertTrue(card.core.chain4())
        assertFalse(card.core.isTextMode())
        assertEquals(0x13, host.cpuRead8(0x449))
        assertEquals(40, host.cpuRead8(0x44A))
        assertEquals(0x13, host.ioRead(VgaBiosRom.SOFT_MODE_PORT))
    }

    @Test
    fun softMode03hRestoresTextAndClearsCursor() {
        val (host, card) = attachHeadless()
        host.ioWrite(VgaBiosRom.SOFT_MODE_PORT, 0x13)
        host.cpuWrite8(0x450, 10)
        host.cpuWrite8(0x451, 5)
        host.ioWrite(VgaBiosRom.SOFT_MODE_PORT, 0x03)
        assertTrue(card.core.isTextMode())
        assertEquals(0x03, card.core.biosMode)
        assertEquals(0x03, host.cpuRead8(0x449))
        assertEquals(80, host.cpuRead8(0x44A))
        assertEquals(0, host.cpuRead8(0x450))
        assertEquals(0, host.cpuRead8(0x451))
    }

    @Test
    fun softTeletypeWritesCellAndAdvancesBdaCursor() {
        val (host, card) = attachHeadless()
        host.ioWrite(VgaBiosRom.SOFT_ATTR_PORT, 0x1E)
        assertEquals(0x1E, host.ioRead(VgaBiosRom.SOFT_ATTR_PORT))
        host.ioWrite(VgaBiosRom.SOFT_TTY_PORT, 'Q'.code)
        assertEquals('Q'.code, card.core.textBuffer[0].toInt() and 0xFF)
        assertEquals(0x1E, card.core.textBuffer[1].toInt() and 0xFF)
        assertEquals(1, host.cpuRead8(0x450))
        assertEquals(0, host.cpuRead8(0x451))
    }

    @Test
    fun mmioPassthroughToPlanarVram() {
        val (host, card) = attachHeadless()
        host.ioWrite(VgaBiosRom.SOFT_MODE_PORT, 0x13)
        host.memWrite(0xA0000 + 5, 0xDE)
        assertEquals(0xDE, host.memRead(0xA0000 + 5))
        assertEquals(0xDE, card.core.memRead(5))
        assertEquals(0xDE, card.core.planes[1][1].toInt() and 0xFF)
    }

    @Test
    fun tickAdvancesFrameCycleAndRepairsBdaMode13h() {
        val (host, card) = attachHeadless()
        host.ioWrite(VgaBiosRom.SOFT_MODE_PORT, 0x13)
        val before = card.core.frameCycle
        host.tick(1_000)
        assertEquals(before + 1_000, card.core.frameCycle)

        host.cpuWrite8(0x449, 0x00) // clobber BDA mode
        host.tick(10)
        assertEquals(0x13, host.cpuRead8(0x449))
        assertEquals(40, host.cpuRead8(0x44A))
    }

    @Test
    fun writePngTextAndGraphics() {
        val (host, card) = attachHeadless()
        val dir = Files.createTempDirectory("vga-card-test").toFile()
        try {
            card.core.textBuffer[0] = 'A'.code.toByte()
            card.core.textBuffer[1] = 0x0F
            val textPng = File(dir, "text.png")
            assertTrue(card.writePng(textPng.absolutePath))
            assertTrue(textPng.exists() && textPng.length() > 0)

            host.ioWrite(VgaBiosRom.SOFT_MODE_PORT, 0x13)
            card.core.dac[7] = 0x00112233
            card.core.planes[0][0] = 7
            val gfxPng = File(dir, "gfx.png")
            assertTrue(card.writePng(gfxPng.absolutePath))
            assertTrue(gfxPng.exists() && gfxPng.length() > 0)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun detachHeadlessDoesNotThrow() {
        val (host, card) = attachHeadless()
        card.detach()
        // Soft ports still mapped on fake host; card host ref cleared so teletype is a no-op.
        host.ioWrite(VgaBiosRom.SOFT_TTY_PORT, 'X'.code)
    }

    @Test
    fun romBaseConfigMapsOptionRomAtC8000() {
        val claims = VgaCardFactory().resourceClaims(mapOf("romBase" to "0xC8000"))
        assertTrue(
            claims.any {
                it.kind == ResourceKind.MEMORY && it.start == 0xC8000 && it.endInclusive == 0xC8000 + 2047
            },
        )
        val (host, _) = attachHeadless(mapOf("romBase" to "0xC8000"))
        assertTrue(host.optionRoms.any { it.first == 0xC8000 })
        assertEquals(0x55, host.cpuRead8(0xC8000))
        assertEquals(0xAA, host.cpuRead8(0xC8001))
        assertFalse(host.optionRoms.any { it.first == 0xC0000 })
    }
}
