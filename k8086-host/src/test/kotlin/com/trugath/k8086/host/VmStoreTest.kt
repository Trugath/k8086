package com.trugath.k8086.host

import com.trugath.k8086.protocol.CardSpecDto
import com.trugath.k8086.protocol.FloppySpec
import com.trugath.k8086.protocol.GraphicsKind
import com.trugath.k8086.protocol.HardDiskSpec
import com.trugath.k8086.protocol.InitialVideoKind
import com.trugath.k8086.protocol.MotherboardSpec
import com.trugath.k8086.protocol.VmDefinition
import com.trugath.k8086.protocol.VmId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

class VmStoreTest {
    @TempDir
    lateinit var temp: File

    @Test
    fun roundTripPersistsAllFields() {
        val store = VmStore(temp)
        val id = VmId(UUID.randomUUID().toString())
        val def = VmDefinition(
            id = id,
            name = "full-vm",
            u18RomPath = "/roms/u18.bin",
            u19RomPath = "/roms/u19.bin",
            motherboard = MotherboardSpec(
                baseMemoryKb = 512,
                mathCoprocessor = true,
                initialVideo = InitialVideoKind.CGA_40x25,
                postLoop = false,
            ),
            graphics = GraphicsKind.CGA,
            enableCom1 = false,
            floppy = FloppySpec(enabled = true, driveImages = listOf("/a.img", "/b.img")),
            hardDisk = HardDiskSpec(
                enabled = true,
                imagePath = "/hd.img",
                secondImagePath = "/hd2.img",
                provisionBytes = 5_000_000L,
                bootFromDisk = true,
                ioBase = 0x320,
                irq = 5,
                dmaChannel = 3,
                useInt13Shim = true,
                cylinders = 306,
                heads = 4,
                sectorsPerTrack = 17,
            ),
            cards = listOf(
                CardSpecDto("/cards/adlib.jar", enabled = true, config = mapOf("base" to "0x388")),
                CardSpecDto("/cards/off.jar", enabled = false, config = emptyMap()),
            ),
        )
        store.save(def)

        assertEquals(listOf(id), store.listIds())
        val loaded = store.load(id)!!
        assertEquals(def.name, loaded.name)
        assertEquals(def.u18RomPath, loaded.u18RomPath)
        assertEquals(def.u19RomPath, loaded.u19RomPath)
        assertEquals(512, loaded.motherboard.baseMemoryKb)
        assertTrue(loaded.motherboard.mathCoprocessor)
        assertEquals(InitialVideoKind.CGA_40x25, loaded.motherboard.initialVideo)
        assertEquals(GraphicsKind.CGA, loaded.graphics)
        assertEquals(false, loaded.enableCom1)
        assertEquals(listOf("/a.img", "/b.img"), loaded.floppy.driveImages)
        assertEquals("/hd.img", loaded.hardDisk.imagePath)
        assertEquals("/hd2.img", loaded.hardDisk.secondImagePath)
        assertEquals(5_000_000L, loaded.hardDisk.provisionBytes)
        assertTrue(loaded.hardDisk.bootFromDisk)
        assertTrue(loaded.hardDisk.useInt13Shim)
        assertEquals(306, loaded.hardDisk.cylinders)
        assertEquals(4, loaded.hardDisk.heads)
        assertEquals(17, loaded.hardDisk.sectorsPerTrack)
        assertEquals(2, loaded.cards.size)
        assertEquals("/cards/adlib.jar", loaded.cards[0].jarPath)
        assertEquals(mapOf("base" to "0x388"), loaded.cards[0].config)
        assertEquals(false, loaded.cards[1].enabled)
    }

    @Test
    fun loadMissingAndDeleteAreSafe() {
        val store = VmStore(temp)
        val id = VmId("missing")
        assertNull(store.load(id))
        store.delete(id) // no throw
        assertTrue(store.listIds().isEmpty())
    }
}
