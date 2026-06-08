# ADR 0007 — Trading model & fixed authored prices (reconstructed, not row-persisted)

- **Status:** Accepted
- **Date:** 2026-06-08
- **Refines:** [ADR 0002](0002-persistence-sqlite-migrations.md) / [ADR 0003](0003-persistence-access-layer-sqldelight.md) (persistence + migrations) and [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable). Realizes use-case 08 (credits & inter-station trading).

## Context

`PROJECT_BRIEF.md` → core_gameplay_loop ("Earn → Improve") and
[economy-and-resources.md](../design/economy-and-resources.md) call for a single **credits**
currency and **inter-station trading** with **MVP-fixed, data-driven prices** (dynamic pricing
deferred to UC14). UC08 must let a docked player buy and sell resources at per-station prices, so
buy-low/sell-high across stations is a viable income path (AC#4), with credits and the price tables
persisting (AC#1/#4).

Two forces shape the design:

- **Determinism + JVM-testability (ADR 0001).** Trading must be a pure function of (credits, cargo,
  market, order) with no engine types, so it slots into the same simulation/replay discipline as
  mining (UC06) and refuelling (UC07) and is unit-testable on the JVM (AC#6).
- **What actually needs to persist.** AC#4 says "prices/stock are fixed for MVP and persist." But a
  *fixed* price table is **authored map data**, identical for every save, already living in
  [MvpSectorMap](../../core/src/main/kotlin/com/orbitalfrontier/world/MvpSectorMap.kt). Writing it
  into every save row would duplicate authored constants into save state, add a `station_price`
  table and migration, and risk a save pinning a *stale* price after a balance change — the same
  reconstruct-on-load reasoning already applied to cargo **capacity** (UC06) and fuel-tank
  **capacity** (UC07).

## Options considered

| Option | For | Against |
|---|---|---|
| **Fixed prices authored in `MvpSectorMap`, reconstructed on load; persist only credits** | No price duplication in saves; a balance re-tune takes effect for every existing save immediately; no extra table/migration; mirrors the capacity-is-reconstructed precedent (UC06/UC07); trading stays a pure function of the injected world. | "Prices persist" (AC#4) is satisfied *logically* (a save always reloads the same fixed prices) rather than by storing rows — must be documented so it isn't mistaken for a missing feature. |
| Persist a `station_price` table per save | Literal reading of "prices persist"; a drop-in seam for dynamic pricing later. | Duplicates authored constants into every save; a re-tune cannot reach existing saves without a data migration; more schema/migration surface for zero MVP behaviour difference; a save can pin stale prices. |
| Hard-code prices inline in the trade screen | Least code now. | Couples UI to balancing data; not reusable by the pure resolver or tests; violates "data-driven prices" (AC#2). |

## Decision

Model trading as a **pure resolver** plus **fixed authored prices that ride with the injected
world**, and **persist only the wallet**:

- **Credits** ([WorldState.credits], `Long`) is the single save-wide currency. It is the **only**
  trading state persisted — `game_state.credits` (schema **v6**; the v5→v6 migration backfills `0`,
  so a migrated save reads back broke and a *new game* seeds `STARTING_CREDITS` in code). The
  repository coerces a corrupt/negative value to `>= 0` on load (never stranded).
- **Prices** are authored as a [StationMarket] of [TradeOffer]s
  (`buyPrice > 0`, `0 <= sellPrice <= buyPrice`) on each [Station] in `MvpSectorMap`. They are **not**
  persisted: a station's market is reconstructed from the injected `SectorWorld` on load, exactly as
  cargo/fuel **capacity** are reconstructed. The `sellPrice <= buyPrice` invariant forbids a
  single-station money loop; cross-station arbitrage (a *different* station paying a higher sell
  price than this one's buy price) is the intended path and is authored distinctly per station.
- **`Trading.resolve(credits, cargo, market, order)`** is pure (`economy` package, no engine types):
  Buy clamps to `min(units, credits / buyPrice, cargo free space)`; Sell clamps to `min(units, held)`;
  every recoverable case (not docked / not offered / unaffordable / hold full / nothing to sell) is a
  no-op returning inputs unchanged — the mining/refuelling explicit-result idiom, all `Long` money
  math. Trading resolves only against a **docked** station's market (the device path supplies it from
  `dockedStation`), so it is implicitly gated on being docked.
- **Buy-hydrogen → fuel (AC#5)** is **compositional**, not a special case: bought Hydrogen lands in
  the cargo hold and is converted to fuel by the existing hub REFUEL (UC07 `Refueling.resolve`).

### Reconciliation with AC#4 ("prices persist")

A save **always reloads the same fixed prices** because they are deterministic authored data keyed by
station id — so the player-observable contract ("the price was X here, and it still is after a
reload") holds. We satisfy it by **reconstruction, not row storage**. When **dynamic pricing**
arrives (UC14), per-station mutable price state will be persisted behind the *same* `StationMarket`
type — a strictly additive change (a new table + migration) that does not touch the pure resolver or
its callers.

## Consequences

- Saves stay compact and a price re-tune reaches every existing save for free; one fewer table +
  migration than row-persisted prices.
- The pure resolver is JVM-testable and reused by the device path and tests verbatim, keeping live
  and (future) replay behaviour identical (AC#6).
- The "fixed prices are authored, not persisted" decision must stay documented (here + in the `.sq`
  header + the design note), or a future contributor may mistake the absent `station_price` table for
  a gap. Dynamic pricing (UC14) is the sanctioned place to add persisted price state.
- `game_state.credits` is the v6 schema delta; `STARTING_CREDITS` and the authored price tables are
  balancing tunables (`[TUNE]`), expected to change without schema impact.
