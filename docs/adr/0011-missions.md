# ADR 0011 — Missions: deterministic static-state instancing, regenerate-and-filter persistence, virtual courier parcel, tick-authoritative timer

- **Status:** Accepted
- **Date:** 2026-06-08
- **Refines:** [ADR 0002](0002-persistence-sqlite-migrations.md) / [ADR 0003](0003-persistence-access-layer-sqldelight.md) (persistence + sequential migrations), [ADR 0006](0006-determinism-and-playthrough-harness.md) (record/replay determinism), [ADR 0007](0007-trading-prices.md) (authored data reconstructed, not row-persisted), [ADR 0009](0009-scanning-and-hidden-contacts.md) (range-based surfacing), and [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable). Realizes use-case 12 (missions — mining & courier).

## Context

`PROJECT_BRIEF.md` → core_gameplay_loop ("Earn") and `docs/design/missions.md` call for a mission
system — **accept → perform → complete → reward** — with two handcrafted MVP types (**mining** quota
and **station-to-station courier**) that are **procedurally instanced** from world state, offered at
**station mission boards** and via **ship radio broadcasts**, held **multiple at once** in a mission
log, persisting **available and accepted** missions across save/reload (UC12 AC#1–#5), with the logic
**pure and JVM-testable** (AC#6) and a recorded playthrough proving the full mining loop (AC#7).

Four forces shape the design:

- **The determinism / byte-identical contract** (ADR 0006). Missions must replay bit-for-bit, and
  adding the system must not perturb any pre-UC12 number, so existing fixtures still replay exactly.
- **"Available missions persist" — but a save should stay compact and authored data is not row-data**
  (ADR 0007). Storing every generated offer row would bloat the save and pin stale offers.
- **Courier needs a timer**, but the device renders at a variable frame rate while replay steps at a
  fixed `dt` — the timer must be the *same* under both.
- **Combat / reputation do not exist yet** (UC13/UC14). Missions must deliver rewards (credits) and
  failure consequences without coupling to systems that aren't designed.

## Options considered

| Decision | Options | Chosen — why |
|---|---|---|
| How "available missions persist" | (a) persist every offer row; (b) **regenerate offers from static authored state on load + filter against persisted accepted/terminal ids** | **(b)** — a save stores only ACTIVE/terminal missions; offers are regenerated deterministically and an accepted/resolved id is filtered out, so the offer list is stable across restarts *without* storing it. Compact, and consistent with ADR 0007 (authored data is reconstructed, not row-persisted). |
| Offer instancing RNG | (a) `Random`/seeded platform RNG; (b) hash of domain objects; (c) **explicit string-hash (FNV-1a) → LCG seeded only by stable String primitives** | **(c)** — the *only* determinism-safe choice. `enum`/data-class/identity `hashCode()` are run-dependent (identity hash) or reorder-fragile; a platform RNG drifts. Seeding an explicit LCG from `PoiId.value`/`SectorId.value`/`ResourceType.name` makes generation a pure, reproducible function of static authored state — which is exactly what makes regenerate-and-filter valid. |
| Courier parcel | (a) a real `Cargo` item; (b) **virtual, tracked on the mission** | **(b)** — a real item could be sold/jettisoned or blocked by a full hold, breaking the delivery. The parcel is a `pickedUp` flag + pickup/destination ids on the mission; it occupies no hold slot. |
| Courier timer | (a) wall-clock seconds; (b) **tick-based `remainingTicks` in the model, dt-paced on the device** | **(b)** — the model timer (decremented one tick per `Missions.advance`) is the shared authority for live + replay. The device accumulates `dt` and fires one advance per fixed real interval (frame-rate-independent); replay fires one per fixed sim step. Wall-clock would not be reproducible. |
| Failure consequence | (a) reputation hit; (b) **fixed credit penalty** | **(b)** — reputation is UC14. A flat `courierFailurePenalty` (wallet floored at 0) is the predefined consequence (AC#4) with no coupling to an undesigned system. |
| Schema | (a) reuse a table; (b) **new additive `mission` table, v9→v10** | **(b)** — additive `CREATE TABLE` migration, minSdk-24-safe, mirrors every prior UC. A migrated save reads back with an empty mission log. |

## Decision

A new pure `mission` package holds the model (`Mission` + `MissionId` + `MissionType`/`MissionStatus`/
`MissionSource`), the `MissionLog` (available + accepted/terminal), the `MissionOrder` (None | Accept |
TurnIn), the `MissionResult`, the authored `MissionParams`, the deterministic `MissionGenerator`, and
the `Missions` resolver (`resolve` for accept/turn-in + automatic courier pickup; `advance` for the
per-tick courier timer). All are side-effect-free, engine-free, and **use no production RNG or wall
clock** — the same code path drives the device and the headless replay harness.

`MissionGenerator` instances offers as a pure function of the **static** authored `SectorWorld` + the
authored `MissionParams`, with every procedural choice drawn from an explicit **FNV-1a string hash →
64-bit LCG** seeded only by stable String primitives. A board surfaces one MINING + one COURIER offer
(when a second station exists); radio surfaces one MINING offer per in-range station, range-filtered
exactly like `Scanning.contactsInRange`. Mission ids are stable strings (`board:<station>:mining`,
`board:<station>:courier`, `radio:<station>`), so a regenerated offer always carries the same id.

Persistence (`OrbitalFrontier.sq` v10 + `9.sqm`) stores **only** ACTIVE/terminal missions in the new
`mission` table. On load the available offers are **regenerated and filtered** against the persisted
ids; unknown enum/resource/station names degrade with a WARN (the row is skipped — never stranded).

`WorldState.missions` (defaulted `MissionLog.EMPTY`) carries the log; `resolve`/`advance` return the
**same instances** on a no-op, so an empty log threads through byte-identically and pre-UC12 fixtures
replay bit-for-bit.

## Consequences

- **Compact, stable offers.** Restarting regenerates the identical offer list minus what's been taken;
  no offer rows accumulate. This rests entirely on generation reading *only static* authored state — if
  a future generator consulted runtime state, a regenerated offer could differ from the accepted one and
  the filter would silently break. The invariant is documented in `MissionGenerator` and must hold.
- **Reproducible.** Identical world + params ⇒ identical offers and outcomes on any JVM, so live and
  replay agree (AC#6) and the recorded mining playthrough (AC#7) is deterministic.
- **Forward room.** Reputation (UC14) plugs into the failure/turn-in path; combat missions add a new
  `MissionType` (Open/Closed); active-mission map markers (deferred) read the existing log. Dynamic /
  faction-driven generation can replace `MissionGenerator`'s body behind the same pure signature.
- **Reversibility.** The schema change is additive; reverting would mean dropping the `mission` table.
  The string-hash→LCG and the stable-id scheme are the load-bearing commitments — changing either would
  renumber every offer id and orphan accepted missions, so they are effectively permanent once shipped.
