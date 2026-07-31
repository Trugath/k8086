package com.trugath.k8086.storage

import java.io.RandomAccessFile

/**
 * XT-era CHS geometry for fixed disks: 17 sectors/track, heads grown until
 * cylinders fit in 10 bits (0..1023). Shared by [HdInt13] and [Wd1003].
 */
data class HdGeometry(
    val cylinders: Int,
    val heads: Int,
    val sectorsPerTrack: Int,
) {
    val sectorsPerCylinder: Int get() = heads * sectorsPerTrack
    val totalSectors: Long get() = cylinders.toLong() * heads * sectorsPerTrack

    fun lba(cyl: Int, head: Int, sector: Int): Long =
        (cyl.toLong() * heads + head) * sectorsPerTrack + (sector - 1)

    fun advanceChs(cyl: Int, head: Int, sector: Int): Triple<Int, Int, Int> {
        var c = cyl
        var h = head
        var s = sector + 1
        if (s > sectorsPerTrack) {
            s = 1
            h++
            if (h >= heads) {
                h = 0
                c++
            }
        }
        return Triple(c, h, s)
    }

    companion object {
        const val DEFAULT_SPT = 17
        const val DEFAULT_HEADS = 4

        fun fromImageSize(
            lengthBytes: Long,
            sectorsPerTrack: Int = DEFAULT_SPT,
            preferHeads: Int = DEFAULT_HEADS,
            overrideCylinders: Int? = null,
            overrideHeads: Int? = null,
            overrideSpt: Int? = null,
        ): HdGeometry {
            if (overrideCylinders != null && overrideHeads != null && overrideSpt != null) {
                return HdGeometry(overrideCylinders, overrideHeads, overrideSpt)
            }
            val spt = overrideSpt ?: sectorsPerTrack
            val total = lengthBytes / 512
            if (overrideCylinders != null && overrideHeads != null) {
                return HdGeometry(overrideCylinders, overrideHeads, spt)
            }
            var heads = overrideHeads ?: preferHeads
            var cyl = if (total == 0L) 0 else (total / (heads * spt)).toInt()
            if (overrideCylinders != null) {
                return HdGeometry(overrideCylinders, heads, spt)
            }
            while (cyl > 1023 && heads < 255) {
                heads++
                cyl = (total / (heads * spt)).toInt()
            }
            if (cyl < 1 && total > 0) cyl = 1
            return HdGeometry(cylinders = cyl.coerceAtMost(1024), heads = heads, sectorsPerTrack = spt)
        }

        fun fromImage(
            image: RandomAccessFile,
            overrideCylinders: Int? = null,
            overrideHeads: Int? = null,
            overrideSpt: Int? = null,
        ): HdGeometry = fromImageSize(
            image.length(),
            overrideCylinders = overrideCylinders,
            overrideHeads = overrideHeads,
            overrideSpt = overrideSpt,
        )
    }
}
