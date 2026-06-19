# ADR 0034 — Dynamic station pricing

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC46 turns trading (UC08) from fixed arbitrage into a living economy. Until now station prices were
**authored, fixed, and never persisted**: `StationMarket(offers: Map<ResourceType, TradeOffer>)` was
carried with the world map (`MvpSectorMap`) and reconstructed on load ([ADR 0007](0007-trading-prices.md)),
and a `FactionPricing` hook — reputation/faction-driven price modulation — was reserved and explicitly
**deferred** by [ADR 0007](0007-trading-prices.md) and [ADR 0013](0013-factions-and-reputation.md). The
economy design note (`docs/design/economy-and-resources.md`) records "dynamic pricing later" as the plan.

The forces:

- **AC#1** — prices must vary over time and/or in response to player buy/sell (supply & demand).
- **AC#2** — modulation must account for faction/sector and (optionally) reputation (the `FactionPricing` seam).
- **AC#3** — the *mutable* per-station price state must persist across save/reload.
- **AC#4** — price changes must be **deterministic** given the seed and player actions, so trading
  playthroughs replay identically.
- **AC#5** — `:core:ktlintCheck :core:test` green; a playthrough that buys/sells and asserts price movement.
- **The #1 constraint (byte-identity).** The 11 committed playthrough fixtures — notably uc08's tick-0
  Titanium sell that asserts exactly 800 credits — and the v19 persistence baseline must stay byte-for-byte
  unchanged, with **zero fixture regeneration**.
- **minSdk-24** persistence rules (no UPSERT/`ON CONFLICT`, no `java.time`, parameterized queries only).

## Options considered

| Option | For | Against |
|---|---|---|
| **Bounded blend (player-driven supply/demand + small seeded drift + faction), persist pressure only** | Satisfies AC#1 *and* AC#2; deterministic from the action log (AC#4); pressure-only persistence keeps drift/decay re-tunable against old saves (ADR-0007 philosophy); identity at the anchor keeps every fixture byte-identical | Two interacting mechanisms to tune; one more table + migration |
| Pure simulated drift (no player effect) | Simplest "prices wander" | Ignores AC#1's "in response to player buying/selling"; a pure time/tick wander risks perturbing fixtures that trade at tick > 0 |
| Persist the full effective price table per station | Trivial load (read the numbers back) | Pins a stale table (re-tune can't reach old saves — the exact wart ADR 0007 avoided); larger save; redundant with authored base |
| No swing caps / no recovery | Less code | Degenerate infinite-profit loop (sell→buy→sell at a drifting price); no mean reversion |

## Decision

Introduce a **bounded blend**, anchored to the authored base price. The effective price per offer is

```
base × supplyDemandMul × driftMul × factionMul   → clamp the COMBINED multiplier to [minMul, maxMul]
                                                  → round to Long, re-clamp to the TradeOffer invariant
```

- **Supply/demand** (player-driven, always-on primary mechanism): `1 - elasticity·(pressure/pressureScale)`
  from a new save-wide `StationMarketState` — net **signed** pressure per (station, resource): a SELL adds
  `+units` (oversupply → price down), a BUY adds `-units` (scarcity → price up). Identity at pressure 0.
- **Drift** (smaller, seeded, kill-switchable via `driftAmplitude = 0`):
  `1 + driftAmplitude·(u(key, epoch) − u(key, 0))`, where `u` is a deterministic `[-1, 1)` value from the
  shared `DeterministicRng` keyed on `"price:<stationId>:<resource>"` + `epoch = tick / driftPeriodTicks`.
  **Exactly 1.0 at epoch 0** (the term subtracts the epoch-0 value from itself).
- **Faction/reputation** (`FactionPricing.adjust`, realizing the deferred ADR-0007/0013 hook): **exactly
  1.0 at neutral (0) standing** or an unaligned station; an allied discount / hostile markup otherwise.
- **Determinism (AC#4):** drift is keyed purely on `(stationId, resource, epoch)` — **no seed mix**: no
  production world-seed exists, `playthrough.seed` is test-only, and the combat precedent (`CombatRng`) is
  seed-independent string keys, so mixing one would break device/replay parity. Per-seed price variance is
  deferred (it would need a persisted world seed) and logged here as a future enhancement.
- **Anti-degenerate loop:** the combined multiplier is clamped to `[minMul, maxMul]`, and
  `StationMarketState.decayed` provides mean-reversion recovery — every `decayPeriodTicks` each non-zero
  pressure steps toward 0 by `max(1, abs·decayNum/decayDen)` (the `max(1, …)` floor guarantees finite-time
  return to base), so prices can't be pinned at a floor/ceiling forever.

**Persistence (AC#3) — schema v19 → v20.** A new per-slot `station_market(slot_id, station_id, resource,
pressure)` table stores **pressure only** — drift + decay are recomputed from the tick (the ADR-0007
reconstructed-on-load philosophy, so a re-tune reaches old saves for free). The `19.sqm` migration is a
purely additive `CREATE TABLE` (minSdk-24-safe; no UPSERT; full delete-then-insert snapshot per slot,
mirroring the `reputation` table), and a regenerated `databases/20.db` baseline keeps `verifyMigrations`
green. Only non-zero pressure rows are written; an absent (station, resource) is at base.

**Byte-identity (the #1 constraint).** At pressure 0 **and** tick 0 / epoch 0 **and** neutral reputation
every multiplier is *exactly* 1.0, so `round(base × 1.0) == base` — uc08's tick-0 sell still yields 800.
The new DTO fields (`StateSnapshotDto.marketState`, `Playthrough.pricingConfig`) and `WorldState` /
`SimulationState.marketState` all default to `EMPTY` / the domain default with `@EncodeDefault(NEVER)`, so a
never-traded run omits them on disk; `decayed`/`withTrade` are same-instance no-ops on empty/off-period.
Result: **zero fixture regeneration**, verified by the full `PlaythroughFixtureTest` + the determinism guard.

This ADR **supersedes the dynamic-pricing *deferral* in [ADR 0007](0007-trading-prices.md) and the
`FactionPricing`-deferral framing in [ADR 0013](0013-factions-and-reputation.md)**: dynamic pricing and the
faction-pricing seam are now implemented. The authored base prices and ADR 0007's "reconstruct-on-load"
treatment of them are unchanged — only the *mutable* pressure now lives in the save.

## Consequences

- **Easier:** a re-tune of any pricing constant (elasticity/drift/decay/faction) instantly affects old
  saves (pressure-only persistence), like every other `*Params`. The trade desk, fuel readout, and
  `Trading.resolve` all charge the same single effective market (computed once in `trade()`), so there is
  never a display/charge mismatch. Pricing is pure and fully JVM-unit-tested.
- **Harder / new constraints:** two interacting mechanisms (supply/demand + drift) to balance; a future
  resource added to a station's market participates automatically (the offer set never grows from pricing,
  only from authoring). The `station_market` table is the first per-(slot, slug-keyed) pricing table.
- **Reversibility:** setting `driftAmplitude = 0` disables drift alone; setting `elasticity = 0` and
  `factionInfluence = 0` collapses the whole system back to fixed authored prices without removing the
  table. Migrating *away* (dropping the table) would be a normal additive-reverse migration.
- **Deferred:** per-seed price variance (needs a persisted world seed); cross-station price propagation /
  a true market sim (out of MVP scope per the brief's non-goals).
