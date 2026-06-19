# ADR 0026 — Save slots: slot_id partition in the single DB + first table-rebuild migration + active-slot pointer

- **Status:** Accepted
- **Date:** 2026-06-19
- **Supersedes (in part):** [ADR 0002](0002-persistence-sqlite-migrations.md) — specifically its
  "**single save slot, autosave only**" decision. Everything else in ADR 0002 (SQLite from the start,
  one DB for world + player + settings, stored schema version, sequential version-by-version
  migrations) **still stands**; ADR 0002 carries a forward note pointing here.

## Context

[ADR 0002](0002-persistence-sqlite-migrations.md) chose SQLite from the start with a **single save
slot, autosave only**. UC38 (save-slot management) adds the ability to keep **multiple independent
saves** with player-visible metadata (name, last-saved time, credits/sector/play-time) and the actions
**save / load / delete / new-game-into-slot**, while the existing single autosave keeps working and
appears as one of the slots (UC38 AC#3). PROJECT_BRIEF.md → in_scope #5 ("Save/load persistence") is
the parent; ADR 0002 / ADR 0003 and `docs/design/save-and-persistence.md` own the storage model.

Two constraints shape the decision:

- **minSdk 24** (PROJECT_BRIEF.md → `stack.versions.android_min_sdk`). The bundled SQLite on API 24/25
  predates `INSERT … ON CONFLICT … DO UPDATE` (UPSERT, SQLite 3.24). Every existing write therefore uses
  `INSERT OR REPLACE` / `INSERT OR IGNORE`; a slot rewrite cannot introduce UPSERT.
- **`core` stays JVM-testable** (ADR 0001) and the **simulation stays time-free** (ADR 0006). A slot's
  "last saved" is genuine wall-clock data, the one place the model would otherwise read a real clock.

## Options considered

| Option | For | Against |
|---|---|---|
| **A — `slot_id` partition column in the single DB (chosen)** | One DB, one driver, one migration chain (ADR 0002/0003 unchanged); slot isolation is a `WHERE slot_id = ?` on every query; cross-slot listing is one cheap header query; legacy save migrates cleanly into slot 0. | Requires adding `slot_id` to every game-state table's primary key — the **first table-rebuild migration** in the codebase (all 15 prior `.sqm` were additive). |
| B — one SQLite **file per slot** | Physical isolation; no schema change to existing tables. | N drivers / N migration runs to manage and version; "list all slots" must open every file; the active-DB indirection touches the whole persistence stack; more moving parts on a frequent autosave. |
| C — serialize each slot as a **blob** in a `slots` table | Minimal schema change. | Throws away the structured/queryable persistence ADR 0002 deliberately chose; whole-blob rewrites on every autosave; migrations become blob surgery. |

## Decision

Adopt **Option A**: partition every game-state table by a **`slot_id`** column added to its primary key,
in the single existing DB.

- **Schema (v17).** `game_state` drops its single-row `CHECK (id = 0)` and is keyed by `slot_id`; the
  other 10 game-state tables (`ship`, `ship_upgrade`, `cargo`, `ship_section_damage`, `field_deposit`,
  `revealed_contact`, `mission`, `reputation`, `owned_station`, `station_module`) gain `slot_id` as the
  first PK column. `game_state` also gains the per-slot display metadata `name`,
  `last_saved_epoch_millis`, `play_time_seconds` (UC38 AC#1). `settings` is **unchanged** — control /
  audio / UI preferences are global, not per-slot.
- **Active-slot pointer.** `meta.active_slot_id` (additive `ALTER TABLE ADD COLUMN`, `DEFAULT 0`) records
  the slot the autosave writes to and `Continue` resumes (UC38 AC#3). The legacy save's pointer backfills
  to slot 0.
- **Migration v16 → v17** is the **first table-rebuild migration**: for each game-state table, `CREATE`
  the v17-shape table → `INSERT … SELECT …, 0, …` (backfilling `slot_id = 0`, the **legacy** slot) →
  `DROP` the old → `ALTER … RENAME` into place. The pre-UC38 single autosave thus reads back intact as
  slot 0 named "Autosave" (UC38 AC#3/#4). Column order/type/affinity/constraints are written to match the
  `.sq` baseline exactly so `verifyMigrations` (the committed `databases/17.db`) stays green. There are no
  foreign keys or explicit indices, so only the primary keys (recreated by the `CREATE`s) need rebuilding.
- **Name-clobber guard (minSdk-24-safe, no UPSERT).** A save is split: `insertSlotHeaderIfAbsent`
  (INSERT OR IGNORE) establishes the row + its player-facing `name` **once** on first save, then
  `updateSlotHeader` writes only the gameplay columns + the autosave metadata (last-saved / play-time) —
  **never `name`**. So an autosave can never overwrite the player's chosen slot name; renaming is the
  separate targeted `setSlotName`. (This is the same seed-then-targeted-UPDATE discipline UC31 used for
  `settings`, and it deliberately replaces the proposal's ON-CONFLICT phrasing, which minSdk-24 cannot run.)
- **Wall clock at the persistence boundary.** A `Clock` port (UC38) is injected into the repository; the
  Android launcher backs it with `System.currentTimeMillis()` and JVM tests use a fixed/fake clock. It is
  read **only** to stamp `last_saved_epoch_millis` — the pure simulation never sees it (ADR 0006 preserved).
- **Play time** is a new `WorldState.playTimeSeconds`, accumulated on the render thread from the same
  per-frame `dt` the sim advances by (only while actually playing), folded onto the autosave snapshot, and
  persisted per slot. It is **not** part of the deterministic state — the replay harness ignores it.
- **Repository shape.** `GameStateRepository` methods take a `SlotId`; a new `SaveSlotRepository`
  capability (ISP) adds `listSlots` / `renameSlot` / `deleteSlot` / `activeSlot` / `setActiveSlot`. The
  autosave follows the live active slot via a supplier, so a "save-as" into another slot re-targets it.

## Consequences

- **Slot isolation (UC38 AC#4)** falls out of the schema: every read/write/delete is a `WHERE slot_id = ?`,
  so one slot can never read or mutate another. Listing slots is one header query (no ship/cargo load).
- **A precedent is set** for repartitioning a table: the table-rebuild recipe (create-new → copy → drop →
  rename, columns matching the `.sq` exactly) is now the pattern for any future PK change, and any such
  migration must keep `verifyMigrations` green by regenerating `databases/<version>.db`.
- **Forward-compatibility holds**: a pre-UC38 save upgrades to slot 0 with no data loss and replays
  byte-identically (the new metadata columns backfill 0 / "Autosave"; `playTimeSeconds` defaults to 0).
- **Reversibility** is the usual cost of a schema decision: changing the partition scheme later would need
  another migration. Multiple-DB-files (Option B) remains available if per-slot physical isolation is ever
  required, but at the cost of the single-chain simplicity this decision keeps.
- ADR 0002's "single slot, autosave only" line is **superseded in part**; its storage/migration model is
  otherwise intact and now hosts N slots on one chain.
