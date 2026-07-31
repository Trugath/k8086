package com.trugath.k8086.cpu

import com.trugath.k8086.api.MemoryRegion
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * Machine-wiring helpers: ROM mapping and disk image setup.
 * Disk accessors used outside this package remain members on [Emulator8086].
 */

/**
 * Map the real IBM 5155/5160 ROM BIOS chips into the F000 segment and set the
 * 8086-family power-on state: CS:IP = FFFF:0000 (linear 0xFFFF0).
 */
internal fun Emulator8086.loadSystemRoms(u18Path: String, u19Path: String) {
    mapSystemRom(u19Path, ROM_BIOS_U19_BASE, 0x2000, "bios-u19")
    mapSystemRom(u18Path, ROM_BIOS_U18_BASE, 0x8000, "bios-u18")
    romLoaded = true
    reset()
}

private fun Emulator8086.mapSystemRom(path: String, base: Int, maxLen: Int, owner: String) {
    val file = File(path)
    if (!file.exists()) throw FileNotFoundException("ROM file not found: $path")
    FileInputStream(file).use { fis ->
        val data = fis.readAllBytes()
        val len = minOf(data.size, maxLen, RAM_SIZE - base)
        val slice = data.copyOf(len)
        memoryBus.map(MemoryRegion.Rom(base, len, slice), owner)
    }
}

/** Open floppy/HDD images and apply DL/AX/CX boot-drive side effects. */
internal fun Emulator8086.setupBootDisks(
    floppyImages: List<String> = emptyList(),
    hardDiskImage: String? = null,
    hardDiskBytes: Long = XT_HARD_DISK_BYTES,
    secondHardDiskImage: String? = null,
) {
    val setup = diskStore.setupBootDisks(floppyImages, hardDiskImage, hardDiskBytes, secondHardDiskImage)
    regs8[REG_DL] = setup.bootDl.toByte()
    if (setup.hardDiskOpened) {
        regs16[REG_AX] = setup.ax.toShort()
        regs16[REG_CX] = setup.cx.toShort()
    }
}

/** Convenience overload: single floppy image (or none). */
internal fun Emulator8086.setupBootDisks(
    floppyImage: String?,
    hardDiskImage: String?,
    hardDiskBytes: Long = XT_HARD_DISK_BYTES,
) = setupBootDisks(listOfNotNull(floppyImage), hardDiskImage, hardDiskBytes)

/** Close any open disk image files (called by the Machine on shutdown). */
internal fun Emulator8086.closeDisks() = diskStore.closeDisks()

/**
 * Swap or eject the floppy image for BIOS drive [drive] (0=A: … 3=D:).
 * Pass [path] null to eject. Caller should signal FDC disk-change afterward.
 */
internal fun Emulator8086.changeFloppyImage(drive: Int, path: String?) =
    diskStore.changeFloppyImage(drive, path)

/** Image file backing a BIOS drive number (0x00 floppy A:, 0x80 hard disk C:). */
internal fun Emulator8086.diskImage(driveNumber: Int): RandomAccessFile? =
    diskStore.diskImage(driveNumber)

internal fun Emulator8086.isDiskOpen(driveNumber: Int): Boolean =
    diskStore.isDiskOpen(driveNumber)
