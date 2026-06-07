package com.orbitalfrontier.playthrough

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Parameterized verification test driven by the `playtest` skill (UC02 AC#9/#10).
 *
 * Replays whichever playthrough is named on the command line:
 *
 * ```
 * ./gradlew :core:test --tests *NamedPlaythroughReplayTest -Dplaythrough.name=<name>
 * ```
 *
 * It uses JUnit's [assumeTrue] on the `playthrough.name` system property, so a plain
 * `./gradlew :core:test` **skips** it (no name supplied) while the feature-specific replay tests
 * always run. The assertions are intentionally generic — they apply to *any* named artifact: the
 * replay completes, spans the full tick range, and is deterministic. Feature-specific state
 * assertions belong in that feature's own replay test (see docs/PLAYTESTING.md). The class name and
 * the property are referenced by `.claude/skills/playtest/SKILL.md` and docs/PLAYTESTING.md.
 */
class NamedPlaythroughReplayTest {
    @Test
    fun `named playthrough replays headlessly and deterministically`() {
        val name = System.getProperty("playthrough.name")
        assumeTrue(
            "pass -Dplaythrough.name=<name> (a file under core/src/test/resources/playthroughs/) to run",
            !name.isNullOrBlank(),
        )

        val playthrough = PlaythroughResources.load(name)

        val first = ReplayRunner().run(playthrough, capturePerTickStates = true)
        val second = ReplayRunner().run(playthrough, capturePerTickStates = true)

        // The replay spanned the whole recording (one snapshot per tick plus the initial state)...
        assertEquals(playthrough.tickCount + 1, first.perTickStates.size)
        assertEquals(playthrough.tickCount, first.finalState.tick)
        // ...and is deterministic: a second replay reproduces it exactly.
        SnapshotAssertions.assertStatesExactlyEqual(first.finalState, second.finalState)
        assertEquals(first.perTickStates, second.perTickStates)
    }
}
