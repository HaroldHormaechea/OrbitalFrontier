package com.orbitalfrontier.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC44 — the combat-HUD wiring inside [PlayScreen] (AC#1/#2/#3/#4) and the
 * draw-side purity of [com.orbitalfrontier.render.CombatHudRenderer] (PIN #3).
 *
 * Why source-anchored, not behavioural: [PlayScreen] builds live libGDX objects (an
 * `OrthographicCamera`, the renderers' `ShapeRenderer`/`SpriteBatch`, scene2d `Stage` actors) in its
 * field initializers, which need a real GL context — it is NOT headlessly constructible, so the wiring
 * cannot be driven from a plain JVM unit test, and production must not be reshaped just to test it. The
 * *derivation* logic is pure and proven for real in [com.orbitalfrontier.render.CombatHudStateTest] /
 * [com.orbitalfrontier.render.CombatFeedbackTest]; here we pin the wiring at the source level, mirroring
 * the repo's existing guards ([Uc40EconomyFeedbackSourceTest], `StationRefuelFeedbackSourceTest`).
 *
 * All assertions run over **comment-stripped CODE** (block + line comments removed), so a doc comment
 * that merely names `CombatHudState`/`combatFeedback`/`ShapeRenderer` is prose and never satisfies the
 * guard — only real code does. The contract pinned:
 *  - AC#1/#2: PlayScreen derives the overlay via the pure `CombatHudState.build(...)` and draws it with
 *    `combatHudRenderer.render(...)` against the WORLD camera.
 *  - AC#3 / UC39: PlayScreen feeds `MotionPreference.reduced` into `combatFeedback.update(...)` (the flag
 *    is injected as a parameter, never read by the pure class).
 *  - PIN #1: the HUD player position comes from `physics.readKinematics().position` — the SAME firing
 *    point the turrets use — not a render-interpolated transform.
 *  - PIN #2: the camera-follow position is rebuilt from the ship each frame and the shake offset is ADDED
 *    to that fresh position (`worldCamera.position.set(cameraX, ...)`), never accumulated onto last
 *    frame's camera — so the shake decays cleanly to 0 (no stuck offset on EncounterCleared).
 *  - PIN #3 / AC#4: the bars + flash are ShapeRenderer draws (the renderer owns a `ShapeRenderer` and
 *    holds no scene2d `Stage`/actor), so they cannot drift into the FIRE-arc / HUD scene2d layer.
 */
class Uc44CombatHudSourceTest {
    // --- AC#1/#2: pure derivation + world-space draw ---------------------------------------------------

    @Test
    fun `PlayScreen derives the overlay through the pure CombatHudState build`() {
        assertTrue(
            "PlayScreen must derive the HUD overlay via the pure CombatHudState.build(...) (AC#1/#2/#5)",
            PLAY_SCREEN.contains(Regex("""CombatHudState\.build\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen draws the overlay with the world camera`() {
        assertTrue(
            "PlayScreen must draw the combat HUD via combatHudRenderer.render(worldCamera, ...) (AC#1/#2)",
            PLAY_SCREEN.contains(Regex("""combatHudRenderer\.render\s*\(\s*worldCamera""")),
        )
    }

    // --- AC#3 / UC39: reduced-motion injected as a parameter into the pure feedback ---------------------

    @Test
    fun `PlayScreen feeds the reduced-motion preference into the feedback update`() {
        assertTrue(
            "PlayScreen must pass MotionPreference.reduced into combatFeedback.update(...) (AC#3/UC39)",
            PLAY_SCREEN.contains(Regex("""combatFeedback\.update\s*\([^)]*MotionPreference\.reduced""")),
        )
    }

    // --- PIN #1: the HUD player position is the firing point (physics kinematics) -----------------------

    @Test
    fun `PlayScreen derives the HUD player position from the physics kinematics (PIN 1)`() {
        assertTrue(
            "the lock must use the SAME firing point the turrets do: playerPos = physics.readKinematics().position",
            PLAY_SCREEN.contains(Regex("""playerPos\s*=\s*physics\.readKinematics\s*\(\s*\)\s*\.position""")),
        )
    }

    // --- PIN #2: non-accumulating camera shake ---------------------------------------------------------

    @Test
    fun `PlayScreen rebuilds the camera-follow position from the ship each frame (PIN 2)`() {
        assertTrue(
            "the camera-follow X must be freshly derived from the ship each frame (not accumulated)",
            PLAY_SCREEN.contains(Regex("""cameraX\s*=\s*renderShip\.position\.x""")),
        )
        assertTrue(
            "the (ship + shake) result must be SET onto the camera position, not added onto the last frame's",
            PLAY_SCREEN.contains(Regex("""worldCamera\.position\.set\s*\(\s*cameraX""")),
        )
    }

    @Test
    fun `PlayScreen never accumulates the shake onto the camera position (PIN 2)`() {
        assertFalse(
            "the camera position must not be mutated in place (no .add(...) → that would let the shake drift)",
            PLAY_SCREEN.contains(Regex("""worldCamera\.position\.add\s*\(""")),
        )
        assertFalse(
            "the camera position must not be incremented in place (no += → that would accumulate the offset)",
            PLAY_SCREEN.contains(Regex("""worldCamera\.position(\.\w+)?\s*\+=""")),
        )
    }

    // --- PIN #3 / AC#4: bars + flash are ShapeRenderer draws, never scene2d actors ----------------------

    @Test
    fun `PlayScreen draws the damage flash through the ShapeRenderer-backed renderer`() {
        assertTrue(
            "the damage-taken flash must be a renderer draw (combatHudRenderer.renderDamageFlash), not a scene2d actor",
            PLAY_SCREEN.contains(Regex("""combatHudRenderer\.renderDamageFlash\s*\(""")),
        )
    }

    @Test
    fun `the combat HUD renderer draws via ShapeRenderer and holds no scene2d layer (PIN 3)`() {
        assertTrue(
            "CombatHudRenderer must own a ShapeRenderer for its world-space bars/reticle and screen-space flash",
            HUD_RENDERER.contains(Regex("""ShapeRenderer\s*\(\s*\)""")),
        )
        // It must NOT touch the scene2d layer — that is where the FIRE arc (UC26) and HUD (UC34) live, and
        // keeping these marks off it is exactly what guarantees no overlap (PIN #3 / AC#4).
        for (forbidden in listOf("scene2d", "Stage", "addActor")) {
            assertFalse(
                "CombatHudRenderer must not use $forbidden — the overlay stays off the FIRE-arc/HUD scene2d layer",
                HUD_RENDERER.contains(forbidden),
            )
        }
    }

    private companion object {
        private val PLAY_SCREEN =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                ),
            )
        private val HUD_RENDERER =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/render/CombatHudRenderer.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/render/CombatHudRenderer.kt",
                ),
            )

        /**
         * Strip Kotlin block (`/* … */`) and line (`// …`) comments so the guards inspect actual CODE only —
         * a doc comment naming CombatHudState/combatFeedback/ShapeRenderer must not satisfy a wiring
         * assertion. Mirrors the comment-stripping in [Uc40EconomyFeedbackSourceTest].
         */
        private fun stripComments(source: String): String =
            source
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

        /**
         * Locate a production source by walking up from the test working directory, trying each candidate
         * relative path at every ancestor (handles running from the module dir, repo root, or a worktree).
         * A missing file is a hard error so the guard fails loudly rather than passing vacuously.
         */
        private fun readSource(vararg candidates: String): String {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in candidates) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate ${candidates.firstOrNull()} from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC44 combat-HUD source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
