# ADR 0035 — Junkyard buy-used parts

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC47 adds the **buy-used** side of junkyards. Until now a junkyard (`StationKind.JUNKYARD`, UC09 /
[ADR 0008](0008-fleet-and-outfitting-persistence.md)) only let the player **remove + sell** used parts
(`OutfitOrder.RemoveSell`, refunding `Outfitting.USED_PART_REFUND_FRACTION = 0.5` of the catalog price)
and **install at full price** from its `outfitMarket`. There was no cheaper acquisition path; the
upgrades-and-progression design note (`docs/design/upgrades-and-progression.md`) records "buying cheaper
used parts is still TBD" and lists the used-part pricing curve as an open question.

The forces:

- **AC#1** — junkyards present a buy list of used parts (a subset of the catalog) priced below new via a
  defined used-part pricing curve.
- **AC#2** — buying a used part installs/stores it exactly like a new part (the UC09 outfitting flow).
- **AC#3** — used stock is finite/variable (not an infinite shelf) and persists/regenerates
  deterministically across save/reload.
- **AC#4** — used-part pricing is data-driven and applies consistently across junkyards.
- **AC#5** — `:core:ktlintCheck :core:test` green; a playthrough buys a used part and asserts the
  discounted cost + install.
- **The #1 constraint (byte-identity).** The committed playthrough fixtures (notably uc08's tick-0
  Titanium sell asserting exactly 800 credits) and the v20 persistence baseline must stay byte-for-byte
  unchanged, with **zero fixture regeneration**.
- **MVP decision (use case):** used parts are **purely cheaper, with NO condition/wear**.
- **minSdk-24** persistence rules (no UPSERT/`ON CONFLICT`, no `java.time`, parameterized queries only).

## Options considered

| Option | For | Against |
|---|---|---|
| **Deterministic baseline + persisted depletion** (baseline a pure function of (junkyard, part); persist only the purchased count) | Satisfies AC#3 finite/deterministic; mirrors UC46's persist-the-delta philosophy; **kills the reload-restock exploit** (depletion is durable); a re-tune of the baseline bounds reaches old saves for free | One more table + migration; baseline can't vary per-save without a persisted world seed (deferred) |
| Persist the full remaining stock per (junkyard, part) | Trivial load (read the number back) | Pins a stale count (a bounds re-tune can't reach old saves); redundant with the deterministic baseline; larger save |
| Flat discount, no stock at all (infinite used shelf) | Simplest | Violates AC#3 ("not an infinite shelf"); no scarcity, so used parts dominate new |
| Condition/wear dimension on used parts | Richer | Explicitly **out of MVP scope** (use-case decision: purely cheaper) |

## Decision

**Used-part pricing — data-driven flat discount (AC#1/#4).** A used copy of a part costs
`round(newPrice × discountFraction)` (clamped `>= 1`), where `discountFraction` is an authored
`UsedPartParams` tunable (default **0.6**). The discount lives in exactly one place and applies to every
junkyard identically. The default 0.6 is deliberately **above** the 0.5 sell-refund fraction, so a
buy-used-then-sell round-trip always loses money — there is no refund-arbitrage exploit. No
condition/wear dimension (MVP decision); a used part is otherwise identical to the new part and installs
through the **same `Loadout.install` path** as `BuyInstall` (AC#2) — only the price and the stock gate
differ.

**Stock — deterministic baseline + persisted depletion (AC#3).** The baseline available count per
(junkyard, part) is a **pure function** of the shared `DeterministicRng`, keyed on the stable slugs
`"usedstock:<junkyardId>:<partId>"`, drawn uniformly in `[minStock, maxStock]` (`UsedPartParams`,
default `1..3`). Only the **player-caused depletion** (the purchased count) is persisted, in a new
save-wide `JunkyardStock`; `available = max(0, baseline − purchased)`. Persisting the *purchases* (not
the remaining count) is what makes the depletion durable: a reload recomputes the same baseline and
re-applies the same purchases, so a player **cannot reload to restock** cheap parts.

> **Reading of AC#3's "persists/regenerates deterministically":** the *depletion persists* and the
> *baseline is recomputed (not stored) on load* — there is **no time-based restock**. Restock cadence is
> the use-case's open question and is **deferred**: the stock key carries no epoch, so a junkyard's
> baseline is fixed for the life of the save and stock only ever decreases (until a fresh game).

**Anti-exploit invariant (the correctness core, AC#3).** `OutfitResult` gains a `junkyardStock` field
with **no convenience default** — every construction site must pass it explicitly, so the compiler
guarantees the depletion is threaded on every path. `Outfitting.resolve` builds its `unchanged` result,
and every non-BuyUsed success (`None` / `BuyInstall` / `RemoveSell`), from the **passed-in** stock
(carried through untouched); **only** a successful `BuyUsed` mutates it (via `JunkyardStock.withPurchase`).
This prevents a None / BuyInstall / RemoveSell tick from silently wiping the depletion — which would let
a reload restock cheap parts.

**Used-part availability is authored per junkyard.** A new `Station.usedPartMarket: OutfitMarket`
(authored map data carried with the world, not persisted — the [ADR 0008](0008-fleet-and-outfitting-persistence.md)
treatment of `outfitMarket`) lists which parts a junkyard offers used, **separate** from its full-price
refit `outfitMarket`, so a junkyard can stock a different used set than the new set it refits with. The
MVP Gamma junkyard authors a `GAMMA_USED_PARTS` subset.

**Persistence (AC#3) — schema v20 → v21.** A new per-slot
`junkyard_stock(slot_id, station_id, upgrade_id, purchased)` table stores **purchases only**. The
`20.sqm` migration is a purely additive `CREATE TABLE` (minSdk-24-safe; no UPSERT; full
delete-then-insert snapshot per slot, mirroring the `station_market` / `reputation` tables), and a
regenerated `databases/21.db` baseline keeps `verifyMigrations` green. Only positive purchased rows are
written; an absent (station, upgrade) is undepleted. An unknown upgrade slug degrades on load (skip with
a WARN), so a removed catalog part never strands a save.

**Determinism + replay-stability (AC#4).** The baseline draw is keyed purely on the (junkyard, part)
slugs — no world seed, no wall clock, no `Math.random` — so it reproduces bit-for-bit on any JVM and in
replay. `UsedPartParams` is **pinned per playthrough** (`Playthrough.usedPartConfig`, mirroring
`pricingConfig`) so a later default retune of the discount fraction or stock bounds can never silently
invalidate an old recorded buy-used replay.

**Byte-identity (the #1 constraint).** The new `junkyardStock` defaults to `JunkyardStock.EMPTY` on
`WorldState` / `SimulationState`, the snapshot DTO (`StateSnapshotDto.junkyardStock`) and the config DTO
(`Playthrough.usedPartConfig`) are `@EncodeDefault(NEVER)`, and `withPurchase` is a same-instance no-op
on a 0-unit purchase. No committed fixture buys a used part, and a None / non-junkyard / BuyInstall /
RemoveSell tick threads the same instance through, so every pre-UC47 fixture omits the field on disk and
**steps byte-identically** — **zero fixture regeneration**, verified by the full `PlaythroughFixtureTest`
+ `verifyMigrations`.

This ADR extends the buy-used deferral recorded in the upgrades-and-progression note and in
[ADR 0008](0008-fleet-and-outfitting-persistence.md); the sell/refit side (UC09) is unchanged.

## Consequences

- **Easier:** a re-tune of any used-part constant (discount fraction / stock bounds) instantly affects
  old saves (depletion-only persistence), like every other `*Params`. Pricing + stock are pure and fully
  JVM-unit-tested. The same effective resolver runs on device and in replay (one source of truth).
- **Harder / new constraints:** the `junkyard_stock` table is the second per-(slot, slug-keyed) economy
  table; a future used part added to a junkyard's `usedPartMarket` participates automatically. The
  non-defaulted `OutfitResult.junkyardStock` is a deliberate compile-time tripwire — keep it
  non-defaulted so the anti-exploit threading can never be silently dropped.
- **Reversibility:** dropping a part from `usedPartMarket` removes its used offer without a migration;
  removing the system entirely would be a normal additive-reverse migration (drop the table).
- **Deferred:** time-based restock cadence (the use-case open question — would need a persisted epoch or
  world clock); per-save baseline variance (needs a persisted world seed, the same deferral as
  [ADR 0034](0034-dynamic-station-pricing.md)); used-part condition/wear (out of MVP scope).
