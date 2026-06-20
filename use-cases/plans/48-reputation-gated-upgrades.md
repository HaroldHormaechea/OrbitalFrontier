---
plan_for: use-cases/48-reputation-gated-upgrades.md
work_branch: feat/uc-48-reputation-gated-upgrades
team: orbital-frontier-uc-48
approved: 2026-06-20
---

UC-48 — Reputation-gated upgrade & ship availability. **Challenger-approved** (no revision round; their two Minors baked in below).

## Analysis
The shop has two parallel acquisition paths, both pure-resolver + thin-screen:
- **Outfit/upgrades:** `outfit/OutfitMarket` (stocked `UpgradeId`s) → `outfit/Outfitting.resolve` (BuyInstall/BuyUsed/RemoveSell). Price on `Upgrade.price`; used price via `UsedPartPricing.usedPrice`.
- **Ships:** `ship/Shipyard` (offered `ShipTypeId`s) → `FleetResolver.resolve` in `ship/Fleet.kt` (BuyShip/SwitchActive). Price on `ShipType.price`.

Both charge the **raw catalog price** today — neither goes through the UC46 pricing seam. The trade desk already computes `MarketPricing.effectiveMarket(...)`, which folds in `FactionPricing.adjust(station.factionId, reputation, params)` (exactly 1.0 at neutral/null-faction). `Station.factionId` exists (UC14); `reputation` is on WorldState/SimulationState and is already mutated by combat (UC43). So both the standing model (UC14) and the faction-price seam (UC46) are in place to reuse — confirmed by challenger against the code.

Schema head is **v21** (ADR 0035); reputation persists since v12. Gating + pricing are **derived at read time** from live `reputation` ⇒ **no schema bump, no migration, no fixture regeneration**; AC#3 ("persist across reload" + "update as standing changes") falls out for free. Screens are GL-backed (not headlessly constructible) ⇒ AC-testable logic goes in pure engine-free classes, screen wiring pinned by a source-anchored guard. The deterministic `sim/Simulation.kt` mirrors PlayScreen's docked-commerce block and is updated in lockstep. All new logic is **purely derived — no new RNG draws** ⇒ neutral-standing replay fixtures stay byte-identical.

## Proposed Solution

**1. Authoring the gate (AC#1).** Add optional `unlockThreshold: Int = 0` to `Upgrade` (`outfit/Upgrade.kt`) and `ShipType` (`ship/ShipType.kt`). Default 0 = ungated ⇒ every existing item back-compatible & byte-identical. Required faction is implicitly the **docked station's `factionId`** — mirroring `FactionPricing`. Authored data, never persisted (only ids persist) ⇒ no DTO/schema impact.

**2. Pure gate + lock-reason state (AC#1 + AC#4).** New `core/src/main/kotlin/com/orbitalfrontier/faction/StandingGate.kt`:
- `object StandingGate.status(requiredStanding: Int, factionId: FactionId?, reputation: Reputation): StandingStatus`.
- `data class StandingStatus(available, requiredStanding, currentStanding, factionId)` (+ `locked` convenience). Rule: `requiredStanding <= 0` ⇒ available; else `currentStanding = reputation.valueFor(factionId)` (0 when null/unknown), `available = currentStanding >= requiredStanding`. A positive threshold at a null-faction station is therefore permanently locked — surfaced as an authoring error, no special-casing. This is the AC#4 "why locked" payload the screen renders.

**3. Price modulation via the UC46 seam (AC#2).** Add a pure helper to `economy/FactionPricing.kt`: `adjustedPrice(basePrice: Long, factionId, reputation, params): Long = roundToLong(basePrice * adjust(...)).coerceAtLeast(1)` — reuses the existing `adjust()` multiplier (exactly 1.0 at neutral; both `Upgrade.price`/`ShipType.price` are `Long`, matching). **Single source of truth** for the effective price; display and charge both call it (display==charge is a hard requirement — challenger confirmed).

**4. Enforce in the pure resolvers (correctness + determinism, not UI-only).** The sim/replay path issues orders directly and bypasses the screen, so resolver-level gating is *necessary* for live==replay parity.
- `Outfitting.resolve`: add `factionId: FactionId? = null, reputation: Reputation = Reputation.EMPTY, pricingParams: PricingParams = PricingParams()` (neutral defaults ⇒ no gate, mul 1.0 ⇒ existing tests/fixtures untouched). `BuyInstall`/`BuyUsed`: if `!StandingGate.status(upgrade.unlockThreshold, factionId, reputation).available` → return `unchanged` (same no-op idiom as "not stocked"). Charge `FactionPricing.adjustedPrice(upgrade.price, …)`; for BuyUsed, **compose-on-base** — faction-adjust the catalog price first, then `UsedPartPricing.usedPrice(...)` on top.
- `FleetResolver.resolve` (`ship/Fleet.kt`): same new params; `BuyShip` gates on `type.unlockThreshold`, charges `adjustedPrice(type.price, …)`.
- `RemoveSell`/`SwitchActive` never consult the gate (see edge case).

**5. Screens — locked rows + modulated prices (AC#1 + AC#4).** `screen/OutfitScreen.kt` & `screen/ShipyardScreen.kt`: inject `factionId: FactionId?`, `reputationSupplier: () -> Reputation`, `pricingParams`. Per **offered** item, compute `StandingStatus` + effective price. Locked items stay **visible** as disabled rows reading "Requires <faction> standing N (you: M)" (AC#4 — not silently vanishing); INSTALL/BUY withheld. The `cost` fed to the existing `PurchaseGate.evaluate`/`PurchaseGate.details` is the **faction-adjusted price**, identical to the resolver deduction. Non-offered items remain absent (that's "not stocked", not gating).

**6. Wiring.** `screen/PlayScreen.kt`: add `fun reputationSnapshot(): Reputation`; in `outfit()`/`fleetCommand()` pass `factionId = station.factionId, reputation = reputation, pricingParams = pricingParams` into the resolvers and use the effective price in `enqueueEconomyError(...)`. `app/OrbitalFrontierGame.kt`: inject `station.factionId`, `{ playScreen?.reputationSnapshot() }`, `pricingParams` into both screens. `sim/Simulation.kt` (test source): mirror the same three args into both resolvers in lockstep.

**7. Authored content + demo (AC#5).**
- **Upgrade gate:** set `unlockThreshold = 10` on the Beta tier-II parts `ENGINE_TUNE_II` and `CARGO_POD_II` (in `UpgradeCatalog.kt`) — both offered only at Beta = LEAGUE.
- **Ship gate (Minor 1 — now concrete, no longer optional):** set `unlockThreshold = 10` on `ShipRoster.PROSPECTOR` (`ship/ShipRoster.kt`), the hull sold at Beta = LEAGUE. This makes "upgrade **& ship** availability" (the UC title, AC#1's shipyard) demonstrably gated at the content level, not just the resolver level.
- Threshold 10 ≤ `ReputationParams.missionCompleteDelta` (10) — the same "threshold ≤ delta" invariant UC14 uses — so completing one league mining mission unlocks all three. Continuous discount is also visible: at +10 standing the default params give mul 0.99 ⇒ round(700×0.99)=693.

**8. ADR (invariant #7).** New `docs/adr/0036-reputation-gated-acquisition.md` (+ `docs/adr/README.md` index row) — documents the authored-threshold × station-faction gate, reuse of UC14 standing + UC46 FactionPricing, derive-at-read-time (no schema bump), locked-with-reason UI, no-confiscation rule. **Supersedes-in-part ADR 0013** ("acquisition is cash-only / gating deferred"). Also drop the "gating deferred" note in `docs/design/upgrades-and-progression.md`. Developer is authorized for `docs/**`. (0036 confirmed as the next free number.)

### Edge case — no confiscation (AC pitfall)
The gate is consulted **only** in BuyInstall/BuyUsed/BuyShip availability (resolver + screen rows). `RemoveSell`, `SwitchActive`, loadout load/persist (`ship_upgrade` table), stat derivation never inspect it. An installed part whose standing later drops below its threshold **stays installed** — gating governs availability for purchase, not retained inventory. Pinned by a unit test (install high → drop standing → assert loadout + derived stats unchanged).

## Files Affected

**Production code (developer):**
- `core/src/main/kotlin/com/orbitalfrontier/outfit/Upgrade.kt` — add `unlockThreshold`.
- `core/src/main/kotlin/com/orbitalfrontier/ship/ShipType.kt` — add `unlockThreshold`.
- `core/src/main/kotlin/com/orbitalfrontier/faction/StandingGate.kt` — NEW pure gate + `StandingStatus`.
- `core/src/main/kotlin/com/orbitalfrontier/economy/FactionPricing.kt` — add `adjustedPrice`.
- `core/src/main/kotlin/com/orbitalfrontier/outfit/Outfitting.kt` — gate + effective price (neutral-default params).
- `core/src/main/kotlin/com/orbitalfrontier/ship/Fleet.kt` — `FleetResolver` gate + effective price.
- `core/src/main/kotlin/com/orbitalfrontier/screen/OutfitScreen.kt` — locked rows + reason + modulated price.
- `core/src/main/kotlin/com/orbitalfrontier/screen/ShipyardScreen.kt` — same.
- `core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt` — `reputationSnapshot()`, resolver wiring, error cost.
- `core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt` — inject faction + reputation + params into both screens.
- `core/src/main/kotlin/com/orbitalfrontier/outfit/UpgradeCatalog.kt` — threshold 10 on `ENGINE_TUNE_II` + `CARGO_POD_II`.
- `core/src/main/kotlin/com/orbitalfrontier/ship/ShipRoster.kt` — threshold 10 on `PROSPECTOR`.
- (`world/MvpSectorMap.kt` needs **no change** — Beta already = LEAGUE and stocks those parts/hull; gating rides on the catalog. Verified `GAMMA_USED_PARTS` = {ENGINE_TUNE_I, CARGO_POD_I, SCANNER_I, FUEL_TANK_I} — none are the gated tier-II parts, so **no unintended junkyard lockout**, Minor 3 cleared.)
- `docs/adr/0036-reputation-gated-acquisition.md` (NEW) + `docs/adr/README.md`; `docs/design/upgrades-and-progression.md`.

**Test code (qa):**
- `core/src/test/kotlin/com/orbitalfrontier/sim/Simulation.kt` — mirror new resolver args (lockstep, invariant #4).
- `core/src/test/kotlin/com/orbitalfrontier/faction/StandingGateTest.kt` — NEW: ungated / locked-below / available-at-or-above / null-faction-locked / status fields.
- `core/src/test/kotlin/com/orbitalfrontier/economy/FactionPricingTest.kt` — add `adjustedPrice` cases (neutral=base, high=discount, low=surcharge, clamp, ≥1).
- `core/src/test/kotlin/com/orbitalfrontier/outfit/OutfittingTest.kt` — gated BuyInstall/BuyUsed no-op below threshold; success + effective deduction at/above; **no-confiscation** retained-loadout test.
- `core/src/test/kotlin/com/orbitalfrontier/ship/FleetTest.kt` — gated BuyShip no-op below / success above + effective price (this is the resolver-level proof of ship gating).
- `core/src/test/kotlin/com/orbitalfrontier/screen/Uc48ReputationGatingSourceTest.kt` — NEW source-anchored guard (both screens consult StandingGate, render locked-with-reason, withhold buy, use adjusted price; game injects suppliers).
- **AC#5 anchor (REQUIRED, per challenger):** `core/src/test/kotlin/com/orbitalfrontier/.../Uc48ReputationGatedAcquisitionTest.kt` — deterministic sim/resolver transition: at neutral, BuyInstall(ENGINE_TUNE_II)/BuyShip(PROSPECTOR) → unchanged (locked); raise league standing ≥ 10; assert both become purchasable AND the effective price reflects the discount. A recorded `uc48` replay fixture is **recommended** (convention parity, authored via `PlaythroughFixtures.kt`) but **not blocking** — QA's call.
- **Fixture audit (Minor 2 — explicit QA assertion):** QA must re-verify that **no committed playthrough fixture performs an outfit/ship purchase at non-zero standing** (uc09 BuyInstall/BuyShip and uc47 BuyUsed run at neutral; uc14/uc43 don't buy outfits/ships), so byte-identity holds via `adjust()`'s `if (standing == 0) return 1.0`. Contingency if one ever appears: regenerate that one fixture with the full UC46-style recipe. This is the linchpin of the zero-regen claim.

## Risks & Considerations
1. **Gate granularity = Option A** (catalog item × docked station's faction) — endorsed by challenger over the per-station id→threshold map (B's generality isn't needed; A leaves `OutfitMarket`/`Shipyard` and every `offers()` site untouched). Limitation: one global threshold per part — acceptable, gating confined to premium items at league stations.
2. **display==charge** — hard requirement; same `adjustedPrice` helper in screen (`PurchaseGate` cost) and resolver (deduction). Any divergence = charged≠shown bug.
3. **BuyUsed compose-on-base** — faction-adjust catalog price then used discount; byte-identical at neutral, clearer composition.
4. **Premium-part-at-junkyard** — a threshold>0 part also stocked in a junkyard used desk would be permanently locked there (null/independents faction). Verified the gated tier-II parts are NOT in `GAMMA_USED_PARTS`, so no accidental lockout; any future authoring must keep that in mind.
5. **Determinism / byte-identity** — everything derived from already-persisted `reputation` (no new RNG draws, no new persisted state); the only sim change is threading three args. Verified green by `PlaythroughFixtureTest` + the determinism guard at neutral standing.
6. **No schema bump** — deliberate (invariant #3), avoids the v22 migration/version-tripwire surface entirely.

Gate command: `./gradlew :core:ktlintCheck :core:test`. All ACs (1–5) covered; all UC pitfalls addressed.

## Challenger verdict
**APPROVE** — no revision round needed. Independently verified all five UC-48 invariants and all five ACs against the codebase. Strongest design point: gating enforced in the resolvers (not just UI) for live==replay parity. Two Minors (concrete ship gate; explicit fixture-audit QA assertion) baked in above.
