package com.trugath.k8086.cards.vga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VgaCoreTest {
    @Test
    fun mode13hEnablesChain4AndIdentityDac() {
        val v = VgaCore()
        v.setMode13h()
        assertTrue(v.chain4())
        assertEquals(0x0F, v.mapMask())
        assertEquals(0x13, v.biosMode)
        assertFalse(v.isTextMode())
        // DAC write PEL
        v.ioWrite(0x3C8, 1)
        v.ioWrite(0x3C9, 0x3F)
        v.ioWrite(0x3C9, 0x00)
        v.ioWrite(0x3C9, 0x00)
        assertEquals(0xFF0000, v.dac[1] and 0xFFFFFF)
    }

    @Test
    fun writeMode0PlanePokeProducesLitPixels() {
        val v = VgaCore()
        v.setMode13h()
        // Mode Y: clear chain-4
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7)
        assertFalse(v.chain4())
        assertEquals(0, v.writeMode())

        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x0F)
        v.dac[0x2A] = 0x00FF00 // green
        v.memWrite(0, 0x2A)

        val out = IntArray(320 * 200)
        v.composeFrame(out)
        var lit = 0
        for (p in out) if ((p and 0xFFFFFF) != 0) lit++
        assertTrue(lit > 0, "mode-0 planar write should light pixels")
    }

    @Test
    fun writeMode1CopiesLatches() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7)

        // Seed source address
        for (p in 0 until 4) v.planes[p][0x10] = (0xA0 + p).toByte()

        // Read loads latches
        v.ioWrite(0x3CE, 4)
        v.ioWrite(0x3CF, 0) // read map 0
        assertEquals(0xA0, v.memRead(0x10))

        // Write mode 1 → dest
        v.ioWrite(0x3CE, 5)
        v.ioWrite(0x3CF, (v.gc[5] and 0xFC) or 0x01)
        assertEquals(1, v.writeMode())

        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x0F)
        v.memWrite(0x20, 0x00) // CPU data ignored

        for (p in 0 until 4) {
            assertEquals(0xA0 + p, v.planes[p][0x20].toInt() and 0xFF)
            assertEquals(0xA0 + p, v.planes[p][0x10].toInt() and 0xFF)
        }
    }

    @Test
    fun writeMode1VhUpdateScreenCrossPage() {
        // Mirrors VH_UpdateScreen: OUT map-mask F, set WM1 via read-modify 3CFh,
        // then byte latch copies from PAGE1 (0) onto PAGE3 (0x8200).
        val v = VgaCore()
        v.setMode13h()
        // Mode Y: clear chain-4, set odd/even disable (VL_DePlaneVGA)
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, (v.seq[4] and 0xF7) or 0x04)
        assertFalse(v.chain4())

        val src = 0x0100
        val dst = 0x8200 + 0x0100
        for (p in 0 until 4) {
            v.planes[p][src] = (0x40 + p).toByte()
            v.planes[p][dst] = 0x11 // stale display page
        }

        // mov ax, SC_MAPMASK+15*256 ; out dx,ax
        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x0F)
        // GC_MODE read-modify → write mode 1
        v.ioWrite(0x3CE, 5)
        val mode = v.ioRead(0x3CF)
        v.ioWrite(0x3CF, (mode and 0xFC) or 1)
        assertEquals(1, v.writeMode())

        // mov al,[si] ; mov [di],al
        assertEquals(0x40, v.memRead(src))
        v.memWrite(dst, 0xFF) // CPU data ignored
        for (p in 0 until 4) {
            assertEquals(0x40 + p, v.planes[p][dst].toInt() and 0xFF)
        }

        // Restore write mode 0 via GC_INDEX+1 (index left at 5)
        val restored = v.ioRead(0x3CF) and 0xFC
        v.ioWrite(0x3CF, restored)
        assertEquals(0, v.writeMode())
        assertTrue(v.wm1WriteCount > 0)
    }

    @Test
    fun writeMode2ExpandsNibblePerPlane() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7) // clear chain-4

        v.ioWrite(0x3CE, 5)
        v.ioWrite(0x3CF, (v.gc[5] and 0xFC) or 0x02)
        assertEquals(2, v.writeMode())

        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x0F)
        // CPU low nibble 0b0101 → planes 0 and 2 = 0xFF, 1 and 3 = 0x00
        v.memWrite(0x40, 0x05)
        assertEquals(0xFF, v.planes[0][0x40].toInt() and 0xFF)
        assertEquals(0x00, v.planes[1][0x40].toInt() and 0xFF)
        assertEquals(0xFF, v.planes[2][0x40].toInt() and 0xFF)
        assertEquals(0x00, v.planes[3][0x40].toInt() and 0xFF)

        v.ioWrite(0x3C5, 0x0A) // planes 1 and 3 only
        v.memWrite(0x41, 0x0F)
        assertEquals(0x00, v.planes[0][0x41].toInt() and 0xFF) // masked off
        assertEquals(0xFF, v.planes[1][0x41].toInt() and 0xFF)
        assertEquals(0x00, v.planes[2][0x41].toInt() and 0xFF)
        assertEquals(0xFF, v.planes[3][0x41].toInt() and 0xFF)
    }

    @Test
    fun writeMode0BitMaskMergesWithLatches() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7)
        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x01) // plane 0 only
        // Seed plane and load latches
        v.planes[0][0x50] = 0xF0.toByte()
        v.memRead(0x50)
        v.ioWrite(0x3CE, 8)
        v.ioWrite(0x3CF, 0x0F) // low nibble writable
        v.memWrite(0x50, 0x0A)
        // high nibble from latch 0xF0, low from CPU 0x0A
        assertEquals(0xFA, v.planes[0][0x50].toInt() and 0xFF)
    }

    @Test
    fun writeMode0SetResetFillsPlanes() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7)
        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x0F)
        v.ioWrite(0x3CE, 0) // set/reset
        v.ioWrite(0x3CF, 0x05) // planes 0 and 2 set
        v.ioWrite(0x3CE, 1) // enable set/reset
        v.ioWrite(0x3CF, 0x0F) // all planes from set/reset
        v.memWrite(0x60, 0x00) // CPU data ignored
        assertEquals(0xFF, v.planes[0][0x60].toInt() and 0xFF)
        assertEquals(0x00, v.planes[1][0x60].toInt() and 0xFF)
        assertEquals(0xFF, v.planes[2][0x60].toInt() and 0xFF)
        assertEquals(0x00, v.planes[3][0x60].toInt() and 0xFF)
    }

    @Test
    fun writeMode0DataRotate() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7)
        v.ioWrite(0x3C4, 2)
        v.ioWrite(0x3C5, 0x01)
        v.ioWrite(0x3CE, 3)
        v.ioWrite(0x3CF, 0x01) // rotate right 1
        v.memWrite(0x70, 0x80)
        assertEquals(0x40, v.planes[0][0x70].toInt() and 0xFF)
    }

    @Test
    fun composeAppliesPelPan() {
        val v = VgaCore()
        v.setMode13h()
        // Mode Y
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, v.seq[4] and 0xF7)
        // Pixel x=1 → plane 1, addr 0
        v.planes[1][0] = 0x2A
        v.dac[0x2A] = 0x00ABCDEF
        // ATC pel pan = 1 via flip-flop
        v.ioWrite(0x3DA, 0) // reset flip-flop
        v.ioWrite(0x3C0, 0x13)
        v.ioWrite(0x3C0, 0x01)
        assertEquals(1, v.pelPan())
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        // Display x=0 samples source x=1
        assertEquals(0xFFABCDEF.toInt(), out[0])
    }

    @Test
    fun chain4MemReadWriteRoundTrip() {
        val v = VgaCore()
        v.setMode13h()
        v.memWrite(5, 0xDE) // plane 1, addr 1
        assertEquals(0xDE, v.memRead(5))
        assertEquals(0xDE, v.planes[1][1].toInt() and 0xFF)
    }

    @Test
    fun crtcStartAddressLatches() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3D4, 0x0C)
        v.ioWrite(0x3D5, 0x12)
        v.ioWrite(0x3D4, 0x0D)
        v.ioWrite(0x3D5, 0x34)
        assertEquals(0x1234, v.startAddress())
    }

    @Test
    fun composeRespectsCrtcStartAddress() {
        val v = VgaCore()
        v.setMode13h()
        // Page at offset 80*200 = 16000 bytes in chain-4 address space → start 0x3E80
        // Simpler: start=1 shifts first pixel to come from plane addr row+…
        v.planes[0][0] = 0
        v.planes[0][1] = 9
        v.dac[9] = 0x00112233
        v.ioWrite(0x3D4, 0x0C)
        v.ioWrite(0x3D5, 0x00)
        v.ioWrite(0x3D4, 0x0D)
        v.ioWrite(0x3D5, 0x01) // start = 1
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        // Pixel (0,0) reads plane0[start+0]=plane0[1]
        assertEquals(0xFF112233.toInt(), out[0])
    }

    @Test
    fun status3daHasVRetraceAndClearsAtcFlipFlop() {
        val v = VgaCore()
        v.atcFlipFlop = true
        // status3da advances ~1/4 scanline; land well inside vblank afterwards.
        val vblankStart = 400 * VgaCore.CYCLES_PER_LINE
        v.frameCycle = (vblankStart + VgaCore.CYCLES_PER_LINE).toLong()
        val st = v.status3da()
        assertTrue((st and 0x08) != 0)
        assertFalse(v.atcFlipFlop)
    }

    @Test
    fun status3daBusyWaitObservesVsyncWithinOneFrame() {
        val v = VgaCore()
        v.setMode13h()
        v.frameCycle = 0
        var sawOff = false
        var sawOn = false
        // Emulate VL_WaitVBL: each IN is followed by a few instructions' worth of ticks.
        var spins = 0
        while (spins < 40_000 && !sawOn) {
            val st = v.status3da()
            v.tickCpuCycles(30)
            if ((st and 0x08) == 0) sawOff = true
            if (sawOff && (st and 0x08) != 0) sawOn = true
            spins++
        }
        assertTrue(sawOff, "expected non-vsync sample")
        assertTrue(sawOn, "expected vsync sample within busy-wait")
        assertTrue(v.status3daSawVSync)
    }

    @Test
    fun status3daAllowsSuccessiveHBlankSamples() {
        val v = VgaCore()
        v.setMode13h()
        // Land in HBlank (not VBlank): line 0, past ACTIVE_LINE_CYCLES.
        v.frameCycle = VgaCore.ACTIVE_LINE_CYCLES.toLong() + 1
        var streak = 0
        var maxStreak = 0
        repeat(20) {
            val st = v.status3da()
            // ~IN + test/jnz between VL_SetScreen samples
            v.tickCpuCycles(28)
            if ((st and 0x09) == 0x01) {
                streak++
                maxStreak = maxOf(maxStreak, streak)
            } else {
                streak = 0
            }
        }
        assertTrue(maxStreak >= 5, "VL_SetScreen needs 5 successive HBlank polls, got $maxStreak")
    }

    @Test
    fun optionRomHasSignatureChecksumAndInt10Entry() {
        val rom = VgaBiosRom.build()
        assertEquals(0x55, rom[0].toInt() and 0xFF)
        assertEquals(0xAA, rom[1].toInt() and 0xFF)
        assertEquals(0x04, rom[2].toInt() and 0xFF)
        assertEquals(0xFA, rom[3].toInt() and 0xFF) // CLI
        var sum = 0
        for (b in rom) sum = (sum + (b.toInt() and 0xFF)) and 0xFF
        assertEquals(0, sum, "option ROM checksum must be 0 mod 256")
        // Soft mode port constant used by ROM OUT
        assertEquals(0x1CE, VgaBiosRom.SOFT_MODE_PORT)
        assertEquals(0x1CF, VgaBiosRom.SOFT_TTY_PORT)
        assertEquals(0x1CD, VgaBiosRom.SOFT_ATTR_PORT)
    }

    @Test
    fun composeChain4FrameUsesDac() {
        val v = VgaCore()
        v.setMode13h()
        v.dac[7] = 0x00112233
        v.planes[0][0] = 7
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        assertEquals(0xFF112233.toInt(), out[0])
    }

    @Test
    fun composeModeYInterleavesPlanesByX() {
        val v = VgaCore()
        v.setMode13h()
        v.ioWrite(0x3C4, 4)
        v.ioWrite(0x3C5, 0x06) // no chain-4, odd/even disable
        assertFalse(v.chain4())
        for (p in 0 until 4) {
            v.planes[p][0] = (0x40 + p).toByte()
            v.dac[0x40 + p] = (0x10 + p) shl 16
        }
        val out = IntArray(320 * 200)
        v.composeFrame(out)
        for (x in 0 until 4) {
            assertEquals(
                ((0x10 + x) shl 16) or 0xFF000000.toInt(),
                out[x],
                "pixel x=$x",
            )
        }
    }

    @Test
    fun mode03hClearsTextBufferAndComposesGlyph() {
        val v = VgaCore()
        v.setMode03h()
        assertTrue(v.isTextMode())
        assertEquals(0x20, v.textBuffer[0].toInt() and 0xFF)
        assertEquals(0x07, v.textBuffer[1].toInt() and 0xFF)
        v.textBuffer[0] = 'A'.code.toByte()
        v.textBuffer[1] = 0x1F
        val out = IntArray(VgaCore.TEXT_PIXEL_W * VgaCore.TEXT_PIXEL_H)
        v.composeTextFrame(out)
        assertTrue(out.any { it == VgaCore.CGA_RGB[0x0F] })
        // Background of cell is blue (attr 0x1F → bg 1)
        assertTrue(out.any { it == VgaCore.CGA_RGB[0x01] })
    }

    @Test
    fun textMemReadWriteAndB800Alias() {
        val v = VgaCore()
        v.setMode03h()
        v.textMemWrite(0, 'X'.code)
        v.textMemWrite(1, 0x0E)
        assertEquals('X'.code, v.textMemRead(0))
        assertEquals(0x0E, v.textMemRead(1))
    }

    @Test
    fun teletypeAdvancesCursorAndWritesCell() {
        val v = VgaCore()
        v.setMode03h()
        var row = 0
        var col = 0
        v.teletype(
            'Z'.code,
            0x07,
            readCursor = { row to col },
            writeCursor = { r, c -> row = r; col = c },
        )
        assertEquals('Z'.code, v.textBuffer[0].toInt() and 0xFF)
        assertEquals(0, row)
        assertEquals(1, col)
    }

    @Test
    fun teletypeCrLfAndScroll() {
        val v = VgaCore()
        v.setMode03h()
        var row = 0
        var col = 5
        v.teletype(0x0D, readCursor = { row to col }, writeCursor = { r, c -> row = r; col = c })
        assertEquals(0, col)
        assertEquals(0, row)
        v.teletype(0x0A, readCursor = { row to col }, writeCursor = { r, c -> row = r; col = c })
        assertEquals(1, row)

        // Fill last row then LF → scroll
        row = VgaCore.ROWS - 1
        col = 0
        v.textBuffer[0] = 'A'.code.toByte()
        v.teletype(0x0A, readCursor = { row to col }, writeCursor = { r, c -> row = r; col = c })
        assertEquals(VgaCore.ROWS - 1, row)
        assertEquals(0x20, v.textBuffer[0].toInt() and 0xFF, "scroll drops first row")
    }

    @Test
    fun setMode03hFrom13hClearsTextLikeDosRestore() {
        val v = VgaCore()
        v.setMode13h()
        v.planes[0][0] = 0xFF.toByte()
        v.setMode03h()
        assertTrue(v.isTextMode())
        assertEquals(0x03, v.biosMode)
        assertEquals(0x20, v.textBuffer[0].toInt() and 0xFF)
        assertEquals(0x07, v.textBuffer[1].toInt() and 0xFF)
        assertFalse(v.chain4())
    }

    @Test
    fun factoryClaimsA000B800AndSoftPorts() {
        val claims = VgaCardFactory().resourceClaims(emptyMap())
        assertTrue(claims.any { it.kind == com.trugath.k8086.api.ResourceKind.MEMORY && it.start == 0xA0000 })
        assertTrue(claims.any { it.kind == com.trugath.k8086.api.ResourceKind.MEMORY && it.start == 0xB8000 })
        assertTrue(
            claims.any {
                it.kind == com.trugath.k8086.api.ResourceKind.IO_PORT &&
                    it.start == VgaBiosRom.SOFT_ATTR_PORT
            },
        )
    }

    @Test
    fun optionRomReportsVgaDisplayCombinationCode() {
        // Wolf VL_VideoID FindPS2: INT 10h AX=1A00 must return AL=1Ah.
        val rom = VgaBiosRom.build()
        var found = false
        for (i in 0x80 until rom.size - 2) {
            if ((rom[i].toInt() and 0xFF) == 0x80 &&
                (rom[i + 1].toInt() and 0xFF) == 0xFC &&
                (rom[i + 2].toInt() and 0xFF) == 0x1A
            ) {
                found = true
                break
            }
        }
        assertTrue(found, "INT 10h must handle AH=1Ah for VGA detection")
    }
}
