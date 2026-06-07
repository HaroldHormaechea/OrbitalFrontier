# ADR 0004 — Inter-sector travel: fixed jump gates

- **Status:** Accepted (confirmed by the project owner 2026-06-07)
- **Date:** 2026-06-07
- **Related:** [world-and-sector.md](../design/world-and-sector.md); PROJECT_BRIEF.md → core_gameplay_loop (Roam)

## Context

The MVP world is **3 small sectors** (~30s to cross) that exist specifically to build and
test inter-sector travel. The owner's reference is the X-series "stations between the
gates" model, and **jump points** are already a planned POI. We need to choose how the
player moves between sectors.

## Options considered

| Option | For | Against |
|---|---|---|
| **Fixed jump points / gates** | Fly into a fixed gate → arrive at the linked gate in the destination sector. Predictable, easily-testable fixed topology (a small graph); matches the X-series feel; trivial to reason about for the 3-sector MVP. | Less freeform than a drive; topology is authored. |
| Jump points + fuel/charge cost | Ties travel into the fuel economy; adds friction/strategy. | Extra balancing; not needed to prove the MVP loop. |
| Free jump drive (from anywhere) | Most freedom. | Hardest to balance/test; weakens the "stations between gates" structure and the authored sector topology. |

## Decision

Use **fixed jump points / gates**. Each sector has gates at fixed locations; each gate
links to a gate in an adjacent sector, forming a **fixed graph** across the 3 MVP sectors.
Flying into a gate transports the ship to its linked gate in the destination sector, where
it arrives at that gate's position. **No fuel cost for the MVP** (the soft-fuel system
affects in-sector max speed, not gating jumps).

## Consequences

- The 3-sector MVP topology is a small, deterministic **graph of gate links** — simple to
  author, persist, and test (validates the jump loop end-to-end).
- **Gates are persisted world objects** (part of sector layout / world state in the save).
- Jump = despawn from origin sector, load destination sector, place ship at the linked
  gate; transition handling (loading, brief animation) is an implementation detail.
- **Extensible:** a fuel/charge cost or a separate jump drive can be layered on later
  without changing the fixed-topology model (revisit via a new ADR if pursued).
