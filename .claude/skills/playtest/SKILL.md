---
name: playtest
description: Verify Orbital Frontier by replaying a named, recorded playthrough headlessly on the JVM and reporting pass/fail. Runs the deterministic record/replay harness (UC02) via `./gradlew :core:test --tests *NamedPlaythroughReplayTest -Dplaythrough.name=<name>` with the project's JAVA_HOME/ANDROID_HOME, then reports the result from the Gradle exit code. Use when asked to "playtest", "replay a playthrough", "verify the game with playthrough <name>", or to confirm a gameplay change didn't regress a recorded scenario. Names map to core/src/test/resources/playthroughs/<name>.json.
---

# Playtest

Replay a **named playthrough** through the deterministic harness and report whether the game still
produces the expected end state. This is the runnable counterpart to `docs/PLAYTESTING.md`: a
playthrough is a committed JSON artifact (seed + fixed `dt` + tick-stamped inputs); replaying it on
the JVM is fast, headless, and deterministic, so it makes a good regression check after any gameplay
change.

## What a "name" is

A name identifies an artifact at `core/src/test/resources/playthroughs/<name>.json`. List the
available names with:

```bash
ls core/src/test/resources/playthroughs/*.json 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.json$//'
```

If the user didn't give a name and exactly one exists, use it; if several exist, ask which (or run
all of them, one invocation each). If none exist, tell the user to record one first (see
`docs/PLAYTESTING.md`).

## How to run it

Always export the project's toolchain env first (this sandbox's JDK + Android SDK), then invoke the
named-replay test for the chosen `<name>`:

```bash
export JAVA_HOME=/workspace/environment-utilities/java/jdk
export ANDROID_HOME=/workspace/environment-utilities/android/sdk
./gradlew :core:test --tests '*NamedPlaythroughReplayTest' -Dplaythrough.name=<name> --console=plain
```

A thin wrapper is provided so you don't have to remember the env or the task:

```bash
.claude/skills/playtest/run-playtest.sh <name>
```

Run it from the repository root (the directory containing `gradlew`).

## Reporting the result

Report strictly from the **Gradle exit code**, not from log scraping:

- **exit 0 → PASS** — the playthrough replayed and all assertions held. Report `PASS: <name>`.
- **non-zero → FAIL** — report `FAIL: <name>` and surface the failing assertion / stack trace lines
  from the Gradle output (and the HTML report path it prints, `core/build/reports/tests/...`).

Notes:

- The `NamedPlaythroughReplayTest` uses JUnit `Assume`, so a plain `./gradlew :core:test` skips it —
  it only runs when `-Dplaythrough.name=` is supplied. A "skipped/ignored" result therefore means
  the name wasn't passed or didn't resolve to an artifact; re-check the name.
- This skill only *verifies* with an existing artifact. Recording a new playthrough and writing the
  replay assertions is a code change — follow `docs/PLAYTESTING.md` (and, in a dev-team run, that's
  QA's job), not this skill.
- Replay targets the pure model, never on-device Box2D (ADR 0005/0006); it needs no emulator.
