package com.trugath.k8086.isa

import com.trugath.k8086.Machine
import com.trugath.k8086.api.IsaCard
import com.trugath.k8086.api.IsaCardFactory
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Loads ISA card JARs via per-jar [URLClassLoader].
 * Parent loader is the API module's class loader so cards share [IsaCard] types
 * with the host without seeing emulator internals.
 *
 * Service providers are read only from the JAR's own `META-INF/services` entry
 * (not the merged classpath), so host/test classpaths that also contain card
 * factories cannot inflate or shadow a slot load.
 */
class IsaSlotLoader {
    private val loaded = mutableListOf<LoadedCard>()

    data class LoadedCard(
        val card: IsaCard,
        val classLoader: URLClassLoader,
        val jarPath: String,
    )

    fun loadAll(machine: Machine, specs: List<CardSpec>): List<IsaCard> {
        val apiCl = IsaCardFactory::class.java.classLoader
        val attached = mutableListOf<IsaCard>()
        for (spec in specs) {
            val jar = File(spec.jarPath)
            require(jar.isFile) { "Card JAR not found: ${spec.jarPath}" }
            val cl = URLClassLoader(arrayOf(jar.toURI().toURL()), apiCl)
            val factories = loadFactoriesFromJar(jar, cl)
            require(factories.isNotEmpty()) {
                "No ${IsaCardFactory::class.java.name} in META-INF/services of ${spec.jarPath}"
            }
            for (factory in factories) {
                val card = factory.create(spec.config)
                val host = IsaHostImpl(machine, card.id)
                card.attach(host)
                loaded += LoadedCard(card, cl, spec.jarPath)
                attached += card
            }
        }
        return attached
    }

    fun detachAll() {
        for (entry in loaded.asReversed()) {
            try {
                entry.card.detach()
            } catch (_: Exception) {
                // best-effort shutdown
            }
            try {
                entry.classLoader.close()
            } catch (_: Exception) {
            }
        }
        loaded.clear()
    }

    fun cards(): List<IsaCard> = loaded.map { it.card }

    companion object {
        internal fun loadFactoriesFromJar(jar: File, cl: ClassLoader): List<IsaCardFactory> {
            val servicePath = "META-INF/services/${IsaCardFactory::class.java.name}"
            JarFile(jar).use { jf ->
                val entry = jf.getJarEntry(servicePath) ?: return emptyList()
                return jf.getInputStream(entry).bufferedReader().use { reader ->
                    reader.lineSequence()
                        .map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }
                        .map { className ->
                            val clazz = Class.forName(className, true, cl)
                            require(IsaCardFactory::class.java.isAssignableFrom(clazz)) {
                                "$className does not implement IsaCardFactory"
                            }
                            clazz.getDeclaredConstructor().newInstance() as IsaCardFactory
                        }
                        .toList()
                }
            }
        }
    }
}
