# Use Case 31: Audio system — SFX & music

## Summary
Add an **audio layer** to the game. Today there is **zero audio** anywhere in `core` or `android` — no `Sound`, `Music`, or asset references. Introduce a libGDX-based audio system with sound effects for the core gameplay events (thrust, weapon fire, hit/explosion, mining, docking, jump, UI taps, mission accept/complete) and ambient/background music, plus a master **mute** toggle and independent SFX/music volume that persist via settings. Audio playback must be driven from the same event seams the HUD/notifications use so it stays deterministic-core-friendly (the pure simulation emits events; the platform layer plays sounds).

## Acceptance Criteria
1. An audio service plays SFX for at least: thrust, weapon fire, enemy hit/destruction, mining tick, dock, jump-gate transition, UI tap, and mission accept/complete.
2. Background/ambient music plays during flight and can be a different track at stations.
3. Master mute plus separate SFX and music volume controls exist and persist across launches.
4. Audio is triggered from gameplay events emitted by the pure core, not embedded inside simulation logic (the deterministic core stays audio-free and JVM-testable).
5. `./gradlew :core:ktlintCheck :core:test` green; the replay harness still runs headless with no audio device.

## Potential Pitfalls & Open Questions
- **Missing input** — actual audio assets (formats/licensing) must be sourced; this UC assumes placeholder or licensed clips are provided.
- **Edge case** — Android audio focus / interruption (calls, headphones) and app background/foreground must pause/resume cleanly.
- **Risk** — keep the core pure: routing audio through the sim would break determinism and the replay tests.

## Original Description
Autonomously captured — all three analysis passes independently flagged the complete absence of audio as a pre-alpha readiness gap.
