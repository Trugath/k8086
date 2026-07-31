package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/** Repo-root asset paths shared by integration and coverage tests. */
internal object TestAssets {
    const val U18_PATH = "roms/u18.bin"
    const val U19_PATH = "roms/u19.bin"
    const val FLOPPY_PATH = "disks/fd.img"

    val u18: File get() = File(U18_PATH)
    val u19: File get() = File(U19_PATH)
    val floppy: File get() = File(FLOPPY_PATH)

    fun assumeRomsPresent() {
        assumeTrue(u18.exists() && u19.exists(), "System ROMs required under roms/ (u18.bin, u19.bin)")
    }

    fun assumeFloppyPresent() {
        assumeTrue(floppy.exists(), "$FLOPPY_PATH boot floppy missing")
    }

    fun machine(showVideo: Boolean = false): Machine {
        assumeRomsPresent()
        return Machine(
            u18.absolutePath,
            u19.absolutePath,
            MachineOptions(
                showVideo = showVideo,
                enableAudio = false,
                exitOnClose = false,
                realtime = false,
            ),
        )
    }
}
