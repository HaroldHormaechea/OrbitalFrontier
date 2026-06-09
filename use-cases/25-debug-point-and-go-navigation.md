# Use Case 25: Debug point-and-go navigation (debug builds only)

## Summary
A debug-only "point-and-go" navigation aid that lets a tester reposition the ship instantly by tapping a destination, bypassing the manual momentum-based joystick piloting. It exists to make on-device verification practical — reaching docking-gated screens (station hub, Buy Fuel, walk-around) and other destinations is otherwise hard to drive reliably with scripted touch input. In a debug build an on-screen debug toggle arms point-and-go; while armed, tapping the main flight view teleports the ship to the tapped target (a POI such as a station, or an arbitrary world point), zeroing its velocity and leaving it sensibly oriented. Arriving at/near a station simply places the ship in normal dock range, so the standard DOCK prompt appears as usual. The entire feature is gated on `BuildConfig.DEBUG` and must be completely absent/inert in release builds, with zero effect on release gameplay, controls, or the deterministic record/replay sim and autosave.

## Acceptance Criteria
1. In a debug build, an on-screen **debug toggle** arms/disarms point-and-go. It is visually distinct and defaults to **off** so it does not hijack normal taps until the tester arms it.
2. While armed (debug build), tapping the main flight view teleports the ship to the tapped destination: tapping a POI (e.g. a station) goes to/adjacent that POI; tapping empty space goes to that world point. On arrival the ship's velocity is zeroed and it is left stationary and sensibly oriented (the "teleport + face" behaviour).
3. Teleporting to/near a station leaves the ship in the normal dockable range so the existing DOCK prompt/flow appears — point-and-go does **not** auto-dock or open the hub itself.
4. The feature is gated on `BuildConfig.DEBUG`: in a release build the toggle and the tap-to-teleport behaviour are absent/inert, and release gameplay, controls, and tap handling are byte-for-byte unchanged. While the toggle is **off** in a debug build, normal taps (minimap zoom, HUD buttons, etc.) behave exactly as today.
5. Point-and-go is a manual debug action and does **not** perturb the deterministic record/replay harness or autosave correctness (it is not part of recorded inputs; teleports are not silently recorded as flight).
6. Tests verify: the debug gate (destination logic reachable in debug, the gating wired to `BuildConfig.DEBUG`), the destination-resolution logic (tap → world point; tap-on-POI → that POI's position/adjacent), and that arrival zeroes velocity. The actual on-device tap→teleport is GL-bound and verified live.

## Potential Pitfalls & Open Questions
- **Edge case** — Tap→world-coordinate mapping must account for the active camera/zoom of the main flight view. The minimap tap (UC23 zoom overlay) and HUD buttons must keep their behaviour; the arm-toggle is what prevents hijacking, so the toggle's armed state must be checked before treating a world-view tap as a teleport.
- **Assumption** — Point-and-go operates from the **main flight view** (not the minimap or the zoom overlay). Driving it from the zoom overlay is out of scope unless trivial.
- **Edge case** — Teleporting onto a station/obstacle: for a POI target, place the ship adjacent / in dock range (not overlapping); for free space, the exact tapped point.
- **Assumption** — "Face" means the ship is left stationary with a sensible heading (e.g. retained or toward the target); exact facing is a minor detail for the implementer.
- **Risk** — Pure, testable destination-resolution logic should be separated from the GL/input glue so the gate + resolution are unit-testable headlessly (the suite has no GL), mirroring the project's pure-model + source-guard pattern.

## Original Description
"Add a debug point and go method of navigation only available in debug mode and adjust your testing skill to use it." (Purpose: a debug-only tap-to-navigate aid so on-device verification can reach docking-gated screens and other destinations without fighting momentum-based manual piloting; the testing/emulator skill will be updated to use it.)

## Clarifications
- Q: Does "go" teleport instantly or auto-pilot to the point?
  A: Teleport + face — instant teleport, velocity zeroed, left sensibly oriented.
- Q: What can the tester point at?
  A: Both POIs and free space (tap a POI to go to it, or tap empty space to go to that world point).
- Q: How is debug mode gated, and how is point-and-go activated?
  A: `BuildConfig.DEBUG` (compiled out of release) plus an on-screen debug toggle that arms point-and-go so normal taps aren't hijacked.
- Q: On arriving at a station, what happens?
  A: Leave the ship in dock range; the normal DOCK prompt appears (no auto-dock).
