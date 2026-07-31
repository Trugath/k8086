package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CliParseTest {
    @Test
    fun parsesCardWithConfig() {
        val cli = parseArgs(arrayOf(TestAssets.FLOPPY_PATH, "--card", "cards/x.jar,irq=5,base=0xC8000"))
        assertEquals(TestAssets.FLOPPY_PATH, cli.floppy)
        assertEquals(1, cli.cards.size)
        assertEquals("cards/x.jar", cli.cards[0].jarPath)
        assertEquals("5", cli.cards[0].config["irq"])
        assertEquals("0xC8000", cli.cards[0].config["base"])
    }
}
