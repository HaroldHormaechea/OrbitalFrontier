---
plan_for: use-cases/47-junkyard-buy-used-parts.md
work_branch: feat/uc-47-junkyard-buy-used-parts
team: orbital-frontier-uc-47
approved: 2026-06-19
---

# UC-47 — Buy used parts at junkyards — FINAL APPROVED PROPOSAL (challenger-approved round 2)

## Analysis — problem + relevant context found in the codebase

UC-47 adds the **buy-used** side of junkyards. Today (UC-09) a junkyard (`StationKind.JUNKYARD`) only lets you *remove + sell* used parts (`OutfitOrder.RemoveSell`, refunds `USED_PART_REFUND_FRACTION`=0.5 of catalog price) and *install at full price* from its `outfitMarket`. There is no cheaper used-buy path. MVP decision (use case): used parts are **purely cheaper, NO condition/wear**.

Systems traced:
- **Outfitting flow** (`core/src/main/kotlin/com/orbitalfrontier/outfit/`): `Outfitting.resolve(credits, loadout, slotCounts, outfitMarket, isJunkyard, order, catalog)` is the pure resolver. `OutfitOrder` sealed (`None`/`BuyInstall`/`RemoveSell`); `OutfitResult(credits, loadout, changed)`. `BuyInstall` gates on catalogued + `outfitMarket.offers(id)` + affordable + free slot via `Loadout.install`. `UpgradeCatalog.MVP` is authored constant (never persisted); `Loadout` stores only `UpgradeId` per (category, slotIndex). Both device (`PlayScreen.outfit` ~L2064) and headless sim (`sim/Simulation.kt` ~L252) call the resolver.
- **Pricing infra (UC-46)**: `economy/MarketPricing` (pure) + `PricingParams` (pure tunables, pinned per-playthrough) + `StationMarketState` (pure persisted **delta**, EMPTY default, canonical drop-zeros, same-instance no-ops). `DeterministicRng` (fnv1a→lcgAdvance→boundedInt/floatFromState) is the only RNG; keys on stable slugs, no world seed.
- **Persistence (v20)**: SQLDelight `OrbitalFrontier.sq` + `migrations/*.sqm` (FROM-version naming; `19.sqm`=v19→v20 added `station_market`). Baselines `databases/1.db..20.db`. `SaveVersion.CURRENT=20L`, init-checked against `OrbitalFrontier.Schema.version`. The `station_market` table (per-slot, only non-zero rows, full delete-then-insert) is the exact model. Repo write ~L336, load `loadStationMarketState` ~L655, wipe ~L471, load wiring ~L169.
- **Snapshot/fixtures**: `WorldState.marketState` + `SimulationState.marketState` (default EMPTY); `StateSnapshotDto.marketState` (`@EncodeDefault(NEVER)`, class-level `@OptIn(ExperimentalSerializationApi)`) + `StationMarketStateDto` mirror in `playthrough/Playthrough.kt` (~L600/L745). 18 committed fixtures. **No fixture buys used parts** — UC09 outfits at alpha-station (DEALER); UC43/UC45 only set `lastDockedStation=gamma-junkyard` (combat, no outfit order). Byte-identity holds iff the new state field defaults EMPTY+omitted and the new order is a no-op everywhere else.
- **Junkyard**: `world/Station.kt` (`kind`, `outfitMarket`, authored, not persisted — ADR 0008). One MVP junkyard `gamma-junkyard` in `MvpSectorMap.kt` ~L300, `GAMMA_JUNKYARD_OUTFIT`={ENGINE_TUNE_I, CARGO_POD_I}.
- ADRs: highest **0034**; `docs/design/upgrades-and-progression.md` L70/L98 flags buy-used + pricing curve as deferred.

## Proposed Solution

**Core decision — AC#3 stock model: deterministic baseline + persisted depletion.** Baseline stock per (junkyard, part) is a **pure function of `DeterministicRng`** keyed on `"usedstock:<junkyardId>:<partId>"`; only the **player-caused depletion** (purchased count) is persisted. `available = max(0, baseline − purchased)`. Mirrors UC-46's persist-the-delta philosophy and kills the reload-restock exploit (`purchased` is durable). **Time-based restock deferred** (use-case "restock cadence" open-question) — baseline keyed on (junkyard, part) only, no epoch. AC#3's "persists/regenerates deterministically" is read as *depletion persists / baseline recomputed (not stored) on load* — documented in ADR 0035.

New **production** files (`core/.../outfit/`):
1. **`UsedPartParams.kt`** (pure data class) — data-driven curve + stock tunables (AC#1/#4): `discountFraction` (e.g. 0.6 [TUNE], `0 < f < 1`), `minStock`/`maxStock` (e.g. 1..3 [TUNE]), `require` invariants, default instance. Mirrors `PricingParams`.
2. **`UsedPartPricing.kt`** (pure object) — `usedPrice(newPrice: Long, params): Long = round(newPrice * discountFraction)`, clamped `>=1`; `baselineStock(junkyardId, partId, params): Int = boundedInt(lcgAdvance(fnv1a("usedstock:${junkyardId.value}:${partId.value}")), maxStock-minStock+1) + minStock`. Pure/deterministic. Mirrors `MarketPricing`.
3. **`JunkyardStock.kt`** (pure data class) — depletion `purchasedByStation: Map<PoiId, Map<UpgradeId, Int>>`, EMPTY default, canonical (drop zeros, same-instance no-op on 0-unit), `purchasedCount(...)`, `withPurchase(...)`. Mirrors `StationMarketState`.

**Resolver extension** (`outfit/Outfitting.kt`):
- New `OutfitOrder.BuyUsed(upgradeId)`.
- `OutfitResult` gains `junkyardStock: JunkyardStock` with **no convenience default** — passed explicitly on every path (compiler-enforced; verified `OutfitResult` is constructed only at the 3 production sites in `Outfitting.kt`, no test builds it directly).
- `Outfitting.resolve` gains defaulted params `usedPartMarket: OutfitMarket = OutfitMarket.EMPTY`, `junkyardStock: JunkyardStock = JunkyardStock.EMPTY`, `stationId: PoiId? = null`, `usedPartParams: UsedPartParams = UsedPartParams()`. Build `unchanged = OutfitResult(credits, loadout, false, junkyardStock)` from the **passed-in** stock; `resolveBuyInstall`/`resolveRemoveSell` success carry the input stock through unchanged; **only `resolveBuyUsed` mutates** (`junkyardStock.withPurchase(stationId, partId, 1)`). `resolveBuyUsed` gates: `isJunkyard` + `usedPartMarket.offers(id)` + `stationId != null` + catalogued + `available = baselineStock − purchased > 0` + affordable at `usedPrice` + free slot.
- **Used-part availability is authored per-junkyard**: new field `Station.usedPartMarket: OutfitMarket = OutfitMarket.EMPTY` (authored, not persisted — ADR 0008 style; **endorsed** over reusing `outfitMarket`). MVP authors `gamma-junkyard` a `GAMMA_USED_PARTS` subset (e.g. {ENGINE_TUNE_I, CARGO_POD_I, SCANNER_I, FUEL_TANK_I}).

**Persistence — SCHEMA BUMP v20→v21:**
- NEW `migrations/20.sqm` (FROM v20): additive `CREATE TABLE junkyard_stock (slot_id INTEGER, station_id TEXT, upgrade_id TEXT, purchased INTEGER, PRIMARY KEY(slot_id, station_id, upgrade_id))` + `UPDATE meta SET save_version = 21`. **NO UPSERT/ON CONFLICT** (minSdk-24), parameterized, additive-only.
- `OrbitalFrontier.sq`: add table + `selectJunkyardStockForSlot` / `deleteAllJunkyardStockForSlot` / `insertJunkyardStockEntry` (mirror station_market), add to per-slot wipe, bump header to v21.
- Regenerate baseline `databases/21.db` via the SQLDelight schema gradle task (`./gradlew :core:generateMainOrbitalFrontierSchema`; verifyMigrations=true).
- `SaveVersion.CURRENT = 21L`.
- `SqlDelightGameStateRepository.kt`: `loadJunkyardStock` (skip zero / unknown-slug WARN), write (delete-then-insert non-zero), wipe (reuse delete-all), wire `junkyardStock = loadJunkyardStock(slotId)` into load assembly.

**Sim + device threading (mirror marketState):**
- `world/WorldState.kt`: add `junkyardStock: JunkyardStock = JunkyardStock.EMPTY`.
- `sim/SimulationState.kt`: add `junkyardStock: JunkyardStock = JunkyardStock.EMPTY` (mirror).
- `sim/Simulation.kt`: docked outfit block passes the junkyard's `usedPartMarket` + `state.junkyardStock` + `stationId` + `usedPartParams` (new ctor param like `pricingParams`) into `Outfitting.resolve`, threads `outfit.junkyardStock` into the next snapshot. None/non-junkyard ⇒ same instance.
- `screen/PlayScreen.kt`: add `junkyardStock` var (seeded from `initialWorldState.junkyardStock`), thread through `outfit()`, fold into autosave snapshot (~L1893), build the used-buy list (available count + used price).

**UI (AC#1)** — `screen/OutfitScreen.kt`: "Used Parts" section at junkyards listing each authored used part with discounted price + remaining stock, tap → `BuyUsed(id)`. All testable logic stays in pure core; pin screen wiring with a **source-anchored guard test** (UC-40/44 precedent).

**ADR 0035** (`docs/adr/0035-*.md`, developer): (1) flat-discount data-driven curve (`UsedPartParams`, no condition); (2) deterministic-baseline + persisted-depletion model (UC-46 analogue) + why over full-stock; (3) restock deferred — **explicitly quoting use-case AC#3 wording** and recording depletion-persists / baseline-recomputed / restock-deferred. Extends the upgrades-and-progression deferral note (L70/L98) + ADR README index.

## Files Affected

**Production code (developer):**
- NEW `core/.../outfit/UsedPartParams.kt`, `UsedPartPricing.kt`, `JunkyardStock.kt`
- `core/.../outfit/Outfitting.kt` (BuyUsed + resolveBuyUsed + non-defaulted `OutfitResult.junkyardStock` threaded through every path + new resolve params)
- `core/.../world/Station.kt` (`usedPartMarket` field)
- `core/.../world/MvpSectorMap.kt` (`GAMMA_USED_PARTS` + wire onto gamma-junkyard)
- `core/.../world/WorldState.kt` (junkyardStock)
- `core/.../screen/OutfitScreen.kt`, `core/.../screen/PlayScreen.kt` (used-buy UI + threading)
- `core/.../save/SqlDelightGameStateRepository.kt` (load/write/wipe junkyard_stock)
- `core/.../save/SaveVersion.kt` (20L→21L)
- `core/src/main/sqldelight/.../OrbitalFrontier.sq` (table+queries+header) + NEW `migrations/20.sqm`
- NEW gradle-generated baseline `core/src/main/sqldelight/databases/21.db`
- NEW `docs/adr/0035-*.md` + `docs/adr/README.md` + `docs/design/upgrades-and-progression.md` edits

**Lockstep test-tree files — DEVELOPER-authored when touched** (project rule #4): `core/src/test/.../sim/SimulationState.kt`, `sim/Simulation.kt`, `playthrough/Playthrough.kt` (NEW `JunkyardStockDto` + `StateSnapshotDto.junkyardStock` `@EncodeDefault(NEVER)`; pin `UsedPartParamsDto` in the Playthrough config like `PricingParamsDto`), `playthrough/ReplayRunner.kt` + `playthrough/InputEvent.kt` (new `BUY_USED` OutfitEvent ↔ OutfitOrder mapping).

**Test code (qa):**
- `core/.../outfit/`: `UsedPartPricingTest` (discount curve + deterministic baseline), `JunkyardStockTest` (canonical/no-op/withPurchase), `OutfittingTest` additions — BuyUsed gates (not-junkyard, not-offered, out-of-stock, unaffordable, no-free-slot, success deducts used price + decrements stock) **and the depletion-threading regression**: at a junkyard with non-empty input `junkyardStock`, each of `None`/`BuyInstall`/`RemoveSell` returns that **same** depletion unchanged (free None-tick keeps `SimulationState.junkyardStock` byte-identical) — guards AC#3.
- NEW fixture `core/src/test/resources/playthroughs/uc47-buy-used-part.json` + `Uc47BuyUsedReplayTest` (AC#5: start docked at gamma-junkyard, BuyUsed a part, assert discounted cost + install + stock decrement; replay identical). Include the buy-used(0.6) > sell-refund(0.5) asymmetry sanity assert.
- Save round-trip `Uc47*SaveReloadReplayTest` (depletion persists; reload doesn't restock) — model on `Uc46DynamicPricingSaveReloadReplayTest`.
- **Version-tripwire (GOTCHA):** `SqlDelightSettingsRepositoryTest` — update **both** `20L` literals (L67 + L77) and the comment L63-65; keep the version-agnostic L66 `assertEquals(Schema.version, version)`. `SaveMigrationTest` — **re-pin the v19→v20 step** (L2429-2436): drop the `== Schema.version` cross-check, keep only `assertEquals(20L, …)`, fix the L2429-2431 comment (mirrors the v18→v19 literal-only precedent L2334-2339); then add a NEW **v20→v21 step test** (top-of-chain, may assert `== Schema.version` alongside `21L`): build a data-bearing v20 DB, `Schema.migrate(driver, 20L, 21L)`, assert `junkyard_stock` exists+empty, prior data survives, version=21, a purchased row round-trips.
- Source-anchored guard test for the OutfitScreen used-buy wiring.

## Risks & Considerations
- **AC#3 reading**: depletion persists, baseline recomputed on load, no time-based restock (deferred per the open-question) — documented in ADR 0035.
- **Byte-identity**: new `junkyardStock` defaults EMPTY + `@EncodeDefault(NEVER)`; `BuyUsed` unused by all 18 fixtures; non-junkyard/None ⇒ same-instance. Verify `databases/21.db` regen is the only generated-artifact change.
- **Determinism**: baseline keyed on stable slugs only (no wall-clock / `Math.random`) → replay-safe.
- **Replay-stability**: `UsedPartParams` pinned per-playthrough so a retune can't invalidate old replays.
- **Anti-exploit invariant**: resolver threads input `junkyardStock` on every non-BuyUsed path (compiler-enforced) so depletion is never silently wiped.
- **Coverage**: pure core logic (pricing/stock/resolve) JVM-testable → easily clears the 60% target; UI excluded.

**Status: challenger-approved (round 2), no outstanding Critical/Major/Minor. Cleared for implementation.**
