# Coding Guidelines

**Status: binding.** All production code in Orbital Frontier MUST follow these guidelines.
They are referenced authoritatively from `PROJECT_BRIEF.md` → Quality & Standards and are
enforced in review (the dev-team's challenger/qa roles check adherence on every change). If
a guideline ever conflicts with `PROJECT_BRIEF.md`, the brief wins — surface the conflict
rather than silently choosing.

These are **architectural** conventions; they complement (do not replace) the formatting/
style baseline (Kotlin official style + ktlint + Android Lint) already in the brief.

## SOLID principles (required)

All code is standardized on **SOLID**. Each principle, with how it applies here:

### S — Single Responsibility Principle
A class/file/module has **one reason to change**. Keep game systems separate: movement,
missions, economy, upgrades, persistence, rendering each own their logic in focused types —
no "god" classes that mix unrelated concerns. A method does one thing; extract when it
grows multiple responsibilities.

### O — Open/Closed Principle
Types are **open for extension, closed for modification**. Add behavior by adding new
types, not by editing a growing `when`/`if` ladder. Example: new **mission types** or
**upgrade categories** should plug in via an abstraction (interface / `sealed` hierarchy /
strategy), not by modifying a central switch every time. Prefer `sealed` classes/interfaces
for closed sets and polymorphism for open ones.

### L — Liskov Substitution Principle
Subtypes must be **substitutable** for their base type without breaking caller
expectations. Don't override a method to throw "not supported" or weaken a contract. Prefer
**composition over inheritance**; use inheritance only for genuine is-a relationships.

### I — Interface Segregation Principle
Prefer **small, focused interfaces** over fat ones. A consumer should not depend on methods
it doesn't use. Split capabilities (e.g. a thing that is `Damageable` vs. `Fuelled` vs.
`Persistable`) instead of one broad interface every entity must implement.

### D — Dependency Inversion Principle
High-level game logic depends on **abstractions**, not concretions; concrete details are
**injected**. This is already load-bearing in the architecture:
- **Persistence** ([ADR 0003](adr/0003-persistence-access-layer-sqldelight.md)): `core`
  depends on a persistence abstraction; the `SqlDriver` (Android vs. JDBC/in-memory) is
  **injected per platform**. This is exactly DIP and is what keeps `core` JVM-testable
  ([ADR 0001](adr/0001-engine-choice.md)).
- Apply the same shape to other platform/engine touchpoints (input, audio, clock,
  randomness): define the interface in `core`, inject the implementation.

## Supporting conventions

- **`core` stays pure & testable.** No Android-SDK or libGDX-platform types leaking into
  game-logic classes that should be unit-testable on the JVM (per ADR 0001). Side effects
  (I/O, rendering, system clock, RNG) come in through injected abstractions.
- **Favor immutability.** Prefer `val` over `var` and immutable data (`data class`,
  read-only collections) for state that doesn't need to mutate; isolate mutable state.
- **Constructor injection** for dependencies (no service-locator/global singletons for
  game systems) — keeps DIP testable and dependencies explicit.
- **Small units, clear names.** Functions and classes small enough to reason about; names
  describe intent. Match the surrounding code's idiom.
- **No premature abstraction.** SOLID is about managing change, not adding layers for their
  own sake. Introduce an abstraction when there's a real second implementation or a
  testability/seam need — not speculatively.

## Enforcement

- **Reviewed every change** by the dev-team (challenger raises SOLID/architecture
  violations; qa verifies testability follows from DIP).
- **Tests prove DIP:** if a unit can't be tested on the JVM without the Android SDK/engine,
  that's a DIP/SRP smell to fix, not to work around.
- Formatting/linting (ktlint, Android Lint) runs in CI per the brief; these guidelines
  cover what linters can't (design/architecture).

## Error handling (required)

- **Log every error.** All caught exceptions and error conditions MUST be **logged with
  context** — no silent catches, no empty `catch` blocks, no swallowing. Use the project
  logging (libGDX/Android logcat per the brief's observability), at an appropriate level,
  with enough context to diagnose.
- **Prefer unchecked exceptions.** Kotlin has no checked exceptions by design — lean into
  that; don't reintroduce checked-exception ceremony. Use **unchecked exceptions for
  exceptional/unexpected conditions and invariant violations** (fail fast on programmer
  errors). For *expected, recoverable* outcomes (validation, "not found", insufficient
  funds/fuel), prefer **explicit return types** (`Result` / a `sealed` result) over using
  exceptions as control flow.
- **Catch at the right boundary.** Don't catch what you can't handle — let it propagate to
  a layer that can log and decide (e.g. a system/game-loop boundary). Catch narrowly,
  **log**, and **degrade gracefully** where possible (mirrors the in-game "never stranded"
  philosophy: avoid hard fail states the player can't recover from).
- **Transactional, corruption-safe saves.** All save writes MUST be **atomic /
  transactional** (a SQLite transaction via SQLDelight — see
  [ADR 0002](adr/0002-persistence-sqlite-migrations.md) /
  [ADR 0003](adr/0003-persistence-access-layer-sqldelight.md) and
  [save-and-persistence.md](design/save-and-persistence.md)) so that a failure **rolls
  back** and **never leaves a partially-written or corrupt save**. A failed autosave must
  leave the **last good save intact** and be **logged**; never overwrite a good save with a
  half-written one. Use write-then-swap / backup-before-migrate for schema migrations.
- **Don't crash on recoverable errors.** Surface user-facing failures gracefully rather
  than crashing the app; genuine crashes are captured by Play Console vitals (brief
  observability).

## Open / to extend

This document currently codifies **SOLID**, the supporting conventions, and
**error handling**. Additional standards (logging conventions detail, package/module
structure, naming specifics, concurrency rules) can be appended here as they are decided.
