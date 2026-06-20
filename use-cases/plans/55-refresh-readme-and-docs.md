---
plan_for: use-cases/55-refresh-readme-and-docs.md
work_branch: feat/uc-55-refresh-readme-docs
team: orbital-frontier-uc-55
approved: 2026-06-20
---

# UC-55 — Refresh stale README & status docs — FINAL APPROVED PROPOSAL (analyst + challenger agreed)

Documentation-only. Source of truth = `USE_CASES.md` ledger (UC-01…UC-54 all `done`) + ADRs (through 0042), NOT the stale use-case text. Verified against ledger, ADRs, and actual assets/build.

### Analysis — stale claims to fix
**README.md** (false under-claims):
- "The **planned** MVP core loop is:" — reframe to current state.
- Big slice paragraph: "first vertical slice is implemented (UC01) … Missions, upgrades, and the rest of the loop are not built yet" → FALSE.
- Known-limitations: "Only the first slice exists … No missions, economy, upgrades, combat, fuel, other entities, or jump gates yet" → FALSE.
- "the action cluster is a **non-functional placeholder**" → FALSE (functional since UC26).
- Non-goals: "no space-station building (deferred stretch)" → FALSE (UC15 sim+persistence + UC51 build UI/world-surfacing/dock-to-use shipped).

**docs/design/README.md** index Status cells: Combat "deferred (real-time decided)" → implemented (UC13 + UC41–44; shields deferred); Station Building "UI/world-surfacing deferred" → built (UC15+UC51; defense/passive-income/teardown/respawn deferred); Missions wording reconciled.

**docs/design/missions.md** Status line "combat missions still deferred" → implemented (UC41/ADR0029); bump Last updated to 2026-06-20.

(All other design-note Status lines already current. ADR index append-only & current — no ADR edits.)

### Proposed Solution
**README.md — honest rewrite** (apply `write-readme` guidance: no marketing adjectives, no empty badges, no fake quick-start, no premature roadmap, no emoji):
1. Keep title + one honest one-liner.
2. Replace planned-loop framing with **current state**: single-player, offline, Android 2D top-down space RPG, **pre-alpha** (maturity `mvp`, NOT production). Full roam→earn→improve→repeat loop is playable: fly through sectors with jump gates (procedural generation available); earn via missions (mining/courier/bounty), real-time combat, mining, salvage, trading; improve via upgrades/outfitting, multiple ships, crew, factions/reputation, player-built stations; autosave to on-device SQLite with multi-slot management. UI: settings, pause, game-over, expanded + combat HUD, notification feed, tutorial, accessibility.
3. **Keep** Requirements block (verified accurate: AGP 8.6.1, Kotlin 2.0.21, libGDX 1.13.1, SQLDelight 2.0.2, Gradle 8.10, JDK17).
4. **Keep** Quick start (all 4 invocations verified valid).
5. Keep Project layout.
6. Replace Known-limitations with HONEST list (no overclaim):
   - Pre-alpha: not on a wide device matrix; balance/[TUNE] values provisional.
   - **Art**: committed design-system texture atlas (UC27, `assets/orbital.png`/`.atlas`) serves as MVP art; some newer systems/POIs reuse existing atlas regions rather than bespoke sprites (ADR 0019/0042); a higher-fidelity bespoke art pass remains future work.
   - **Audio**: present (UC31/ADR0020) but **placeholder synthesised clips**, not final SFX/music. (Drop old "no audio" claim.)
   - Font (UC28/ADR0017) + UI skin (UC29/ADR0018) are real — do NOT list as missing.
   - Deferred per ADRs: combat shields; power capacitor + reactor-upgrade category; crew skills/desertion/debt; station defense/passive income/crew-staffing/teardown/respawn; generational save-backup history; encounter/bounty content in procedurally-generated sectors.
   - Genuine **non-goals** (keep): multiplayer/online; monetization (ads/IAP); iOS/desktop/web ports. **Remove station-building from non-goals.**
   - Keep working-title caveat.
7. Add short **Documentation** pointer to `docs/` (design notes + ADRs) + `PROJECT_BRIEF.md` as contract. Keep License.
8. Action cluster: describe as functional, **cite no ADR** (none exists; UC26 ledger is authority).

**docs/design/README.md** — update the 3 stale index Status cells only; leave layout intact.
**docs/design/missions.md** — correct Status line + bump Last updated.

Point-in-time correction only: no wholesale design-note rewrites, ADRs append-only, NO gameplay code.

### Files Affected
- **Production code (developer)** — docs only: `README.md`, `docs/design/README.md`, `docs/design/missions.md`.
- **Test code (qa)** — verification only: run gate `./gradlew :core:ktlintCheck :core:test` (trivially green — no code change); verify all README links resolve **including the new `docs/` Documentation pointer**; verify the 4 quick-start gradle invocations name real tasks/modules; grep rewritten README for residual false claims: "first vertical slice", "not built yet", "non-functional placeholder", "no audio", station-building-as-non-goal.

### Risks & Considerations
- Use-case text itself is stale ("UC01–27", "no audio") — resolved against ledger + ADRs. AC#2's "no audio" example is obsolete; treated as built-but-placeholder.
- Over-correction guarded both ways: art/audio are placeholder/atlas-reuse, font/skin are real — neither under- nor over-claimed.
- Honesty: maturity `mvp` / pre-alpha — no production/marketing claims, no badges, no roadmap.
- Scope discipline: README + 2 design-doc Status edits; ADRs append-only; no code.

Challenger approved (one revision: art bullet wording — incorporated). Ready for developer.
