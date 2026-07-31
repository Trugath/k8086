package com.trugath.k8086

import com.trugath.k8086.bus.*
import com.trugath.k8086.chipset.*
import com.trugath.k8086.cpu.*
import com.trugath.k8086.isa.*
import com.trugath.k8086.storage.*
import com.trugath.k8086.video.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class IsaSlotLoaderCoverageTest {
    @Test
    fun missingJarAndEmptyServiceFileFail() {
        val machine = TestAssets.machine()
        val loader = IsaSlotLoader()

        assertThrows(IllegalArgumentException::class.java) {
            loader.loadAll(machine, listOf(CardSpec("/no/such/card.jar")))
        }

        val emptyJar = File.createTempFile("empty-card", ".jar")
        emptyJar.deleteOnExit()
        JarOutputStream(emptyJar.outputStream()).use { jos ->
            jos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            jos.write("Manifest-Version: 1.0\n".toByteArray())
            jos.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            loader.loadAll(machine, listOf(CardSpec(emptyJar.absolutePath)))
        }
    }

    @Test
    fun detachAllIsIdempotent() {
        val loader = IsaSlotLoader()
        loader.detachAll()
        assertTrue(loader.cards().isEmpty())
    }
}
