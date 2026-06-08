# ADR 0013 — Factions & reputation: action-driven standing, separate gate filter, additive v12 persistence

- **Status:** Accepted
- **Date:** 2026-06-08

## Context

UC14 introduces **factions** (the powers that own stations) and a per-faction **reputation**
that the player earns through their actions, gating some mission offers. It activates two
hooks deferred in earlier UCs: the mission system's "station faction state drives availability"
(UC12 / ADR 0011) and the economy's dynamic-pricing tie-in (UC08).

The hard constraint is the project's determinism invariant (ADR 0006 / ADR 0011): mission
generation must remain a **pure function of static authored world state**, byte-identical on
replay, and a recorded pre-UC14 playthrough must continue to replay byte-for-byte. Reputation
is, by definition, *mutable runtime state* — so the design question is how to add it without
ever letting runtime state leak into generation. The acceptance criteria also require a
recorded playthrough (UC02) that completes a faction mission and then observes both increased
reputation and a newly **available** gated offer (AC#6).

The brief lists no `## Profiles`, keeps all logic JVM-testable (ADR 0001), and persists with
sequential additive SQLDelight migrations (ADR 0002 / 0003).

## Options considered

| Option | For | Against |
|---|---|---|
| **Reputation changes visibility only; gating is a SEPARATE pure filter applied AFTER generation + the takenIds filter** | `MissionGenerator` stays a pure function of static state — adding a gated offer never perturbs existing offers' bytes; gate is trivially testable in isolation; pre-UC14 replay unaffected | One more filter step at each of the three surface sites (board, radio, simulation) |
| Bake the gate into `MissionGenerator` (skip gated offers during generation) | One fewer call site | Generation would consult runtime reputation → breaks the regenerate-and-filter invariant; a regenerated offer could differ from the accepted one; replay no longer byte-stable |
| Reputation as a per-ship stat | Mirrors crew | Wrong model — standing is the *player's*, not a ship's; would not survive ship switching |
| Mutate reputation as a side effect of generation/gating | Fewer moving parts | Hidden side effects in a "pure" read path; non-deterministic; untestable |

## Decision

1. **Reputation is a save-wide, pure value** (`faction.Reputation` over `Map<FactionId,Int>`,
   neutral = 0 = absent entry), carried on `WorldState` like `credits`. `EMPTY` is the default,
   so a fresh game and every pre-UC14 save read back fully neutral and replay byte-identically.

2. **Gating is a separate pure filter.** `ReputationGate.isAvailable(mission, reputation)` is
   the identity over an un-gated offer; a gated offer (non-null `unlockFaction`) is available
   only when the player's standing `>= unlockThreshold`. It is applied at **three symmetric
   sites, AFTER `MissionGenerator` + the `takenIds` filter**: the board (`PlayScreen
   .stationMissionBoard`), the radio (`PlayScreen` flight loop), and the simulation
   (`sim.Simulation`, the replay/test path). `MissionGenerator` itself never reads reputation —
   it only **stamps** each offer with its source-station faction and the authored gate, both
   from static world state, so each offer stays independently string-seeded and adding a gated
   offer does not change any existing offer's bytes.

3. **Reputation changes are action-driven, applied only in the pure resolvers.**
   `Missions.resolve` grants `ReputationParams.missionCompleteDelta` to a completed mission's
   `factionId`; `Missions.advance` applies `ReputationParams.courierFailDelta` to a timed-out
   courier's faction (alongside the existing credit penalty). Both clamp to
   `[min, max]` and return the **same** reputation instance on a no-op (a faction-less mission,
   or any non-completing path), preserving the same-instance / byte-identical invariant.

4. **Faction attribution rule.** A board/radio mining offer and the gated `:premium` offer are
   credited to their **source station's** faction. A courier is credited to its **pickup
   (source) station's** faction — even though the parcel is delivered at the destination, the
   contract is the source faction's.

5. **Threshold ≤ delta (AC#6 guarantee).** The authored gated `:premium` offer's
   `unlockThreshold` (10) is `<=` `missionCompleteDelta` (10), and Alpha Station's regular board
   mining mission shares Alpha's faction (`league`) with Alpha's `:premium` gate. So completing
   one Alpha mining mission moves `league` from 0 → 10, which clears the Alpha `:premium` gate —
   exactly the "complete a faction mission → a gated offer becomes available" scenario.

6. **Persistence is additive (schema v11 → v12).** A new `reputation(faction_id PK, value)`
   table stores only **non-neutral** standings (delete-then-insert full snapshot, minSdk-24
   safe), and a nullable `mission.faction_id` column records a persisted mission's attribution.
   The **gate** (`unlockFaction`/`unlockThreshold`) is deliberately **not** persisted: it only
   affects AVAILABLE offers, which are never stored (regenerate-and-filter). Factions themselves
   are fixed authored data (`Factions` catalog), reconstructed on load. An unknown faction slug
   degrades gracefully (skip the reputation row / drop the mission attribution, with a WARN —
   "never stranded"). `SaveVersion.CURRENT` is bumped to 12 and must equal the generated schema
   version (the init-check fails fast otherwise).

### Authored MVP wiring

- Factions: `league` (Alpha + Beta stations) and `independents` (Gamma junkyard).
- `ReputationParams` defaults: `missionCompleteDelta = +10`, `courierFailDelta = -15`,
  `min = -100`, `max = +100`.
- One gated `board:<station>:premium` courier per faction station (`unlockThreshold = 10`).

## Consequences

- **Determinism preserved.** Generation never reads reputation; the gate is an identity over
  ungated offers; resolvers return same instances on no-op. Pre-UC14 fixtures replay
  byte-for-byte, and a UC14 fixture can assert the unlock transition deterministically.
- **Clear single-writer mutation.** Reputation moves only through the two pure `Missions`
  functions; the play screen and the simulation fold the result back identically, so live and
  replay agree (the same `ReputationParams` feed both).
- **Cheap, compact saves.** Only non-neutral standings are rows; a fully-neutral game writes
  none. Additive migration keeps every prior save loadable.
- **Reversible / extensible.** New factions or gated offers are pure authored-data edits. The
  gate filter generalizes to any future surface site by the same `ReputationGate` call.

### Deferred (documented here as explicit hooks; NOT built in UC14 — all optional / non-AC)

- **Dynamic price modulation (`FactionPricing`).** Reputation- or faction-state-driven station
  prices (the UC08 dynamic-pricing hook). The seam is `Station.factionId` + `Reputation`; a
  future `FactionPricing.adjust(market, faction, reputation)` would wrap `StationMarket` reads.
- **Reward modulation.** Scaling mission rewards (credits / resource bonus) by standing.
- **Combat-driven reputation.** Destroying a faction's ships (UC13) changing standing — the
  `Reputation.with` seam already exists; only a combat-side call site is missing.

These are recorded so a later UC can pick them up without re-deriving the model; none is
required by UC14's acceptance criteria.
