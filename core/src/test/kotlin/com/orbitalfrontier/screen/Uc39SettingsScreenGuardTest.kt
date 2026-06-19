package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC39's accessibility settings (AC#1/#2/#3/#4),
 * mirroring [Uc37SettingsScreenGuardTest]. The pure selection/clamp/persistence logic is covered
 * behaviourally elsewhere ([com.orbitalfrontier.settings.ColorVisionModeTest],
 * [com.orbitalfrontier.render.TextScaleTest], [com.orbitalfrontier.render.MotionPreferenceTest],
 * [com.orbitalfrontier.render.PaletteTest], [com.orbitalfrontier.faction.FactionColorsTest],
 * [com.orbitalfrontier.save.SqlDelightSettingsRepositoryTest]); the Scene2D glue that *assembles* the
 * ACCESSIBILITY group and the renderer that *honours* reduced-motion live in libGDX-touching code the
 * headless backend cannot construct, so the structural contract is pinned at the source level.
 *
 * ACs covered here (structural/wiring half):
 *  - **AC#1/#2/#3** — the panel ships an ACCESSIBILITY group with the colour-vision, text-size and
 *    reduced-motion rows.
 *  - **AC#4 (applies live)** — each control writes its global render knob (Palette/TextScale/
 *    MotionPreference) AND persists through the repository on the same tap (the UC31 two-step discipline).
 *  - **AC#3 (render site)** — the parallax starfield consults [com.orbitalfrontier.render.MotionPreference]
 *    so reduced-motion actually attenuates the only current motion source.
 */
class Uc39SettingsScreenGuardTest {
    // --- AC#1/#2/#3: the ACCESSIBILITY group ships the three accessibility rows --------------------

    @Test
    fun `the settings panel builds an ACCESSIBILITY group`() {
        assertTrue(
            "AC#1: the settings panel groups an `ACCESSIBILITY` section",
            SETTINGS_PANEL_SOURCE.contains("section(\"ACCESSIBILITY\")"),
        )
    }

    @Test
    fun `the accessibility group ships the colour-vision, text-size and reduced-motion rows`() {
        assertTrue("AC#1: colour-vision row is present", SETTINGS_PANEL_SOURCE.contains("row(colorVisionButton)"))
        assertTrue("AC#2: text-size row is present", SETTINGS_PANEL_SOURCE.contains("row(textScaleButton)"))
        assertTrue("AC#3: reduced-motion row is present", SETTINGS_PANEL_SOURCE.contains("row(reducedMotionButton)"))
    }

    // --- AC#4: each control applies live to its global knob AND persists on the same tap ----------

    @Test
    fun `the colour-vision control applies to the live Palette and persists`() {
        assertTrue("AC#1/#4: toggling colour-vision sets the live render Palette mode", SETTINGS_PANEL_SOURCE.contains("Palette.setMode("))
        assertTrue("AC#4: the colour-vision choice is persisted", SETTINGS_PANEL_SOURCE.contains("saveColorVisionMode("))
    }

    @Test
    fun `the text-size control applies to the live TextScale and persists`() {
        assertTrue("AC#2/#4: cycling text size writes the global TextScale knob", SETTINGS_PANEL_SOURCE.contains("TextScale.set("))
        assertTrue("AC#4: the text-size factor is persisted", SETTINGS_PANEL_SOURCE.contains("saveTextScale("))
    }

    @Test
    fun `the reduced-motion control applies to the live MotionPreference and persists`() {
        assertTrue(
            "AC#3/#4: toggling reduced-motion writes the global MotionPreference flag",
            SETTINGS_PANEL_SOURCE.contains("MotionPreference.set("),
        )
        assertTrue("AC#4: the reduced-motion flag is persisted", SETTINGS_PANEL_SOURCE.contains("saveReducedMotion("))
    }

    // --- AC#3 render site: the starfield honours the reduced-motion flag --------------------------

    @Test
    fun `the parallax starfield consults the reduced-motion preference`() {
        assertTrue(
            "AC#3: StarfieldRenderer reads MotionPreference.reduced so the parallax can be stopped",
            STARFIELD_SOURCE.contains("MotionPreference.reduced"),
        )
    }

    private companion object {
        private val SETTINGS_PANEL_SOURCE: String = readSource("screen/SettingsPanel.kt")
        private val STARFIELD_SOURCE: String = readSource("render/StarfieldRenderer.kt")

        /**
         * Locates a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles running from the module dir, the repo root, or
         * a git worktree). Hard-fails rather than passing silently (mirrors [Uc37SettingsScreenGuardTest]).
         */
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
            throw AssertionError(
                "Could not locate $relative from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC39 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
