package com.trugath.k8086.protocol

/**
 * Shipped system ROM defaults (rmDOS U18/U19), relative to the k8086 working directory.
 *
 * Override with `K8086_U18_ROM` / `K8086_U19_ROM`, or pick alternate images when creating
 * or editing a VM in the workstation.
 */
object SystemRomDefaults {
    const val U18_RELATIVE = "roms/u18.bin"
    const val U19_RELATIVE = "roms/u19.bin"
    /** Guest Fixed Disk option ROM (C800:); resolved beside U18 or via [FDROM_RELATIVE]. */
    const val FDROM_RELATIVE = "roms/fdrom.bin"

    fun resolve(getenv: (String) -> String? = System::getenv): Pair<String, String> {
        val u18 = getenv("K8086_U18_ROM") ?: U18_RELATIVE
        val u19 = getenv("K8086_U19_ROM") ?: U19_RELATIVE
        return u18 to u19
    }
}
