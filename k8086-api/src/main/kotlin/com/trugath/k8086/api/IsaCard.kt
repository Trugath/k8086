package com.trugath.k8086.api

/**
 * Type of a card configuration field shown in the start wizard / CLI help.
 *
 * [IRQ] and [DMA] are first-class so future cards (e.g. NE2000) can declare
 * interrupt and DMA lines without inventing ad-hoc string conventions.
 */
enum class ConfigFieldType {
    STRING,
    PATH,
    INT,
    HEX_INT,
    BOOL,
    CHOICE,
    IRQ,
    DMA,
    /** Virtual network id; wizard shows a chooser of known networks. */
    NETWORK,
}

/**
 * One configurable key for an ISA card plugin.
 *
 * [affectsResources] should be true when changing the value changes the card's
 * [IsaCardFactory.resourceClaims] footprint (ports, IRQ, DMA, memory).
 */
data class ConfigField(
    val key: String,
    val label: String,
    val type: ConfigFieldType,
    val defaultValue: String = "",
    val description: String = "",
    val choices: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val affectsResources: Boolean = false,
)

/** Kind of hardware resource a card (or the motherboard) occupies. */
enum class ResourceKind {
    IO_PORT,
    IRQ,
    DMA_CHANNEL,
    MEMORY,
}

/**
 * Inclusive resource range claimed by a device.
 *
 * For IRQ and DMA, [start] == [endInclusive] is a single line/channel.
 * For I/O and memory, the range is inclusive on both ends.
 */
data class ResourceClaim(
    val kind: ResourceKind,
    val start: Int,
    val endInclusive: Int,
    val owner: String,
) {
    init {
        require(endInclusive >= start) {
            "ResourceClaim end < start for $owner ($kind)"
        }
    }

    fun overlaps(other: ResourceClaim): Boolean =
        kind == other.kind && start <= other.endInclusive && other.start <= endInclusive

    fun describe(): String = when (kind) {
        ResourceKind.IO_PORT ->
            if (start == endInclusive) "I/O 0x${start.toString(16)}"
            else "I/O 0x${start.toString(16)}-0x${endInclusive.toString(16)}"
        ResourceKind.IRQ -> "IRQ$start"
        ResourceKind.DMA_CHANNEL -> "DMA$start"
        ResourceKind.MEMORY ->
            "mem 0x${start.toString(16)}-0x${endInclusive.toString(16)}"
    }
}

/**
 * Human-facing metadata for an ISA card, used by the start wizard and tooling.
 *
 * [category] groups cards in the UI (Sound, Memory, Network, Utility, …).
 */
data class CardDescriptor(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "Expansion",
    val fields: List<ConfigField> = emptyList(),
)

/** Factory discovered via ServiceLoader in card JARs. */
interface IsaCardFactory {
    fun create(config: Map<String, String>): IsaCard

    /** Schema and display metadata for wizards / help. */
    fun descriptor(): CardDescriptor

    /**
     * Resources this card will claim for [config], without attaching.
     * Used for collision checks before boot.
     */
    fun resourceClaims(config: Map<String, String>): List<ResourceClaim> = emptyList()
}

/** An ISA expansion card loaded from a JAR. */
interface IsaCard {
    val id: String
    val name: String
    fun attach(host: IsaHost)
    fun detach() {}
}
