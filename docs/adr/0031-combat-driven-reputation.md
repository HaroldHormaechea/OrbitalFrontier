# ADR 0031 — Combat-driven reputation

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC43 wires combat into the faction reputation system (UC14). [ADR 0013](0013-factions-and-reputation.md)
recorded that "the `Reputation.with` seam already exists; only a combat-side call site is missing", and
the bounty work ([ADR 0029](0029-combat-bounty-missions.md)) deliberately threaded reputation **unchanged**
through `BountyTracking.applyKills`, naming this the *UC43 seam*. So the standing model, its clamp, its
persistence (v12 `reputation` table), its replay snapshot (`StateSnapshotDto.reputation`) and its three
read sites (`ReputationGate` on board / radio / simulation) are all already in place — what is missing is
**(a)** a mapping from a destroyed hostile to a faction and **(b)** the combat call site that applies the
change.

Two design questions had to be settled: how a kill **attributes** to a faction, and which **direction**
the standing moves. The constraints are the project's binding ones: the combat model stays pure and
deterministic (replayable, [ADR 0006](0006-determinism-and-playthrough-harness.md)); the `notify` package
stays engine-free; and — the top risk — the change must not perturb any of the 15 committed playthrough
fixtures (the regenerate-and-compare guard), which means RAIDER/SCAVENGER behaviour and every existing
serialized byte must stay identical.

## Options considered

### Attribution — how a kill maps to a faction

| Option | For | Against |
|---|---|---|
| **archetype → faction** (faction is intrinsic to the ship type, on `HostileArchetype.factionId`) | A ship type *is* who flies it; mirrors the existing archetype-stat authoring; one edit per type; an unaligned type is simply `null` (the neutral-hostile rule falls out for free) | A given hull can't belong to different factions in different zones (not needed for the MVP) |
| zone → faction (faction is a property of where the encounter spawned) | Same hull could be "owned" by different factions per region | Puts political data on geometry; the bounty zones already prove zones are about *spawning*, not identity; more state to thread through the kill path |

### Direction — what a kill does to standing

| Option | For | Against |
|---|---|---|
| **negative on kill** (a loss, clamped at `min` = −100) | Matches the fiction (shooting a faction's ships angers it); symmetric with `courierFailDelta`; sets up the future reprisal/enemy-AI hook (UC45) | Sustained kills can lock a faction's reputation-gated offers (deliberate — see Consequences) |
| positive on kill (a "wanted" bounty-style gain) | Would reward grinding kills | Conflates the *bounty payout* (already credits) with *standing*; politically incoherent for a neutral kill |

## Decision

- **Attribution is archetype → faction.** `HostileArchetype` gains `factionId: FactionId? = null`. A new
  pure resolver `combat/CombatReputation.applyKills(destroyed, reputation, params)` reads each destroyed
  hostile's archetype, and for an affiliated one applies the standing change through the existing
  `Reputation.with` seam; a `null` faction is skipped (the **neutral-hostile** rule). It is the exact
  combat analogue of `Salvage` / `BountyTracking`: engine-free, deterministic, and a **same-instance
  no-op** when no faction kill landed.
- **Direction is negative on kill**, tuned by a new `ReputationParams.combatKillDelta` (default −5,
  `require(<= 0)` like `courierFailDelta`), clamped to `[min, max]`. Pinnable per playthrough.
- **Exactly one** faction-affiliated archetype is added — `INDEPENDENT_MARAUDER` (Independents) — spawned
  by **one new natural `EncounterZone`** authored in **Gamma Verge** (the Independents' home turf). Gamma
  is chosen deliberately: **no committed fixture ever roams Gamma** (only the UC03 jump leaves Alpha, and
  it goes to Beta), and the natural-spawn check filters zones by the player's *current* sector — so this
  zone can never be seen during an existing replay, sidestepping the fixture-stability top risk. Alpha
  keeps its single natural zone, so the UC42 replay's `encounterZones(alpha).single()` still holds.
  **RAIDER and SCAVENGER stay unaligned (`null`)**, so the neutral-hostile rule holds and every committed
  combat fixture (including UC41's "reputation unchanged" assertion) stays byte-identical.
- **Single-faction MVP (UC43 AC#2).** `Faction` has no relationship graph, so a kill only ever moves its
  own faction's standing. Allied/rival propagation is a noted future-UC seam, not built here.
- **Notification (UC43 AC#4).** A new `NotificationKind.REPUTATION_CHANGED` (`WARNING`, coalescable) — the
  renderer colours by *severity*, so no renderer change is needed. `GameNotifications.reputationChanged`
  takes a plain display **String** (not a `FactionId`) so `notify` stays decoupled from `faction`.
  `forCombatEvent` is unchanged; the toast is enqueued separately at the kill site (device only), because
  every combat event maps to `null` for the feed by design.
- **No schema bump (UC43 AC#3).** Reputation is already persisted (v12), already in the replay snapshot,
  and already flushed by the existing `hostile-destroyed` autosave. **v19 stays.** The one new serialized
  field, `ReputationParamsDto.combatKillDelta`, is `@EncodeDefault(NEVER)` with a domain-derived default,
  so a run under the default tuning omits it and every committed fixture stays byte-identical.
- **Lockstep (project rule #1).** The call site is wired identically in `PlayScreen.stepCombatOnce`
  (device, with the toast) and the test-set `sim.Simulation` (headless, standing only), so live and
  replayed standings match bit-for-bit.

## Consequences

- Combat now feeds the **Earn → reputation** arm of the core loop: shooting a faction's ships measurably
  worsens standing, which the existing `ReputationGate` already reads on the board, the radio and in the
  simulation — so reputation-gated premium offers react to combat with no new read sites.
- **Deliberate gameplay edge:** because Independents run the Gamma junkyard whose premium offer is gated at
  `>= 10`, repeatedly destroying Independent Marauders can drive that standing down and **lock** the gate.
  This is the intended "actions have consequences" behaviour (UC43 AC#3 "reflected wherever standing is
  read"), and standing recovers through the existing positive seams (faction missions).
- Adding more faction-affiliated archetypes or new factions later needs **no schema change** (factions are
  slug-keyed authored constants with graceful unknown-slug handling) — only authoring.
- The allied/rival relationship model (so a kill can ripple to allies/rivals) and combat-driven
  **reprisals** (hostile standing spawning attackers) remain deferred to a future UC (enemy-AI UC45).
- Reversibility is cheap: the resolver is one pure object and one call site per loop; removing it is local.
  Reputation, bounty (UC41) and salvage (UC42) remain independent — a single faction kill may pay a bounty,
  drop a salvage wreck **and** move standing, with no interference between the three.
