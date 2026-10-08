package com.trugath.k8086.isa

import com.trugath.k8086.Machine
import com.trugath.k8086.api.IsaCard

internal expect class IsaSlotLoader() {
    fun loadAll(machine: Machine, specs: List<CardSpec>): List<IsaCard>
    fun detachAll()
    fun cards(): List<IsaCard>
}
