# ADR 0008 — Fleet & outfitting: derived stats, junkyard-as-kind, additive v7 persistence

- **Status:** Accepted
- **Date:** 2026-06-08
- **Refines:** [ADR 0002](0002-persistence-sqlite-migrations.md) / [ADR 0003](0003-persistence-access-layer-sqldelight.md) (persistence + sequential migrations), [ADR 0007](0007-trading-prices.md) (authored data reconstructed, not row-persisted), and [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable). Realizes use-case 09 (outfitting, upgrades, junkyards & multiple ships).

## Context

`PROJECT_BRIEF.md` → core_gameplay_loop ("Improve") and
[upgrades-and-progression.md](../design/upgrades-and-progression.md) call for **horizontal fit-out
progression**: ships are roles with per-category **slot layouts**, **upgrades** fill free slots and
modify the matching stat, parts are bought at dealers and only **removed/sold/refit at junkyards**,
and the player can **own multiple ships** and switch the active one while docked — all persisted.

UC04–UC08 modelled a **single** ship: `WorldState`/`SimulationState` held one ship's kinematics,
cargo and fuel directly, and the save had one `ship` row (capacity reconstructed, not stored — the
pattern established for cargo in UC06 and fuel in UC07). UC09 forces three decisions: how to
represent a multi-ship fleet without breaking that single-ship code and its recorded playthroughs;
how upgrades affect stats; and how junkyards fit the world model. The hard constraint is the
**byte-identical contract**: the refactor must not change any pre-UC09 number, so existing fixtures
still replay bit-for-bit.

## Options considered

| Option | For | Against |
|---|---|---|
| **Fleet of `OwnedShip`s; capacity/movement are *derived* stats (`type + loadout`); junkyard is a station *kind*; additive v7 schema** | Active ship accessors keep every read site compiling; derived stats mean a save never pins a stale capacity (extends the UC06/07 precedent to all stats); junkyard reuses docking/minimap/hub unchanged; migration is purely additive (ALTER + new table) so old saves upgrade untouched | A `ship ↔ outfit` package pairing (a cycle Kotlin allows); pure resolvers take decoupled pieces, not a `Station`, so the call sites assemble inputs |
| Store each ship's capacities as columns | Simpler load (no re-derivation) | Re-introduces the stale-capacity bug UC06/07 deliberately avoided; an upgrade retune would silently invalidate saves |
| Junkyard as a new `Poi` subtype | Matches the design note's first wording | Forces docking, the minimap transponder and the hub to learn a new POI type for what is really a station *capability* |
| Resolvers take the world `Station` | Fewer call-site args | Couples the pure `outfit`/`ship` packages to `world`; breaks the `Trading`-takes-`StationMarket` precedent that keeps resolvers world-agnostic |

## Decision

1. **Fleet model.** `WorldState`/`SimulationState` hold a `Fleet` (a sorted-by-id list of `OwnedShip`
   + an `activeShipId`); `ship`/`cargo`/`fuel` become computed accessors delegating to `fleet.active`,
   so existing read sites are unchanged. Each `OwnedShip` carries its own `ShipType`, kinematics,
   cargo, fuel and `Loadout`.
2. **Capacity & movement are derived stats**, not save data. `ShipStats` derives cargo/fuel/scan/crew
   capacities and effective movement params from `ShipType` baseline + `Loadout` deltas
   (`UpgradeCatalog`). `OwnedShip.withLoadout` is the **single** re-derivation point. The starter
   type's empty-fit stats are pinned to today's constants (`Cargo.DEFAULT_CAPACITY`,
   `FuelParams.DEFAULT_TANK_CAPACITY`, identity movement) — the byte-identical contract.
3. **Junkyard is a `StationKind`, not a new `Poi`.** A junkyard is a dockable `Station` whose `kind`
   permits remove/sell/refit; this **supersedes** the design note's "junkyard = NEW POI type" wording.
   Outfit desks (`OutfitMarket`) and shipyards (`Shipyard`) are likewise authored station capabilities,
   reconstructed from the map and **never persisted** (ADR 0007 precedent). Upgrade/ship **prices**
   live on the `Upgrade`/`ShipType`, not per station.
4. **Pure resolvers stay world-agnostic.** `Outfitting`/`FleetResolver` take the decoupled pieces they
   need (`OutfitMarket`/`Shipyard`/slot counts/junkyard-flag), mirroring `Trading` taking a
   `StationMarket`; the sim and device build those from the docked station.
5. **Additive schema v7.** `ship` gains `ship_type TEXT DEFAULT 'starter'`; a new
   `ship_upgrade(ship_id, slot_category, slot_index, upgrade_id)` table holds each ship's fit
   (gap-tolerant, keyed by slot). The v6→v7 migration only adds the column (backfilled to the starter
   type) and the empty table, so a migrated save reads back as one starter ship with no upgrades.
   Unknown persisted ship type / upgrade id / resource degrade to skip-with-WARN — never crash.

## Consequences

- **Easier:** adding ship types or upgrades is data-only (roster/catalog); a stat retune touches one
  place; multi-ship saves load via one generalized loop; old saves and recorded playthroughs are
  unaffected (additive migration + empty-`ownedShips` legacy decode in the playthrough DTO).
- **Harder / costs:** a `ship ↔ outfit` package cycle (benign in Kotlin); device wiring carries the
  most incidental complexity (switching the active ship re-seeds the Box2D body and repositions the
  ship to the docked spot — a device-only nicety the pure sim does not do).
- **Reversibility:** the schema is forward-only (a future change is another `.sqm` + version bump, per
  ADR 0002). Reputation gating (UC14), ship removal/trade-in and idle-ship storage are deliberately
  out of scope — a fleet only grows in this UC.
