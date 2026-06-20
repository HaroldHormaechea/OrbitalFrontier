# ADR 0037 — Power brownout: rate-based budget with protected-floor priority shedding

- **Status:** Accepted
- **Date:** 2026-06-20

## Context

UC07 shipped a pure power model (`PowerModel.drawAt`/`status`, `PowerParams`) whose reactor
output was **uncapped**: total module draw could exceed reactor output with no consequence.
Both `PROJECT_BRIEF.md` (Technologies → data_stores/power) and
[`docs/design/power-and-energy.md`](../design/power-and-energy.md) flagged the **power budget
cap** and the **player-facing power UI** as deferred/open. UC49 completes the model and surfaces
it (AC#1–#5).

The design note left "pool vs. rate", capacitor buffering, and manual-vs-automatic allocation
open. The use case's own guidance defaults to **rate-based with automatic priority shedding**,
and calls out two hard constraints: the model must stay **pure/deterministic** (it runs inside the
record/replay harness, ADR 0006) and brownout **must never deadlock the ship** (always leave it the
power to fly).

The fuel economy is already tuned: `PowerParams` base/thrust draws realise the UC16 25/75 split and
the ~30-minute reference-tank duration. Any new power demand must not perturb fuel burn, and no
recorded playthrough fixture may need regeneration.

## Options considered

| Option | For | Against |
|---|---|---|
| **Rate-based budget, automatic priority shedding, protected HELM floor (chosen)** | Pure/deterministic; no new state or schema; no-deadlock by construction (protected set never shed); sheddable draws kept out of fuel burn → zero fixture regen | No burst smoothing (capacitor); "throttle" expressed as on/off shedding, not continuous scaling |
| Manual power allocation (FTL/X-series) | Deep, player-driven | Heavy UI; not mobile-legible (brief: "not a spreadsheet"); over-scoped for MVP |
| Continuous output scaling (slow ship under load) | Smooth degradation | Risks bricking the ship (deadlock pitfall); couples power into movement math + fuel tuning |
| Capacitor/battery buffer now | Smooths weapon/jump spikes (AC#2) | Adds transient runtime state + a charge model; AC#2 is explicitly conditional — deferred |

## Decision

A pure, rate-based **power budget** resolved per tick by `power/Brownout.resolve(thrusting, params)`,
returning a transient `BrownoutResult` (`poweredSystems`, `shedSystems`, `isBrownout`, plus the
budget figures the HUD reads).

- **Systems & priority** (`power/PowerSystem`): `HELM` (protected) < `SCANNER` < `WEAPONS` by
  `shedPriority`. `HELM` stands for thrust/helm + the always-on base hotel load.
- **Protected floor / no-deadlock (AC#4):** the protected set is **never shed**. Even in the
  degenerate case where the protected draw alone exceeds reactor output, every sheddable system is
  shed, `isBrownout = true`, and the protected systems stay powered — the ship always keeps the
  power to fly. Thrust/helm is never reduced; visible brownout effects are WEAPONS/SCANNER
  suppression only.
- **Shedding:** if `protectedDraw + weaponsDraw + scannerDraw ≤ reactorOutput`, all powered and
  `isBrownout = false`. Otherwise shed sheddable systems ascending by priority (SCANNER, then
  WEAPONS) until protected + remaining ≤ output, or all sheddable are shed.
- **Budget-demand vs fuel-burn-demand divergence (the key invariant):** the new sheddable draws
  `PowerParams.weaponsDraw` / `scannerDraw` feed the **brownout budget ONLY**. They are deliberately
  NOT part of `PowerModel.drawAt`, so **fuel burn = base + thrust** (unchanged) while **budget demand
  = base + thrust + weapons + scanner**. This keeps the UC16 fuel tuning intact and is the reason the
  feature is fuel-neutral. Both draws default `0`.
- **Application via the shared seams (lockstep):** suppression is threaded into the existing pure
  functions, not duplicated — `Combat.step(…, weaponsPowered)` (a shed WEAPONS suppresses player
  fixed + turret fire; hostiles still fire) and `Scanning.resolve(…, scannerPowered)` (a shed SCANNER
  suppresses the scan). Both params default `true`. `PlayScreen` and the test-set `Simulation` compute
  the brownout from the **same** `thrusting` bool already fed to `FuelBurn.step` and pass the same
  flags — live == replay (project rule #1).
- **Surfacing (AC#3):** the pure engine-free `render/HudViewModel` gains `reactorOutput`,
  `powerDraw`, `brownout`, `shedSystems`; `HudRenderer` draws a `PWR <pct>%` line that turns amber
  and names the shed systems during brownout. Brownout/shed state is **render-only**.
- **No new state / no schema bump:** power is transient. Brownout state never enters
  `SimulationState` or a recorded artifact, so the save schema stays at v21. The
  `PowerParamsDto.weaponsDraw`/`scannerDraw` config fields are `@EncodeDefault(NEVER)` (omitted at the
  default 0), so every pre-UC49 fixture serialises byte-identically.

**Capacitor (AC#2) deferred:** AC#2 is conditional ("if the chosen model includes one"). The chosen
model does not; burst buffering is left to a future ADR.

## Consequences

- **Byte-identity holds** on the invariants above: protected base+thrust is never shed (fuel path
  untouched), sheddable draws default 0, existing fixtures never fire/scan under brownout, and the new
  DTO fields are omitted by default. The harness's `PlaythroughFixtureTest` regen-compare is the
  proof. Note `UC07_THIRSTY_POWER` (thrust draw ≫ output) now reports `isBrownout = true`
  **render-only** yet replays byte-for-byte (it never fires or scans, and brownout state isn't
  recorded).
- Fitting now has stakes: a future ship/upgrade that pins positive `weaponsDraw`/`scannerDraw` can
  exceed its reactor and brown out, shedding scanner before weapons — without touching fuel economy.
- Shedding is binary (on/off), not graded; "reduced thrust" from the use case is intentionally not
  implemented (it would risk deadlock). A capacitor and continuous throttle remain open for a later
  ADR if burst loads (weapons/jump) need smoothing.
- The model is reactor-output-vs-draw only; per-module reactor upgrades and a dedicated reactor
  category remain design-note open questions, unblocked by this ADR (they would feed `reactorOutput`
  and the sheddable draws).
