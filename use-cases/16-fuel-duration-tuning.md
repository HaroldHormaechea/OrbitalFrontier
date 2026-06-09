# Use Case 16: Fuel duration tuning (~30 min under propulsion)

## Summary
Re-tune fuel consumption so that continuous propulsion drains a full tank in roughly 30 minutes of real playtime, giving testers a usable window before refuelling becomes necessary. Rather than hand-setting every ship, pick one reference ship, calibrate its consumption rate so a full tank lasts ~30 minutes of sustained thrust, and scale all other ships' consumption from that reference (preserving their relative differences in tank size and thrust). This builds on the existing fuel & power system (UC07) and only changes consumption/balance constants — not the fuel mechanic itself. The target is approximate ("about half an hour"), so exactness is less important than landing in the right ballpark and being easy to re-tune later.

## Acceptance Criteria
1. A single reference ship is identified (documented in code/comment), and its fuel-consumption-under-thrust constant is set so a full tank is exhausted in approximately 30 minutes (±~10%) of continuous propulsion.
2. All other ships' consumption is derived proportionally from the reference rather than independently hand-tuned, so their relative fuel economy is preserved.
3. The change touches only consumption/balance values; the fuel depletion mechanic, refuel mechanic, and out-of-fuel behaviour are unchanged.
4. The "30 minutes of continuous thrust" figure is measured against real elapsed game time under sustained propulsion (verifiable via the deterministic replay harness, UC02, or an equivalent timed test), not merely asserted.
5. The tuning constants are centralised/easy to find so they can be re-adjusted without code archaeology.

## Potential Pitfalls & Open Questions
- **Edge case** — Ships with very different tank sizes/thrust may land far from 30 min once scaled from a single reference; the ~10% tolerance applies to the reference ship, others may legitimately differ.
- **Assumption** — "Under propulsion" means continuous forward thrust at the normal (non-boost) rate. If boost/afterburner consumes fuel at a different multiplier, it is scaled by the same factor, not separately re-tuned.
- **Risk** — Real-time 30-minute measurement is slow to test directly; prefer deriving the per-second consumption math and asserting on the computed rate, with at most one slower integration check.

## Original Description
"I need fuel to last for about half an hour under propulsion, so adjust consumption for all ships."

## Clarifications
- Q: For "~30 min under propulsion", per-ship tuning or scale from one reference?
  A: Scale from one reference ship (calibrate the reference to 30 min, scale others proportionally).
