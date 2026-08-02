package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CliArgsCoverageTest {
    @Test
    fun parseArgsSupportsEqualsFormAndHardDisk() {
        val cli = parseArgs(arrayOf(TestAssets.FLOPPY_PATH, "hd.img", "--card=cards/x.jar,irq=5"))
        assertEquals(TestAssets.FLOPPY_PATH, cli.floppy)
        assertEquals("hd.img", cli.hardDisk)
        assertEquals(1, cli.cards.size)
        assertEquals("cards/x.jar", cli.cards[0].jarPath)
        assertEquals("5", cli.cards[0].config["irq"])
    }

    @Test
    fun parseArgsRejectsUnknownOptionAndExtraPositional() {
        assertThrows(IllegalArgumentException::class.java) {
            parseArgs(arrayOf("--nope"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseArgs(arrayOf("a.img", "b.img", "c.img"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseArgs(arrayOf("--card"))
        }
    }

    @Test
    fun parseCardSpecRequiresKeyValuePairs() {
        assertThrows(IllegalArgumentException::class.java) {
            parseCardSpec("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseCardSpec("x.jar,notakeyvalue")
        }
        val ok = parseCardSpec("x.jar,a=1,b=two")
        assertEquals(mapOf("a" to "1", "b" to "two"), ok.config)
    }

    @Test
    fun parseArgsSupportsHeadlessAndSerialLog() {
        val cli = parseArgs(
            arrayOf(
                TestAssets.FLOPPY_PATH,
                "--headless",
                "--serial-log",
                "/tmp/serial.log",
                "--quiet",
            ),
        )
        assertTrue(cli.headless)
        assertEquals("/tmp/serial.log", cli.serialLog)
        assertTrue(cli.quiet)
    }

    @Test
    fun parseArgsSupportsSerialLogEqualsForm() {
        val cli = parseArgs(arrayOf("--serial-log=/tmp/a.log", "--floppy", TestAssets.FLOPPY_PATH))
        assertEquals("/tmp/a.log", cli.serialLog)
        assertEquals(TestAssets.FLOPPY_PATH, cli.floppy)
    }

    @Test
    fun parseArgsSupportsParallelLog() {
        val spaced = parseArgs(
            arrayOf(TestAssets.FLOPPY_PATH, "--parallel-log", "/tmp/lpt1.log", "--quiet"),
        )
        assertEquals("/tmp/lpt1.log", spaced.parallelLog)
        val eq = parseArgs(arrayOf("--parallel-log=/tmp/b.log", "--floppy", TestAssets.FLOPPY_PATH))
        assertEquals("/tmp/b.log", eq.parallelLog)
    }

    @Test
    fun parseArgsSupportsCgaExpectAndMaxInstructions() {
        val cli = parseArgs(
            arrayOf(
                TestAssets.FLOPPY_PATH,
                "--cga-expect",
                "A:\\>",
                "--max-instructions",
                "12345",
                "--quiet",
            ),
        )
        assertEquals("A:\\>", cli.cgaExpect)
        assertEquals(12345L, cli.maxInstructions)
        assertTrue(cli.quiet)
    }

    @Test
    fun parseArgsSupportsNoFloppyInt13Shim() {
        val cli = parseArgs(arrayOf(TestAssets.FLOPPY_PATH, "--no-floppy-int13-shim", "--quiet"))
        assertFalse(cli.floppyInt13Shim)
        val on = parseArgs(arrayOf(TestAssets.FLOPPY_PATH, "--floppy-int13-shim=true"))
        assertTrue(on.floppyInt13Shim)
    }
}
