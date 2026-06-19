# ADR 0030 — Loot & salvage economy

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC42 closes the combat → **Earn** link of the core gameplay loop (PROJECT_BRIEF.md → `core_gameplay_loop`):
before this change a destroyed hostile dropped nothing and combat (UC13, [ADR 0012](0012-real-time-combat.md))
did not feed the economy. A kill must now produce collectible **salvage** — credits and/or resources — that
the player picks up by flying near the wreck, with credits going to the wallet and resources to cargo
(UC42 AC#1/#2), capacity overflow handled deterministically and surfaced as a notification (AC#3), and the
whole thing **pure and seed-deterministic** so recorded playthroughs replay bit-for-bit (AC#4).

Hard constraints from the existing architecture:

- **Determinism / lockstep ([ADR 0006](0006-determinism-and-playthrough-harness.md)).** Any combat wiring
  must be added to **both** the device orchestrator (`screen/PlayScreen`) and the test-set orchestrator
  mirror (`sim/Simulation`), or live and replay diverge.
- **Byte-identical fixtures.** 14 committed combat playthroughs serialize their `combatConfig` block; the
  codec encodes defaults globally. A new combat draw or a new always-serialized config field would shift
  their bytes and force a fixture regen.
- **UC41 interaction ([ADR 0029](0029-combat-bounty-missions.md)).** Bounty contracts already pay out on a
  kill. UC42's pitfall list flags the risk of double-rewarding a single kill.

Three decisions had no precedent and are recorded here.

## Options considered

| Option | For | Against |
|---|---|---|
| **Caller-side, combat-RNG-independent loot keyed `salvage:$zoneId:$hostileId` (chosen)** | Combat sim draws no new RNG → all 14 fixtures stay byte-identical, no regen; loot is reproducible from a stable string key; generation lives outside `Combat.step` (Single Responsibility) | A second seed seam to reason about (mitigated: it reuses the one `DeterministicRng` primitive) |
| Draw loot inside `Combat.step` from `CombatRng` | One RNG seam | Advances the combat RNG stream → every combat fixture's bytes change → 14-fixture regen; couples loot to the damage/targeting draws |
| **Salvage as transient world state, excluded from the save (chosen)** | No schema bump (v19 unchanged); mirrors `CombatState`/ADR 0012 precedent exactly; reload simply has no pending wrecks | A wreck in flight at save time is lost on reload (acceptable: salvage is ephemeral, like an active encounter) |
| Persist salvage in the save DTO | Wrecks survive a reload | A v19→v20 migration + new rows for ephemeral, seconds-lived state; contradicts the transient-combat model |
| **Bounty + salvage stack as distinct sources (chosen)** | Matches the fantasy (contract reward vs. wreckage scrap); separate code paths; no special-casing | The UC41 replay test's exact-credits assertion must widen to `bounty + salvage` |
| Suppress salvage on bounty kills | Keeps UC41 credits clean | Arbitrary; a killed ship has scrap regardless of why it was killed; couples two systems |

## Decision

- **Caller-side, seed-deterministic loot.** A new pure `combat/LootTable.roll(archetypeId, seedKey)` rolls a
  hostile's loot from `seedKey = "salvage:$zoneId:${hostileId.value}"` via the shared
  `common/DeterministicRng` (FNV-1a → LCG): one credit draw, then two draws per authored `LootDrop` (chance,
  then quantity) in catalog order. It draws **no** combat-RNG numbers, so `Combat.step` is untouched and the
  combat fixtures stay byte-identical. Loot is authored per `HostileArchetypeId` (`RAIDER`, `SCAVENGER`, a
  `DEFAULT` fallback for an un-catalogued id) — all numbers `[TUNE]`.
- **Spawn on kill, collect by proximity.** `Salvage.spawn(...)` mints one `combat/SalvageDrop` per kill at the
  hostile's pre-step position (the `CombatEvent.HostileDestroyed` event carries only the id, so the kill
  position + archetype are read from the pre-step `CombatState.hostiles`). `Salvage.collect(...)` walks drops
  in `SalvageId` order and, for each within `CombatParams.salvagePickupRadius` of the player, takes its
  credits (routed through `PlayScreen`'s single `applyCreditChange` chokepoint) and offers its resources to
  `Cargo.add` (capacity-respecting partial fill) in `ResourceType` declaration order.
- **Deterministic overflow (AC#3).** When the hold fills, the leftover resources stay on the drop (credits
  zeroed — already collected) so they can be picked up later once space frees, and the reused
  `GameNotifications.actionRejected("CARGO FULL")` toast fires (no new `NotificationKind`).
- **Transient world state, no schema bump.** `salvage: List<SalvageDrop>` + a monotonic `nextSalvageId`
  allocator live on `WorldState`/`SimulationState` but are **excluded from the save DTO** — they reconstruct
  empty on reload, exactly like `CombatState` (ADR 0012). `SaveVersion` stays **19**; no migration.
- **Bounty + salvage stack.** They are distinct credit sources on separate code paths: the bounty payout
  (`BountyTracking.applyKills`) and the salvage credits (`Salvage.collect`) fold into the wallet
  independently, with no shared state, so a single kill is never double-counted but yields both.
- **`CombatParamsDto.salvagePickupRadius` is `@EncodeDefault(NEVER)`.** Because the `combatConfig` block is
  serialized in every fixture (the codec's global `encodeDefaults = true`), a plain new field would add a key
  to all 14 and break their bytes. Marking it omit-by-default (its default derived from `CombatParams()`)
  keeps every fixture byte-identical; a future playthrough that pins a non-default radius records it, so the
  replay it asserts is still reproduced exactly. This mirrors the UC41/UC14/UC15 `@EncodeDefault(NEVER)`
  precedent on the sibling config/state DTOs.

### Determinism (the lockstep)

The spawn (in the combat branch, threaded into both `Simulation.step` return paths) and the collect (run
once per tick, **unconditionally, after movement and before the combat branch**, so a tick that ends the
encounter cannot skip it) are added identically to `PlayScreen` and the test-set `Simulation`. Loot is pure
and FNV-1a-keyed, so it is byte-stable across runs; no new combat RNG, no `java.time`.

## Consequences

- Combat now feeds the economy — the **Earn** pillar's combat path is live; harder hostiles can be made more
  rewarding by authoring richer `LootTable` entries (data-only).
- Salvage is ephemeral: a wreck floating at save time is gone on reload (consistent with the transient combat
  model). If persistent wrecks are ever wanted, that is a deliberate later ADR with a migration.
- The UC41 combat-bounty replay test's exact-credits assertion widens to `bountyReward + salvage` (derived from
  the actual `LootTable.roll`, no magic literals) — the stacking is intended, not a regression.
- Loot contents/odds and the pickup radius are `[TUNE]` placeholders; balancing is a later pass.
- A fallback resources-only salvage (credits omitted) is a trivial switch if the economy later prefers it.
