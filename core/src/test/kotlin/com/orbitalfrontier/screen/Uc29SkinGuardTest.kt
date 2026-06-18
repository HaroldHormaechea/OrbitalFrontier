package com.orbitalfrontier.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** structural contract of UC29 (the finished design-system UI
 * skin — `OrbitalUiSkin`, the rename of the retired `PlaceholderControlsSkin`). The skin builds its chrome
 * from runtime [com.badlogic.gdx.graphics.Pixmap]s / [com.badlogic.gdx.graphics.Texture]s on the GL
 * thread, so it cannot be constructed on the headless JVM test backend. As with the repo's other screen
 * guards ([Uc26ActionArcGuardTest], [Uc28FontWiringGuardTest]) the contract is therefore pinned at the
 * source-text level.
 *
 * ACs covered (structural half):
 *  - **AC#1/#2** — the new skin replaces `PlaceholderControlsSkin` *everywhere*: no consumer screen/widget
 *    still names the placeholder, and each references `OrbitalUiSkin`. The "Placeholder" identity is gone.
 *  - **AC#4** — disabled, pressed (down) and focused/over states are styled, not just the default: the
 *    button style declares `down`/`disabled`/`over`/`focused` nine-patch slots plus per-state font colours
 *    (`downFontColor`/`disabledFontColor`).
 *  - **AC#5** — the new skin metrics are pinned as named constants (nine-patch size/corner/border/accent),
 *    and the `panel` background carries zero pad insets so it cannot perturb the width-budget screens or
 *    the UC20 station-menu grid. The retired flat-`rect()`-only button path and the deleted
 *    `actionButtonStyle` must be gone.
 */
class Uc29SkinGuardTest {
    // --- AC#1/#2: the placeholder skin is gone from every consumer -----------------------------------

    @Test
    fun `no consumer still references the retired PlaceholderControlsSkin`() {
        for (file in CONSUMERS) {
            val src = readMainSource(file)
            assertFalse(
                "AC#1/#2: $file must NOT reference the retired PlaceholderControlsSkin",
                src.contains("PlaceholderControlsSkin"),
            )
            assertTrue(
                "AC#1/#2: $file must reference the finished OrbitalUiSkin",
                src.contains("OrbitalUiSkin"),
            )
        }
    }

    @Test
    fun `the placeholder skin source file no longer exists`() {
        // The rename is a real move, not a copy — the old file must be deleted (AC#1).
        val old = locateMainSourceOrNull("screen/controls/PlaceholderControlsSkin.kt")
        assertTrue(
            "AC#1: PlaceholderControlsSkin.kt must be deleted (renamed to OrbitalUiSkin.kt)",
            old == null,
        )
        // ...and the replacement must exist.
        assertTrue(
            "AC#1: OrbitalUiSkin.kt must exist",
            locateMainSourceOrNull("screen/controls/OrbitalUiSkin.kt") != null,
        )
    }

    // --- AC#4: disabled / pressed / focused states are styled ---------------------------------------

    @Test
    fun `the button style declares down disabled and over focused nine-patch slots`() {
        val code = stripComments(skinSource())
        for (slot in listOf("down", "disabled", "over", "focused")) {
            assertTrue(
                "AC#4: buttonStyle must style the `$slot` state with a nine-patch (not just the default)",
                Regex("\\b$slot\\s*=\\s*bevelPatch\\(").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `the button style declares per-state font colours`() {
        val code = stripComments(skinSource())
        for (prop in listOf("downFontColor", "disabledFontColor")) {
            assertTrue(
                "AC#4: buttonStyle must set `$prop` so pressed/disabled text is themed",
                Regex("\\b$prop\\s*=").containsMatchIn(code),
            )
        }
    }

    // --- AC#5: pinned skin metrics + the panel's zero pad insets ------------------------------------

    @Test
    fun `the nine-patch metric constants are pinned as named constants`() {
        val code = stripComments(skinSource())
        for (constant in listOf("PATCH_SIZE", "PATCH_CORNER", "BORDER_PX", "ACCENT_PX")) {
            assertTrue(
                "AC#5: the skin metric `$constant` must be a named constant (so it can be guarded)",
                Regex("\\bconst\\s+val\\s+$constant\\s*=").containsMatchIn(code),
            )
        }
    }

    @Test
    fun `the panel background contributes zero pad insets`() {
        // AC#5 / UC20 protection: the panel nine-patch only paints — it must add no layout padding, or it
        // would eat the width budget the station-menu grid relies on at the narrow-width floor.
        val code = stripComments(skinSource())
        for (inset in listOf("leftWidth", "rightWidth", "topHeight", "bottomHeight")) {
            assertTrue(
                "AC#5: the panel must zero its `$inset` inset (no layout padding contributed)",
                Regex("\\b$inset\\s*=\\s*0f\\b").containsMatchIn(code),
            )
        }
    }

    // --- AC#5: the retired flat-fill button path and actionButtonStyle are gone ----------------------

    @Test
    fun `the retired flat rect-only button helper is gone`() {
        val code = stripComments(skinSource())
        assertFalse(
            "AC#5: the old flat-fill `rect(...)` button helper must be retired (buttons are bevelled nine-patches now)",
            Regex("\\bfun\\s+rect\\s*\\(").containsMatchIn(code),
        )
        assertFalse(
            "AC#5: no button state may be built from the retired flat `rect(...)` drawable",
            Regex("=\\s*rect\\(").containsMatchIn(code),
        )
    }

    @Test
    fun `the deleted actionButtonStyle is gone everywhere`() {
        // It was removed as dead code in UC29; nothing in production may still name it.
        for (file in CONSUMERS + listOf("screen/controls/OrbitalUiSkin.kt")) {
            assertFalse(
                "AC#5: $file must not reference the deleted actionButtonStyle",
                readMainSource(file).contains("actionButtonStyle"),
            )
        }
    }

    private companion object {
        /** Every Scene2D widget / screen that consumed the placeholder skin and now consumes OrbitalUiSkin. */
        private val CONSUMERS =
            listOf(
                "screen/MainMenuScreen.kt",
                "screen/StationHubScreen.kt",
                "screen/TradeScreen.kt",
                "screen/ShipyardScreen.kt",
                "screen/OutfitScreen.kt",
                "screen/HireScreen.kt",
                "screen/MissionBoardScreen.kt",
                "screen/PlayScreen.kt",
                "screen/StationWalkaroundScreen.kt",
                "screen/SettingsOverlay.kt",
                "screen/controls/ActionCluster.kt",
                "screen/controls/MovementJoystick.kt",
            )

        private fun skinSource(): String = readMainSource("screen/controls/OrbitalUiSkin.kt")

        private fun readMainSource(relative: String): String =
            locateMainSourceOrNull(relative)?.readText()
                ?: throw AssertionError(
                    "Could not locate $relative from user.dir=${System.getProperty("user.dir")}; " +
                        "the UC29 source-anchored skin guard cannot run (refusing to pass silently).",
                )

        /**
         * Walk up from the test working directory trying the candidate relative path at every ancestor
         * (handles running from the module dir, the repo root, or a git worktree). Returns null if absent,
         * so callers can assert either presence or deletion.
         */
        private fun locateMainSourceOrNull(relative: String): File? {
            val candidates =
                listOf(
                    "src/main/kotlin/com/orbitalfrontier/$relative",
                    "core/src/main/kotlin/com/orbitalfrontier/$relative",
                )
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in candidates) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f
                }
                dir = dir.parentFile
            }
            return null
        }

        /** Remove `/* */` block comments and `//` line comments so KDoc prose can't trip a code-level guard. */
        private fun stripComments(source: String): String {
            val noBlock = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            return noBlock.lineSequence().joinToString("\n") { line ->
                val idx = line.indexOf("//")
                if (idx >= 0) line.substring(0, idx) else line
            }
        }
    }
}
