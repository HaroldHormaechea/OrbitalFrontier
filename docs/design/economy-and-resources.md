# Design Note — Economy & Resources

- **Status:** in-progress (model decided; resource values, fuel & shipyard detail open)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → core_gameplay_loop (Earn → Improve); non_goals (no large-scale economy sim in MVP); [missions.md](missions.md) (rewards, courier cargo), [world-and-sector.md](world-and-sector.md) (asteroids, stations, prices), [upgrades-and-progression.md](upgrades-and-progression.md) (sinks: upgrades/ships/cargo), [save-and-persistence.md](save-and-persistence.md) (wallet/cargo/prices)

## Summary

A single **credits** currency. The player earns credits from **mission rewards** and by
**selling resources**, and spends them on **ship upgrades, repairs, refueling, crew,
cargo, and buying additional ships to pilot**. **Inter-station trading is in scope** for
the MVP (buy low / sell high), with **fixed prices in the MVP** and **dynamic pricing
later**. This is the bridge from "Earn" to "Improve".

## Goals

- A legible, single-currency economy that makes upgrades feel earned.
- Mining and trading are both viable income paths (alongside mission rewards).
- No full market sim for MVP — fixed prices now, dynamic later.

## Mechanics / ideas

**Currency:** a single **credits** currency. No secondary/special currencies for MVP.

**Resources (mined from asteroid fields).** Target **5–10** resources spanning basic
materials to tech inputs. Provisional MVP list (values/uses are placeholders for
balancing):

| Resource | Rough role |
|---|---|
| **Hydrogen** | **ship fuel/propellant** (minable or buyable) |
| Water Ice | life-support / refinable feedstock (→ hydrogen) |
| Iron Ore | basic structure / hull |
| Copper | wiring / electronics |
| Silicon | electronics / computing |
| Aluminum | lightweight structure |
| Nickel | alloys |
| Titanium | advanced hull / armor |
| Rare Earth Elements | advanced tech / sensors |
| Helium-3 | advanced reactor / high-tech |
| Platinum | high-value catalyst / electronics |

Resources act as **trade goods** (sold for credits) and may also be **inputs to tech/
upgrades** _(TODO: do upgrades cost resources + credits, or credits only?)_.

**Earning:** **mission rewards** + **selling resources** (and, later, combat loot/salvage).

**Trading (in MVP):** stations **buy and sell** resources/goods. Buying low at one
station and selling high at another is a valid income path. **Prices are fixed for the
MVP** but **dynamic later** (driven by sector/faction state — see
[world-and-sector.md](world-and-sector.md), factions). Station offers/prices/stock are
**persisted**.

**Implemented (UC08, [ADR 0007](../adr/0007-trading-prices.md)).** Each station carries a
`StationMarket` — a map of `ResourceType` → `TradeOffer(buyPrice, sellPrice)` (both `Long`
credits/unit) — authored per station in
[`MvpSectorMap`](../../core/src/main/kotlin/com/orbitalfrontier/world/MvpSectorMap.kt). Invariant:
`buyPrice > 0` and `0 <= sellPrice <= buyPrice`, so no single station pays more to buy back than it
charges to sell (no money loop); cross-station arbitrage is achieved by authoring **distinct** tables
(e.g. Iron Ore sells higher at Beta than it buys at Alpha; Titanium sells higher at Alpha than it
buys at Beta — AC#4). Buying/selling is resolved by a **pure** `Trading.resolve(credits, cargo,
market, order)` (the mining/refuelling analogue): Buy clamps to `min(units, credits / buyPrice, cargo
free space)`, Sell to `min(units, held)`, with every recoverable case (not docked / not offered /
unaffordable / hold full / nothing to sell) a no-op. Trade resolves only while **docked** (the market
comes from the docked station). **Buying Hydrogen feeds fuel (AC#5)** with no special case — it lands
in cargo and the existing station REFUEL converts it (UC07).

**Fixed prices are authored, not row-persisted** ([ADR 0007](../adr/0007-trading-prices.md)). Because
MVP prices are fixed authored constants, they are reconstructed from `MvpSectorMap` on load (like
cargo/fuel *capacity*), not stored per save — a save always reloads the same prices, so AC#4's
"prices persist" holds by reconstruction. Only the player's **credits** wallet is persisted
(`game_state.credits`, schema v6). Dynamic pricing (UC14) is where mutable per-station price state
will move into the save, behind the same `StationMarket` type.

**Spending sinks:**
- **Ship upgrades** (see [upgrades-and-progression.md](upgrades-and-progression.md))
- **Repairs** (sectional damage from combat)
- **Refueling**
- **Crew** (wages/hiring)
- **Cargo** (capacity)
- **Buying new ships to pilot** — the player can **own multiple ships and switch between
  them while docked** _(NEW feature)_.

**Fuel — hydrogen (soft constraint).** Fuel is the **Hydrogen** resource: it can be
**mined or bought**, so a player can self-supply or refuel at stations. Ships consume it;
**low fuel reduces max speed**, but there are **no "stranded/lost in space" fail states** —
the player can always limp to a station/asteroid to refuel.

**Fuel consumption model.** Burn rate = the sum of:
1. **Base ship requirement** — a constant idle draw determined by the **ship type**.
2. **Installed-module energy usage** — each installed upgrade/module has an energy cost;
   more/heavier modules → higher passive draw. Energy demand is modeled by the dedicated
   **[power-and-energy.md](power-and-energy.md)** system (a confirmed feature).
3. **Active engine / RCS usage** — extra consumption **when triggered** (thrusting,
   braking, or maneuvering via RCS). Coasting on momentum costs little/nothing; burning
   thrust costs fuel.

So a heavily-fitted ship sitting still still sips fuel (1 + 2), and hard maneuvering spikes
it (3). Fuel is stored per ship; capacity is set by **fuel-tank** upgrades (see
[upgrades-and-progression.md](upgrades-and-progression.md)).

**Cargo & upgrade slots.** Ships have **limited cargo capacity** (a stat, upgradeable),
and a **limited set of upgrades by type/slot category** (e.g. weapons, communications,
hull plating, engines, sensors). Upgrades compete for category slots rather than being
unlimited.

## Player-facing behavior

- **Wallet** (credits) and a **cargo/inventory** screen (per ship, capacity-limited).
- **Station trade UI** — buy/sell resources & goods; **refuel** and **repair** at stations.
- **Shipyard** — buy additional ships; **switch active ship while docked**.
- **Junkyard** — sell/remove **used upgrades** and refit (used parts can't be sold back to
  normal dealers; see [upgrades-and-progression.md](upgrades-and-progression.md)).
- **Mining** interaction out in asteroid fields.

## Data & state

Persisted (see [save-and-persistence.md](save-and-persistence.md)):
- **Credits** — implemented as `game_state.credits` (schema v6, UC08).
- **Cargo/inventory per ship**; **owned ships + their loadouts**; **active ship**.
- **Fuel level per ship.**
- **Station prices/offers** — MVP-fixed, so **reconstructed from `MvpSectorMap` on load, not stored
  per save** ([ADR 0007](../adr/0007-trading-prices.md)); a save reloads the same fixed prices, so
  they "persist" by reconstruction. (Mutable per-station price/stock state lands in the save with
  **dynamic pricing**, UC14.) Asteroid-field **depletion** is persisted and owned by
  [world-and-sector.md](world-and-sector.md).

## Dependencies & interactions

- **Fed by** missions (rewards), mining (world asteroids), and combat loot (later).
- **Drains into** upgrades & progression (upgrades, **ships**, cargo), crew
  (wages/hiring), repairs (sectional damage), and fuel.
- **Trading** couples to stations (world) and, later, **factions** (dynamic pricing).
- **Ship-switching** couples to ship-and-controls and upgrades (each ship has its own
  loadout/cargo/fuel).
- **Fuel** couples to ship-and-controls (modulates `max_speed`).

## Open questions

- Resource **values/yields** and overall economic **balancing**.
- **Upgrade cost model:** credits only, or credits + resources?
- ~~Fuel model~~ — **RESOLVED: fuel = Hydrogen (mined or bought); burn = base ship draw +
  installed-module energy + active engine/RCS use.** Energy demand is owned by the
  dedicated **[power-and-energy.md](power-and-energy.md)** system. Remaining: actual rate
  values (balancing).
- **Cargo:** base capacity and upgrade steps.
- **Upgrade slot categories:** the full list and per-category slot counts (with
  [upgrades-and-progression.md](upgrades-and-progression.md)).
- **Shipyard:** where ships are sold, how many can be owned, where idle ships are stored.
- **Dynamic pricing** model (post-MVP).

## Decided

- **Single credits currency.**
- **5–10 asteroid resources** (provisional list above).
- Earn via **mission rewards + selling resources**.
- **Inter-station trading in MVP**; **fixed prices for MVP, dynamic later**.
- Sinks: **upgrades, repairs, refueling, crew, cargo, buying ships**.
- **Own & switch multiple ships** (while docked).
- **Fuel = Hydrogen** (minable or buyable); **soft constraint** (low fuel → lower max
  speed; **never stranded**). Burn = **base ship draw + installed-module energy + active
  engine/RCS use**.
- **Limited cargo**; **upgrades limited by type/slot category**.

## References

Starsector economy & trade (inspiration, simplified); X4 station trading; Galaxy Genome
mining. See PROJECT_BRIEF.md → Reference Points & Inspiration.
