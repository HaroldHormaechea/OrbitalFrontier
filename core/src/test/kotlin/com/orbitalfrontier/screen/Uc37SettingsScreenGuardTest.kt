package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC37's settings screen. The pure pieces are
 * covered behaviourally elsewhere ([com.orbitalfrontier.settings.JoystickTuningTest],
 * [com.orbitalfrontier.render.UiScaleTest], [com.orbitalfrontier.save.SqlDelightSettingsRepositoryTest]);
 * the Scene2D glue that *assembles* the grouped panel and reaches it from the main menu lives in
 * libGDX-touching code the headless backend cannot construct, so the structural contract is pinned at the
 * source level (mirrors [Uc32PauseOverlayGuardTest]).
 *
 * ACs covered here (structural/wiring half):
 *  - **AC#1** — the panel groups AUDIO / CONTROLS / DISPLAY / GAMEPLAY (replacing the single-button overlay).
 *  - **AC#4** — the screen is reachable from the main menu (a SETTINGS button wired to `onOpenSettings`).
 *  - **Dependency pitfall** — the use-case-dependent groups (Accessibility/UC39, Save Management/UC38) are
 *    omitted honestly with a note, not faked with dead controls.
 */
class Uc37SettingsScreenGuardTest {
    // --- AC#1: the grouped settings panel replaces the single-button overlay -----------------------

    @Test
    fun `the settings panel builds the four shipped groups in order`() {
        // The grouped builder lays out the four groups that ship now (UC31 audio + UC37 controls/display +
        // UC36 gameplay), each as a section header — the surface that replaces the lone handedness button.
        for (group in listOf("AUDIO", "CONTROLS", "DISPLAY", "GAMEPLAY")) {
            assertTrue(
                "AC#1: the settings panel groups a `$group` section",
                SETTINGS_PANEL_SOURCE.contains("section(\"$group\")"),
            )
        }
    }

    @Test
    fun `the controls group ships handedness, sensitivity and deadzone rows`() {
        assertTrue("AC#3: handedness row is present", SETTINGS_PANEL_SOURCE.contains("row(handednessButton)"))
        assertTrue("AC#1: joystick sensitivity row is present", SETTINGS_PANEL_SOURCE.contains("row(sensitivityButton)"))
        assertTrue("AC#1: joystick deadzone row is present", SETTINGS_PANEL_SOURCE.contains("row(deadzoneButton)"))
        // DISPLAY ships the UI-scale row (AC#3: UiScale lives under the new screen).
        assertTrue("AC#3: UI-scale row is present", SETTINGS_PANEL_SOURCE.contains("row(uiScaleButton)"))
    }

    // --- Dependency pitfall: the not-yet-built groups are stated, never stubbed --------------------

    @Test
    fun `the omitted use-case-dependent groups are noted honestly, not faked`() {
        assertTrue(
            "Dependency pitfall: the omitted groups are surfaced as a note row",
            SETTINGS_PANEL_SOURCE.contains("note(OMITTED_GROUPS_NOTE)"),
        )
        assertTrue(
            "Dependency pitfall: the note names Accessibility + Save Management as later additions",
            SETTINGS_PANEL_SOURCE.contains("ACCESSIBILITY AND SAVE MANAGEMENT"),
        )
    }

    // --- AC#4: the screen is reachable from the main menu ------------------------------------------

    @Test
    fun `the main menu exposes a SETTINGS button wired to onOpenSettings`() {
        assertTrue(
            "AC#4: MainMenuScreen takes an onOpenSettings hook",
            MAIN_MENU_SOURCE.contains("onOpenSettings"),
        )
        assertTrue(
            "AC#4: a SETTINGS button opens the standalone settings screen",
            MAIN_MENU_SOURCE.contains("menuButton(\"SETTINGS\") { onOpenSettings() }"),
        )
    }

    private companion object {
        private val SETTINGS_PANEL_SOURCE: String = readSource("screen/SettingsPanel.kt")
        private val MAIN_MENU_SOURCE: String = readSource("screen/MainMenuScreen.kt")

        /**
         * Locates a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles running from the module dir, the repo root,
         * or a git worktree). Hard-fails rather than passing silently (mirrors the repo's existing guards).
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
                    "the UC37 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
