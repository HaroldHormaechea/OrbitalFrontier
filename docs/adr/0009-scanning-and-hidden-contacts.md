# ADR 0009 — Active scanning & hidden contacts: shared Contact capability + additive v8 persistence

- **Status:** Accepted
- **Date:** 2026-06-08
- **Refines:** [ADR 0002](0002-persistence-sqlite-migrations.md) / [ADR 0003](0003-persistence-access-layer-sqldelight.md) (persistence + sequential migrations), [ADR 0005](0005-movement-integration.md) / [ADR 0006](0006-determinism-and-playthrough-harness.md) (pure resolver run identically by device + replay), and [ADR 0001](0001-engine-choice.md) (`core` stays JVM-testable). Realizes use-case 10 (active scanning & hidden contacts).

## Context

`PROJECT_BRIEF.md` → core_gameplay_loop ("Roam"/"Earn") and
[world-and-sector.md](../design/world-and-sector.md) ("Detection — transponders & active scanning")
call for a two-tier information layer: **transponder-broadcasting** POIs (gates, stations) show on
the minimap automatically (UC03/UC05), while **hidden contacts** — ships/objects running without a
transponder — stay invisible until the player runs an **active scan** that reaches them within the
ship's **sensor range** (a stat improved by the UC09 `SCANNER_I` upgrade). Revealed contacts must
**persist** (stay known after scanning, including across save/reload), and the scan logic must be
**pure and JVM-testable**, with a recorded playthrough (UC02) asserting both an in-range reveal and
an out-of-range non-reveal.

This forces three decisions: how hidden contacts share the minimap's existing transponder-rendering
seam without re-introducing per-type `if` ladders; where the "revealed" state lives; and how it is
persisted under the established additive-migration discipline (ADR 0002) without a stale-capacity
style pitfall.

## Options considered

| Option | For | Against |
|---|---|---|
| **`Contact` capability split (chosen): extract `interface Contact { contactKind }`, make `Transponder : Contact`, add `HiddenContact : Poi, Contact`; minimap draws every `Contact`, gating non-`Transponder` ones on a revealed set** | Reuses the UC05 Open/Closed minimap seam unchanged (one `when(contactKind)`); "hidden" is simply "a `Contact` that is not a `Transponder`"; no new POI-type branch anywhere; pure model stays JVM-testable | One more capability interface; existing `contactKind` overrides now satisfy `Contact` (mechanical) |
| Add a `hidden: Boolean` flag to `Transponder` | No new interface | Conflates "broadcasts" with "is on the map"; every consumer of `Transponder` must now special-case the flag; breaks the ISP intent of the UC05 design |
| Reveal state on each `HiddenContact` / in the `SectorWorld` graph | Co-located with the contact | The graph is **fixed authored data** rebuilt every load (ADR 0004); per-player mutable state must not live there — same reason dock/mining state lives in `WorldState` |
| Pure `Scanning.resolve` returning a `Set<PoiId>`, save-wide on `WorldState.revealedContacts` | Mirrors `Docking`/`Mining` resolver style; deterministic, run identically by device + replay (ADR 0005/0006); ids globally unique so the set is sector-agnostic and monotonic | Set grows unbounded over a long game (bounded in practice by authored contact count) |

## Decision

1. **Shared `Contact` capability.** Introduce `interface Contact { val contactKind: ContactKind }`;
   `Transponder` now extends `Contact` (adding no members), and a new `HiddenContact(id, position,
   contactKind = SHIP) : Poi, Contact` models a no-transponder contact. `ContactKind` gains `SHIP`.
   The minimap renders every `Contact`, drawing a `Transponder` unconditionally but a plain `Contact`
   only when its id is in the revealed set — a triangle marker for the `SHIP` kind. No per-type
   branch; the marker `when` is the only switch, extended by one arm.

2. **Pure resolver + save-wide reveal set.** `Scanning.resolve(world, sector, shipPos, scanRange,
   revealed, action)` is a side-effect-free function mirroring `Docking`/`Mining`: on `SCAN` it
   **unions** every `HiddenContact` in the current sector within `scanRange` into `revealed`; on
   `NONE`/nothing-new it returns the **same set instance** (cheap `!==` change detection). It only
   ever unions — revealed contacts **never re-hide** (UC10 AC#4 / pitfall). `scanRange` comes from the
   existing `ShipStats.scanRange(type, loadout)` (UC09), so a `SCANNER_I` fit widens it with no new
   plumbing. The revealed ids live on `WorldState.revealedContacts: Set<PoiId>` — save-wide (a
   `PoiId` is globally unique across the sector graph), not per-ship or per-sector.

3. **Additive v8 persistence.** A new single-column `revealed_contact(contact_id TEXT PRIMARY KEY)`
   table; load reads it into the set, save does `INSERT OR IGNORE` per id (append-only, monotonic,
   minSdk-24-safe — same rationale as `field_deposit`). Migration `7.sqm` creates the empty table and
   bumps `save_version` to 8; `databases/8.db` is the regenerated schema baseline. A migrated save
   reads back with nothing revealed.

## Consequences

- **Easier:** future contact kinds (e.g. mission/combat targets in UC12/UC13) reuse the `Contact`
  seam and the revealed-set machinery — add a `ContactKind` value and a marker arm, nothing else.
  Live device scanning and the headless replay harness call the **same** `Scanning.resolve`, so a
  recorded scan playthrough (UC10 AC#6) is authoritative.
- **Harder / watch:** the revealed set is monotonic and unbounded in principle; acceptable because the
  MVP map has a small, fixed authored contact count. A `revealed_contact` row whose contact the map no
  longer contains is kept harmlessly (resolves to nothing) — no load-time pruning, matching the
  "never stranded" stance.
- **Reversibility:** the schema change is additive and the capability split is internal to `core`;
  reverting would mean dropping the table (data loss of reveal state only) and folding `Contact` back
  into `Transponder`. Deferred per the use case: scan time/cooldown (instantaneous one-shot reveal for
  the MVP) and any re-hide behaviour.
