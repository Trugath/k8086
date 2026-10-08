package com.trugath.k8086.isa

import com.trugath.k8086.Machine
import com.trugath.k8086.api.IsaCard

internal actual class IsaSlotLoader actual constructor() {
    actual fun loadAll(machine: Machine, specs: List<CardSpec>): List<IsaCard> {
        if (specs.isNotEmpty()) {
            error("ISA JAR cards are not supported in the browser demo")
        }
        return emptyList()
    }

    actual fun detachAll() {}

    actual fun cards(): List<IsaCard> = emptyList()
}
