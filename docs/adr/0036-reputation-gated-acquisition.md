# ADR 0036 — Reputation-gated upgrade & ship acquisition

- **Status:** Accepted (supersedes ADR 0013 in part)
- **Date:** 2026-06-20

## Context

UC48 closes the gating gap the design has carried since UC14. Per-faction reputation shipped in UC14
([ADR 0013](0013-factions-and-reputation.md)) but gates only **mission offers** (`ReputationGate`
filtering the board/radio/sim offer lists). Upgrade and ship **acquisition stayed cash-only**: the
shop (`Outfitting.resolve` over a station's `outfitMarket`) and shipyard (`FleetResolver.resolve` over
its `shipyard`) charged the **raw catalog price** and never consulted standing. ADR 0013 and the
upgrades-and-progression design note both record this as a deferred hook ("reputation gating of
upgrades/ships — still deferred").

The pieces to realize it already exist:

- **Standing model (UC14):** `Reputation.valueFor(factionId)` (neutral 0 default), mutated by mission
  turn-ins, courier failures (UC14), and combat kills (UC43).
- **Station ownership (UC14):** `Station.factionId: FactionId?` — the faction a station belongs to.
- **Faction price seam (UC46, [ADR 0034](0034-dynamic-station-pricing.md)):** `FactionPricing.adjust()`
  already grades a station's trade prices by standing, **exactly 1.0 at neutral / null faction**, and is
  folded into `MarketPricing.effectiveMarket(...)`. The shop/shipyard simply never went through it.

The forces (UC48 ACs):

- **AC#1** — a station's outfit/shipyard catalog gates available items by the player's standing with
  that station's faction.
- **AC#2** — standing also modulates price (discount high, surcharge/lockout low) via the **same** seam
  as UC46.
- **AC#3** — gating/price effects persist across save/reload and update as standing changes.
- **AC#4** — a locked item shows **why** (its standing requirement) rather than silently vanishing.
- **AC#5** — `:core:ktlintCheck :core:test` green; a playthrough raises standing and a previously-locked
  item becomes purchasable.
- **Pitfall** — an item already installed when standing later drops below its threshold must **not** be
  confiscated.

## Options considered

| Option | For | Against |
|---|---|---|
| **A — authored threshold per catalog item × the docked station's faction** | Reuses UC14 standing + UC46 `FactionPricing` unchanged; one new pure `StandingGate`; leaves `OutfitMarket`/`Shipyard` and every `offers()` site untouched; the required faction is implicit (the station's), so it stays pure authored data — never persisted, no schema/DTO impact | One global threshold per part (not per-station) — acceptable, gating is confined to premium items at league stations |
| B — per-station `Map<itemId, threshold>` on each market | Per-station granularity | Generality UC48 doesn't need; touches every market authoring + persistence site; more surface for no MVP benefit |
| Derive a persisted "unlocked" set at purchase time | Could model one-time unlocks | New persisted state + a migration; AC#3 ("update as standing changes") wants a *live* derivation, not a latch |

## Decision

Adopt **Option A**, derived **entirely at read time** from live `reputation` — **no schema bump, no
migration, no fixture regeneration**:

1. **Authoring.** Add `unlockThreshold: Int = 0` to `Upgrade` and `ShipType`. Default 0 = ungated, so
   every existing item is back-compatible and byte-identical. The required faction is the **docked
   station's `factionId`** (mirroring `FactionPricing`), so the threshold stays pure authored data.
2. **Pure gate.** New `faction/StandingGate.status(requiredStanding, factionId, reputation):
   StandingStatus` — `requiredStanding <= 0` ⇒ available; else `reputation.valueFor(factionId)` (0 when
   null) must be `>= requiredStanding`. `StandingStatus(available, requiredStanding, currentStanding,
   factionId)` carries the AC#4 "why locked" payload. A positive threshold at a faction-less station is
   permanently locked — surfaced as an authoring error, not special-cased.
3. **Price seam (AC#2).** New `FactionPricing.adjustedPrice(basePrice, factionId, reputation, params) =
   round(basePrice × adjust(...)).coerceAtLeast(1)` — the **single source of truth** for an
   acquisition's effective price. Exactly the base at neutral.
4. **Enforce in the pure resolvers, not just the UI.** `Outfitting.resolve` and `FleetResolver.resolve`
   gain neutral-default `factionId/reputation/pricingParams` params; `BuyInstall`/`BuyUsed`/`BuyShip`
   no-op when locked and charge `adjustedPrice(...)`. BuyUsed composes on the faction-adjusted base
   (faction-adjust, then the UC47 used discount). This keeps **live == replay** parity, since the
   sim/replay path issues orders directly and bypasses the screen.
5. **Screens (AC#1/#4).** Locked items render as **visible disabled rows** reading
   `Requires <faction> standing N (you: M)`; the `cost` fed to `PurchaseGate` is the same
   `adjustedPrice(...)` the resolver deducts — **display == charge**.
6. **Authored content (AC#5).** `unlockThreshold = 10` on the Beta (LEAGUE) tier-II parts
   `ENGINE_TUNE_II` / `CARGO_POD_II` and on the league hull `PROSPECTOR`. `10 ≤
   ReputationParams.missionCompleteDelta (10)` (the UC14 "threshold ≤ delta" invariant), so completing
   one league mission unlocks all three.

This **supersedes ADR 0013 in part** (the "acquisition is cash-only / gating deferred" note) while
leaving its reputation + mission-gating decisions in force.

## Consequences

- **No new persisted state.** Gating + pricing are derived from the already-persisted `reputation`, so
  AC#3 (persist across reload, update as standing changes) falls out for free, the v21 schema head is
  untouched, and no `.sqm` migration / version tripwire is added.
- **Byte-identity / zero fixture regen.** All new logic is purely derived with **no new RNG draws**, and
  neutral-default params (`Reputation.EMPTY`, `PricingParams()`) keep `adjust()` at exactly 1.0 and the
  gate open. Existing recorded playthroughs purchase at neutral standing, so their fixtures stay
  byte-identical. (QA re-verifies no committed fixture buys at non-zero standing.)
- **No confiscation (pitfall).** The gate is consulted **only** in BuyInstall/BuyUsed/BuyShip
  availability. `RemoveSell`, `SwitchActive`, loadout load/persist, and stat derivation never inspect
  it, so a part installed while allied stays installed if standing later drops — gating governs
  *purchase availability*, not retained inventory.
- **Premium-part-at-junkyard caveat.** A `threshold > 0` part also stocked at a faction-less junkyard
  used desk would be permanently locked there. The gated tier-II parts are **not** in
  `GAMMA_USED_PARTS`, so there is no accidental lockout today; future authoring must keep this in mind.
- **Limitation.** One global threshold per item (Option A). Per-station thresholds, allied/rival
  propagation, and continuous-curve tuning remain future work; the `unlockThreshold` values and the
  `FactionPricing` curve are `[TUNE]`.
- **Lockstep cost.** The resolver signature change is mirrored in `sim/Simulation.kt` (test source) in
  lockstep, so any future docked-commerce change must keep both in step.
