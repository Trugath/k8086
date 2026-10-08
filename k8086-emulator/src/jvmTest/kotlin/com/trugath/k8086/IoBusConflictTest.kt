package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IoBusConflictTest {
    @Test
    fun secondMapperOnSamePortFails() {
        val bus = IoBus()
        val a = object : com.trugath.k8086.api.IoDevice {
            override fun ioReadByte(port: Int) = 0x11
            override fun ioWriteByte(port: Int, value: Int) {}
        }
        val b = object : com.trugath.k8086.api.IoDevice {
            override fun ioReadByte(port: Int) = 0x22
            override fun ioWriteByte(port: Int, value: Int) {}
        }
        bus.map(a, listOf(0x300), owner = "card-a")
        val ex = assertThrows(IllegalStateException::class.java) {
            bus.map(b, listOf(0x300), owner = "card-b")
        }
        assertTrue(ex.message!!.contains("0x300") || ex.message!!.contains("300"))
    }
}
