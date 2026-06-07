# Documentation

Reference documentation for Orbital Frontier. These docs capture **intent** —
game-system ideas and architecture decisions — so they can be consulted by the
developer and by the dev-team agents (analyst, challenger, developer, qa) when
implementing features. `PROJECT_BRIEF.md` points here; the brief stays the
single source of truth for contractual/structured fields, while these docs hold
the reasoning and design detail behind them.

## Layout

| Folder / file | Purpose | Start here |
|---|---|---|
| [`design/`](design/) | Internal design notes: how each game system should work (ideas, mechanics, open questions). Living documents, may be incomplete. | [design/README.md](design/README.md) |
| [`adr/`](adr/) | Architecture Decision Records: a dated, numbered log of significant technical decisions and why they were made. | [adr/README.md](adr/README.md) |
| [`coding-guidelines.md`](coding-guidelines.md) | **Binding** coding standards (SOLID + supporting conventions). Required for all production code; enforced in review. | [coding-guidelines.md](coding-guidelines.md) |
| [`PLAYTESTING.md`](PLAYTESTING.md) | How to record & replay deterministic playthroughs for testing (the UC02 harness, in the core test source set). Every gameplay UC's QA adds a playthrough + replay test. | [PLAYTESTING.md](PLAYTESTING.md) |

## How agents should use these

- **Design notes** are *advisory intent*, not a contract. When a design note and
  `PROJECT_BRIEF.md` conflict, the brief wins — surface the conflict rather than
  silently choosing. A note marked `draft` / `not yet specified` means the
  decision is still open: ask or propose, don't invent silently.
- **ADRs** record decisions that are in force. Treat an `Accepted` ADR as binding
  unless a later ADR supersedes it. Propose a new ADR (don't edit an old one's
  decision) when a decision changes.
- **Coding guidelines** (`coding-guidelines.md`) are **binding standards** — all
  production code must follow them (SOLID + supporting conventions). Violations are
  review-blocking. The brief still wins on any direct conflict.

## How to add a doc

- New design note: copy [`design/_TEMPLATE.md`](design/_TEMPLATE.md) to
  `design/<system>.md`, fill it in, and add a row to `design/README.md`.
- New ADR: copy [`adr/_TEMPLATE.md`](adr/_TEMPLATE.md) to
  `adr/NNNN-<slug>.md` (next number), fill it in, and add a row to `adr/README.md`.
