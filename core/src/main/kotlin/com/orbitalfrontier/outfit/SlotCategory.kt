package com.orbitalfrontier.outfit

/**
 * The categories of outfitting slot a ship exposes (UC09 AC#1; docs/design/upgrades-and-progression.md
 * "Upgrade slots — free slots by category").
 *
 * A closed set modelled as an `enum` (coding-guidelines § O): the MVP categories are fixed and known
 * at compile time, and a new category is added by introducing a new constant — never by editing a
 * central `when`. Each [com.orbitalfrontier.outfit.Upgrade] belongs to exactly one category and may
 * only be installed into a free slot **of that category** ([Loadout.install]); a ship type declares
 * how many slots it has per category ([com.orbitalfrontier.ship.ShipType.slotCounts]), so slot
 * layout is data-driven and varies by ship role (AC#1).
 *
 * **The constant name is the persisted key** — the `ship_upgrade.slot_category` column stores
 * [name] (stable across reordering, unlike the ordinal), so appending categories is save-safe but
 * renaming one is a migration. The set matches the design note's provisional MVP list.
 */
enum class SlotCategory {
    WEAPONS,
    COMMUNICATIONS,
    HULL_PLATING,
    ENGINES,
    SENSORS,
    CARGO,
    FUEL_TANK,
    CREW_QUARTERS,
}
