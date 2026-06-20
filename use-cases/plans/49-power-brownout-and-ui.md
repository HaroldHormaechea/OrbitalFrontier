---
plan_for: use-cases/49-power-brownout-and-ui.md
work_branch: feat/uc-49-power-brownout-and-ui
team: orbital-frontier-uc-49
approved: 2026-06-20
---

# UC-49 — Power brownout/throttle & power UI surfacing — APPROVED PROPOSAL (analyst↔challenger agreed, 2 rounds)

## Analysis
Builds on UC07. Current `core/.../power/`: pure `PowerModel` (`drawAt`/`status`), `PowerParams` (reactorOutput=2.0, base≈0.0139, thrust≈0.0417, UC16 25/75 split), `PowerStatus`. Reactor output is currently **uncapped** (brownout deferred — both code & power.md flag it). Fuel burn is the single shared `FuelBurn.step → PowerModel.drawAt`, called by both `PlayScreen` (line 852) and test-set `sim/Simulation` (line 426). HUD uses the pure engine-free `render/HudViewModel` (built per-tick, drawn by `HudRenderer`); precedent for screen wiring is the source-anchored `Uc44CombatHudSourceTest` (PlayScreen has live GL in field inits → not headlessly constructible). Combat fire and scan go through the shared pure `Combat.step` / `Scanning.resolve`. Power state is **transient** — not in `save/`, schema stays v21, no bump. `PowerParams` is annotation-free; replay snapshots use a separate `PowerParamsDto` (Playthrough.kt:228) with `@EncodeDefault(NEVER)`.

## Proposed Solution
**Pure, rate-based power-budget model with automatic priority shedding** (the UC's stated default; realizes power.md's deferred "power budget cap"). No manual allocation. **Capacitor deferred** (AC#2 is conditional).

- **Protected set never shed:** HELM (thrust/helm) + base hotel load are always powered even if they alone exceed output → ship can never be bricked (no-deadlock by construction; no throttle path needed for MVP).
- **Sheddable, lowest-priority-first:** SCANNER → WEAPONS.
- `Brownout.resolve(demand, reactorOutput)` → `BrownoutResult{ poweredSystems, shedSystems, isBrownout }`. If total demand ≤ output → all powered, `isBrownout=false`. Else shed sheddable systems ascending until protected+remaining ≤ output. **Degenerate case (deliberate, documented):** if the protected set alone exceeds output, all sheddable systems are shed and `isBrownout=true` even though budget can't be met — protected systems stay powered.

### New production files (`core/src/main/kotlin/com/orbitalfrontier/power/`)
- `PowerSystem.kt` — enum `{ HELM, WEAPONS, SCANNER }` with explicit priority; HELM marked protected.
- `Brownout.kt` (+ `BrownoutResult`) — pure, deterministic, engine-free resolver per above.

### Modified production
- `PowerParams.kt` — add **budget-only** sheddable draws `weaponsDraw`/`scannerDraw`, default **0f**. They feed the brownout budget ONLY, NOT `drawAt`/fuel burn (protects UC16 25/75 tuning).
- `PlayScreen.kt` + test-set `sim/Simulation.kt` (**LOCKSTEP**) — each tick compute `BrownoutResult` from the run's PowerParams using the **identical `thrusting` bool already fed to `FuelBurn.step`** (`!input.released && input.magnitude > params.inputDeadzone`, computed once). Thread powered flags into the shared `Combat.step` (suppress fire when WEAPONS shed) and scan (suppress when SCANNER shed). At full power → all flags true → identity → byte-identical.
- HUD (AC#3): `HudViewModel.kt` gains power fields (`reactorOutput`, `powerDraw`, `brownout`, `shedSystems`) built from `PowerModel.status` + `BrownoutResult` (pure, unit-tested); `HudRenderer.kt` draws a power bar + brownout indicator. The brownout/shed state is **render-only** — NOT in `SimulationState`, NOT in recorded artifacts.
- Docs: new ADR in `docs/adr/` documenting the model, the protected-floor no-deadlock guarantee, the degenerate case, the **budget-demand (`base+thrust+weapons+scanner`) vs fuel-burn-demand (`base+thrust`) divergence**, and capacitor deferral. Update `docs/design/power-and-energy.md` status. (Developer authorized for docs/**.)

## Files Affected
**Production (developer):** NEW `…/power/PowerSystem.kt`, `…/power/Brownout.kt`; MOD `…/power/PowerParams.kt`, `…/screen/PlayScreen.kt`, `…/render/HudViewModel.kt`, `…/render/HudRenderer.kt`, `…/combat/Combat.kt`, `…/world/Scanning.kt`; DOCS new ADR + `docs/design/power-and-energy.md`.

**Test (qa):** NEW `…/power/BrownoutTest.kt` (shed order, protected floor/no-deadlock, no-brownout identity, degenerate protected-over-budget case); extend `render/HudViewModelTest.kt` (power fields); NEW `…/screen/Uc49PowerHudSourceTest.kt` (comment-stripped source guard, per Uc44 precedent). MOD test-set: `…/sim/Simulation.kt` (mirror), `…/playthrough/Playthrough.kt` `PowerParamsDto` (+ fields, `@EncodeDefault(NEVER)`), `…/playthrough/PlaythroughFixtures.kt` — **new sheddable-driven over-draw fixture** (base+thrust UNDER output; weapons/scanner push over; built on `UC13_COMBAT` template) asserting SCANNER shed → SCAN reveals nothing, WEAPONS shed → FIRE emits no projectile, `isBrownout=true` (AC#5). **Byte-identity proof:** existing `PlaythroughFixtureTest` regen-compare must pass unchanged (incl. `UC07_THIRSTY_POWER`, which now reports `isBrownout=true` render-only but is byte-identical).

## Risks & Considerations
1. **Lockstep mirror (top risk):** brownout compute + flag threading mirrored in PlayScreen & Simulation; mitigated by routing application through shared `Combat.step`/`Scanning.resolve` and brownout being a no-op at full power.
2. **Byte-identity** rests on: protected set never shed (fuel path untouched), sheddable defaults 0 + existing fixtures don't fire/scan, `@EncodeDefault(NEVER)`. QA's `PlaythroughFixtureTest` is the proof.
3. **AC#4:** HELM/thrust intentionally never reduced (no-deadlock) — visible brownout effects are WEAPONS/SCANNER suppression; "reduced thrust" deliberately not implemented.
4. No schema bump (transient). minSdk-24 safe (pure math, no java.time/UPSERT).

Gate: `./gradlew :core:ktlintCheck :core:test`. Challenger approved revision 2. Ready for developer + QA.

## Challenger verdict
**APPROVED** after one revision round. Caught one Major: the original determinism argument falsely claimed no fixture over-draws power — `UC07_THIRSTY_POWER` draws 10 u/s thrusting vs output 2.0. Byte-identity re-grounded on the correct invariant (protected base+thrust never shed → fuel path untouched; new sheddable draws default 0; UC07 never fires/scans; `@EncodeDefault(NEVER)` omits new DTO fields). Key honored constraints: lockstep PlayScreen↔Simulation using the same `thrusting` bool; budget-only sheddable draws (not fuel burn); AC#5 fixture sheddable-driven; HELM never reduced.
