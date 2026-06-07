package com.orbitalfrontier.playthrough

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards (and regenerates) every committed playthrough artifact (UC02 AC#7/#8; UC03 AC#9).
 *
 * Each fixture JSON is generated from its builder in [PlaythroughFixtures.ALL] and committed under
 * `core/src/test/resources/playthroughs/`. This keeps the artifacts **reproducible**: rather than
 * hand-editing JSON, you rebuild them from code. Two tests, both iterating every registered fixture:
 *
 *  - [`committed fixtures match the recorder-built playthroughs`] always runs and fails if any
 *    committed file drifts from its builder (e.g. someone edited the JSON by hand, or changed a
 *    builder — including the UC03 `currentSector` snapshot field — without regenerating). This is
 *    the diff-stability contract.
 *  - [`regenerate the committed fixtures`] is normally **skipped**; run it with `-Dfixture.regen=true`
 *    (or `FIXTURE_REGEN=true`) to (re)write every committed JSON from its builder after an intended
 *    change. Adding `currentSector` to [StateSnapshotDto] changes the on-disk text, so UC01's
 *    artifact must be regenerated through this path too.
 */
class PlaythroughFixtureTest {
    @Test
    fun `committed fixtures match the recorder-built playthroughs`() {
        for ((name, builder) in PlaythroughFixtures.ALL) {
            val committed = PlaythroughResources.load(name)
            val built = builder()

            assertEquals(
                "Committed playthroughs/$name.json is out of date; " +
                    "re-run this test class with -Dfixture.regen=true to regenerate it.",
                built,
                committed,
            )
            // The committed text is exactly what the codec emits for the builder (stable/diffable form).
            assertEquals(PlaythroughCodec.encode(built), readCommittedJson(name).trimEnd('\n'))
        }
    }

    @Test
    fun `regenerate the committed fixtures`() {
        // Accept either the system property (-Dfixture.regen=true, when the build forwards it) or the
        // FIXTURE_REGEN env var (always inherited by the forked test JVM) so regeneration is
        // reproducible regardless of how test-JVM system properties are wired.
        assumeTrue(
            "set -Dfixture.regen=true or FIXTURE_REGEN=true to (re)write the committed fixtures",
            System.getProperty("fixture.regen") == "true" || System.getenv("FIXTURE_REGEN") == "true",
        )
        for ((name, builder) in PlaythroughFixtures.ALL) {
            val file = fixtureFile(name)
            file.parentFile.mkdirs()
            file.writeText(PlaythroughCodec.encode(builder()) + "\n")
        }
    }

    private fun readCommittedJson(name: String): String =
        PlaythroughResources::class.java.classLoader
            .getResourceAsStream(PlaythroughResources.resourcePath(name))!!
            .bufferedReader()
            .use { it.readText() }

    private fun fixtureFile(name: String): File {
        val relative = "src/test/resources/${PlaythroughResources.resourcePath(name)}"
        // Gradle runs the Test task with the module dir as the working dir; fall back to a couple of
        // candidate roots so the regenerator also works if invoked from the repo root.
        val candidates = listOf(File(relative), File("core", relative), File("../core", relative))
        return candidates.firstOrNull { it.parentFile?.isDirectory == true } ?: candidates.first()
    }
}
