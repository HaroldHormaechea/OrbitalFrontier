# Design Note — Save & Persistence

- **Status:** draft (not yet specified)
- **Last updated:** 2026-06-07
- **Related:** PROJECT_BRIEF.md → in_scope #5, data_stores, Development → migrations (save versioning)

## Summary

How game progress is saved and restored so it survives app restarts — the JSON-file/SharedPreferences approach and its versioning. _TODO._

## Summary of approach (from brief)

Per the brief: local JSON save (+ SharedPreferences), optional SQLite if state grows; a `saveVersion` field with forward-migration on load. This note expands the detail.

## Goals

- _TODO: reliable, corruption-resistant saves; forward-compatible across versions._

## Mechanics / ideas

_TODO: what is in the save (ship loadout, currency/inventory, missions, world discovery, settings); when it writes (event-driven per brief: mission complete, upgrade purchased); single slot vs. multiple._

## Player-facing behavior

_TODO: autosave indicator, new game/continue, (post-MVP) cloud save deferred._

## Data & state

_TODO: canonical schema of the save file; ownership shared across systems — keep it centralized._

## Dependencies & interactions

_TODO: every stateful system serializes through here; coordinate save schema changes with a migration._

## Open questions

_TODO: JSON only for MVP, or SQLite from the start? single vs. multiple save slots?_

## References

libGDX Preferences + JSON serialization; brief save-versioning note.
