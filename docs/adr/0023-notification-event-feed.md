# ADR 0023 — In-game notification / event feed

- **Status:** Accepted
- **Date:** 2026-06-18

## Context

Discrete gameplay moments — a completed jump (UC03), docking/undocking (UC05), the mission life-cycle
(UC12), the combat boundary (UC13), low fuel (UC07), credit gains/losses (UC08+) — were already detected at
well-defined seams in `PlayScreen`, where they drive autosaves, the audio cues (UC31/[ADR 0020](0020-audio-system.md))
and log lines. But none of them were **shown to the player**: the only on-screen surface was the UC26
single-line context readout, and everything else lived in logcat. For an `mvp` vertical slice that is a
feedback gap — the player can dock, complete a mission or lose a courier and never see acknowledgement.

UC-35 adds a **transient toast feed**. Four forces make it a decision worth recording:

1. **Where the cues are classified.** The brief's binding **SOLID** principle and the ADR 0001
   JVM-testability constraint push the *what* (which events toast, their severity, how they coalesce) into a
   pure, libGDX-free model — mirroring how [`audio/Sfx`](../../core/src/main/kotlin/com/orbitalfrontier/audio/Sfx.kt)
   already separates cue meaning from the engine-bound player.
2. **Flood defense.** Combat (UC13) emits per-tick events; an unfiltered feed would become a wall of
   per-shot toasts (the UC-35 "bursty events" pitfall). A burst of credit changes or several couriers
   lapsing together has the same shape.
3. **Placement.** AC#4 requires the toasts never obstruct the action arc (UC26), the minimap (UC22) or the
   HUD readouts (UC34) — all of which already own reserved screen regions.
4. **Scope.** AC names transient toasts as the MVP and a persistent scrollable feed as optional.

## Options considered

| Option | For | Against |
|---|---|---|
| **Pure `notify` model + event-driven enqueue from the existing seams; transient toasts only (chosen)** | Reuses the proven `Sfx` split — a pure catalogue/factory/queue scanned by a purity guard, plus a thin GL renderer; the feed is driven by the same gameplay seams as audio so it stays consistent with the sim (AC#3) and never polled; the queue is a JVM-testable value machine (AC#5). | Two new small layers (pure + render) and a handful of enqueue sites to maintain in lock-step with the audio seams. |
| Poll game state each frame and diff for changes | No new event wiring | Polling drifts from the simulation (AC#3 explicitly forbids it), re-derives state the seams already know, and has to reverse-engineer "what changed" — fragile and duplicative. |
| Render toasts straight from libGDX in `PlayScreen` with no pure model | Fewer files | Puts cue classification + coalescing in engine-bound code — untestable headlessly, violates the ADR 0001 boundary the audio system established, and the purity guard would have nothing to protect. |
| Build the persistent scrollable feed now | Richer history | Out of the AC's MVP scope; more UI surface + a persistence question (is history saved?) for a feature the toasts already cover. Deferred. |

## Decision

Add a **pure `com.orbitalfrontier.notify` package** plus a thin render layer, fed from the existing seams.

- **Pure model (no libGDX, purity-guarded).** `NotificationSeverity` (INFO/WARNING), `NotificationKind`
  (one per AC#1 event family, each carrying a default severity + a `coalescable` flag), the immutable
  `GameNotification` value, a `GameNotifications` factory (a builder per family + `creditDelta(old,new)` +
  `forCombatEvent(event)` — a direct mirror of `Sfx.forCombatEvent` with an exhaustive `when`), a
  `NotificationPolicy` of tunables, and the `NotificationQueue` state machine. The whole package is scanned
  wholesale by a UC-35 purity guard, exactly like the `audio` package (AC#3).

- **Two-level flood defense.** *Layer 1 (upstream):* every per-tick combat event maps to `null` in
  `GameNotifications.forCombatEvent`, so per-shot events never reach the queue — only the encounter boundary
  toasts (entered/left combat). *Layer 2 (in the queue):* `enqueue` **coalesces** a `coalescable` kind into
  an existing live entry sharing its `coalesceKey` within `coalesceWindowSeconds` by **refreshing** it
  (adopt the newest content, reset its display clock) rather than appending — the drop/refresh knob — and a
  `maxQueued` cap bounds the worst case. Discrete one-shots (jump, dock, mission accept/complete) are not
  coalescable and stack as distinct entries.

- **Non-overlapping, auto-dismissing display (AC#2).** Only the first `maxVisible` entries are visible and
  only those age; the rest wait their turn and begin their clock once promoted. `update(dt)` is called
  **only inside the gated sim advance**, so toasts age and dismiss in sim time and freeze under the pause
  (UC32) / destruction (UC33) screens exactly like the rest of the sim. Entered-combat has no event in the
  `CombatEvent` hierarchy, so the screen detects it on the combat-active rising edge (a prev-flag),
  symmetric with the left-combat toast that flows through `forCombatEvent(EncounterCleared)`.

- **Credit chokepoint.** A private `PlayScreen.applyCreditChange(new)` diffs old→new and enqueues
  `creditDelta`, and every credit-mutation site (trade, hire, outfit, ship buy, fuel buy, mission
  reward/penalty, courier timeout, station build) routes through it — one place to keep gain/loss surfacing
  consistent.

- **Render layer (GL-bound).** `NotificationLayout` is pure world-unit geometry (mirrors `HudLayout`/
  `MinimapLayout`): a **top-centre band stacking downward**, threaded through the clear gap to the right of
  the top-left HUD block and left of the top-right minimap (a `RIGHT_RESERVED` reservation), starting below
  the UC32 pause button (`TOP_INSET`). The bottom-corner action arc (UC26) is tall enough at the 960×540
  floor that its top edge reaches *up into* the lower toast rows, so a row whose bottom dips below the arc
  top narrows its right edge to clear the worst-case right-handed arc (the left-handed arc sits left of the
  band already) — per-row clearance, not "by construction". At the smallest viewport the toast width shrinks
  to the available band rather than overrunning a neighbour (AC#4).
  `NotificationRenderer` mirrors `HudRenderer` — draws the `visible()` snapshot tinted by severity from the
  design-system `Palette`, suppressed under any full-screen modal (map/pause/destruction).

- **Transient MVP; persistent feed deferred.** Toasts are not persisted (combat-transient, like the
  encounter state of ADR 0012); a scrollable recent-events log remains an explicitly deferred follow-up.

## Consequences

- The feed is consistent with the simulation by construction (event-driven, advanced only when the sim
  advances) and headlessly testable: a JVM test drives events through `NotificationQueue` and asserts
  `visible()` (AC#5), with no GL context. The purity guard makes the engine-free boundary self-enforcing.
- A new gameplay event needs a `NotificationKind` + a factory builder + one enqueue at its seam; a new
  `CombatEvent` subtype forces a deliberate decision in `forCombatEvent` (exhaustive `when`, no `else`).
- The drop/refresh coalescing knob shows one persistent toast through a burst with no occurrence count; if a
  count ("×N") is later wanted, it is a localized change to the queue entry and renderer, no model reshape.
- Credit toasts enqueued by docked services (the player is on a hub screen) surface when the play screen is
  next shown (on undock), bounded by `maxQueued` — acceptable for the MVP.
- Reversibility is cheap: the model is additive and self-contained; widening to a persistent feed reuses the
  same `GameNotification` values and would add storage + a second view, not change the toast path.
