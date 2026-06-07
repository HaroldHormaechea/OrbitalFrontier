---
schema_version: 1
project:
  name: orbital-frontier
  maturity_target: mvp
stack:
  languages: [Kotlin]
  frameworks: [libGDX]
  runtimes: [mobile]
  versions: {kotlin: "2.0.21", gradle: "8.10", libgdx: "1.13.1", android_compile_sdk: "35", android_min_sdk: "24"}
  data_stores: ["local file/JSON save (SharedPreferences + JSON)", "SQLite (optional, for larger game state)"]
build:
  tool: gradle
  commands: {test: "./gradlew test", lint: "./gradlew lint", format: "./gradlew ktlintFormat"}
paths:
  production: ["core/src/main/**", "android/src/main/**"]
  test: ["core/src/test/**", "android/src/test/**", "android/src/androidTest/**"]
  api_boundary: []
test:
  framework: junit
  levels: [unit, integration]
  coverage_target: "60% on core game logic"
profiles: []
deployment:
  provider: google-play
  iac: none
  environments: [dev, internal-testing, production]
vcs:
  enabled: true
  already_initialized: false
  default_branch: main
  remote: git@github.com:HaroldHormaechea/OrbitalFrontier.git
use_cases:
  index: USE_CASES.md
  folder: use-cases/
---

# Project Brief

> **Decision-mode note:** This brief was scaffolded by the `project-builder` subagent, which cannot ask interactive questions. All choices below are derived from the pre-gathered context and explicitly-flagged sensible defaults. Items marked **[CONFIRM]** should be reviewed by you and changed via `/revise-brief` if they do not match your intent. The engine/framework choice in particular was made in "advise" mode — see the tradeoff table under **Technologies**.

## Overview

- **name:** Orbital Frontier (working title — folder `OrbitalFrontier`, frontmatter slug `orbital-frontier`). **[CONFIRM]** The Orbital/Frontier/Space-Frontier name space on Play is crowded; no exact "Orbital Frontier" collision found, but the store/display name may be revisited before release.
- **problem:** Mobile space-game players who want an RPG-style experience (ship ownership, upgrades, missions, progression) are mostly served either by shallow arcade shooters or by heavy, complex space sims. There is room for an approachable, focused 2D top-down space RPG that delivers a satisfying fly → mission → upgrade loop without overwhelming complexity.
- **users:**
  1. Casual-to-mid-core mobile gamers who enjoy space/sci-fi themes and light RPG progression, playing in short-to-medium sessions on a phone.
  2. Top-down shooter / roguelite fans looking for more persistent progression than typical arcade titles.
  3. The developer (you) — building it as a hobby/learning project with a public Play release as a goal. **[CONFIRM]** scope: treated as MVP for a wider community, not a commercial venture.
- **value_proposition:** An approachable, pick-up-and-play 2D top-down space RPG: own and upgrade a single ship, roam a sector, take on missions, and grow stronger — depth of progression without the steep learning curve of full space sims.
- **core_gameplay_loop:** The game is built around a single non-linear, free-form loop the player repeats and deepens over time. This is the design north star — features are judged by how well they serve it, and it is registered here in the brief (not as a single use case) because it is the persistent backbone of the whole project, implemented incrementally rather than all at once:
  1. **Roam** — fly the ship freely through a 2D top-down sector; no forced path or linear campaign. The player chooses where to go and what to pursue.
  2. **Earn** — take on missions and/or gather resources (combat bounties, delivery/escort, mining/salvage, exploring points of interest). Multiple viable playstyles, not one prescribed route.
  3. **Improve** — spend earned currency/resources/XP to upgrade and customize the ship (and, post-MVP, build/expand a personal space station/base).
  4. **Repeat, deeper** — a stronger ship/base unlocks harder, more rewarding content, feeding back into roam → earn → improve.
  Individual use cases each implement a slice of this loop (e.g. "accept and complete a bounty mission", "purchase a ship upgrade"); the loop itself is the connective tissue between them.
- **maturity_target:** mvp — a playable vertical slice early adopters can install: the core loop (fly, take a mission, earn rewards, upgrade) plus a handful of missions and a working upgrade tree, with rough edges allowed.
- **in_scope (MVP):**
  1. Player-controlled single ship with 2D top-down movement and controls suited to touch.
  2. A mission/quest system (accept, perform, complete, reward) with a small starter set of mission types.
  3. Ship upgrade & progression system (currency/XP, an upgrade tree affecting ship stats).
  4. A roamable sector/space environment to fly through (points of interest, encounters).
  5. Save/load persistence so progress survives app restarts.
- **non_goals (MVP):**
  1. Multiplayer / online play of any kind (single-player only for the MVP).
  2. Space-station building — **stretch/maybe** feature, explicitly out of the MVP scope (tracked as a future use case).
  3. iOS / desktop / web ports — Android only for now (libGDX keeps the door open later).
  4. Monetization mechanics (IAP, ads) — not in the MVP.
  5. Procedurally infinite universe / large-scale economy simulation.
- **success_criteria:**
  - The full core loop is playable end-to-end on a real Android device: fly, accept a mission, complete it, earn rewards, spend them on an upgrade, and feel the difference.
  - Progress persists across app restarts.
  - At least one early tester (besides the developer) completes several missions and reports the loop is fun.
  - Builds and installs as a debug APK on a current Android device without crashing in a normal session.

## Reference Points & Inspiration

- **Primary reference — Starsector** (open-world, single-player, top-down 2D space RPG). It is the closest realization of Orbital Frontier's intended feel: free-form, non-linear play where the player roams a sector and chooses their own path (trader, bounty hunter, explorer, industrialist), customizes/upgrades their ship, and progresses through an emergent economy rather than a scripted campaign. Orbital Frontier targets the **same core fantasy and loop**, scaled down to an Android MVP (single ship focus, touch-first controls, a smaller hand-built sector instead of a full economy sim). When a design question is ambiguous, "what would feel right in the spirit of Starsector, simplified for mobile?" is the tie-breaker.
- **Secondary references:**
  - **Naev** — open-source 2D action/RPG space game; useful as a *readable, studyable* implementation of mission/faction/upgrade systems.
  - **Star Valor** — top-down single-player space RPG with a tight roam → fight/mission → upgrade loop; another close design analog.
- **Mobile-market comparables (the actual competitive field on Android):** Galaxy Genome (mine + bounty missions, Newtonian ship control), Space RPG 3 / 4 (premium single-player; salvage, surveying, player colonies), SpaceCrafter RPG (offline). These confirm the niche exists on Android but is thinly served — a polished, focused take is an opportunity.
- **Scope guardrail:** these references inform *feel and systems*, not scale. Orbital Frontier deliberately stays single-player, offline, and Android-first (see non-goals); it borrows the loop and progression philosophy of the larger titles without their economy-sim or multiplayer complexity.

## Monetization

- **commercial_intent:** no (for the MVP). **[CONFIRM]** Framed as a hobby/community release. A monetization layer can be added later via `/revise-brief` if the project grows.
- **model:** none (free, no ads, no IAP in the MVP).
- **license:** **All Rights Reserved (proprietary).** A `LICENSE` file at the repo root declares copyright © 2026 Harold Hormaechea with all rights reserved. The source is published publicly for visibility/reference only — it is **not** open-source; no use, copying, modification, or distribution is permitted without express written permission.
- **target_market:** Casual/mid-core Android gamers interested in space/sci-fi top-down RPGs; global Play Store audience.
- **tiers:** none.
- **constraints:**
  - MVP commits to **no ads and no data resale** to keep the experience clean and avoid privacy/compliance overhead.
  - If any analytics are added later, they must be privacy-respecting and disclosed (Play Data Safety form).
  - A future commercial model (one-time purchase, cosmetic IAP, or rewarded ads) is deliberately deferred, not ruled out.

## Technologies

> **Engine choice — CONFIRMED: libGDX + Kotlin.** The scaffolder advised in "advise" mode and the owner confirmed **libGDX + Kotlin** (2026-06-07). The tradeoff table below is retained for the record. Changing the engine later would be a significant re-scaffold; it is now a settled decision (see ADR 0001, status Accepted).

### Engine/framework options considered (2D top-down, Android, missions + upgrades)

| Option | Language | Strengths for this game | Tradeoffs |
|---|---|---|---|
| **libGDX (recommended)** | Kotlin/Java | Mature, code-first 2D framework; excellent for top-down sprites, tilemaps, physics (Box2D); cross-platform later (desktop/iOS) from one codebase; great for upgrade/mission systems written in real code; testable game logic on the JVM. | No visual editor (code + asset pipeline); you build/choose your own ECS and tooling. |
| Godot 4 | GDScript / C# | Free/open-source, full editor, scene system, strong 2D, exports to Android; fast iteration with a visual editor. | Separate engine/runtime (not pure Android-native); GDScript is engine-specific; integrates less cleanly with a pure Gradle/JUnit workflow. |
| Native Kotlin + Compose/Canvas | Kotlin | Pure Android-native, smallest footprint, full Jetpack ecosystem, easiest CI on a stock Android toolchain. | You hand-roll the game loop, rendering, and physics; least game-oriented of the options; scaling to richer 2D gets laborious. |
| Unity | C# | Industry standard, huge asset store, strongest tooling/visual editor, easy Android export. | Heavy runtime, licensing/splash considerations, C#/.NET toolchain outside a clean Gradle flow; overkill for a focused 2D RPG. |
| Flutter + Flame | Dart | Single codebase, good UI tooling, Flame is a capable 2D engine. | Dart ecosystem for games is smaller; less proven for action-y top-down gameplay; another toolchain to learn. |

**Why libGDX is the default:** it is the best fit for a code-driven 2D top-down RPG with custom mission/upgrade systems, stays on a Gradle/JUnit toolchain (so the dev-team's `./gradlew test` flow and unit-testable game logic work out of the box), and keeps a future cross-platform port open without committing to a heavy engine now.

- **constraints:** Must run on Android phones. Engine choice was open ("advise"). No hard forbidden tools stated.
- **runtimes:** mobile (Android). Cross-platform (desktop/iOS) deliberately left open for later via libGDX's backends.
- **languages:** Kotlin (primary). Confirmed.
- **frameworks:** libGDX (game framework), Box2D (via libGDX, optional, for movement/collision), Android SDK. Confirmed.
- **data_stores:**
  - `{engine: "JSON file + SharedPreferences", purpose: "save/load game state, settings"}` — simplest fit for single-player MVP.
  - `{engine: "SQLite (optional)", purpose: "structured game state if save data grows (missions, inventory)"}` — deferred until needed.
- **auth_strategy:** none — fully offline single-player MVP; no accounts.
- **external_services:** none for the MVP. (Play Console for distribution; optional Play Games Services / cloud save deferred.)
- **ai_ml_dependency:** none. (Enemy/NPC behavior is rule-based game AI, not ML.)

## Architecture

- **platforms:** Android (mobile) only for the MVP. libGDX keeps desktop/iOS available later but they are non-goals now.
- **service_shape:** Single client-side application (no backend). Organized as a **modular libGDX project**: a platform-agnostic `core` module holding all game logic and rendering, and an `android` launcher module. This mirrors libGDX's standard multi-module layout and keeps game logic JVM-testable independent of Android.
- **components:**
  - `{name: "core", runtime: "JVM/Android", responsibility: "game loop, screens, entities, mission system, upgrade/progression system, save serialization, rendering"}`
  - `{name: "android", runtime: "Android", responsibility: "Android launcher Activity, app manifest, platform integration, asset packaging, APK build"}`
  - `{name: "assets", runtime: "n/a", responsibility: "sprites, tilemaps, audio, fonts, packed atlases"}`
- **communication:** In-process only — no network. `{from: "android launcher", to: "core game", protocol: "in-process (libGDX ApplicationListener)"}`.
- **async_workloads:** None beyond the in-engine game loop and frame updates. No servers, queues, or schedulers.
- **integrations:** None for MVP. (Future: Play Games Services for achievements/cloud save — deferred.)
- **data_flow_narrative:** Touch input → core game updates entity/world state each frame → state rendered to the screen via libGDX. On meaningful events (mission complete, upgrade purchased) the game serializes state to a local JSON save; on launch the core reads that save (or starts a new game). All data stays on-device.
- **trust_boundaries:** Single-user, on-device, offline app. The only external input is local touch input; the only persisted data is the local save file (no sensitive/PII data, no network egress). Nothing leaves the device in the MVP.
- **multi_tenancy:** not applicable (single-player, single-device).

## Documentation & References

The target project keeps reference documentation under [`docs/`](docs/) that agents
and contributors should consult when implementing features. The brief remains the
single source of truth for structured/contractual fields; these docs hold the
reasoning and design detail behind them.

- **Design notes — [`docs/design/`](docs/design/)** (index: `docs/design/README.md`):
  internal notes on how each game system should work (ship & controls, world & sector,
  missions, combat, economy & resources, upgrades & progression, save & persistence,
  and the post-MVP station-building stretch). These are **advisory intent**, not a
  contract — when a design note conflicts with this brief, the brief wins and the
  agent surfaces the conflict rather than choosing silently. A note marked
  `draft (not yet specified)` means the design is still open: propose or ask, do not
  invent silently. Template: `docs/design/_TEMPLATE.md`.
- **Architecture Decision Records — [`docs/adr/`](docs/adr/)** (index: `docs/adr/README.md`):
  the dated log of significant technical decisions (starting with ADR 0001, the
  libGDX engine choice). An `Accepted` ADR is **binding** unless superseded by a later
  ADR; decisions change by adding a new ADR, never by rewriting an old one. Template:
  `docs/adr/_TEMPLATE.md`.

Agents (analyst, challenger, developer, qa) should read the relevant design note and
any in-force ADRs before implementing a system, and should propose updates (a new ADR,
or filling in a draft design note) rather than leaving design decisions implicit in code.

## Quality & Standards

- **style_guide:** Kotlin official style guide (Android Kotlin conventions).
- **linters_formatters:**
  - Kotlin: **ktlint** (lint + format) via Gradle (`ktlintCheck` / `ktlintFormat`). **[CONFIRM]** detekt can be added for deeper static analysis.
  - Android: Android Lint (`./gradlew lint`).
- **testing:**
  - levels: unit, integration. (No e2e/instrumented suite required for MVP, but the `android/src/androidTest/**` scope exists for optional instrumented tests.)
  - coverage_target: ~60% on core game logic (mission system, progression math, save/load round-trips). UI/rendering excluded from the target.
  - frameworks: JUnit (JVM unit tests of `core` logic), optional Espresso/AndroidJUnit for instrumented tests later.
- **security:**
  - Gradle dependency review / version catalog to keep libs current.
  - No secrets in the MVP (no network, no keys). The release signing keystore (when created) stays out of git via `.gitignore`.
  - No threat-model document for the MVP (offline single-player, minimal attack surface) — **[CONFIRM]**; add one if scope grows to networking/IAP.
- **accessibility_target:** No formal WCAG target (native game UI, not web). Aim for legible text, adequate touch-target sizes, and colorblind-friendly cues as a best-effort guideline. **[CONFIRM]**
- **performance_budgets:**
  - Target a smooth **60 FPS** on a mid-range Android phone during normal play. **[CONFIRM]** numbers.
  - Keep frame-time stable (avoid GC stalls); reasonable APK size; fast cold start (< a few seconds to playable).
- **documentation:** README plus lightweight Markdown ADRs for notable decisions (e.g., the engine choice). Full docs site not needed.
- **observability:** Logs only (libGDX/Android logcat) for the MVP. No metrics/tracing (offline app). Optional crash reporting (e.g., Play Console vitals / Crashlytics) deferred.

## Profiles

None

## Deployment

### Production

- **hosting:** Google Play Store distribution (app store, not a server). No backend hosting required.
- **cloud:** none (no server infrastructure). Google Play Console is the distribution channel.
- **iac:** none — app distribution, not provisioned infrastructure.
- **ci_cd:** GitHub Actions **[CONFIRM]** — build the debug APK and run `./gradlew test` + `ktlintCheck` on push/PR; a release/signed-bundle (AAB) workflow can be added when nearing a store release.
- **environments:** dev (local), internal-testing (Play internal testing track), production (Play production track).
- **secrets:** Release signing keystore + Play upload credentials kept out of git (gitignored / CI secrets) — only needed at release time, not for the MVP.
- **observability:** Google Play Console vitals (crashes/ANRs) once published; logcat locally.
- **dr:** none required (no server state); player progress is local. Future cloud save (Play Games Services) would add device-loss recovery — deferred.

### Development

- **environment:** Native toolchain — Android Studio / Gradle with the Android SDK. (Note: this ai-sandbox session bakes the Android SDK and can boot a KVM emulator via the `android-emulator-setup` skill for testing.)
- **containerization:** not used (Android/Gradle native toolchain).
- **hot_reload:** Standard Gradle incremental builds + Android Studio instant run; libGDX desktop launcher can be added later for fast iteration without an emulator (deferred — Android-only scaffold for now).
- **seed_data:** A new-game default state in code; optionally a small set of fixture mission definitions. No external seed pipeline.
- **migrations:** Save-file versioning handled in the serializer (a `saveVersion` field with forward-migration on load); no database migration tool for the MVP.

## Scaffolding Plan

Structure-only scaffold for a libGDX (Kotlin) Android game using the standard `core` + `android` multi-module layout. No dependencies are installed, no builds are run. Build files are minimal placeholders that establish the module shape; exact dependency/plugin versions should be finalized when first opened in Android Studio (pinned versions in frontmatter are current-as-of-scaffold references, **[CONFIRM]**).

### Directories

- `core/src/main/kotlin/com/orbitalfrontier/` — platform-agnostic game code (entry point, screens, systems).
- `core/src/main/kotlin/com/orbitalfrontier/screens/` — game screens (menu, play).
- `core/src/main/kotlin/com/orbitalfrontier/entity/` — ship and world entities.
- `core/src/main/kotlin/com/orbitalfrontier/mission/` — mission/quest system.
- `core/src/main/kotlin/com/orbitalfrontier/progression/` — upgrades/progression.
- `core/src/main/kotlin/com/orbitalfrontier/save/` — save/load serialization.
- `core/src/test/kotlin/com/orbitalfrontier/` — JVM unit tests for core logic.
- `android/src/main/kotlin/com/orbitalfrontier/android/` — Android launcher.
- `android/src/main/res/values/` — Android resources (strings).
- `android/src/test/kotlin/com/orbitalfrontier/android/` — Android-side unit tests.
- `android/src/androidTest/kotlin/com/orbitalfrontier/android/` — instrumented tests (optional, placeholder kept).
- `assets/` — game assets (sprites, tilemaps, audio, fonts).
- `docs/adr/` — lightweight Markdown ADRs.
- `use-cases/` — formalized use-case files (managed by `define-use-case`).

### Files

- `settings.gradle.kts` — Gradle root settings declaring `core` and `android` modules.
- `build.gradle.kts` — root Gradle build (shared config placeholder).
- `gradle.properties` — Gradle/Android build flags placeholder.
- `core/build.gradle.kts` — core (Kotlin JVM) module build placeholder.
- `android/build.gradle.kts` — Android application module build placeholder.
- `android/src/main/AndroidManifest.xml` — Android manifest with launcher activity.
- `android/src/main/res/values/strings.xml` — app display name resource.
- `core/src/main/kotlin/com/orbitalfrontier/OrbitalFrontierGame.kt` — libGDX ApplicationListener stub (game entry point).
- `core/src/main/kotlin/com/orbitalfrontier/screens/.gitkeep`, `entity/.gitkeep`, `mission/.gitkeep`, `progression/.gitkeep`, `save/.gitkeep` — keep empty package dirs tracked.
- `core/src/test/kotlin/com/orbitalfrontier/.gitkeep` — keep test dir tracked.
- `android/src/main/kotlin/com/orbitalfrontier/android/AndroidLauncher.kt` — Android launcher Activity stub.
- `android/src/test/kotlin/com/orbitalfrontier/android/.gitkeep`, `android/src/androidTest/kotlin/com/orbitalfrontier/android/.gitkeep` — keep test dirs tracked.
- `assets/.gitkeep` — keep assets dir tracked.
- `docs/adr/0001-engine-choice.md` — ADR recording the libGDX engine decision and alternatives considered.
- `use-cases/.gitkeep` — keep use-case dir tracked.
- `README.md` — project README (generated from this brief).
- `.gitignore` — ignore build outputs, IDE files, local Gradle, keystores, local props.

### Commands (in order)

1. `mkdir -p` (single call) for every directory above.
2. `Write` each file listed above.
3. `git -C /workspace/OrbitalFrontier init -b main`
4. (No remote provided — skip `git remote add`. No `git add`/`commit`/`push` — first commit is yours.)

