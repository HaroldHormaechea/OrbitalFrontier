# Use Case 44: Combat HUD — targeting, health bars & hit feedback

## Summary
Add combat feedback to the HUD. Today combat is **auto-aim only** (hold FIRE, turrets auto-target) with minimal feedback: no target reticle/lock indicator, no enemy health bars, and no hit/damage flashes beyond the section schematic. Add a **target-lock indicator** showing which hostile the turrets are engaging, **enemy health/hull bars** above hostiles, and **hit feedback** (flash/shake/particle) on both dealing and taking damage, so the player can read the fight.

## Acceptance Criteria
1. The currently auto-targeted hostile shows a target-lock indicator/reticle.
2. Hostiles display a health/hull bar reflecting their current integrity; the player ship shows hull/section status prominently during combat.
3. Hits register visible feedback (flash/particle/shake) for both damage dealt and damage taken, respecting the reduced-motion setting (UC39).
4. The combat HUD composes with the always-on FIRE arc button (UC26) and existing HUD (UC34) without overlap.
5. `./gradlew :core:ktlintCheck :core:test` green; targeting/health-bar state is unit-tested against the combat model.

## Potential Pitfalls & Open Questions
- **Decision** — auto-aim stays the MVP control (no manual aiming); this UC is feedback only. Player-designated targeting is a separate later feature.
- **Edge case** — multiple simultaneous hostiles: health bars must not clutter; cap or fade distant ones.
- **Dependency** — reduced-motion (UC39) gates the shake/flash intensity.

## Original Description
Autonomously captured from the UI/UX + capture analysis — combat proves only auto-aim destruction with no target lock, health bars, or hit feedback.
