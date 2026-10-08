package com.trugath.k8086

import com.trugath.k8086.config.CardCatalog
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.io.File

class CardCatalogTest {
    @Test
    fun refreshSkipsIncompatibleFactoriesWithoutThrowing() {
        assertDoesNotThrow {
            CardCatalog(listOf(File("cards"))).use { catalog ->
                catalog.refresh()
                // Freshly built example cards should expose descriptors.
                for (entry in catalog.entries()) {
                    entry.descriptor()
                }
            }
        }
    }
}
