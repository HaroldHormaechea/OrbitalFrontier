package com.orbitalfrontier.playthrough

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards (and regenerates) the committed `uc01-thrust-north` playthrough artifact (UC02 AC#7/#8).
 *
 * The fixture JSON is generated from [PlaythroughFixtures.uc01ThrustNorth] and committed under
 * `core/src/test/resources/playthroughs/`. This keeps the artifact **reproducible**: rather than
 * hand-editing JSON, you rebuild it from code. Two tests:
 *
 *  - [`committed fixture matches the recorder-built playthrough`] always runs and fails if the
 *    committed file drifts from the builder (e.g. someone edited the JSON by hand, or changed the
 *    builder without regenerating). This is the diff-stability contract.
 *  - [`regenerate the committed fixture`] is normally **skipped**; run it with `-Dfixture.regen=true`
 *    to (re)write the committed JSON from the builder after an intended change.
 */
class PlaythroughFixtureTest {
    @Test
    fun `committed fixture matches the recorder-built playthrough`() {
        val committed = PlaythroughResources.load(PlaythroughFixtures.UC01_THRUST_NORTH)
        val built = PlaythroughFixtures.uc01ThrustNorth()

        assertEquals(
            "Committed playthroughs/${PlaythroughFixtures.UC01_THRUST_NORTH}.json is out of date; " +
                "re-run this test class with -Dfixture.regen=true to regenerate it.",
            built,
            committed,
        )
        // The committed text is exactly what the codec emits for the builder (stable/diffable form).
        assertEquals(PlaythroughCodec.encode(built), readCommittedJson().trimEnd('\n'))
    }

    @Test
    fun `regenerate the committed fixture`() {
        // Accept either the system property (-Dfixture.regen=true, when the build forwards it) or the
        // FIXTURE_REGEN env var (always inherited by the forked test JVM) so regeneration is
        // reproducible regardless of how test-JVM system properties are wired.
        assumeTrue(
            "set -Dfixture.regen=true or FIXTURE_REGEN=true to (re)write the committed fixture",
            System.getProperty("fixture.regen") == "true" || System.getenv("FIXTURE_REGEN") == "true",
        )
        val file = fixtureFile()
        file.parentFile.mkdirs()
        file.writeText(PlaythroughCodec.encode(PlaythroughFixtures.uc01ThrustNorth()) + "\n")
    }

    private fun readCommittedJson(): String =
        PlaythroughResources::class.java.classLoader
            .getResourceAsStream(PlaythroughResources.resourcePath(PlaythroughFixtures.UC01_THRUST_NORTH))!!
            .bufferedReader()
            .use { it.readText() }

    private fun fixtureFile(): File {
        val relative =
            "src/test/resources/${PlaythroughResources.resourcePath(PlaythroughFixtures.UC01_THRUST_NORTH)}"
        // Gradle runs the Test task with the module dir as the working dir; fall back to a couple of
        // candidate roots so the regenerator also works if invoked from the repo root.
        val candidates = listOf(File(relative), File("core", relative), File("../core", relative))
        return candidates.firstOrNull { it.parentFile?.isDirectory == true } ?: candidates.first()
    }
}
