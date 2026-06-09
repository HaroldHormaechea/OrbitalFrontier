# Use Case 18: Fix broken station refuelling

## Summary
Station refuelling is currently unreliable: sometimes pressing refuel at a station does nothing at all, and other times it only adds about 20 units of fuel instead of filling the tank (or the intended/affordable amount). This is a bug fix on the stations/docking (UC05) + fuel (UC07) integration. The dev-team must reproduce the intermittent behaviour, find the root cause (likely a state/quantity or transaction bug — e.g. a hardcoded/partial amount, a race between credit deduction and fuel addition, or a stale fuel-capacity read), and make refuelling deterministic and correct: a refuel purchase adds the expected fuel and deducts the corresponding credits, every time.

## Acceptance Criteria
1. Refuelling at a station reliably adds fuel on every invocation — the "nothing happens" case no longer occurs.
2. Refuelling fills to the intended amount (up to tank capacity, bounded by available credits / price), never a spurious fixed partial amount such as ~20 units.
3. Credits deducted always match the fuel actually added at the station's fuel price (no charge-without-fuel and no fuel-without-charge).
4. Attempting to refuel when already full, when broke, or when fuel is unavailable produces correct, clear behaviour (no-op with feedback) rather than a silent or partial failure.
5. A regression test reproduces the prior intermittent/partial-refuel condition and now passes deterministically (via the replay harness or a unit/integration test on the refuel transaction).

## Potential Pitfalls & Open Questions
- **Ambiguity** — Root cause is unknown; "20 units" suggests either a hardcoded increment, a per-tick refuel loop that runs once, or a unit/capacity mismatch. The fix must address the actual cause, not just clamp symptoms.
- **Edge case** — Partial refuel when the player can only afford some fuel must still be supported and correct; "fill" means "as much as capacity AND credits allow".
- **Risk** — Intermittency hints at order-of-operations or timing (e.g. UI event firing before/after a state update). Verify the fix removes the non-determinism, not just the average outcome.

## Original Description
"Refuel in stations is broken." (Symptom on clarification: "sometimes nothing happens. Other times I get refueled 20 units only.")

## Clarifications
- Q: What's the observed symptom of the refuel bug?
  A: Intermittent — sometimes nothing happens; other times it only refuels ~20 units. (Diagnose root cause; AC = refuel works correctly and deterministically end-to-end.)
