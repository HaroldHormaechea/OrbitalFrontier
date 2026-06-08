package com.orbitalfrontier.ship

import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.Fuel
import com.orbitalfrontier.outfit.Loadout

/**
 * Test helper bridging the pre-UC09 single-ship call sites onto the UC09 [Fleet] shape.
 *
 * Before UC09 a `SimulationState` / `WorldState` was built from one ship's kinematics, cargo and fuel
 * directly. UC09 moved those onto the **active ship** inside a [Fleet]. This helper rebuilds the exact
 * pre-UC09 starting point — a single [OwnedShip.STARTER_SHIP_ID] starter ship (the [ShipRoster.STARTER]
 * type, identity stats), active — while letting a test override that ship's [kinematics], [cargo],
 * [fuel] or [loadout]. With no overrides it is value-equal to [Fleet.starter], so a fixture migrated
 * from `ship=/cargo=/fuel=` to `fleet = singleShipFleet(...)` is a zero-behaviour-change rewrite.
 *
 * Uses [OwnedShip.copy] (not [OwnedShip.withLoadout]) so the supplied cargo/fuel are taken verbatim —
 * capacities are **not** re-derived. The default [loadout] is empty (the byte-identical case); pass a
 * non-empty loadout only when you have already set matching cargo/fuel capacities yourself.
 */
fun singleShipFleet(
    kinematics: ShipKinematics = ShipKinematics(),
    cargo: Cargo = Cargo.empty(),
    fuel: Fuel = Fuel.full(),
    loadout: Loadout = Loadout.EMPTY,
): Fleet {
    val ship =
        OwnedShip.starter().copy(
            kinematics = kinematics,
            cargo = cargo,
            fuel = fuel,
            loadout = loadout,
        )
    return Fleet(listOf(ship), OwnedShip.STARTER_SHIP_ID)
}
