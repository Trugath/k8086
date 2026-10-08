package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class IsaCardIntegrationTest {
    @Test
    fun loadsSampleRomJarAndMapsOptionRomAndPorts() {
        val jar = locateOrBuildSampleJar()
        val machine = Machine(
            u18RomPath = TestAssets.U18_PATH,
            u19RomPath = TestAssets.U19_PATH,
            showVideo = false,
        )
        TestAssets.assumeRomsPresent()

        val cards = machine.loadCards(listOf(CardSpec(jar.absolutePath)))
        assertEquals(1, cards.size)
        assertEquals("com.trugath.k8086.cards.sample-rom", cards[0].id)

        assertEquals(0x55, machine.cpu.readPhysByte(0xC8000))
        assertEquals(0xAA, machine.cpu.readPhysByte(0xC8001))
        assertEquals(0xCB, machine.cpu.readPhysByte(0xC8003))

        // Scratch port
        machine.cpu.setReg16(REG_DX, 0x300)
        // Drive OUT via mapped device: write through IoBus
        machine.ioBus.deviceFor(0x300)!!.ioWriteByte(0x300, 0x5A)
        assertEquals(0x5A, machine.ioBus.deviceFor(0x300)!!.ioReadByte(0x300))
    }

    @Test
    fun dmaChannelConflictWithFdc() {
        val machine = Machine(
            TestAssets.U18_PATH,
            TestAssets.U19_PATH,
            showVideo = false,
        )
        TestAssets.assumeRomsPresent()
        assertThrows(IllegalStateException::class.java) {
            machine.claimDmaChannel(2, "intruder")
        }
    }

    @Test
    fun loadsAllExampleCardJars() {
        TestAssets.assumeRomsPresent()

        val jars = listOf(
            "sample-rom" to "com.trugath.k8086.cards.sample-rom",
            "ram-umb" to "com.trugath.k8086.cards.ram-umb",
            "adlib" to "com.trugath.k8086.cards.adlib",
            "heartbeat" to "com.trugath.k8086.cards.heartbeat",
            "ems-window" to "com.trugath.k8086.cards.ems-window",
        )
        for ((name, expectedId) in jars) {
            val jar = File("cards/$name/build/libs/$name-1.0-SNAPSHOT.jar")
            org.junit.jupiter.api.Assumptions.assumeTrue(jar.isFile) {
                "Build card jars first: ./gradlew :cards:$name:jar"
            }
            val machine = Machine(TestAssets.u18.absolutePath, TestAssets.u19.absolutePath, showVideo = false)
            val cards = machine.loadCards(listOf(CardSpec(jar.absolutePath)))
            assertEquals(1, cards.size, name)
            assertEquals(expectedId, cards[0].id, name)
        }
    }

    private fun locateOrBuildSampleJar(): File {
        val candidates = listOf(
            File("cards/sample-rom/build/libs/sample-rom-1.0-SNAPSHOT.jar"),
            File("cards/sample-rom/build/libs/sample-rom.jar"),
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        // Build via gradle if missing
        val pb = ProcessBuilder("./gradlew", ":cards:sample-rom:jar", "-q")
            .directory(File("."))
            .inheritIO()
        val code = pb.start().waitFor()
        assertEquals(0, code, "Failed to build sample-rom jar")
        return candidates.first { it.isFile }
    }
}
