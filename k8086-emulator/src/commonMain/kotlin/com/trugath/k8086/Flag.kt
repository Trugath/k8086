package com.trugath.k8086

import kotlin.concurrent.Volatile

internal class Flag(initial: Boolean = false) {
    @Volatile
    private var value = initial
    fun get(): Boolean = value
    fun set(v: Boolean) {
        value = v
    }
    fun compareAndSet(expected: Boolean, newValue: Boolean): Boolean {
        if (value != expected) return false
        value = newValue
        return true
    }
}

internal class LongFlag(initial: Long = 0L) {
    @Volatile
    private var value = initial
    fun get(): Long = value
    fun set(v: Long) {
        value = v
    }
}
