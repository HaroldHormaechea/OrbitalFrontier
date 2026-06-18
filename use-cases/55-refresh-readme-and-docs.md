# Use Case 55: Refresh stale README & status docs

## Summary
Correct documentation that misrepresents the project's state. The README still claims "The first vertical slice is implemented (use-case 01) … Missions, upgrades, and the rest of the loop are not built yet", calls the action cluster "a non-functional placeholder", and lists station-building as a non-goal — all wildly out of date now that UC01–27 are `done` (missions UC12, combat UC13, factions UC14, station-building UC15, the action arc UC26, design-system art UC27 all shipped). Rewrite the README and any status-bearing docs to honestly reflect the implemented state and the genuine remaining gaps (the candidate use cases in this set), without overclaiming polish that isn't there.

## Acceptance Criteria
1. The README's "implemented / not built" claims match reality (UC01–27 done), and the obsolete "first vertical slice only" / "action cluster is a placeholder" / "station-building is a non-goal" statements are removed or corrected.
2. A "Known limitations / not yet built" section honestly lists the real remaining gaps (placeholder art/font, no audio, deferred systems) rather than implying completeness.
3. The docs/design README index and any per-note `Status` lines that are stale are reconciled with shipped use cases.
4. No marketing inflation — the README stays honest about pre-alpha readiness (use the write-readme guidance: no empty badges, no fake quick-start, no premature roadmap).
5. Links and build/run instructions in the README are valid against the current Gradle setup.

## Potential Pitfalls & Open Questions
- **Edge case** — don't overcorrect into claiming polish that isn't done (art/audio/UI are still placeholder); describe state accurately.
- **Dependency** — this should be refreshed again after the readiness UCs land; it is a point-in-time correction now.
- **Note** — this is documentation-only; no gameplay code changes.

## Original Description
Autonomously captured from the implementation analysis — README.md and several doc status lines describe an early state that is no longer true (UC02–27 are all done).
