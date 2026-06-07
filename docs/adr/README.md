# Architecture Decision Records

A dated, numbered log of significant technical decisions for Orbital Frontier and
the reasoning behind them. ADRs are append-only history: when a decision changes,
add a **new** ADR that supersedes the old one rather than rewriting it.

An `Accepted` ADR is binding unless a later ADR supersedes it. `PROJECT_BRIEF.md`
still wins on any conflict — keep them in sync.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-engine-choice.md) | Game engine / framework choice (libGDX + Kotlin) | Accepted |

## Adding an ADR

1. Copy [`_TEMPLATE.md`](_TEMPLATE.md) to `NNNN-<slug>.md` using the next number.
2. Fill in Context / Options / Decision / Consequences; set `Status` and `Date`.
3. Add a row to the table above.
4. To change a past decision, create a new ADR and set the old one's status to
   `Superseded by ADR-NNNN`.
