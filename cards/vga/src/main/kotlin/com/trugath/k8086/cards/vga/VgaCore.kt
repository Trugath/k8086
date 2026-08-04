package com.trugath.k8086.cards.vga

/**
 * Minimal VGA state for text mode 03h, Mode 13h, and planar Mode Y.
 */
class VgaCore {
    val planes = Array(4) { ByteArray(PLANE_SIZE) }

    /** 80×25 attribute text (B800:0000). */
    val textBuffer = ByteArray(TEXT_BYTES)

    val seq = IntArray(8)
    val gc = IntArray(16)
    val crtc = IntArray(32)
    val atc = IntArray(32)
    val dac = IntArray(256) // packed 0x00RRGGBB

    var seqIndex = 0
    var gcIndex = 0
    var crtcIndex = 0
    var atcIndex = 0
    var atcFlipFlop = false // false = expect index next
    var miscOutput = 0x63
    var featureControl = 0
    var dacWriteIndex = 0
    var dacReadIndex = 0
    var dacSub = 0 // 0=R 1=G 2=B
    var dacPelMask = 0xFF
    private val dacPending = IntArray(3)
    /** VGA read latches — filled from all four planes on each VRAM read. */
    private val latches = ByteArray(4)

    /** BIOS mode byte (BDA 40:49). */
    var biosMode: Int = 0x03

    /** Pixel clock / timing stub for 3DA. */
    var frameCycle: Long = 0L

    /** Diagnostic: guest bytes written into planar VRAM since last mode set. */
    var memWriteCount: Long = 0L
    var wm1WriteCount: Long = 0L
    var status3daReadCount: Long = 0L
    var status3daLast: Int = 0
    var status3daSawActive: Boolean = false // bit0 clear
    var status3daSawHBlank: Boolean = false // bit0 set, bit3 clear
    var status3daSawVSync: Boolean = false

    fun chain4(): Boolean = (seq[4] and 0x08) != 0
    fun mapMask(): Int = seq[2] and 0x0F
    fun readMapSelect(): Int = gc[4] and 0x03
    fun writeMode(): Int = gc[5] and 0x03
    fun bitMask(): Int = gc[8] and 0xFF
    fun setReset(): Int = gc[0] and 0x0F
    fun enableSetReset(): Int = gc[1] and 0x0F
    fun dataRotate(): Int = gc[3] and 0x07
    /** ATC horizontal pel pan (0–7 typical for Mode Y). */
    fun pelPan(): Int = atc[0x13] and 0x0F
    fun startAddress(): Int = ((crtc[0x0C] and 0xFF) shl 8) or (crtc[0x0D] and 0xFF)

    fun isTextMode(): Boolean = biosMode <= 0x03 || biosMode == 0x07

    fun setMode03h() {
        biosMode = 0x03
        seq[2] = 0x03
        seq[4] = 0x00 // not chain-4
        gc[4] = 0
        gc[5] = 0x10
        gc[6] = 0x0E
        crtc[0x0C] = 0
        crtc[0x0D] = 0
        crtc[0x11] = 0x0E
        miscOutput = 0x67
        clearTextScreen(' '.code, 0x07)
    }

    fun setMode13h() {
        biosMode = 0x13
        // SEQ: map mask all, memory mode chain-4
        seq[2] = 0x0F
        seq[4] = 0x0E // chain-4 + extended memory
        gc[4] = 0
        gc[5] = 0x40 // 256-color
        gc[6] = 0x05 // A000
        // VGA BIOS defaults bit mask to all bits enabled.
        gc[8] = 0xFF
        crtc[0x0C] = 0
        crtc[0x0D] = 0
        crtc[0x11] = 0x0E // unlock CRTC 0-7 for games that poke
        miscOutput = 0x63
        // Identity DAC (VGA default-ish): index = grey/color ramp
        for (i in 0 until 256) {
            val c = i and 0xFF
            dac[i] = (c shl 16) or (c shl 8) or c
        }
        // Clear planar VRAM
        for (p in 0 until 4) planes[p].fill(0)
        latches.fill(0)
        memWriteCount = 0
        wm1WriteCount = 0
        status3daReadCount = 0
        status3daSawActive = false
        status3daSawHBlank = false
        status3daSawVSync = false
    }

    fun clearTextScreen(ch: Int, attr: Int) {
        val c = (ch and 0xFF).toByte()
        val a = (attr and 0xFF).toByte()
        var i = 0
        while (i < TEXT_BYTES) {
            textBuffer[i++] = c
            textBuffer[i++] = a
        }
    }

    fun textMemWrite(off: Int, value: Int) {
        val o = off and 0x7FFF
        if (o < TEXT_BYTES) textBuffer[o] = (value and 0xFF).toByte()
    }

    fun textMemRead(off: Int): Int {
        val o = off and 0x7FFF
        return if (o < TEXT_BYTES) textBuffer[o].toInt() and 0xFF else 0
    }

    /**
     * BIOS teletype (INT 10h AH=0Eh) for page 0.
     * [readCursor]/writeCursor] bridge to BDA cursor at 40:50.
     */
    fun teletype(
        ch: Int,
        attr: Int = 0x07,
        readCursor: () -> Pair<Int, Int>,
        writeCursor: (row: Int, col: Int) -> Unit,
    ) {
        var (row, col) = readCursor()
        when (ch and 0xFF) {
            0x07 -> { /* bell — ignore */ }
            0x08 -> { // backspace
                if (col > 0) col-- else if (row > 0) {
                    row--
                    col = COLS - 1
                }
                putChar(row, col, ' '.code, attr)
            }
            0x0A -> { // LF
                row++
            }
            0x0D -> { // CR
                col = 0
            }
            else -> {
                putChar(row, col, ch, attr)
                col++
                if (col >= COLS) {
                    col = 0
                    row++
                }
            }
        }
        while (row >= ROWS) {
            scrollUp(attr)
            row = ROWS - 1
        }
        writeCursor(row.coerceIn(0, ROWS - 1), col.coerceIn(0, COLS - 1))
    }

    private fun putChar(row: Int, col: Int, ch: Int, attr: Int) {
        val i = (row * COLS + col) * 2
        if (i in 0 until TEXT_BYTES - 1) {
            textBuffer[i] = (ch and 0xFF).toByte()
            textBuffer[i + 1] = (attr and 0xFF).toByte()
        }
    }

    private fun scrollUp(fillAttr: Int) {
        System.arraycopy(textBuffer, COLS * 2, textBuffer, 0, (ROWS - 1) * COLS * 2)
        val base = (ROWS - 1) * COLS * 2
        val a = (fillAttr and 0xFF).toByte()
        var i = base
        while (i < TEXT_BYTES) {
            textBuffer[i++] = 0x20
            textBuffer[i++] = a
        }
    }

    fun tickCpuCycles(cycles: Int) {
        frameCycle = (frameCycle + cycles) % CYCLES_PER_FRAME
    }

    fun inVRetrace(): Boolean {
        val line = (frameCycle / CYCLES_PER_LINE).toInt()
        return line >= 400 // ~ vblank after 400 lines worth
    }

    fun inHRetrace(): Boolean {
        val inLine = (frameCycle % CYCLES_PER_LINE).toInt()
        return inLine >= ACTIVE_LINE_CYCLES
    }

    /** Input status 1 (color 3DA / mono 3BA). Bit0=display enable (1=retrace), bit3=vr. */
    fun status3da(): Int {
        atcFlipFlop = false
        // Do not advance frame timing here — the CPU already ticks the card on
        // each IN via IsaHost. Extra advances made HBlank (~160 cycles) too
        // short for VL_SetScreen's five successive blanking samples.
        var st = 0
        if (inHRetrace() || inVRetrace()) st = st or 0x01
        if (inVRetrace()) st = st or 0x08
        status3daReadCount++
        status3daLast = st
        if ((st and 0x01) == 0) status3daSawActive = true
        if ((st and 0x09) == 0x01) status3daSawHBlank = true
        if ((st and 0x08) != 0) status3daSawVSync = true
        return st
    }

    fun memWrite(offset: Int, value: Int) {
        memWriteCount++
        val off = offset and 0xFFFF
        if (chain4()) {
            val plane = off and 3
            val addr = off ushr 2
            if (addr < PLANE_SIZE) planes[plane][addr] = (value and 0xFF).toByte()
            return
        }
        val addr = off and (PLANE_SIZE - 1)
        val mask = mapMask()
        val bitMask = bitMask()
        val keep = bitMask.inv() and 0xFF
        when (writeMode()) {
            1 -> {
                // Write mode 1: copy latches through map mask (bit mask ignored on VGA).
                wm1WriteCount++
                for (p in 0 until 4) {
                    if ((mask and (1 shl p)) != 0) {
                        planes[p][addr] = latches[p]
                    }
                }
            }
            2 -> {
                // Write mode 2: expand CPU low nibble; bit mask vs latches.
                for (p in 0 until 4) {
                    if ((mask and (1 shl p)) != 0) {
                        val expanded =
                            if ((value and (1 shl p)) != 0) 0xFF else 0
                        val latch = latches[p].toInt() and 0xFF
                        planes[p][addr] =
                            ((expanded and bitMask) or (latch and keep)).toByte()
                    }
                }
            }
            else -> {
                // Write mode 0: rotate → set/reset → bit mask vs latches → map mask.
                val rot = dataRotate()
                val cpu = value and 0xFF
                val rotated = if (rot == 0) {
                    cpu
                } else {
                    ((cpu ushr rot) or (cpu shl (8 - rot))) and 0xFF
                }
                val esr = enableSetReset()
                val sr = setReset()
                for (p in 0 until 4) {
                    if ((mask and (1 shl p)) != 0) {
                        val src = if ((esr and (1 shl p)) != 0) {
                            if ((sr and (1 shl p)) != 0) 0xFF else 0x00
                        } else {
                            rotated
                        }
                        val latch = latches[p].toInt() and 0xFF
                        planes[p][addr] =
                            ((src and bitMask) or (latch and keep)).toByte()
                    }
                }
            }
        }
    }

    fun memRead(offset: Int): Int {
        val off = offset and 0xFFFF
        return if (chain4()) {
            val plane = off and 3
            val addr = off ushr 2
            if (addr < PLANE_SIZE) planes[plane][addr].toInt() and 0xFF else 0
        } else {
            val addr = off and (PLANE_SIZE - 1)
            for (p in 0 until 4) {
                latches[p] = planes[p][addr]
            }
            planes[readMapSelect()][addr].toInt() and 0xFF
        }
    }

    /** Compose 320×200 Mode 13h / Mode Y into ARGB ints. */
    fun composeFrame(outRgb: IntArray) {
        require(outRgb.size >= 320 * 200)
        val start = startAddress()
        val pan = pelPan().coerceIn(0, 7)
        var di = 0
        for (y in 0 until 200) {
            val row = start + y * 80
            for (x in 0 until 320) {
                val sx = x + pan
                val plane = sx and 3
                val addr = row + (sx ushr 2)
                val idx = if (addr in 0 until PLANE_SIZE) {
                    planes[plane][addr].toInt() and 0xFF
                } else {
                    0
                }
                outRgb[di++] = dac[idx] or 0xFF000000.toInt()
            }
        }
    }

    /** Compose 80×25 text (8×8 glyphs) into 640×200 ARGB. */
    fun composeTextFrame(outRgb: IntArray) {
        require(outRgb.size >= TEXT_PIXEL_W * TEXT_PIXEL_H)
        var di = 0
        for (row in 0 until ROWS) {
            for (gy in 0 until 8) {
                for (col in 0 until COLS) {
                    val cell = (row * COLS + col) * 2
                    val ch = textBuffer[cell].toInt() and 0xFF
                    val attr = textBuffer[cell + 1].toInt() and 0xFF
                    val fg = CGA_RGB[attr and 0x0F]
                    val bg = CGA_RGB[(attr ushr 4) and 0x07]
                    val bits = VgaFont.row(ch, gy)
                    for (gx in 0 until 8) {
                        outRgb[di++] = if ((bits and (0x80 ushr gx)) != 0) fg else bg
                    }
                }
            }
        }
    }

    fun ioWrite(port: Int, value: Int) {
        val v = value and 0xFF
        when (port and 0xFFFF) {
            0x3C2 -> miscOutput = v
            0x3C4 -> seqIndex = v
            0x3C5 -> if (seqIndex in seq.indices) seq[seqIndex] = v
            0x3C6 -> dacPelMask = v
            0x3C7 -> {
                dacReadIndex = v
                dacSub = 0
            }
            0x3C8 -> {
                dacWriteIndex = v
                dacSub = 0
            }
            0x3C9 -> {
                dacPending[dacSub] = v and 0x3F
                dacSub++
                if (dacSub == 3) {
                    fun s(x: Int) = x * 255 / 63
                    dac[dacWriteIndex and 0xFF] =
                        (s(dacPending[0]) shl 16) or (s(dacPending[1]) shl 8) or s(dacPending[2])
                    dacWriteIndex = (dacWriteIndex + 1) and 0xFF
                    dacSub = 0
                }
            }
            0x3CE -> gcIndex = v
            0x3CF -> if (gcIndex in gc.indices) gc[gcIndex] = v
            0x3D4, 0x3B4 -> crtcIndex = v
            0x3D5, 0x3B5 -> {
                val idx = crtcIndex and 0xFF
                if (idx <= 7 && (crtc[0x11] and 0x80) != 0) return
                crtc[idx] = v
            }
            0x3C0 -> {
                if (!atcFlipFlop) {
                    atcIndex = v and 0x1F
                    atcFlipFlop = true
                } else {
                    if (atcIndex in atc.indices) atc[atcIndex] = v
                    atcFlipFlop = false
                }
            }
            0x3DA, 0x3BA -> {
                atcFlipFlop = false
                featureControl = v
            }
        }
    }

    fun ioRead(port: Int): Int = when (port and 0xFFFF) {
        0x3C2 -> 0x10
        0x3CC -> miscOutput and 0xFF
        0x3C4 -> seqIndex and 0xFF
        0x3C5 -> if (seqIndex in seq.indices) seq[seqIndex] and 0xFF else 0
        0x3C6 -> dacPelMask and 0xFF
        0x3C7 -> 0
        0x3C8 -> dacWriteIndex and 0xFF
        0x3C9 -> {
            val rgb = dac[dacReadIndex and 0xFF]
            val component = when (dacSub) {
                0 -> (rgb ushr 16) and 0xFF
                1 -> (rgb ushr 8) and 0xFF
                else -> rgb and 0xFF
            }
            val six = component * 63 / 255
            dacSub++
            if (dacSub == 3) {
                dacSub = 0
                dacReadIndex = (dacReadIndex + 1) and 0xFF
            }
            six
        }
        0x3CE -> gcIndex and 0xFF
        0x3CF -> if (gcIndex in gc.indices) gc[gcIndex] and 0xFF else 0
        0x3D4, 0x3B4 -> crtcIndex and 0xFF
        0x3D5, 0x3B5 -> crtc[crtcIndex and 0xFF] and 0xFF
        0x3C1 -> if (atcIndex in atc.indices) atc[atcIndex] and 0xFF else 0
        0x3DA, 0x3BA -> status3da()
        else -> 0xFF
    }

    fun debugSnapshot(): String {
        var nz = 0
        for (p in 0 until 4) {
            for (b in planes[p]) if (b.toInt() != 0) nz++
        }
        var lit = 0
        if (!isTextMode()) {
            val out = IntArray(320 * 200)
            composeFrame(out)
            for (px in out) if ((px and 0xFFFFFF) != 0) lit++
        }
        return "biosMode=0x${biosMode.toString(16)} text=${isTextMode()} chain4=${chain4()} " +
            "wm=${writeMode()} mapMask=${mapMask()} bitMask=${bitMask()} start=${startAddress()} " +
            "nzVRAM=$nz litPixels=$lit dac1=0x${(dac[1] and 0xFFFFFF).toString(16)} " +
            "memWr=$memWriteCount wm1=$wm1WriteCount " +
            "3daReads=$status3daReadCount 3daLast=0x${status3daLast.toString(16)} " +
            "sawAct=$status3daSawActive sawHBlank=$status3daSawHBlank sawVSync=$status3daSawVSync"
    }

    companion object {
        const val PLANE_SIZE = 65536
        const val COLS = 80
        const val ROWS = 25
        const val TEXT_BYTES = COLS * ROWS * 2
        const val TEXT_PIXEL_W = COLS * 8
        const val TEXT_PIXEL_H = ROWS * 8
        const val CYCLES_PER_LINE = 800
        const val CYCLES_PER_FRAME = CYCLES_PER_LINE * 449
        const val ACTIVE_LINE_CYCLES = 640
        const val VRETRACE_CYCLES = CYCLES_PER_LINE * 40

        /** CGA/EGA text attribute RGB (ARGB opaque). */
        val CGA_RGB = intArrayOf(
            0xFF000000.toInt(), // 0 black
            0xFF0000AA.toInt(), // 1 blue
            0xFF00AA00.toInt(), // 2 green
            0xFF00AAAA.toInt(), // 3 cyan
            0xFFAA0000.toInt(), // 4 red
            0xFFAA00AA.toInt(), // 5 magenta
            0xFFAA5500.toInt(), // 6 brown
            0xFFAAAAAA.toInt(), // 7 light gray
            0xFF555555.toInt(), // 8 dark gray
            0xFF5555FF.toInt(), // 9 light blue
            0xFF55FF55.toInt(), // A light green
            0xFF55FFFF.toInt(), // B light cyan
            0xFFFF5555.toInt(), // C light red
            0xFFFF55FF.toInt(), // D light magenta
            0xFFFFFF55.toInt(), // E yellow
            0xFFFFFFFF.toInt(), // F white
        )
    }
}
