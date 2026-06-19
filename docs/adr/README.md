# Architecture Decision Records

A dated, numbered log of significant technical decisions for Orbital Frontier and
the reasoning behind them. ADRs are append-only history: when a decision changes,
add a **new** ADR that supersedes the old one rather than rewriting it.

An `Accepted` ADR is binding unless a later ADR supersedes it. `PROJECT_BRIEF.md`
still wins on any conflict â keep them in sync.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-engine-choice.md) | Game engine / framework choice (libGDX + Kotlin) | Accepted |
| [0002](0002-persistence-sqlite-migrations.md) | Persistence: SQLite from the start with versioned migrations | Accepted |
| [0003](0003-persistence-access-layer-sqldelight.md) | Persistence access layer: SQLDelight | Accepted |
| [0004](0004-inter-sector-travel-fixed-gates.md) | Inter-sector travel: fixed jump gates | Accepted |
| [0005](0005-movement-integration.md) | Movement integration: pure velocity model + Box2D as integrator | Accepted |
| [0006](0006-determinism-and-playthrough-harness.md) | Determinism and the playthrough record/replay harness | Accepted |
| [0007](0007-trading-prices.md) | Trading model & fixed authored prices (reconstructed, not row-persisted) | Accepted |
| [0008](0008-fleet-and-outfitting-persistence.md) | Fleet & outfitting: derived stats, junkyard-as-kind, additive v7 persistence | Accepted |
| [0009](0009-scanning-and-hidden-contacts.md) | Active scanning & hidden contacts: shared Contact capability + additive v8 persistence | Accepted |
| [0010](0010-crew-and-turret-operability.md) | Crew: persisted per-ship count, derived capacity, one-time hire, turret operability as a pure flag | Accepted |
| [0011](0011-missions.md) | Missions: deterministic static-state instancing, regenerate-and-filter persistence, virtual courier parcel, tick-authoritative timer | Accepted |
| [0012](0012-real-time-combat.md) | Real-time combat: transient encounter state, durable per-ship damage, additive v11 persistence | Accepted |
| [0013](0013-factions-and-reputation.md) | Factions & reputation: action-driven standing, separate gate filter, additive v12 persistence | Accepted |
| [0014](0014-owned-stations.md) | Player-owned stations: player state in WorldState, station-build capability flag, additive v13 persistence | Accepted |
| [0015](0015-ui-scale-and-universal-object-glyphs.md) | UI Ã2 scale knob (UI/HUD only) + universal in-world object glyphs (exhaustive POI glyph resolver) | Accepted |
| [0016](0016-on-foot-station-mode.md) | On-foot station mode: transient non-persisted prototype, shop via existing TradeScreen | Accepted |
| [0017](0017-typography-and-scalable-font.md) | Typography: pre-baked bitmap game font (JetBrains Mono OFL), bake-time-only freetype, downscaled at runtime | Accepted |
| [0018](0018-ui-skin.md) | Finished UI skin: runtime palette-driven nine-patch chrome (rename PlaceholderControlsSkin → OrbitalUiSkin) | Accepted |
| [0019](0019-world-sprite-sizing.md) | Per-type world sprite sizing (WorldSpriteSizes single source of truth); no new art produced | Accepted |
| [0020](0020-audio-system.md) | Audio: event-driven AudioService port (pure core stays audio-free), libGDX impl in render/, looping thrust + added stopSfx, idempotent music, additive v14 settings migration, placeholder synthesised clips | Accepted |
| [0021](0021-pause-overlay.md) | In-flight pause overlay: pure PauseState gate freezes the per-frame advance (inverse of the LIVE map overlay), Android back → pause, held-input + THRUST neutralization, durable quit-to-main-menu | Accepted |
| [0022](0022-ship-destruction-screen.md) | Ship-destruction screen: respawn-with-penalty (no permadeath), pure DestructionSummary + DestructionState gate nested under pause, durable onCriticalEvent flush (no schema bump), game-start fallback respawn | Accepted |
| [0023](0023-notification-event-feed.md) | In-game notification feed: pure libGDX-free notify model (purity-guarded), two-level flood defense (per-tick combat→null + drop/refresh coalescing), event-driven enqueue from existing seams, top-centre toast band, transient-MVP / persistent-feed deferred | Accepted |
| [0024](0024-first-run-tutorial-onboarding.md) | First-run tutorial & onboarding: pure libGDX-free step machine observing existing event seams (determinism-safe), draw-only hint overlay with visual-only control highlight, cross-screen two-part copy, additive v15 settings flag, replayable from settings | Accepted |
| [0025](0025-settings-screen.md) | Full settings screen: one shared grouped SettingsPanel hosted by the in-flight overlay + a new main-menu screen; joystick sensitivity/deadzone tuning applied only at the joystick boundary (determinism-safe); UiScale promoted to a clamped mutable persisted control (rendering-only, on-rebuild HUD boundary); additive v16 settings migration; UC38/UC39 groups documented-omitted not faked | Accepted |
| [0026](0026-save-slots.md) | Save slots: `slot_id` partition column in the single DB (over per-slot files/blobs); first table-rebuild migration (v16→v17) backfilling the legacy save into slot 0; `meta.active_slot_id` pointer; minSdk-24-safe seed-then-targeted-UPDATE name-clobber guard (no UPSERT); injected wall-clock for last-saved; play-time on WorldState outside replay equality | Accepted (supersedes ADR 0002 in part) |

## Adding an ADR

1. Copy [`_TEMPLATE.md`](_TEMPLATE.md) to `NNNN-<slug>.md` using the next number.
2. Fill in Context / Options / Decision / Consequences; set `Status` and `Date`.
3. Add a row to the table above.
4. To change a past decision, create a new ADR and set the old one's status to
   `Superseded by ADR-NNNN`.
