---
plan_for: use-cases/46-dynamic-station-pricing.md
work_branch: feat/uc-46-dynamic-station-pricing
team: orbital-frontier-uc-46
approved: 2026-06-19
---

# UC-46 Dynamic Station Pricing — FINAL APPROVED PROPOSAL (analyst↔challenger agreed, round 2)

TARGET_DIR=`/workspace/OrbitalFrontier`. Brief frontmatter valid (schema_version 1, profiles empty — no profile skills). No code blocks (paths + patterns only).

## Analysis
**Problem.** Make station prices dynamic (per-station buy/sell varying by player-driven supply/demand + bounded seeded drift + faction/reputation), with the mutable price state persisted in the save behind the existing `StationMarket` type — a schema bump (v19→v20).

**Relevant code (verified):**
- `economy/StationMarket.kt` — immutable `StationMarket(offers: Map<ResourceType, TradeOffer>)`; `TradeOffer(buyPrice, sellPrice)` invariant `buyPrice>0`, `0<=sellPrice<=buyPrice`. Prices are authored on `world/Station.kt` (`market`), built in `world/MvpSectorMap.kt` (`ALPHA_MARKET`/`BETA_MARKET`, ~L333/352). Per ADR 0007 reconstructed-on-load, never persisted; ADR 0007/0013 reserve dynamic pricing + a `FactionPricing` hook (no type exists yet).
- `economy/Trading.kt` `Trading.resolve(credits, cargo, market: StationMarket?, order)` — pure; `TradeResult(credits, cargo, tradedUnits, kind)` returns the **clamped fill** (0/null on no-op). Callers (lockstep, rule #1): device `screen/PlayScreen.kt:1907 trade()` (+ fuel readout L2130, `screen/TradeScreen.kt`); sim mirror `sim/Simulation.kt:206` docked branch.
- `common/DeterministicRng.kt` (`fnv1a`→`lcgAdvance`→`floatFromState`); precedent `combat/CombatRng.seeded("encounter:$zoneId:$spawnTick")` — string-keyed, **no global seed**, no wall clock. `Simulation` already injects pinned per-playthrough params (`bountyParams`, `reputationParams`).
- Persistence at **v19**: `save/SaveVersion.kt` CURRENT=19L with init-check `==OrbitalFrontier.Schema.version`; migrations `1..18.sqm` (FROM-version names: 18.sqm=v18→v19); baselines `databases/1..19.db`; `verifyMigrations=true` (`core/build.gradle.kts:40`). **Template = UC-41 v19 commit a2e1c67.** Closest persistence analog = **reputation** (`SqlDelightGameStateRepository.kt:319-323` write / `:608-620` load): save-wide map table, deleteAll→insert-non-zero, per-slot wipe (~L446), skip-unknown-slug WARN on load.
- Snapshot/lockstep: `world/WorldState.kt` (prod) ↔ `sim/SimulationState.kt` (test mirror); serialized via `playthrough/Playthrough.kt`→`StateSnapshotDto` (~L491-660). Codec global `encodeDefaults=true`, so new fields MUST be `@EncodeDefault(EncodeDefault.Mode.NEVER)`+EMPTY default to omit on disk (precedent: `reputation`/`stations` DTOs ~L599-611 with `ReputationDto`/`StationsDto`).
- **#1 risk — fixtures.** Only `uc08-trade.json` trades (1 SELL of 6 TITANIUM at **tick 0**, docked alpha-station, credits 500, tickCount 4); `Uc08TradeReplayTest` asserts `credits == 500 + 6*authored sellPrice(50) = 800` + re-run bit-identical. No other fixture asserts trade prices. `PlaythroughFixtureTest` rebuilds every fixture from its builder and asserts both `built==committed` AND `encode(built)==committed JSON` byte-for-byte.

## Proposed Solution
**Pricing model — bounded blend, anchored to base.** Effective price = authored base × supply/demand mult × (optional) seeded drift mult × faction/reputation mult, clamped to `[minMul,maxMul]`, then re-clamped to the `TradeOffer` invariant. **Hard byte-identity rule:** at pressure-0 AND tick-0/epoch-0 AND neutral reputation every multiplier == exactly 1.0 → effective == base (so uc08's tick-0 SELL hits base 50 → 800). Supply/demand (player-driven) is the always-on primary mechanism; drift is a smaller seeded term with a kill-switch; passive decay provides recovery.

New **production** files (`core/src/main/kotlin/com/orbitalfrontier/economy/`):
1. `StationMarketState.kt` — pure save-wide value `Map<PoiId, Map<ResourceType, Int>>` of **net signed pressure** (+N = player sold N → oversupply → prices drop; −N = player bought → scarcity → prices rise). Shape mirrors `WorldState.fieldDepletion`. `EMPTY` companion. Methods:
   - `withTrade(stationId, resource, kind: TradeKind, units: Int)` — fold a **clamped fill** in (units from `trade.tradedUnits`, sign from `trade.kind`); a no-op fill (units 0 / kind null) returns the **same instance**.
   - `decayed(tick: Int, params: PricingParams)` — mean-reversion recovery: **strict no-op (same EMPTY instance) when the map is empty OR `tick % decayPeriodTicks != 0`**; else each non-zero `pressure` steps toward 0 by `step = max(1, abs(pressure)*decayNum/decayDen)` (integer, no floats), `new = pressure - sign(pressure)*min(step, abs(pressure))` (never overshoots), entries reaching 0 dropped, emptied stations dropped → fully-recovered collapses to EMPTY. Finite-time return to base (the `max(1,…)` floor guarantees healing); prevents permanent floor/ceiling (anti-degenerate-loop).
2. `PricingParams.kt` — all `[TUNE]`, pure data: `elasticity`, `pressureScale`, `minMul`, `maxMul` (e.g. 0.5/1.5), `driftAmplitude` (default small; **0 fully disables drift**), `driftPeriodTicks`, `decayNum`, `decayDen`, `decayPeriodTicks`. Defaults reproduce today's economy at pressure 0.
3. `MarketPricing.kt` — pure `effectiveMarket(base: StationMarket, stationId, state: StationMarketState, tick: Int, params, factionId, reputation): StationMarket`. Per offer: supply/demand mult = f(pressure/pressureScale, elasticity) (identity at 0); drift mult = `1 + driftAmplitude*(seededUnit(key,epoch) − seededUnit(key,0))`, `epoch = tick/driftPeriodTicks`, `key = "price:${stationId.value}:${resource.name}"`, `seededUnit` via `DeterministicRng.fnv1a(key+":"+epoch)`→`floatFromState`→[-1,1]; **epoch 0 ⇒ exactly 1.0** (anchor); faction/reputation mult via `FactionPricing.adjust` (identity at neutral). Combine, clamp `[minMul,maxMul]`, round Long, enforce `buyPrice>0` & `0<=sellPrice<=buyPrice`.
4. `FactionPricing.kt` — realizes the deferred ADR-0007/0013 hook: `adjust(...)` → reputation-graded multiplier, **exactly 1.0 at neutral (0) standing** (keeps uc08 byte-identical; alpha-station `league` starts neutral). Reputation modifier INCLUDED (AC#2 optional, fixture-safe because identity at neutral).

**Determinism (AC#4):** drift keyed purely on `(stationId, resource, epoch)` via `DeterministicRng` — **NO seed mix** (verified: no production run/world seed exists; `playthrough.seed` is test-only; combat precedent is seed-independent string keys; mixing would break device/replay parity). Whole sim stays seeded; supply/demand deterministic from the action log. Per-seed price variance deferred (would need a persisted world seed — logged as a future enhancement in ADR 0034).

**Persistence (AC#3) — schema v19→v20, exact UC-41 template:**
- `save/OrbitalFrontier.sq`: new table `station_market(slot_id INTEGER NOT NULL, station_id TEXT NOT NULL, resource TEXT NOT NULL, pressure INTEGER NOT NULL, PRIMARY KEY (slot_id, station_id, resource))` + queries `selectStationMarketForSlot`, `deleteAllStationMarketForSlot`, `insertStationMarketEntry`, and a `deleteStationMarketForSlot` line in the per-slot wipe block; bump `.sq` header to v20. **Persist pressure only — NOT drift/decay** (recomputed from tick, so a re-tune reaches old saves free, per ADR-0007 philosophy).
- New `save/migrations/19.sqm` (FROM-version 19→20): `CREATE TABLE station_market(...)` then `UPDATE meta SET save_version = 20 WHERE id = 0;`. **minSdk-24-safe** — plain CREATE TABLE, full delete-then-insert snapshot, **no UPSERT/ON CONFLICT**, parameterized only.
- New baseline `save/databases/20.db` — regenerate via the SQLDelight schema task writing `schemaOutputDirectory` (discover exact name via `./gradlew :core:tasks`, e.g. `generateMainOrbitalFrontierSchema`); adding 19.sqm bumps generated `Schema.version` to 20, so `verifyMigrations=true` passes only with this baseline present.
- `save/SaveVersion.kt`: CURRENT 19L→20L.
- `save/SqlDelightGameStateRepository.kt`: in the save transaction mirror the reputation block — `deleteAllStationMarketForSlot(slotId)` then insert each **non-zero** pressure row; add `deleteStationMarketForSlot` to the per-slot wipe (~L446); new `loadStationMarketState(slotId)` (skip unknown station/resource slugs with WARN, drop zero pressure) wired into the `WorldState` load (~L161, beside `reputation = loadReputation(slotId)`).

**Lockstep snapshot (rule #1):**
- `world/WorldState.kt`: add `val marketState: StationMarketState = StationMarketState.EMPTY`.
- `sim/SimulationState.kt`: add mirror `val marketState: StationMarketState = StationMarketState.EMPTY` (same-instance-on-no-op discipline).
- `playthrough/Playthrough.kt`: add `@EncodeDefault(NEVER) val marketState: StationMarketStateDto = StationMarketStateDto.EMPTY` to `StateSnapshotDto` (wired into `from(state)` + `toSimulationState()`); new sibling `StationMarketStateDto` (map `String→Map<String,Int>` = stationSlug→resourceName→pressure, EMPTY + from()/toMarketState()). Also add `pricingConfig: PricingParamsDto` with `@EncodeDefault(NEVER)` default. Empty/default ⇒ omitted on disk ⇒ every committed fixture byte-identical, no regen.

**Wiring (lockstep, both callers):**
- `sim/Simulation.kt`: inject `pricingParams: PricingParams = PricingParams()`; at tick start `val decayedMarket = state.marketState.decayed(state.tick, pricingParams)`. Docked branch (L206): `val effective = MarketPricing.effectiveMarket(station?.market ?: StationMarket.EMPTY, dockedStation, decayedMarket, state.tick, pricingParams, station?.factionId, state.reputation)`, `Trading.resolve(state.credits, refuel.cargo, effective, tradeOrder)`, `marketAfter = decayedMarket.withTrade(dockedStation, resource, trade.kind, trade.tradedUnits)`, add `marketState = marketAfter` to the docked-return `state.copy(...)`. In-flight path threads `marketState = decayedMarket`.
- `screen/PlayScreen.kt`: hold mutable `marketState` (seeded from loaded `WorldState`); per tick apply `decayed`; in `trade()` compute the effective market **once** and use that single value for BOTH the displayed price (TradeScreen + fuel readout L2130) and `Trading.resolve`; fold the clamped-fill pressure update in; include `marketState` in the autosave snapshot.
- `playthrough/ReplayRunner.kt`: pass `playthrough.pricingConfig.toPricingParams()` into the `Simulation(...)` ctor.

**ADR 0034** (developer deliverable; highest existing is 0033): records the pricing model (blend), v20 pressure-only persistence (drift+decay recomputed from tick), the swing caps, determinism + tick-0/epoch-0 byte-identical anchor, the decay/recovery model, and **explicitly supersedes the dynamic-pricing *deferral* in ADR 0007 and the FactionPricing-deferral framing in ADR 0013**; logs per-seed variance as a future persisted-world-seed enhancement. Update `docs/adr/README.md`; flip `docs/design/economy-and-resources.md` and the `StationMarket.kt` doc comment ("dynamic pricing is deferred" → implemented in UC-46).

**AC#5 — tests/fixture:**
- NEW `resources/playthroughs/uc46-dynamic-pricing.json` (docked at a market station; multiple SELLs and/or BUYs of one resource across ticks so pressure accumulates and the price moves) reproduced by a `PlaythroughFixtures.uc46…` builder; `Uc46DynamicPricingReplayTest` asserts the 2nd trade's unit price ≠ the 1st (price moved), stays within `[minMul,maxMul]`, and re-run is bit-identical.
- NEW `save/Uc46…SaveReloadReplayTest.kt` — marketState survives save/reload.
- NEW pure unit tests: `MarketPricingTest` (incl. a dedicated **`drift == exactly 1.0 at tick 0/epoch 0 for several arbitrary keys`** guard), `FactionPricingTest`, `StationMarketStateTest` (withTrade clamped-fill + decay: no-op-at-empty, finite recovery, no overshoot).
- `save/SaveMigrationTest.kt`: add a v19→v20 case (new table exists, prior data survives, `save_version`=20). New table is independent of the settings-column chain → existing migration-chain tests unperturbed.
- **Explicit suite-wide guard:** the full `PlaythroughFixtureTest` suite passes with **zero fixture regeneration** (covers all docking fixtures uc05/07/09/11/12/15…, not just uc08); also re-run the determinism guard.
- Gate: `./gradlew :core:ktlintCheck :core:test` green.

## Files Affected
**Production code (developer) — `core/src/main/**` + docs:**
- NEW `economy/StationMarketState.kt`, `economy/PricingParams.kt`, `economy/MarketPricing.kt`, `economy/FactionPricing.kt`
- EDIT `world/WorldState.kt` (+marketState), `economy/StationMarket.kt` (doc), `screen/PlayScreen.kt` (decay + single effective-market for display+resolve + clamped-fill pressure + autosave + load seed), `screen/TradeScreen.kt` (effective prices)
- EDIT `save/OrbitalFrontier.sq` (+table+queries, v20 header), NEW `save/migrations/19.sqm`, NEW `save/databases/20.db` (generated), EDIT `save/SaveVersion.kt` (20L), `save/SqlDelightGameStateRepository.kt` (write+wipe+loadStationMarketState)
- NEW `docs/adr/0034-dynamic-station-pricing.md`, EDIT `docs/adr/README.md`, `docs/design/economy-and-resources.md`

**Test code — `core/src/test/**`:**
- **Authored by the DEVELOPER (lockstep mirror logic, rule #1 — these four ARE in the developer's write scope):** `sim/SimulationState.kt` (+marketState mirror), `sim/Simulation.kt` (pricingParams + decay + effective-market wiring), `playthrough/Playthrough.kt` (StateSnapshotDto field + StationMarketStateDto + PricingParamsDto), `playthrough/ReplayRunner.kt` (pass pricingConfig).
- **Authored by QA (assertions/fixtures):** `playthrough/PlaythroughFixtures.kt` (uc46 builder), NEW `playthrough/Uc46DynamicPricingReplayTest.kt`, NEW `save/Uc46…SaveReloadReplayTest.kt`, NEW `economy/MarketPricingTest.kt` + `economy/FactionPricingTest.kt` + `economy/StationMarketStateTest.kt`, NEW `resources/playthroughs/uc46-dynamic-pricing.json`, EDIT `save/SaveMigrationTest.kt` (+v19→v20 case).

## Risks & Considerations
1. **#1 — fixture byte-identity.** Guaranteed by (a) `@EncodeDefault(NEVER)`+EMPTY DTO fields, (b) all multipliers == 1.0 at pressure-0/tick-0/epoch-0/neutral (uc08 → 800), (c) decay strict no-op at empty (same instance), (d) MvpSectorMap authored base prices unchanged. QA must run the full `PlaythroughFixtureTest` + determinism guard with zero regen.
2. **Anti-degenerate loop.** Solved by `[minMul,maxMul]` caps + the committed decay/recovery model.
3. **Baseline DB regen.** Must produce `databases/20.db` via the SQLDelight schema task or the `verifyMigrations` build fails; developer confirms the exact task name.
4. **Backward-compat.** Old saves (no station_market rows) load EMPTY → base prices → identical to today; migration is purely additive CREATE TABLE.
5. **UI.** TradeScreen + fuel readout now show effective (living) prices — intended by AC#1; no test asserts a fixed displayed price.
6. **minSdk-24.** No UPSERT/`ON CONFLICT`, no `java.time` in production paths, parameterized queries only — all honored.

All 5 acceptance criteria mapped; no criterion dropped. Challenger-approved.
