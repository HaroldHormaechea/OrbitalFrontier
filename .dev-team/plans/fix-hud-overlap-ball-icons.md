---
plan_for: "(free-form task)"
work_branch: feat/fix-hud-overlap-ball-icons
team: orbital-frontier
approved: 2026-06-20
---

# Implementation Plan of Record (FINAL) — HUD overlap fix + Settings relocation + PAUSE removed + main-menu Settings bug

**Branch:** `feat/fix-hud-overlap-ball-icons` · **TARGET_DIR:** `/workspace/OrbitalFrontier` · work-in-place. **Profiles:** none.
**Status:** challenger-approved (v3) + both deferred items resolved by the user; PAUSE direction finalized to "not visible." Coordinates are world units (stage = ScreenViewport ÷ `UiScale.factor`, default 2); min supported viewport = **960×540 world**.

## User directives (resolved live)
- **Settings symptom = "nothing happens at all"** → confirms the save-gate root cause (§C/§4), not a fit-clip.
- **Bottom-right cluster = screenshot-first** → QA proved only the settings panel overlaps persistently → cluster DROPPED.
- **"PAUSE must not be visible"** → no on-screen PAUSE control at all; pause stays reachable via the device Back key (§3).

---
## Analysis

**A. HUD overlap (QA-confirmed).** The in-flight `SettingsOverlay` (240×250 ScrollPane of the FULL settings panel) shows persistently during flight in a top-left band only ~20 world units tall (between `HUD_BLOCK_HEIGHT`=192 and `bottomControlBand()`=328), overlapping BOTH the HUD readout block (`HudLayout.blockRect`) AND the bottom-left joystick. UC37 regression; no comprehensive pairwise overlap guard exists.

**B. PAUSE ↔ tutorial overlap (QA-confirmed).** At the 540 floor in the first-run tutorial, the top-center PAUSE button overlaps the tutorial hint band + SKIP STEP/SKIP ALL (a "known floor-only cosmetic" in `TutorialOverlayLayout`'s doc). **Resolved by construction below** — PAUSE is no longer drawn.

**C. Main-menu Settings (root cause CONFIRMED by user: "nothing happens at all," menu in save-unreadable/degraded state).** Main-menu SETTINGS is **gated on a readable save**: in degraded mode (`saveUnavailable != null`) `onOpenSettings` warn-and-returns instead of opening (OrbitalFrontierGame.kt:380–386). Enabler: `settingsRepository` is `lateinit` (137), set only in `wirePersistence` (275); `bootWithoutDatabase` (243–262) never sets it, so the gate avoids an uninitialized-lateinit crash. Settings share the save DB (ADR 0002), so an unopenable save = no settings store.

---
## Proposed Solution

### 1. Single source of truth + comprehensive guard
New pure `render/HudControlLayout.kt` returns world-unit rects for every DRAWN on-screen control given (vpWidth, vpHeight, handedness): joystick, action-arc footprint, HUD readout block, **top-left settings ball**, minimap. (No PAUSE — see §3.) `PlayScreen.layoutControls()` positions actors from it. New `screen/Uc56HudOverlapGuardTest.kt` asserts pairwise NON-overlap across the FULL set INCLUDING tutorial-active state — joystick, arc footprint, readout block, settings ball, minimap, **tutorial band (`TutorialOverlayLayout.bandRect`)**, **SKIP STEP + SKIP ALL**, **toast band (`NotificationLayout.toastRect` × maxVisible=3)** — at **960×540 and 1280×720, both handedness**, from the shared pure layouts/real constants (no mirrored literals).

### 2. Settings → top-left ball + dedicated in-flight modal
- Remove the persistent in-flight `SettingsOverlay` corner panel. New reusable `screen/controls/BallButton.kt` (circular icon + small-font label below); add a ball style + small-label style + SETTINGS glyph to `OrbitalUiSkin`.
- **Settings ball, 72², top-left corner: x[24,96] y[444,516]** (floor). Give `HudLayout.blockRect` a **left inset** so the readout block starts at x≈96 (block x[96,456]); ball/block edge-touch (no overlap). Clear of minimap and bottom band.
- **Settings ball visibility = same as the old in-flight overlay: hidden during combat / map / pause** — the combat ship-schematic is also top-left (`ShipSchematicRenderer`: left=MARGIN, top=vpHeight−96), so hiding the ball in combat avoids a new combat-state collision.
- **Modal semantics:** tapping the ball freezes the sim (reuses the existing pause/freeze primitive — no ADR 0006 impact) and shows the shared `SettingsPanel` as a dedicated modal (own backdrop + Close/Back → directly back to flight, auto-resume); pause-only actions (Resume/Save/Quit) NOT surfaced.

### 3. PAUSE — NOT VISIBLE (user override); Back-key pause already wired
- **Remove the on-screen PAUSE control completely** — delete the top-center PAUSE `TextButton`, its `ClickListener`, its `layoutControls()` placement, and its per-frame visibility line (PlayScreen.kt ≈543, 704–719, 1537–1540, 1407–1410). Nothing PAUSE-related is drawn on the HUD in any state. This removes the tutorial-state PAUSE overlap **by construction**.
- **Pause functionality is preserved with ZERO on-screen footprint — and it already exists.** The Android Back key is already caught and routed to the pause overlay (UC32): `PlayScreen` `show()` calls `Gdx.input.setCatchKey(Input.Keys.BACK, true)` (≈859) and an appended `InputAdapter.keyDown` (≈749–760) does: `pauseSettingsShown → exitPauseSettings()`, else `paused → resumeGame()`, else `openPause()`; the catch is released in `hide()` (≈2758). So Back already opens/closes the pause overlay holding **RESUME/SAVE/QUIT/SETTINGS** — no new pause trigger needs to be built. The developer's job is just to **remove the button and verify** the Back-key path still works without it (it is independent of the button).
- **One small integration point:** extend that Back `keyDown` precedence so the new in-flight **Settings-ball modal** also closes on Back — i.e. `settingsBallModalOpen → closeSettingsModal()` BEFORE the existing pause cases. Keeps Back = "close the topmost in-flight surface, else open pause."
- **Dropped vs the prior draft:** no PAUSE ball, no top-right placement, no hide-during-tutorial logic, and **no `NotificationLayout.RIGHT_RESERVED` change** (it was only to clear the pause ball — `NotificationLayout.kt` reverts to no change). PAUSE carries no rect in `HudControlLayout` or the guard.

### 4. Main-menu Settings save-gate fix (decouple global prefs from save state)
- New `core/.../save/DefaultSettingsRepository.kt` — tiny in-memory `SettingsRepository` (loads return `*.DEFAULT`/last-set-in-session; saves update an in-memory holder; `ensureInitialized()` no-op).
- In `bootWithoutDatabase` (~line 251): `settingsRepository = DefaultSettingsRepository()` + `handedness = settingsRepository.loadHandedness()`.
- **Ungate `onOpenSettings` (380–386): always `openSettings()`** (drop the `degraded` branch). `onLoadGame` KEEPS its degraded gate (Load needs the DB).
- Degraded-mode settings apply live via the global knobs; persistence is session-only there (honest limitation, not a crash) until a New Game creates a fresh DB.
- **Gated first step for the developer:** on the running emulator, **induce degraded mode** (corrupt/missing save), reproduce the silent no-op pre-fix, apply the fix, re-verify SETTINGS opens. `MainMenuScreen`/`SettingsScreen` max-UI-scale (3.0) fit-safety is a **secondary defensive** item (lightweight guard; completion NOT gated on it).

### 5. ⚠️ BINDING condition — renderer/reservation share ONE origin source
`HudRenderer` draws readout text at `x = MARGIN * uiScale` (≈24), independent of `blockRect`. If `blockRect` is inset alone, the guard goes GREEN while the drawn text still starts at x≈24 and visually collides with the settings ball. **HudRenderer's draw x-origin and the reservation rect MUST read one shared inset source** (draw from `blockRect.x` / a shared `BLOCK_X`); the guard asserts against that source, not a literal.

### 6. Excluded (with reason)
- **Bottom-right ball cluster** — dropped (QA: only the settings panel overlaps persistently).
- **Action-button label-over-own-icon** — intended icon+label ball pattern, not a cross-element overlap; optional small-label polish only if trivial.

---
## Files Affected

**Production — developer (core/src/main/kotlin/com/orbitalfrontier/):**
- `render/HudControlLayout.kt` — NEW (joystick, arc footprint, readout block, settings ball, minimap; NO pause).
- `screen/controls/BallButton.kt` — NEW (circular icon + small-label-below widget).
- `screen/controls/OrbitalUiSkin.kt` — ball style + small-label style + SETTINGS glyph (no PAUSE glyph needed).
- `render/HudLayout.kt` — left inset on `blockRect` (shared origin source, §5).
- `render/HudRenderer.kt` — draw x-origin from the SAME shared inset source (§5, BINDING).
- `screen/PlayScreen.kt` — drive `layoutControls()` from `HudControlLayout`; remove the persistent settings panel display; add top-left Settings ball (combat/map/pause-hidden) → freeze + dedicated modal; **remove the PAUSE `TextButton` entirely**; extend the existing BACK `keyDown` precedence to close the Settings-ball modal first; update the visibility block (≈1374, 1405–1411).
- `screen/SettingsOverlay.kt` — dedicated centered modal (not a corner panel).
- `app/OrbitalFrontierGame.kt` — init `DefaultSettingsRepository` + handedness in `bootWithoutDatabase` (~251); ungate `onOpenSettings` (380–386).
- `screen/SettingsScreen.kt` (+ `MainMenuScreen.kt`) — fit-safe layout (secondary defensive).
- `render/NotificationLayout.kt` — **no change** (the pause-ball reserve widening is dropped).

**Test — QA (core/src/test/kotlin/com/orbitalfrontier/):**
- `screen/Uc56HudOverlapGuardTest.kt` — NEW; pairwise set: joystick, arc footprint, readout block, settings ball, minimap + tutorial band + SKIP STEP + SKIP ALL + toast band (NO pause rect); 960×540 & 1280×720, both handedness; assert ball↔block clearance via the shared origin source (§5).
- A behavioural test that main-menu Settings opens in degraded mode (`DefaultSettingsRepository` round-trips defaults; the `onOpenSettings` gate is gone).
- A lightweight `MainMenuScreen`/`SettingsScreen` fit-safety guard (secondary).
- Update `Uc22MinimapTopRightGuardTest` / `Uc34ExpandedHudGuardTest` / `Uc37SettingsScreenGuardTest` (pin the old settings-panel placement + the `settingsOverlay.isVisible` string — expected, not a regression). **`Uc32PauseOverlayGuardTest`** — review/update for the removed on-screen pause button (the Back-key pause path stays; assert pause is reachable via Back, no on-screen pause control drawn). `Uc26ActionArcGuardTest` unaffected.
- Emulator: induce degraded mode for the Settings repro/fix/re-verify; screenshot normal + tutorial states for zero-overlap ground truth; confirm Back opens/closes pause (Resume/Save/Quit reachable) and the Settings ball opens its modal + closes on Back.

---
## Risks & Considerations
- **§5 shared-origin (top correctness risk):** without it the guard passes while reality overlaps.
- **Back-key is now the ONLY pause trigger** — verify it stays caught throughout flight (UC32 `setCatchKey` in `show`, released in `hide`) and that removing the button doesn't disturb it; ensure the Settings-modal-close precedence doesn't swallow the pause/resume cases. Android-only target, so acceptable.
- **Settings ball must hide in combat** (top-left shares with the combat ship-schematic) — preserves prior behavior.
- **Degraded-mode settings are session-only** (no DB) — acceptable/documented; optional future polish: flush in-memory settings into the fresh DB on New-Game recovery.
- Existing source-anchored guards (incl. `Uc32PauseOverlayGuardTest`) pin the old layout/pause button and must be updated alongside.
- Determinism: all new layout is pure/rendering-only; the in-flight settings modal freezes the sim — no ADR 0006 impact.

## Challenger verdict
**Approve** (v3) with the §5 shared-origin binding condition. Both deferred decisions (bottom-right cluster, PAUSE relocation) and the Settings root cause were resolved by the user: cluster dropped, PAUSE not visible (Back-key pause), Settings save-gate confirmed.
