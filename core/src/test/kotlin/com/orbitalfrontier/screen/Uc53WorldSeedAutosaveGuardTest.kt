package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** UC53 autosave-path criterion that lives in [PlayScreen].
 *
 * Why source-anchored, not behavioural: [PlayScreen] is a libGDX `ScreenAdapter` whose construction
 * pulls in Box2D (`ShipPhysics`) and a GL-backed `GameAssets`, so it is **not** headlessly
 * constructible (the same reason [Uc21MainMenuGuardTest] / [Uc20StationGridGuardTest] guard their
 * GL-bound ACs at the source level). The *persistence* half of AC#3 — that a world seed survives
 * save → reload and regenerates an identical world — is covered behaviourally in
 * [com.orbitalfrontier.save.SqlDelightGameStateRepositoryTest]; this guard pins the one structural
 * link that lives in the GL-bound screen: the autosave snapshot must carry the **session seed**.
 *
 * The defect this guards against (the Major the challenger flagged): if `currentWorldState()` rebuilt a
 * [com.orbitalfrontier.world.WorldState] without threading the seed, [com.orbitalfrontier.world.WorldState
 * .worldSeed] would default back to `WorldSeed.MVP` and every autosave would **silently revert the seed**
 * to the authored map — making AC#3 vacuous. So the screen must (1) hold the seed as a session field
 * sourced from the loaded `initialWorldState`, and (2) re-emit it in `currentWorldState()`.
 */
class Uc53WorldSeedAutosaveGuardTest {
    @Test
    fun `PlayScreen holds the session world seed sourced from the loaded initial state`() {
        val source = readSource("screen/PlayScreen.kt")
        assertTrue(
            "PlayScreen must hold the world seed as a session field seeded from initialWorldState.worldSeed " +
                "(else the autosave path has no seed to re-emit and AC#3 is vacuous)",
            Regex("""val\s+worldSeed\s*:\s*WorldSeed\s*=\s*initialWorldState\.worldSeed""").containsMatchIn(source),
        )
    }

    @Test
    fun `currentWorldState re-emits the session world seed onto the autosave snapshot`() {
        val source = readSource("screen/PlayScreen.kt")
        val body = section(source, "fun currentWorldState(")
        assertTrue(
            "currentWorldState() must thread the session seed (worldSeed = worldSeed) onto the snapshot, " +
                "NOT let WorldState.worldSeed default back to WorldSeed.MVP on every autosave (AC#3, ADR 0041)",
            Regex("""worldSeed\s*=\s*worldSeed""").containsMatchIn(body),
        )
    }

    private companion object {
        /**
         * The body of the declaration whose header is [header], from the header to the first line that
         * is a single closing brace at the declaration's indentation (mirrors [Uc21MainMenuGuardTest]).
         */
        private fun section(
            source: String,
            header: String,
        ): String {
            val start = source.indexOf(header)
            if (start < 0) throw AssertionError("Could not locate '$header' in source")
            val rest = source.substring(start)
            val end = Regex("""\n {4}}""").find(rest)?.range?.last ?: rest.length
            return rest.substring(0, end)
        }

        private fun readSource(relative: String): String {
            val candidates =
                listOf(
                    "src/main/kotlin/com/orbitalfrontier/$relative",
                    "core/src/main/kotlin/com/orbitalfrontier/$relative",
                )
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in candidates) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError("Could not locate source for $relative")
        }
    }
}
