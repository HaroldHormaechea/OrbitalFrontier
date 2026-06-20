---
plan_for: use-cases/50-crew-depth-fleet-management.md
work_branch: feat/uc-50-crew-depth-fleet-management
team: orbital-frontier-uc-50
approved: 2026-06-20
---

# UC50 — Crew depth, wages & fleet/crew management. FINAL proposal — approved by challenger (no Critical/Major; 4 Minor folded in).

## Analysis
Today (ADR 0010 / UC11) crew is a per-ship **Int count** (`OwnedShip.crew`) driving only two pure derivations: `TurretOperability.turretsOperable(crew)` and `ShipStats.crewCapacity` clamping. Hiring = pure `Hiring.resolve`, wired in `PlayScreen.hire` + the test-set `Simulation` docked branch in lockstep, with thin `HireScreen`. UC09 gives `Fleet`/`FleetResolver` with `SwitchActive` surfaced inside `ShipyardScreen` (the bare switch). Crew is serialized as `OwnedShipDto.crew: Int`, asserted by `Uc11CrewReplayTest` + 6 combat fixtures (`withActive(f.active.withCrew(1))`). Save schema **v21**.

**Key design lever:** crew identities/roles affect NO deterministic computation in the MVP (turret-op + wages use the **count** only; skills-affecting-systems is STRETCH/deferred). So the count stays exactly where it is (deterministic, byte-identical), and identities are a **production-only overlay** that never enters `SimulationState`/`OwnedShip`/`OwnedShipDto` — structurally guaranteeing zero artifact regen.

## Proposed Solution

### A. Crew identities (AC#1) — production-only overlay; count stays authoritative
- NEW `crew/CrewMember.kt`: `CrewId` (value class/Long), `CrewRole` enum (e.g. PILOT/GUNNER/ENGINEER/DECKHAND — **inert metadata; system effects are STRETCH, do NOT gold-plate**), `CrewMember(id, name, role)` pure value.
- NEW `crew/CrewRoster.kt`: save-wide roster over `List<CrewMember>` each carrying `assignedShipId: ShipId`; `EMPTY`, `forShip(id)`, pure mutators, and a deterministic generic-name synthesizer (used by migration-load + default hire names).
- NEW `crew/CrewAssignment.kt`: pure resolver — reassign a member to another ship (decrement source `crew`, increment target, clamped to target `crewCapacity`, move the roster entry) and set role. Returns `(fleet, roster, changed)`. Switch-active reuses `Fleet.switchActive`/`FleetResolver` (NOT duplicated).
- MODIFY `ship/Fleet.kt`: add `totalCrew` (Σ ship.crew).
- MODIFY `world/WorldState.kt`: add `crewRoster: CrewRoster = CrewRoster.EMPTY` (defaulted). **Not** added to `SimulationState`.
- Framing (no two-sources-of-truth): `ship.crew` = *how many* (deterministic); roster = *who* (identity). Invariant `roster.forShip(s).size == s.crew`, maintained by hire/assignment resolvers and **reconciled on load** (synthesize generic members to a count with fewer rows = the migration path).

### B. Wages / upkeep (AC#2) — deterministic, default-OFF
- NEW `crew/Wages.kt`: `WageParams(creditsPerCrewPerPeriod: Long = 0, periodTicks: Int = [TUNE])`, `WageResult(credits, paid, unpaid, changed)`, pure `Wages.resolve(credits, totalCrew, params)`. **Default rate 0 ⇒ same-value no-op ⇒ all existing fixtures byte-identical** (zero-regen lever).
- **[Binding, challenger #2] Tick-deterministic:** credits ARE in replay equality, so the drain must key on the integer `tick` (`tick % WAGE_PERIOD_TICKS`) in BOTH `Simulation.step` (apply at step start, thread `creditsAfterWages` into both the docked-freeze base and the in-flight base) AND `PlayScreen.advanceSimulation` (a `wageTickAccumulator` mirroring `missionTickAccumulator`/`MISSION_TICK_SECONDS`), routed through `applyCreditChange`, autosave on drain, WARNING toast on shortfall. `Uc50WagesReplayTest` must assert **credits parity across record→replay**, not merely "drain happened."
- **Unpaid rule (AC pitfall, challenger #2-approved):** pay `min(owed, credits)`, clamp at 0 (preserves credits≥0 + load-time `coerceAtLeast(0)`), surface an "unpaid wages" WARNING (UC40 tiers). **No desertion, no debt in MVP** — explicitly *defined as clamp-at-zero/drain-stalls* and recorded in ADR 0038 (the pitfall requires the behavior be defined).

### C. Fleet/crew management screen (AC#3)
- NEW `screen/FleetCrewScreen.kt`: thin view (mirrors `HireScreen`/`ShipyardScreen`, no game logic) listing ships + each ship's crew (name+role), with reassign / change-role / **switch-active**, firing intents to PlayScreen. Primary ship-switch surface.
- **[Binding, challenger #3] "Replacing the bare UC09 switch":** `ShipyardScreen`'s existing switch is KEPT (avoids UC09 test regressions); the new screen is the primary surface — two switch surfaces coexist. This divergence from a literal "replace" is **recorded in ADR 0038** as a deliberate decision, not a silent reinterpretation.
- MODIFY `app/OrbitalFrontierGame.kt` + `StationHubScreen.kt`: construct/route the screen; wire a non-zero production `WageParams`; supply roster/fleet/credits.
- AC-testable logic is pure (`CrewAssignment`/`CrewRoster`/`Fleet`, unit-tested directly); GL screen wiring pinned by a **source-anchored guard** (`Uc44CombatHudSourceTest` precedent).

### D. Persistence (AC#4) — additive v21→v22 (full recipe)
- MODIFY `OrbitalFrontier.sq`: new per-slot `crew_member(slot_id, ship_id, crew_id, name, role, PK(slot_id, ship_id, crew_id))` + select/deleteAllForSlot/insert; full delete-then-insert snapshot per slot (like `reputation`); add to per-slot wipe; header → v22.
- NEW `migrations/21.sqm` (FROM-version = old **21**): additive `CREATE TABLE crew_member …` + `UPDATE meta SET save_version = 22`. **No backfill SQL** — repository synthesizes identities from `ship.crew` on load. NO UPSERT (minSdk-24).
- MODIFY `save/SaveVersion.kt`: `CURRENT = 22L`.
- MODIFY `save/SqlDelightGameStateRepository.kt`: load crew_member → `CrewRoster`, reconcile-to-counts (synthesize missing; unknown role degrades→default+WARN); save = delete-then-insert per slot; wire `WorldState.crewRoster`; hire path adds a named member.
- Regenerate baseline `databases/22.db` via `./gradlew :core:generateMainOrbitalFrontierSchema` (`verifyMigrations=true` enforces).

### E. ADR
- NEW `docs/adr/0038-crew-depth-and-wages.md` (realizes ADR 0010's deferred wages; supersedes-in-part). **Must record:** identity-overlay model (production-only, count stays deterministic); wage cadence + default-0 zero-regen; unpaid rule = clamp-at-zero/no-desertion/no-debt (deferred); **[challenger #4]** wage-accumulator phase not persisted (only drained credits persist — AC#2 satisfied); **[challenger #3]** the kept-ShipyardScreen-switch divergence; **[challenger #1]** crew capacity is immovable by outfitting in the MVP (`UpgradeCatalog.MVP` has no crew-quarters part), so `withLoadout`'s crew re-clamp never strands roster rows today. MODIFY `docs/adr/README.md` index row.

## Files Affected
**Production code (developer):** `crew/CrewMember.kt` (new), `crew/CrewRoster.kt` (new), `crew/CrewAssignment.kt` (new), `crew/Wages.kt` (new), `ship/Fleet.kt`, `world/WorldState.kt`, `screen/FleetCrewScreen.kt` (new), `screen/StationHubScreen.kt`, `screen/PlayScreen.kt` (wage tick drive + roster-on-hire + screen wiring + autosave snapshot includes crewRoster), `app/OrbitalFrontierGame.kt`, `save/OrbitalFrontier.sq`, `save/migrations/21.sqm` (new), `save/SaveVersion.kt`, `save/SqlDelightGameStateRepository.kt`, `databases/22.db` (regenerated), `docs/adr/0038-*.md` (new), `docs/adr/README.md`.

**Test code (qa):** `crew/CrewMemberTest.kt` / `CrewRosterTest.kt` / `CrewAssignmentTest.kt` (assignment moves crew + adjusts counts + flips turret-op; role change; reconcile-from-counts; capacity clamp on reassign), `crew/WagesTest.kt` (drain math, clamp-at-0 unpaid, rate-0 no-op, multi-crew), `sim/Simulation.kt` (+`SimulationState.kt` if needed — wage lockstep mirror; **roster NOT added**), `playthrough/PlaythroughFixtures.kt` + new `Uc50WagesReplayTest.kt` (+`PlaythroughFixtureTest` repro; crew + non-zero WageParams; **assert credits parity record→replay**), `save/SqlDelightGameStateRepositoryTest.kt` (crew_member round-trip + migrated-count→synthesized-identity + assignment persist), `save/SqlDelightSettingsRepositoryTest.kt` (version tripwire 21→22, ~lines 63-77), `save/SaveMigrationTest.kt` (new v21→v22 step test asserting literal `22` + crew_member added + save survives; update full-chain `migrate(…,22L)`/`Schema.version`), `screen/Uc50FleetCrewSourceTest.kt` (new source-anchored guard), plus a guard/test pinning **crew capacity immovable by MVP outfitting** (challenger #1).

> **Cross-role lockstep:** `Wages.resolve` (core/main) is wired into BOTH `PlayScreen` (developer) and the test-set `Simulation` (qa) — must match exactly.

## Risks & Considerations
1. **Zero-regen proof obligations (QA):** (a) WageParams default rate 0 ⇒ wage no-op ⇒ existing fixtures byte-identical; (b) identities only on `WorldState.crewRoster` ⇒ artifacts structurally unchanged; (c) only NEW fixture is the wage fixture. Confirm no existing fixture's bytes move.
2. **Two-facets sync** (count vs roster): single `CrewAssignment` resolver + load reconcile; verify hire/reassign keep `roster.forShip(s).size == s.crew`.
3. **Refit re-clamp gap (challenger #1, Minor/dead-path):** `withLoadout` decrements crew outside `CrewAssignment`, but `UpgradeCatalog.MVP` has no crew-quarters upgrade so capacity is constant per type today ⇒ never strands rows. Documented in ADR + pinned by a test.
4. **Wage accumulator phase not persisted:** drained credits persist (AC#2 met); cadence phase resets on reload — documented.
5. **Live↔replay wage parity:** best-effort mirror like UC46 market; new fixture proves the Simulation path; tick-keyed in both per challenger #2.
6. **minSdk-24:** CREATE TABLE only, no UPSERT, no java.time.
7. **Scope:** crew skills-affecting-systems + crew upgrades are STRETCH — explicitly OUT (deferred in ADR). MVP = identities+roles + wages + management screen.

## ORCHESTRATOR ROLE-SPLIT NOTE (overrides the analyst's "QA take the Simulation mirror" suggestion)
Per the established lockstep convention, the **DEVELOPER** (not QA) authors the test-source lockstep mirror so PlayScreen and its mirror change together: developer owns `sim/Simulation.kt`, `sim/SimulationState.kt` (if touched), and any `playthrough/Playthrough.kt` DTO shim needed to encode `WageParams` in the replay snapshot (with `@EncodeDefault(NEVER)`). QA owns ALL other test files: the new `PlaythroughFixtures.kt` wage fixture + `Uc50WagesReplayTest`, all `crew/*Test.kt`, the repository test, the version-tripwire tests, the source-anchored guard, and the capacity-immovable guard.

## Challenger verdict
**APPROVE** — verified all six scrutiny points against the codebase: scope held (skills/upgrades deferred), schema recipe complete (21.sqm additive no-UPSERT + 22.db regen + SaveVersion 22L + repo block + both tripwires named), AC#1 turret-op untouched, byte-identity via roster-on-WorldState-only + WageParams default 0, unpaid rule defined (clamp-at-0/WARN/no-desertion), screen logic pure + source-guarded + active-switch reuses FleetResolver. 4 Minors folded in (refit-reconcile note, tick-deterministic wage + parity assertion, kept-ShipyardScreen-switch divergence recorded, unpersisted accumulator phase documented).
