package com.trugath.k8086.protocol

internal expect object HostEnv {
    fun getenv(name: String): String?
}
