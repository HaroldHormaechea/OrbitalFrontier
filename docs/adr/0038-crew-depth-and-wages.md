# ADR 0038 — Crew depth, wages & fleet/crew management

- **Status:** Accepted
- **Date:** 2026-06-20

## Context

UC50 deepens crew beyond the bare integer count introduced in UC11 / [ADR 0010](0010-crew-and-turret-operability.md)
and adds the management surface the multi-ship fleet (UC09 / [ADR 0008](0008-fleet-and-outfitting-persistence.md))
has lacked. Until now **crew is a single per-ship `Int`** (`OwnedShip.crew`) that drives exactly two
pure derivations — `TurretOperability.turretsOperable(crew)` and `ShipStats.crewCapacity` clamping — and
hiring is a pure `Hiring.resolve` with a thin `HireScreen`. ADR 0010 explicitly recorded **wages as
deferred future work** ("a new resolver + an ADR"). The multiple ships from UC09 have only a bare
active-ship switch surfaced inside `ShipyardScreen`; there is no fleet/crew screen.

The forces:

- **AC#1** — crew are individual entities (named, with a role) rather than a single count; turret
  operability still derives correctly.
- **AC#2** — crew incur a periodic wage/upkeep credit drain that persists across save/reload.
- **AC#3** — a fleet/crew management screen lists ships and crew, supports assigning crew to ships/roles,
  and switching the active ship (replacing the bare UC09 switch).
- **AC#4** — crew and assignments persist across save/reload.
- **AC#5** — `:core:ktlintCheck :core:test` green; the wage drain and crew-derived operability are
  unit-tested.
- **The #1 constraint (byte-identity).** The committed playthrough fixtures (notably uc08's tick-0
  Titanium sell asserting exactly 800 credits) and the v21 persistence baseline must stay byte-for-byte
  unchanged, with **zero fixture regeneration**.
- **MVP scope (use case).** Identities + roles + wages + the management screen are in; **crew skills that
  affect systems and crew upgrades are STRETCH and deferred.**
- **minSdk-24** persistence rules (no UPSERT / `ON CONFLICT`, no `java.time`, parameterized queries only).

**Key design lever:** crew identities/roles affect **no** deterministic computation in the MVP — turret
operability and wages both key on the **count** only. So the count can stay exactly where it is
(deterministic, byte-identical) and identities become a **production-only overlay**.

## Options considered

| Option | For | Against |
|---|---|---|
| **Identity overlay on `WorldState` only; count stays authoritative on `OwnedShip.crew`** | Identities add **zero bytes** to the deterministic record/replay artifacts (`SimulationState`/`OwnedShipDto` untouched) ⇒ structural byte-identity; turret-op untouched; one new additive table | Two facets (count vs. roster) to keep in sync — handled by a single resolver + load-time reconcile |
| Put `List<CrewMember>` on `OwnedShip` (drop the int count) | One source of truth | Regenerates **every** combat fixture + the save baseline (the snapshot bytes move); turret-op derivation rewritten; violates the #1 constraint |
| Default-on wages | Simpler "feature is live" story | A non-zero default drain moves every existing fixture's credits ⇒ mass fixture regen |
| **Default-OFF wages (`WageParams` rate 0), production wires a non-zero rate** | Rate-0 ⇒ `Wages.resolve` is a same-value no-op ⇒ **every existing fixture byte-identical**; the only new fixture is the UC50 wage fixture | Live vs. replay parity rests on the recorder capturing the live `WageParams` (handled by the per-artifact `wageConfig` snapshot) |

## Decision

**A. Crew identities — production-only overlay; count stays authoritative.** New pure `crew/CrewMember`
(`CrewId`, `CrewRole` enum {PILOT, GUNNER, ENGINEER, DECKHAND; `DEFAULT = DECKHAND`}, each member carries
its `assignedShipId`), `crew/CrewRoster` (sorted-by-id `List<CrewMember>`, `forShip`, `withMember`,
`hiredOnto`, and a deterministic generic-name synthesizer), and `crew/CrewAssignment` (`CrewOrder` →
`Reassign` / `ChangeRole`; the active-ship switch is **not** here — it reuses `FleetResolver`). The roster
lives on `WorldState.crewRoster` only — **never** on `OwnedShip` / `SimulationState` / `OwnedShipDto`. The
framing invariant is `roster.forShip(s).size == s.crew`, maintained by the hire / assignment resolvers and
**reconciled on load** (synthesize generic members up to each ship's count). Role is **inert metadata** in
the MVP (no resolver reads it).

**B. Wages — deterministic, default-OFF.** New pure `crew/Wages` with `WageParams(creditsPerCrewPerPeriod
= 0, periodTicks = 600)`. The drain is **tick-deterministic**: it keys on the integer tick
(`WageParams.isWageTick`) and is applied identically at step start in `Simulation.step` (threaded as
`creditsAfterWages` into both the docked-freeze chain and the in-flight chain) and on device in
`PlayScreen.advanceSimulation` (a `wageTickAccumulator` mirroring the courier `missionTickAccumulator`),
routed through the `applyCreditChange` chokepoint, autosaving on a drain. Because credits are in
record/replay equality, **rate 0 ⇒ owed 0 ⇒ same-value no-op ⇒ byte-identical**.

**C. Unpaid rule (MVP).** The bill is `creditsPerCrewPerPeriod × fleet.totalCrew`; the player pays
`min(owed, credits)`, the balance **clamps at 0** (preserving credits ≥ 0 and the load-time
`coerceAtLeast(0)`), and any shortfall raises an `UNPAID_WAGES` WARNING toast (UC40 severity tiers).
**There is NO desertion and NO accruing debt** — an unaffordable period simply drains the wallet to 0 and
the shortfall is forgotten. (Desertion/debt are deferred.)

**D. Fleet/crew management screen.** New thin `screen/FleetCrewScreen` (no game logic, mirrors
`HireScreen`/`ShipyardScreen`) lists ships + each ship's crew and fires `CrewOrder`/`FleetOrder` intents
to the play screen; reached from a new `FLEET` hub row. It is the **primary** ship-switch surface.

**E. Persistence — additive v21→v22.** A new per-slot `crew_member(slot_id, ship_id, crew_id, name, role,
PK(slot_id, ship_id, crew_id))` table, full delete-then-insert snapshot per slot (like `reputation` /
`station_market`); header → v22; `SaveVersion.CURRENT = 22L`; regenerated `databases/22.db`. The
migration `21.sqm` is an **additive `CREATE TABLE` with NO backfill SQL and NO UPSERT** — a pre-UC50 save
has no rows, so the repository **synthesizes** generic members up to each ship's persisted `crew` count on
load (reconcile-to-counts). An unknown role slug degrades to `DECKHAND` (WARN); a duplicate `crew_id` is
dropped defensively.

## Consequences

- **Zero fixture regen.** Identities are structurally absent from the deterministic artifacts, and wages
  default to rate 0, so every committed playthrough fixture and the v21 baseline are byte-for-byte
  unchanged. The only new fixture is the UC50 wage fixture (asserting credit **parity across
  record→replay**, not merely "a drain happened").
- **[Challenger #4] The wage-accumulator phase is NOT persisted** — only the *drained credits* persist
  (which satisfies AC#2). The cadence phase (`wageTickAccumulator` on device, the tick modulus in the sim)
  resets on reload, exactly like the courier `missionTickAccumulator`. A reload may therefore shift the
  next drain by up to one period; this is accepted (the durable economic effect — the credits — survives).
- **[Challenger #3] Two switch surfaces coexist.** The literal AC#3 wording is "replacing the bare UC09
  switch", but `ShipyardScreen`'s existing switch is **kept** (removing it would regress the UC09 tests);
  the new `FleetCrewScreen` is the primary surface. This is a deliberate divergence recorded here, not a
  silent reinterpretation. Both route to the same pure `FleetResolver.switchActive` — no duplicated logic.
- **[Challenger #1] Refit re-clamp is a dead path today.** `OwnedShip.withLoadout` re-clamps `crew` to the
  new fit's crew capacity, which could in principle strand roster rows above the count. But
  `UpgradeCatalog.MVP` has **no crew-quarters upgrade**, so crew capacity is constant per ship type today
  and `withLoadout` never shrinks it ⇒ no roster row is ever stranded by outfitting. A guard test pins
  this. (A crew-capacity upgrade would need the refit path to also trim the roster — deferred with the
  rest of crew upgrades.)
- **Best-effort live↔replay cadence (risk).** The device paces wages off real time (`WAGE_PERIOD_SECONDS`)
  while the sim keys on the integer tick (`periodTicks`) — the same best-effort tier as the courier timer
  and the UC46 market. The wage **amount** and the clamp-at-0 rule are identical between the two; the new
  fixture proves the deterministic (sim) path exactly.
- **Per-artifact wage tuning.** `Playthrough.wageConfig` (a `@EncodeDefault(NEVER)` `WageParamsDto`) pins
  the `WageParams` a run recorded under, so a later retune can't invalidate an old replay; a default
  (rate-0) run omits it on disk entirely.
- **Realizes ADR 0010's deferred wages** (supersedes it in part). Crew skills affecting systems, crew
  upgrades, desertion/debt, and a dedicated crew-capacity upgrade remain deferred.

This ADR supersedes the wages-deferral in [ADR 0010](0010-crew-and-turret-operability.md) in part.
