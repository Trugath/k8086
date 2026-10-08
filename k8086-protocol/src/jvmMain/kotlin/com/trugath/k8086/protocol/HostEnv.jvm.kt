package com.trugath.k8086.protocol

internal actual object HostEnv {
    actual fun getenv(name: String): String? = System.getenv(name)
}
