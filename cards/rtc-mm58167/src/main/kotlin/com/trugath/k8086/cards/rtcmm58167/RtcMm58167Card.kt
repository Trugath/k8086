package com.trugath.k8086.cards.rtcmm58167

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind
import java.time.LocalDateTime
import java.time.ZoneOffset

class RtcMm58167CardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.rtc-mm58167",
        name = "MM58167 Real-Time Clock",
        description = "SixPakPlus-style battery RTC at 0x2C0 with a small option ROM that " +
            "hooks INT 1Ah AH=02–04 (get/set time/date).",
        category = "I/O",
        fields = listOf(
            ConfigField(
                "base", "I/O base", ConfigFieldType.HEX_INT, "0x2C0",
                "MM58167 register window (32 ports)", affectsResources = true,
            ),
            ConfigField(
                "romBase", "Option ROM base", ConfigFieldType.HEX_INT, "0xCC000",
                "2K-aligned; leave 0 to skip ROM", affectsResources = true,
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val base = parseHex(config["base"]) ?: 0x2C0
        val romBase = parseHex(config["romBase"]) ?: 0xCC000
        return RtcMm58167Card(base, romBase)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val base = parseHex(config["base"]) ?: 0x2C0
        val romBase = parseHex(config["romBase"]) ?: 0xCC000
        val id = descriptor().id
        return buildList {
            add(ResourceClaim(ResourceKind.IO_PORT, base, base + 0x1F, id))
            if (romBase != 0) {
                add(ResourceClaim(ResourceKind.MEMORY, romBase, romBase + 511, id))
            }
        }
    }
}

/**
 * Simplified MM58167 clock/calendar at [ioBase] (default 0x2C0).
 *
 * Registers (BCD): 1=seconds, 2=minutes, 3=hours, 5=day, 6=month, 7=year.
 * Host wall-clock backs the counters; writes update the latch.
 *
 * Optional option ROM at [romBase] hooks INT 1Ah AH=02–04.
 */
class RtcMm58167Card(
    private val ioBase: Int,
    private val romBase: Int,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.rtc-mm58167"
    override val name = "MM58167 RTC @ 0x${ioBase.toString(16)}"

    override fun attach(host: IsaHost) {
        host.mapIo(Mm58167(ioBase), ioBase until ioBase + 0x20)
        if (romBase != 0) {
            host.mapOptionRom(buildRtcOptionRom(ioBase), romBase)
        }
    }
}

class Mm58167(
    private val base: Int,
    private var epochSecond: Long = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC),
    private var nanosAtEpoch: Long = System.nanoTime(),
) : IoDevice {
    private val regs = IntArray(32)

    init {
        syncFromHost()
    }

    private fun syncFromHost() {
        val elapsed = (System.nanoTime() - nanosAtEpoch) / 1_000_000_000L
        val dt = LocalDateTime.ofEpochSecond(epochSecond + elapsed, 0, ZoneOffset.UTC)
        regs[0] = 0
        regs[1] = toBcd(dt.second)
        regs[2] = toBcd(dt.minute)
        regs[3] = toBcd(dt.hour)
        regs[4] = toBcd(dt.dayOfWeek.value % 7)
        regs[5] = toBcd(dt.dayOfMonth)
        regs[6] = toBcd(dt.monthValue)
        regs[7] = toBcd(dt.year % 100)
    }

    private fun applyWritesToEpoch() {
        val year = 2000 + fromBcd(regs[7])
        val month = fromBcd(regs[6]).coerceIn(1, 12)
        val day = fromBcd(regs[5]).coerceIn(1, 31)
        val hour = fromBcd(regs[3]).coerceIn(0, 23)
        val minute = fromBcd(regs[2]).coerceIn(0, 59)
        val second = fromBcd(regs[1]).coerceIn(0, 59)
        val dt = try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (_: Exception) {
            LocalDateTime.now(ZoneOffset.UTC)
        }
        epochSecond = dt.toEpochSecond(ZoneOffset.UTC)
        nanosAtEpoch = System.nanoTime()
    }

    override fun ioReadByte(port: Int): Int {
        syncFromHost()
        val off = port - base
        return if (off in 0 until 32) regs[off] and 0xFF else 0xFF
    }

    override fun ioWriteByte(port: Int, value: Int) {
        val off = port - base
        if (off !in 0 until 32) return
        regs[off] = value and 0xFF
        if (off in 1..7) applyWritesToEpoch()
    }

    companion object {
        fun toBcd(v: Int): Int {
            val n = v.coerceIn(0, 99)
            return ((n / 10) shl 4) or (n % 10)
        }

        fun fromBcd(v: Int): Int {
            val t = v and 0xFF
            return ((t shr 4) and 0x0F) * 10 + (t and 0x0F)
        }
    }
}

/**
 * 512-byte option ROM: hooks INT 1Ah for AH=02/03/04; AH=00/01 chain to prior handler.
 *
 * Data at ROM+0x100: old INT 1Ah offset/segment; ROM+0x104: I/O base word.
 */
fun buildRtcOptionRom(ioBase: Int): ByteArray {
    val rom = ByteArray(512)
    rom[0] = 0x55.toByte()
    rom[1] = 0xAA.toByte()
    rom[2] = 0x01

    val buf = ArrayList<Int>(256)
    fun e(vararg b: Int) {
        b.forEach { buf.add(it and 0xFF) }
    }

    // --- init at offset 3 ---
    e(0x1E) // PUSH DS
    e(0x31, 0xC0) // XOR AX,AX
    e(0x8E, 0xD8) // MOV DS,AX
    e(0xA1, 0x68, 0x00) // MOV AX,[1Ah*4]
    e(0x2E, 0xA3, 0x00, 0x01) // CS: MOV [0100],AX
    e(0xA1, 0x6A, 0x00)
    e(0x2E, 0xA3, 0x02, 0x01) // CS: MOV [0102],AX
    e(0xB8) // MOV AX,handler
    val handlerImmAt = buf.size
    e(0x00, 0x00)
    e(0xA3, 0x68, 0x00)
    e(0x8C, 0xC8) // MOV AX,CS
    e(0xA3, 0x6A, 0x00)
    e(0x1F) // POP DS
    e(0xCB) // RETF

    val handlerOff = 3 + buf.size
    buf[handlerImmAt] = handlerOff and 0xFF
    buf[handlerImmAt + 1] = (handlerOff shr 8) and 0xFF

    // --- handler ---
    // CMP AH,imm / JE rel placeholders collected then patched
    data class Je(val at: Int, var target: Int = -1)
    val jes = mutableListOf<Je>()

    fun cmpAhJe(ah: Int): Je {
        e(0x80, 0xFC, ah)
        val je = Je(buf.size)
        e(0x74, 0x00)
        jes.add(je)
        return je
    }

    val j0 = cmpAhJe(0)
    val j1 = cmpAhJe(1)
    val j2 = cmpAhJe(2)
    val j3 = cmpAhJe(3)
    val j4 = cmpAhJe(4)
    val jmpChainAt = buf.size
    e(0xEB, 0x00)

    val chainOff = 3 + buf.size
    e(0x2E, 0xFF, 0x2E, 0x00, 0x01) // JMP FAR CS:[0100]

    fun loadIoBase() {
        e(0x2E, 0x8B, 0x16, 0x04, 0x01) // MOV DX,CS:[0104]
    }

    val getTimeOff = 3 + buf.size
    loadIoBase()
    e(0x42) // INC DX → seconds
    e(0xEC); e(0x88, 0xC3) // BL=sec
    e(0x42); e(0xEC); e(0x88, 0xC7) // BH=min
    e(0x42); e(0xEC); e(0x88, 0xC5) // CH=hour
    e(0x88, 0xF9) // CL=BH min
    e(0x88, 0xDE) // DH=BL sec
    e(0xB2, 0x00) // DL=0
    e(0xF8); e(0xCF)

    val setTimeOff = 3 + buf.size
    loadIoBase()
    e(0x42)
    e(0x88, 0xF0); e(0xEE) // OUT sec=DH
    e(0x42)
    e(0x88, 0xC8); e(0xEE) // OUT min=CL
    e(0x42)
    e(0x88, 0xE8); e(0xEE) // OUT hour=CH
    e(0xF8); e(0xCF)

    val getDateOff = 3 + buf.size
    loadIoBase()
    e(0x83, 0xC2, 0x05) // day
    e(0xEC); e(0x88, 0xC2) // DL=day
    e(0x42); e(0xEC); e(0x88, 0xC6) // DH=month
    e(0x42); e(0xEC)
    e(0xB5, 0x20) // CH=20h
    e(0x88, 0xC1) // CL=year
    e(0xF8); e(0xCF)

    j0.target = chainOff
    j1.target = chainOff
    j2.target = getTimeOff
    j3.target = setTimeOff
    j4.target = getDateOff

    fun rel8(fromAfter: Int, target: Int): Int = (target - fromAfter) and 0xFF
    for (je in jes) {
        val absJe = 3 + je.at
        buf[je.at + 1] = rel8(absJe + 2, je.target)
    }
    buf[jmpChainAt + 1] = rel8(3 + jmpChainAt + 2, chainOff)

    for (i in buf.indices) {
        rom[3 + i] = buf[i].toByte()
    }

    rom[0x104] = (ioBase and 0xFF).toByte()
    rom[0x105] = ((ioBase shr 8) and 0xFF).toByte()

    var sum = 0
    for (i in 0 until 511) sum = (sum + (rom[i].toInt() and 0xFF)) and 0xFF
    rom[511] = ((0 - sum) and 0xFF).toByte()
    return rom
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}
