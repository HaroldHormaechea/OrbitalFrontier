#!/usr/bin/env bash
# Replay a named playthrough through the deterministic harness and report PASS/FAIL.
# Usage: .claude/skills/playtest/run-playtest.sh <name>
#   <name> maps to core/src/test/resources/playthroughs/<name>.json
# Run from the repository root (the directory containing ./gradlew).
set -euo pipefail

if [[ $# -ne 1 || -z "${1:-}" ]]; then
  echo "usage: $0 <playthrough-name>" >&2
  echo "available:" >&2
  ls core/src/test/resources/playthroughs/*.json 2>/dev/null \
    | xargs -n1 basename 2>/dev/null | sed 's/\.json$//' >&2 || true
  exit 2
fi

name="$1"

# Project toolchain (this ai-sandbox session). Respect any value already exported.
export JAVA_HOME="${JAVA_HOME:-/workspace/environment-utilities/java/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/environment-utilities/android/sdk}"

if ./gradlew :core:test --tests '*NamedPlaythroughReplayTest' \
      -Dplaythrough.name="$name" --console=plain; then
  echo "PASS: $name"
else
  status=$?
  echo "FAIL: $name (gradle exit $status)" >&2
  echo "See core/build/reports/tests/test/index.html for details." >&2
  exit "$status"
fi
