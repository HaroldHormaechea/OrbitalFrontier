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
| [0002](0002-persistence-sqlite-migrations.md) | Persistence: SQLite from the start with versioned migrations | Accepted |
| [0003](0003-persistence-access-layer-sqldelight.md) | Persistence access layer: SQLDelight | Accepted |
| [0004](0004-inter-sector-travel-fixed-gates.md) | Inter-sector travel: fixed jump gates | Accepted |
| [0005](0005-movement-integration.md) | Movement integration: pure velocity model + Box2D as integrator | Accepted |
| [0006](0006-determinism-and-playthrough-harness.md) | Determinism and the playthrough record/replay harness | Accepted |
| [0007](0007-trading-prices.md) | Trading model & fixed authored prices (reconstructed, not row-persisted) | Accepted |
| [0008](0008-fleet-and-outfitting-persistence.md) | Fleet & outfitting: derived stats, junkyard-as-kind, additive v7 persistence | Accepted |
| [0009](0009-scanning-and-hidden-contacts.md) | Active scanning & hidden contacts: shared Contact capability + additive v8 persistence | Accepted |

## Adding an ADR

1. Copy [`_TEMPLATE.md`](_TEMPLATE.md) to `NNNN-<slug>.md` using the next number.
2. Fill in Context / Options / Decision / Consequences; set `Status` and `Date`.
3. Add a row to the table above.
4. To change a past decision, create a new ADR and set the old one's status to
   `Superseded by ADR-NNNN`.
