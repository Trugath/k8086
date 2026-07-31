package com.trugath.k8086.config

import com.trugath.k8086.api.IsaCardFactory
import com.trugath.k8086.isa.IsaSlotLoader
import java.io.File
import java.net.URLClassLoader

/**
 * Discovers card JARs and loads their [IsaCardFactory] metadata for the wizard.
 *
 * Scans common build-output locations under [searchRoots] plus any explicit paths.
 * Class loaders are kept open for the lifetime of the catalog so descriptors remain usable.
 */
class CardCatalog(
    private val searchRoots: List<File> = defaultSearchRoots(),
) : AutoCloseable {
    data class Entry(
        val jarPath: String,
        val factory: IsaCardFactory,
        private val classLoader: URLClassLoader,
    ) {
        fun descriptor() = factory.descriptor()
    }

    private val loaders = mutableListOf<URLClassLoader>()
    private val entries = mutableListOf<Entry>()

    fun entries(): List<Entry> = entries.toList()

    fun refresh(extraJars: List<File> = emptyList()) {
        close()
        val jars = linkedSetOf<File>()
        for (root in searchRoots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .maxDepth(6)
                .filter { it.isFile && it.name.endsWith(".jar") && it.name != "k8086-api.jar" }
                .forEach { jars += it.absoluteFile }
        }
        for (j in extraJars) {
            if (j.isFile) jars += j.absoluteFile
        }
        val apiCl = IsaCardFactory::class.java.classLoader
        for (jar in jars.sortedBy { it.name.lowercase() }) {
            var opened: URLClassLoader? = null
            try {
                val cl = URLClassLoader(arrayOf(jar.toURI().toURL()), apiCl)
                opened = cl
                val factories = IsaSlotLoader.loadFactoriesFromJar(jar, cl)
                val accepted = ArrayList<Entry>(factories.size)
                for (factory in factories) {
                    // Probe current API (stale JARs throw AbstractMethodError, an Error).
                    try {
                        factory.descriptor()
                        accepted += Entry(jar.absolutePath, factory, cl)
                    } catch (_: Throwable) {
                        // Skip factories compiled against an older IsaCardFactory.
                    }
                }
                if (accepted.isEmpty()) {
                    cl.close()
                    opened = null
                    continue
                }
                loaders += cl
                opened = null
                entries += accepted
            } catch (_: Throwable) {
                // Skip unreadable / non-card / incompatible JARs.
                try {
                    opened?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun close() {
        for (cl in loaders.asReversed()) {
            try {
                cl.close()
            } catch (_: Exception) {
            }
        }
        loaders.clear()
        entries.clear()
    }

    companion object {
        fun defaultSearchRoots(): List<File> = listOf(
            File("cards"),
            File("build/libs"),
        )
    }
}
