    fun renderFrame() {
        composeFrame()
    }

    private fun composeFrame(): Boolean {
        val composite = computeCompositeActive().also { compositeActiveCached = it }
        val (w, h) = when {
            graphicsMode() && highResGraphics() -> 640 to 200
            graphicsMode() -> 320 to 200
            else -> textColumns() * 8 to 200
        }
        ensureFramebuffer(w, h)
        when {
            graphicsMode() && composite && highResGraphics() -> renderCompositeMode6()
            graphicsMode() && composite -> renderCompositeMode4()
            graphicsMode() -> renderGraphics()
            else -> renderText()
        }
        hostUi.onFramebufferReady(fbWidth, fbHeight, pixels)
        return true
    }

    private fun pix(x: Int, y: Int, color: Int) {
        pixels[y * fbWidth + x] = color or 0xFF000000.toInt()
    }

    private fun renderText() {
        val cols = fbWidth / 8
        val base = 0xB8000
        for (row in 0 until 25) {
            for (col in 0 until cols) {
                val cell = base + (row * cols + col) * 2
                val ch = cpu.readPhysByte(cell)
                val attr = cpu.readPhysByte(cell + 1)
                drawGlyph(col * 8, row * 8, ch, attr)
            }
        }
    }

    private fun renderCompositeMode6() {
        val lut = compositeLuts.mode6
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            for (byteX in 0 until 80) {
                val b = cpu.readPhysByte(rowBase + byteX)
                val c0 = lut[(b shr 4) and 0x0F]
                val c1 = lut[b and 0x0F]
                val x0 = byteX * 8
                for (i in 0 until 4) pix(x0 + i, y, c0)
                for (i in 0 until 4) pix(x0 + 4 + i, y, c1)
            }
        }
    }

    private fun renderCompositeMode4() {
        val lut = compositeLuts.mode4
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            for (byteX in 0 until 80) {
                val b = cpu.readPhysByte(rowBase + byteX)
                val p0 = (b shr 6) and 3
                val p1 = (b shr 4) and 3
                val p2 = (b shr 2) and 3
                val p3 = b and 3
                val c0 = lut[(p0 shl 2) or p1]
                val c1 = lut[(p2 shl 2) or p3]
                val x0 = byteX * 4
                pix(x0, y, c0)
                pix(x0 + 1, y, c0)
                pix(x0 + 2, y, c1)
                pix(x0 + 3, y, c1)
            }
        }
    }

    private fun renderGraphics() {
        val hi = highResGraphics()
        val colors = graphicsPalette()
        for (y in 0 until 200) {
            val rowBase = 0xB8000 + (y and 1) * 0x2000 + (y shr 1) * 80
            if (hi) {
                for (x in 0 until 640) {
                    val b = cpu.readPhysByte(rowBase + (x shr 3))
                    val on = (b shr (7 - (x and 7))) and 1
                    pix(x, y, if (on != 0) colors[1] else colors[0])
                }
            } else {
                for (x in 0 until 320) {
                    val b = cpu.readPhysByte(rowBase + (x shr 2))
                    val px = (b shr (6 - 2 * (x and 3))) and 3
                    pix(x, y, colors[px])
                }
            }
        }
    }

    private fun graphicsPalette(): IntArray {
        val bg = CGA_PALETTE[colorSelect and 0x0F]
        if (highResGraphics()) return intArrayOf(CGA_PALETTE[0], bg)
        val intensity = if ((colorSelect and 0x10) != 0) 8 else 0
        return if ((colorSelect and 0x20) != 0) {
            intArrayOf(bg, CGA_PALETTE[3 + intensity], CGA_PALETTE[5 + intensity], CGA_PALETTE[7 + intensity])
        } else {
            intArrayOf(bg, CGA_PALETTE[2 + intensity], CGA_PALETTE[4 + intensity], CGA_PALETTE[6 + intensity])
        }
    }

    private fun drawGlyph(x: Int, y: Int, ch: Int, attr: Int) {
        val bgIndex = if (blinkEnabled()) (attr shr 4) and 0x07 else (attr shr 4) and 0x0F
        val bg = CGA_PALETTE[bgIndex]
        val hideFg = blinkEnabled() && (attr and 0x80) != 0 && !blinkVisible()
        val fg = if (hideFg) bg else CGA_PALETTE[attr and 0x0F]
        for (yy in 0 until 8) {
            val bits = CgaFont.row(ch, yy)
            for (xx in 0 until 8) {
                val on = (bits and (0x80 shr xx)) != 0
                pix(x + xx, y + yy, if (on) fg else bg)
            }
        }
    }

    private fun ensureFramebuffer(w: Int, h: Int) {
        if (fbWidth != w || fbHeight != h || pixels.size != w * h) {
            fbWidth = w
            fbHeight = h
            pixels = IntArray(w * h)
        }
    }

    fun writeFramePng(path: String) {
        composeFrame()
        if (fbWidth == 0) return
        hostUi.writeFramePng(path, fbWidth, fbHeight, pixels)
    }

    fun copyFramebuffer(): FramebufferSnapshot? = platformLock(fbLock) {
        composeFrame()
        if (fbWidth == 0) return@platformLock null
        FramebufferSnapshot(
            width = fbWidth,
            height = fbHeight,
            argb = pixels.copyOf(),
            graphicsMode = graphicsMode(),
            compositeMode = compositeMode,
            compositeActive = compositeActiveCached,
        )
    }

    fun disposeWindow() = hostUi.dispose()

    internal fun compositeTitle(): String {
        val comp = when (compositeMode) {
            CgaComposite.Mode.AUTO -> if (compositeActiveCached) "composite:auto" else "rgb:auto"
            CgaComposite.Mode.ON -> "composite:on"
            CgaComposite.Mode.OFF -> "rgb:off"
        }
        val hue = if (hueOffsetDeg == 0.0) "" else " hue=${hueOffsetDeg.toInt()}"
        return "k8086 - IBM 5155 ($comp$hue)  [F12 composite · F11 hue]"
    }

    fun crtcRegister(i: Int): Int = crtc[i and 0x1F]
    fun modeControlValue(): Int = modeControl
    fun colorSelectValue(): Int = colorSelect
    fun framesRenderedCount(): Long = framesRendered
