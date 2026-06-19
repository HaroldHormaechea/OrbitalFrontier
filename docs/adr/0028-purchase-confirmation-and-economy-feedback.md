# ADR 0028 — Purchase confirmation gate, ERROR severity tier, and sells-not-gated

- **Status:** Accepted
- **Date:** 2026-06-19

## Context

UC-40 asks for three things across all five economy flows (Trade, Outfit, Shipyard, Hire, refuel):
a confirmation dialog before a significant spend, animated credit-change feedback, and a clear styled
error in place of the old bare status string. Today every economy action routes through a pure resolver
(`Trading`, `Outfitting`, `FleetResolver`, `Hiring`, `StationRefuel`) on `PlayScreen`, returns a short
status, and the screen shows it in a label — with no spend confirmation and no visible delta. The
notification layer from UC-35 (`notify` package + `render/NotificationRenderer`) already exists, is
engine-free (ADR 0001 JVM-testability; guarded by `NotifyPurityGuardTest`), and already enqueues a
`+N`/`-N CR` toast on every credit mutation via `PlayScreen.applyCreditChange`.

Three decisions in this slice were worth recording: **what counts as "significant"** (the confirm
threshold), **how a refusal is coloured** (a new severity), and **whether sells are confirmed**.

## Options considered

| Option | For | Against |
|---|---|---|
| **Single pure `PurchaseGate` + `[TUNE]` threshold constant** | One source of truth for "confirm vs. proceed vs. insufficient"; JVM-unit-testable; every screen consults it identically | One more pure type to maintain |
| Per-screen inline threshold checks | No new type | Re-derives the rule five times; drifts; untestable in isolation |
| **New `ERROR` severity tier (danger red) for refusals** | A rejection reads as visually distinct from a routine amber `CREDIT_LOSS` spend | One more enum value + a renderer branch |
| Reuse `WARNING` for refusals | No new tier | A refused buy would look identical to a normal spend — fails AC#3's "clear, styled error" |
| **Gate keyed on cost → sells bypass the dialog** | A sell has no spend to confirm; only the gain-delta feedback is meaningful | Asymmetric (buys confirm, sells don't) — must be documented |
| Confirm sells too | Symmetric | Pointless friction; the gate has no cost to evaluate for a gain |

## Decision

1. **Confirm threshold.** A spend of **`PurchaseGate.CONFIRMATION_THRESHOLD_CREDITS = 1000` credits** or
   more prompts a confirmation dialog; anything below proceeds silently. The constant is flagged `[TUNE]`
   and lives once, in the pure `economy/PurchaseConfirmation.kt`. The gate decides only the
   confirm-threshold + affordability axis; the resolvers remain the authority on whether an action
   actually happens and why not — a confirmed buy the resolver still no-ops falls through to the
   styled-error branch.

2. **New `ERROR` severity tier.** `NotificationSeverity` gains `ERROR`, rendered in `Palette.DANGER`
   (colourblind-aware red), used by two new kinds `INSUFFICIENT_CREDITS` and `ACTION_REJECTED`. A normal
   spend stays amber `WARNING` (`CREDIT_LOSS`); a refusal is red `ERROR`. The renderer must **not** reuse
   the warning colour for a refusal.

3. **Sells are not gated.** The gate keys on a known spend `cost`, so sells (and the hydrogen-conversion
   refuel, which costs no credits) bypass the confirm dialog and receive only the gain-delta feedback.
   Buying a ship and switching to it are separate taps today, so "confirm once per intent" holds without
   special multi-step handling.

Additionally, the UC-35 `NotificationQueue` is **hoisted to a single shared instance** constructed in
`OrbitalFrontierGame` and injected into `PlayScreen` plus all four economy desks and the hub, so the
credit delta a buy produces (and any styled error) surfaces on the desk the player is actually looking at.
The queue stays pure and single-render-thread-owned (only one screen renders at a time). The renderer
gains a fade-in/out + slight upward drift driven by a new pure `NotificationQueue.visibleWithProgress()`
life-fraction snapshot (`visible()` is kept intact for existing callers/tests).

## Consequences

- The threshold, the affordability rule, and the confirm/proceed/insufficient decision are testable
  headlessly in one place; tuning the threshold is a one-line change.
- The notification model stays engine-free (the new `ERROR` tier and life-fraction snapshot are pure;
  only `NotificationRenderer` binds them to a colour + animation), so `NotifyPurityGuardTest` still holds.
- A refused economy action now always surfaces a styled toast instead of a silent/bare-string no-op, and
  the same `+N`/`-N CR` flash appears on whichever economy surface is open — uniform across all five flows.
- The buy/sell asymmetry (sells skip the dialog) is intentional and recorded here so it is not mistaken
  for an omission; a future "confirm large sells" need would revisit this ADR.
- Reversibility is low-cost: the gate is a pure object, the severity is one enum value, and the shared
  queue is a constructor wire-up — none of it touches the resolvers or the save format.
