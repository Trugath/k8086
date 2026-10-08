package com.trugath.k8086.api

/** CPU identity shared by configuration, persistence, and presentation layers. */
enum class CpuModel(val label: String, val wireName: String) {
    I8088("8088 (XT)", "8088"),
    I8086("8086", "8086"),
    I80286("80286", "80286"),
    I80386("80386", "80386"),
    ;

    companion object {
        fun fromWire(value: String?): CpuModel = when (value?.trim()?.uppercase()) {
            "8086", "I8086" -> I8086
            "80286", "I80286", "286" -> I80286
            "80386", "I80386", "386" -> I80386
            else -> I8088
        }
    }
}
