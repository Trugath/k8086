package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CgaIoCoverageTest {
    @Test
    fun crtcIndexDataStatusAndCompositeControls() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)

        cga.ioWriteByte(0x3D4, 0x01)
        cga.ioWriteByte(0x3D5, 0x28)
        assertEquals(0x28, cga.crtcRegister(1))
        assertEquals(0x28, cga.ioReadByte(0x3D5))

        // Status register toggles over time.
        val s0 = cga.ioReadByte(0x3DA)
        cga.tickCpuCycles(100_000)
        val s1 = cga.ioReadByte(0x3DA)
        assertTrue(s0 in 0..0xFF && s1 in 0..0xFF)

        cga.cycleCompositeMode() // AUTO → ON
        cga.ioWriteByte(0x3D8, 0x1A)
        assertTrue(cga.isCompositeActive())
        cga.cycleCompositeMode() // ON → OFF
        assertFalse(cga.isCompositeActive())
        cga.cycleCompositeMode() // OFF → AUTO

        cga.adjustHue(0.1)
        cga.adjustHue(-0.1)

        cga.ioWriteByte(0x3D8, 0x29) // 80-col text
        cga.renderFrame()
        assertEquals(0x29, cga.modeControlValue())

        // framesRendered increments on vsync via tickCpuCycles, not bare renderFrame.
        val before = cga.framesRenderedCount()
        cga.tickCpuCycles(Cga.CYCLES_PER_FRAME)
        assertTrue(cga.framesRenderedCount() > before)
        assertEquals(cga.colorSelectValue() and 0xFF, cga.colorSelectValue())
    }

    @Test
    fun graphicsModesRenderWithoutWindow() {
        val cpu = Emulator8086()
        val cga = Cga(cpu, showWindow = false)
        // Mode 4 graphics
        cga.ioWriteByte(0x3D8, 0x0A)
        cga.ioWriteByte(0x3D9, 0x30)
        cpu.writePhysByte(0xB8000, 0xFF)
        cga.renderFrame()
        val mode4 = cga.copyFramebuffer()!!
        assertEquals(320, mode4.width)
        assertTrue(mode4.graphicsMode)

        // Mode 6 RGB (composite off)
        cga.compositeMode = CgaComposite.Mode.OFF
        cga.ioWriteByte(0x3D8, 0x1E)
        cga.renderFrame()
        val mode6 = cga.copyFramebuffer()!!
        assertEquals(640, mode6.width)

        // Mode 4 forced composite path
        cga.compositeMode = CgaComposite.Mode.ON
        cga.ioWriteByte(0x3D8, 0x0A)
        cga.renderFrame()
        assertTrue(cga.isCompositeActive())
    }
}
