package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import com.trugath.k8086.cards.adlib.AdlibCardFactory
import com.trugath.k8086.cards.emswindow.EmsWindowCard
import com.trugath.k8086.cards.emswindow.EmsWindowCardFactory
import com.trugath.k8086.cards.heartbeat.HeartbeatCardFactory
import com.trugath.k8086.cards.ramumb.RamUmbCardFactory
import com.trugath.k8086.cards.samplerom.SampleRomCard
import com.trugath.k8086.cards.samplerom.SampleRomCardFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Behavioral unit tests for the in-repo example ISA cards.
 * Cards attach through a real [Machine] so MemoryBus / IoBus / PIC wiring is exercised.
 */
class ExampleCardsTest {
    private lateinit var machine: Machine

    @BeforeEach
    fun setUp() {
        machine = TestAssets.machine(showVideo = false)
        // Unmask IRQ2–7 so raised lines are visible in IRR.
        machine.pic.ioWriteByte(0x21, 0x00)
    }

    // --- sample-rom -----------------------------------------------------------

    @Test
    fun sampleRomFactoryBuildsSignatureAndScratchPort() {
        val card = SampleRomCardFactory().create(mapOf("base" to "0xC8000", "port" to "0x301"))
        card.attach(IsaHostImpl(machine, card.id))

        assertEquals(0x55, machine.cpu.readPhysByte(0xC8000))
        assertEquals(0xAA, machine.cpu.readPhysByte(0xC8001))
        assertEquals(0x01, machine.cpu.readPhysByte(0xC8002))
        assertEquals(0xCB, machine.cpu.readPhysByte(0xC8003))

        val port = machine.ioBus.deviceFor(0x301)!!
        port.ioWriteByte(0x301, 0xA5)
        assertEquals(0xA5, port.ioReadByte(0x301))
    }

    @Test
    fun sampleRomRejectsBadSignatureViaHost() {
        val host = IsaHostImpl(machine, "bad")
        assertThrows(IllegalArgumentException::class.java) {
            host.mapOptionRom(byteArrayOf(0x00, 0x00), 0xC8000)
        }
    }

    @Test
    fun sampleRomCompanionBuildsValidImage() {
        val rom = SampleRomCard.buildOptionRom()
        assertEquals(512, rom.size)
        assertEquals(0x55.toByte(), rom[0])
        assertEquals(0xAA.toByte(), rom[1])
    }

    // --- ram-umb --------------------------------------------------------------

    @Test
    fun ramUmbMapsWritableWindow() {
        val card = RamUmbCardFactory().create(emptyMap())
        card.attach(IsaHostImpl(machine, card.id))

        machine.cpu.writePhysByte(0xE0000, 0x12)
        machine.cpu.writePhysByte(0xEFFFF, 0x34)
        assertEquals(0x12, machine.cpu.readPhysByte(0xE0000))
        assertEquals(0x34, machine.cpu.readPhysByte(0xEFFFF))
    }

    @Test
    fun ramUmbHonorsBaseAndSizeConfig() {
        val card = RamUmbCardFactory().create(mapOf("base" to "0xD0000", "size" to "0x8000"))
        card.attach(IsaHostImpl(machine, card.id))

        machine.cpu.writePhysByte(0xD0000, 0x77)
        assertEquals(0x77, machine.cpu.readPhysByte(0xD0000))
        // Just past the 32 KB window — ordinary system RAM.
        machine.cpu.writePhysByte(0xD8000, 0x88)
        assertEquals(0x88, machine.cpu.readPhysByte(0xD8000))
    }

    @Test
    fun ramUmbRejectsMisalignedBase() {
        val card = RamUmbCardFactory().create(mapOf("base" to "0xE0001"))
        assertThrows(IllegalArgumentException::class.java) {
            card.attach(IsaHostImpl(machine, card.id))
        }
    }

    // --- adlib ----------------------------------------------------------------

    @Test
    fun adlibIndexDataRoundTripAndTimerStatus() {
        val card = AdlibCardFactory().create(mapOf("audio" to "false"))
        card.attach(IsaHostImpl(machine, card.id))
        val statusPort = machine.ioBus.deviceFor(0x388)!!
        val dataPort = machine.ioBus.deviceFor(0x389)!!

        assertEquals(0x00, statusPort.ioReadByte(0x388))

        // Load timer-1 so the next OPL tick wraps 0xFF → 0x00 and sets flags.
        statusPort.ioWriteByte(0x388, 0x02)
        dataPort.ioWriteByte(0x389, 0xFF)
        statusPort.ioWriteByte(0x388, 0x04)
        dataPort.ioWriteByte(0x389, 0x01) // enable timer 1

        machine.tickDevices(80)

        val status = statusPort.ioReadByte(0x388)
        assertTrue((status and 0xC0) == 0xC0, "timer-1 expiry should set IRQ+T1 bits, status=$status")

        // Reset flags via bit 7 of timer control.
        statusPort.ioWriteByte(0x388, 0x04)
        dataPort.ioWriteByte(0x389, 0x80)
        assertEquals(0x00, statusPort.ioReadByte(0x388) and 0xE0)
    }

    @Test
    fun adlibCustomPortConfig() {
        val card = AdlibCardFactory().create(mapOf("port" to "0x220", "audio" to "false"))
        card.attach(IsaHostImpl(machine, card.id))
        assertNotNull(machine.ioBus.deviceFor(0x220))
        assertNotNull(machine.ioBus.deviceFor(0x221))
        assertNull(machine.ioBus.deviceFor(0x388))
    }

    @Test
    fun adlibKeyOnTracksChannelRegisters() {
        val card = AdlibCardFactory().create(mapOf("audio" to "false")) as com.trugath.k8086.cards.adlib.AdlibCard
        card.attach(IsaHostImpl(machine, card.id))
        val idx = machine.ioBus.deviceFor(0x388)!!
        idx.ioWriteByte(0x388, 0xA0)
        idx.ioWriteByte(0x389, 0xAE)
        idx.ioWriteByte(0x388, 0xB0)
        idx.ioWriteByte(0x389, 0x2B) // key-on
        assertTrue(card.anyKeyOn())
        idx.ioWriteByte(0x388, 0xB0)
        idx.ioWriteByte(0x389, 0x0B) // key-off
        assertFalse(card.anyKeyOn())
    }

    // --- heartbeat ------------------------------------------------------------

    @Test
    fun heartbeatArmsAndRaisesIrq() {
        val card = HeartbeatCardFactory().create(mapOf("port" to "0x310", "irq" to "5"))
        card.attach(IsaHostImpl(machine, card.id))
        val dev = machine.ioBus.deviceFor(0x310)!!

        assertEquals(0x00, dev.ioReadByte(0x310) and 0x01)

        // Period = 1 ms → threshold 4770 cycles.
        dev.ioWriteByte(0x310, 1)
        assertEquals(0x01, dev.ioReadByte(0x310) and 0x01)

        machine.pic.lowerIrq(5)
        machine.tickDevices(4770)

        assertTrue((machine.pic.requestRegister() and (1 shl 5)) != 0, "IRQ5 should be pending")
        val status = machine.ioBus.deviceFor(0x310)!!.ioReadByte(0x310)
        assertTrue((status and 0x02) != 0, "pending bit should be set")
        // Pending clears on read.
        assertEquals(0, machine.ioBus.deviceFor(0x310)!!.ioReadByte(0x310) and 0x02)
    }

    @Test
    fun heartbeatStopClearsArm() {
        val card = HeartbeatCardFactory().create(emptyMap())
        card.attach(IsaHostImpl(machine, card.id))
        val dev = machine.ioBus.deviceFor(0x310)!!
        dev.ioWriteByte(0x310, 5)
        dev.ioWriteByte(0x310, 0)
        assertEquals(0x00, dev.ioReadByte(0x310) and 0x01)
    }

    @Test
    fun heartbeatRejectsInvalidIrq() {
        val card = HeartbeatCardFactory().create(mapOf("irq" to "1"))
        assertThrows(IllegalArgumentException::class.java) {
            card.attach(IsaHostImpl(machine, card.id))
        }
    }

    // --- ems-window -----------------------------------------------------------

    @Test
    fun emsWindowMapsPagesAndRemaps() {
        val card = EmsWindowCardFactory().create(emptyMap())
        card.attach(IsaHostImpl(machine, card.id))

        // Default window 0 → logical page 0.
        machine.cpu.writePhysByte(0xD0000, 0xAA)
        assertEquals(0xAA, machine.cpu.readPhysByte(0xD0000))

        // Remap window 0 to page 2; page 0 data must leave the frame.
        machine.ioBus.deviceFor(0x260)!!.ioWriteByte(0x260, 2)
        assertEquals(0x00, machine.cpu.readPhysByte(0xD0000))

        machine.cpu.writePhysByte(0xD0000, 0xBB)
        // Switch back to page 0 — original byte restored.
        machine.ioBus.deviceFor(0x260)!!.ioWriteByte(0x260, 0)
        assertEquals(0xAA, machine.cpu.readPhysByte(0xD0000))

        assertEquals(0, machine.ioBus.deviceFor(0x260)!!.ioReadByte(0x260))
        machine.ioBus.deviceFor(0x261)!!.ioWriteByte(0x261, 5)
        assertEquals(5, machine.ioBus.deviceFor(0x261)!!.ioReadByte(0x261))
    }

    @Test
    fun emsWindowFourWindowsAreIndependent() {
        val card = EmsWindowCardFactory().create(mapOf("pages" to "8"))
        card.attach(IsaHostImpl(machine, card.id))

        machine.ioBus.deviceFor(0x260)!!.ioWriteByte(0x260, 0)
        machine.ioBus.deviceFor(0x261)!!.ioWriteByte(0x261, 1)
        machine.cpu.writePhysByte(0xD0000, 0x10) // window 0
        machine.cpu.writePhysByte(0xD4000, 0x20) // window 1
        assertEquals(0x10, machine.cpu.readPhysByte(0xD0000))
        assertEquals(0x20, machine.cpu.readPhysByte(0xD4000))
    }

    @Test
    fun emsWindowRejectsUnalignedFrame() {
        val card = EmsWindowCardFactory().create(mapOf("frame" to "0xD1000"))
        assertThrows(IllegalArgumentException::class.java) {
            card.attach(IsaHostImpl(machine, card.id))
        }
    }

    @Test
    fun emsWindowUnmapFloatsAndIgnoresWrites() {
        val card = EmsWindowCardFactory().create(emptyMap())
        card.attach(IsaHostImpl(machine, card.id))

        machine.cpu.writePhysByte(0xD0000, 0xAA)
        machine.ioBus.deviceFor(0x260)!!.ioWriteByte(0x260, EmsWindowCard.UNMAPPED)
        assertEquals(EmsWindowCard.UNMAPPED, machine.ioBus.deviceFor(0x260)!!.ioReadByte(0x260))
        assertEquals(0xFF, machine.cpu.readPhysByte(0xD0000))
        machine.cpu.writePhysByte(0xD0000, 0x55)
        assertEquals(0xFF, machine.cpu.readPhysByte(0xD0000))

        machine.ioBus.deviceFor(0x260)!!.ioWriteByte(0x260, 0)
        assertEquals(0xAA, machine.cpu.readPhysByte(0xD0000))
    }

    @Test
    fun emsWindowOutOfRangePageUnmaps() {
        val card = EmsWindowCardFactory().create(mapOf("pages" to "4"))
        card.attach(IsaHostImpl(machine, card.id))

        machine.ioBus.deviceFor(0x260)!!.ioWriteByte(0x260, 4)
        assertEquals(EmsWindowCard.UNMAPPED, machine.ioBus.deviceFor(0x260)!!.ioReadByte(0x260))
        assertEquals(0xFF, machine.cpu.readPhysByte(0xD0000))
    }
}
