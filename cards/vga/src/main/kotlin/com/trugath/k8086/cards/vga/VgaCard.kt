package com.trugath.k8086.cards.vga

import com.trugath.k8086.api.CardDescriptor
import com.trugath.k8086.api.ConfigField
import com.trugath.k8086.api.ConfigFieldType
import com.trugath.k8086.api.IoDevice
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.api.IsaHost
import com.trugath.k8086.api.MemoryDevice
import com.trugath.k8086.api.MemoryRegion
import com.trugath.k8086.api.ResourceClaim
import com.trugath.k8086.api.ResourceKind
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class VgaCardFactory : IsaCardFactory {
    override fun descriptor() = CardDescriptor(
        id = "com.trugath.k8086.cards.vga",
        name = "VGA (Wolf3D-scoped)",
        description = "Mode 03h text + Mode 13h / Mode Y VGA with C000 option ROM. " +
            "Disable built-in CGA (--no-cga / SW1 special).",
        category = "Video",
        fields = listOf(
            ConfigField(
                "romBase", "Option ROM base", ConfigFieldType.HEX_INT, "0xC0000",
                "2K-aligned VGA BIOS base", affectsResources = true,
            ),
            ConfigField(
                "window", "Show window", ConfigFieldType.BOOL, "true",
                "Open a present window (640×400 text / 640×400 mode 13h scaled)",
            ),
            ConfigField(
                "exitOnClose", "Exit on close", ConfigFieldType.BOOL, "true",
                "Closing the window exits the JVM (CLI)",
            ),
        ),
    )

    override fun create(config: Map<String, String>): IsaCard {
        val romBase = parseHex(config["romBase"]) ?: 0xC0000
        val window = parseBool(config["window"], true)
        val exitOnClose = parseBool(config["exitOnClose"], true)
        return VgaCard(romBase, window, exitOnClose)
    }

    override fun resourceClaims(config: Map<String, String>): List<ResourceClaim> {
        val romBase = parseHex(config["romBase"]) ?: 0xC0000
        val id = descriptor().id
        return listOf(
            ResourceClaim(ResourceKind.IO_PORT, 0x3C0, 0x3DF, id),
            ResourceClaim(ResourceKind.IO_PORT, VgaBiosRom.SOFT_ATTR_PORT, VgaBiosRom.SOFT_TTY_PORT, id),
            ResourceClaim(ResourceKind.MEMORY, 0xA0000, 0xAFFFF, id),
            ResourceClaim(ResourceKind.MEMORY, 0xB8000, 0xBFFFF, id),
            ResourceClaim(ResourceKind.MEMORY, romBase, romBase + 2047, id),
        )
    }
}

/**
 * Wolf3D-scoped VGA ISA card: text mode 03h, planar VRAM, DAC/CRTC/SEQ, C000 INT 10h ROM.
 *
 * Frame presents are **not** done from the CPU tickable — composeFrame(320×200) on the
 * emu thread starved Wolf3D (frozen picture, music/IRQs still alive). The Swing timer
 * pulls frames at ~60 Hz instead.
 */
class VgaCard(
    private val romBase: Int,
    private val showWindow: Boolean,
    private val exitOnClose: Boolean,
) : IsaCard {
    override val id = "com.trugath.k8086.cards.vga"
    override val name = "VGA (text / Mode 13h / Mode Y)"

    val core = VgaCore()
    private var host: IsaHost? = null
    private var window: VgaWindow? = null
    private var ttyAttr = 0x07
    private var dirtyAccum = 0

    override fun attach(host: IsaHost) {
        this.host = host
        require(romBase and 0x7FF == 0) { "VGA ROM base must be 2K-aligned" }

        core.setMode03h()

        host.mapOptionRom(VgaBiosRom.build(), romBase)
        host.mapMemory(
            MemoryRegion.Mmio(
                0xA0000,
                0x10000,
                object : MemoryDevice {
                    override fun memReadByte(offset: Int): Int = core.memRead(offset)
                    override fun memWriteByte(offset: Int, value: Int) = core.memWrite(offset, value)
                },
            ),
        )
        host.mapMemory(
            MemoryRegion.Mmio(
                0xB8000,
                0x8000,
                object : MemoryDevice {
                    override fun memReadByte(offset: Int): Int = core.textMemRead(offset)
                    override fun memWriteByte(offset: Int, value: Int) = core.textMemWrite(offset, value)
                },
            ),
        )

        val io = object : IoDevice {
            override fun ioReadByte(port: Int): Int = when (port and 0xFFFF) {
                VgaBiosRom.SOFT_MODE_PORT -> core.biosMode and 0xFF
                VgaBiosRom.SOFT_ATTR_PORT -> ttyAttr and 0xFF
                VgaBiosRom.SOFT_TTY_PORT -> 0
                else -> core.ioRead(port)
            }

            override fun ioWriteByte(port: Int, value: Int) {
                when (port and 0xFFFF) {
                    VgaBiosRom.SOFT_MODE_PORT -> applyBiosMode(value and 0xFF)
                    VgaBiosRom.SOFT_ATTR_PORT -> ttyAttr = value and 0xFF
                    VgaBiosRom.SOFT_TTY_PORT -> doTeletype(value and 0xFF)
                    else -> core.ioWrite(port, value)
                }
            }
        }
        host.mapIo(io, 0x3C0..0x3DF)
        host.mapIo(io, VgaBiosRom.SOFT_ATTR_PORT..VgaBiosRom.SOFT_TTY_PORT)

        host.addTickable { cycles ->
            core.tickCpuCycles(cycles)
            // Keep BDA 40:49 aligned when games reprogram CRTC after INT10 mode set
            // (or when something clobbers low memory after we wrote the mode).
            if (core.biosMode == 0x13) {
                val h = host
                if (h != null && (h.cpuRead8(0x449) and 0xFF) != 0x13) {
                    writeBdaMode(0x13, columns = 40, pageSize = 0xFA00)
                }
            }
            // Mark dirty sparingly — Swing timer (~60 Hz) does the expensive compose.
            if (showWindow) {
                dirtyAccum += cycles
                if (dirtyAccum >= 80_000) {
                    dirtyAccum = 0
                    window?.markDirty()
                }
            }
        }

        if (showWindow) {
            window = VgaWindow("k8086 VGA", exitOnClose, host.consoleControls()).also {
                it.ensureOpen()
                it.bindCore(core)
            }
        }
    }

    override fun detach() {
        window?.close()
        window = null
        host = null
    }

    private fun applyBiosMode(mode: Int) {
        when (mode and 0xFF) {
            0x13 -> {
                core.setMode13h()
                writeBdaMode(0x13, columns = 40, pageSize = 0xFA00)
            }
            0x00, 0x01, 0x02, 0x03, 0x07 -> {
                core.setMode03h()
                // Keep requested bios mode number for AH=0Fh (0/1/2/3/7).
                core.biosMode = mode and 0xFF
                writeBdaMode(mode and 0xFF, columns = if (mode == 0x00 || mode == 0x01) 40 else 80, pageSize = 0x1000)
                host?.cpuWrite8(0x450, 0)
                host?.cpuWrite8(0x451, 0)
            }
            else -> {
                // Unknown → text so POST/DOS stay visible.
                core.setMode03h()
                core.biosMode = mode and 0xFF
                writeBdaMode(mode and 0xFF, columns = 80, pageSize = 0x1000)
            }
        }
        window?.markDirty()
    }

    private fun doTeletype(ch: Int) {
        val h = host ?: return
        val attr = if (ttyAttr == 0) 0x07 else ttyAttr
        core.teletype(
            ch,
            attr,
            readCursor = {
                val col = h.cpuRead8(0x450) and 0xFF
                val row = h.cpuRead8(0x451) and 0xFF
                row to col
            },
            writeCursor = { row, col ->
                h.cpuWrite8(0x450, col and 0xFF)
                h.cpuWrite8(0x451, row and 0xFF)
            },
        )
        window?.markDirty()
    }

    private fun writeBdaMode(mode: Int, columns: Int, pageSize: Int) {
        val h = host ?: return
        h.cpuWrite8(0x449, mode and 0xFF)
        h.cpuWrite8(0x44A, columns and 0xFF)
        h.cpuWrite8(0x44B, (columns ushr 8) and 0xFF)
        h.cpuWrite8(0x44C, pageSize and 0xFF)
        h.cpuWrite8(0x44D, (pageSize ushr 8) and 0xFF)
        h.cpuWrite8(0x462, 0)
    }

    /** Host/debug snapshot of adapter state (used by CLI after a smoke run). */
    fun debugSnapshot(): String = core.debugSnapshot()

    /**
     * Write the current composed frame as a PNG. Used by headless dumps
     * (`K8086_VGA_DUMP_DIR`) when a Swing window is not available.
     */
    fun writePng(path: String): Boolean {
        return try {
            val text = core.isTextMode()
            val w = if (text) VgaCore.TEXT_PIXEL_W else 320
            val h = if (text) VgaCore.TEXT_PIXEL_H else 200
            val pixels = IntArray(w * h)
            if (text) core.composeTextFrame(pixels) else core.composeFrame(pixels)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, pixels, 0, w)
            val file = File(path)
            file.parentFile?.mkdirs()
            ImageIO.write(img, "png", file)
            true
        } catch (_: Exception) {
            false
        }
    }
}

private fun parseHex(s: String?): Int? {
    if (s == null) return null
    val t = s.trim().removePrefix("0x").removePrefix("0X")
    return t.toIntOrNull(16) ?: t.toIntOrNull()
}

private fun parseBool(s: String?, default: Boolean): Boolean {
    if (s == null) return default
    return when (s.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }
}
