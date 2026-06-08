package com.orbitalfrontier.economy

/**
 * The data-driven catalog of resources that can be mined from asteroid fields and carried as cargo
 * (UC06 AC#1; docs/design/economy-and-resources.md "Resources").
 *
 * A closed set modelled as an `enum` (coding-guidelines § O): the MVP resources are fixed and known
 * at compile time, and new resources are added by introducing a new constant — never by editing a
 * central `when`. The set spans basic materials (Hydrogen … Nickel) to tech inputs (Titanium …
 * Platinum), matching the economy design note's provisional list.
 *
 * **Ordinal order is a load-bearing invariant.** The mining extraction order ([com.orbitalfrontier
 * .world.Mining]) walks the resources in declaration order, so the order below is part of the
 * deterministic simulation contract — appending new resources is safe, reordering changes mining
 * behaviour and replay outcomes. The persistence layer keys rows by [name] (stable across reorders),
 * so the order is purely a runtime/mining concern, not a save-format one.
 *
 * No economic value lives here — pricing/yields are a later concern (UC08 selling); this type is the
 * pure identity + display label only, keeping the `economy` package free of engine and balancing
 * concerns (Single Responsibility).
 */
enum class ResourceType(val displayName: String) {
    HYDROGEN("Hydrogen"),
    WATER_ICE("Water Ice"),
    IRON_ORE("Iron Ore"),
    COPPER("Copper"),
    SILICON("Silicon"),
    ALUMINUM("Aluminum"),
    NICKEL("Nickel"),
    TITANIUM("Titanium"),
    RARE_EARTH_ELEMENTS("Rare Earth Elements"),
    HELIUM_3("Helium-3"),
    PLATINUM("Platinum"),
}
