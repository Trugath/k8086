package com.trugath.k8086.cpu

import com.trugath.k8086.platform.HostPlatform
import com.trugath.k8086.storage.DiskImage

/**
 * Floppy/HDD image backing store indexed by BIOS drive number
 * (0x00–0x03 = A:–D:, 0x80/0x81 = hard disks).
 *
 * Owned by [Machine] wiring; [Emulator8086] may forward for compatibility.
 */
internal class DiskImageStore {
    private val disk = arrayOfNulls<DiskImage>(256)

    /**
     * Open floppy/HDD images and return boot-drive / geometry hints for the CPU.
     * Hard-disk path prefixed with `@` selects HD boot (DL=0x80).
     */
    fun setupBootDisks(
        floppyImages: List<String> = emptyList(),
        hardDiskImage: String? = null,
        hardDiskBytes: Long = XT_HARD_DISK_BYTES,
        secondHardDiskImage: String? = null,
    ): BootDiskSetup {
        var hdPath = hardDiskImage
        val bootFromHardDisk = hdPath != null && hdPath.startsWith("@")
        if (bootFromHardDisk) {
            hdPath = hdPath.substring(1)
        }
        val bootDl = if (bootFromHardDisk) 0x80 else 0

        require(floppyImages.size <= 4) { "At most 4 floppy drives (A:–D:)" }
        for ((i, path) in floppyImages.withIndex()) {
            disk[i] = HostPlatform.openDisk(path)
        }
        var ax = 0
        var cx = 0
        hdPath?.let {
            val hdFile = HostPlatform.openOrCreateDisk(it, hardDiskBytes)
            disk[0x80] = hdFile
            val sectorCount = hdFile.length() / 512
            ax = (sectorCount and 0xFFFF).toInt()
            cx = ((sectorCount shr 16) and 0xFFFF).toInt()
        }
        secondHardDiskImage?.let {
            disk[0x81] = HostPlatform.openOrCreateDisk(it, hardDiskBytes)
        }
        return BootDiskSetup(bootDl = bootDl, ax = ax, cx = cx, hardDiskOpened = hdPath != null)
    }

    fun closeDisks() {
        for (i in disk.indices) {
            disk[i]?.close()
            disk[i] = null
        }
    }

    /**
     * Swap or eject the floppy image for BIOS drive [drive] (0=A: … 3=D:).
     * Pass [path] null to eject.
     */
    fun changeFloppyImage(drive: Int, path: String?) {
        require(drive in 0..3) { "Floppy drive must be 0..3 (got $drive)" }
        disk[drive]?.close()
        disk[drive] = null
        if (path != null) {
            disk[drive] = HostPlatform.openDisk(path)
        }
    }

    fun diskImage(driveNumber: Int): DiskImage? = disk.getOrNull(driveNumber)

    fun isDiskOpen(driveNumber: Int): Boolean = disk.getOrNull(driveNumber) != null

    internal data class BootDiskSetup(
        val bootDl: Int,
        val ax: Int,
        val cx: Int,
        val hardDiskOpened: Boolean,
    )
}
