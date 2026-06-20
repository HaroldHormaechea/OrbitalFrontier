package com.orbitalfrontier.screen

import com.orbitalfrontier.render.AutosaveIndicatorState
import com.orbitalfrontier.render.GameFont
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC52 — the autosave-indicator + save-robustness **wiring** that cannot be
 * driven from a plain JVM unit test because the call sites build live libGDX / Android objects needing a
 * real GL context or device (PlayScreen's renderers, the `AndroidSqliteDriver`). The pure derivations are
 * proven for real elsewhere ([com.orbitalfrontier.render.AutosaveIndicatorStateTest],
 * [com.orbitalfrontier.save.SaveDatabaseOpenerTest], [com.orbitalfrontier.save.AutosaveControllerTest]);
 * here we pin the wiring at the source level, mirroring the repo's existing guards
 * ([Uc44CombatHudSourceTest], [Uc49PowerHudSourceTest]).
 *
 * All assertions run over **comment-stripped CODE**, so a doc comment merely naming a symbol never
 * satisfies a wiring assertion — only real code does. The contract pinned:
 *  - AC#2: PlayScreen polls the cross-thread signal each frame, feeds the indicator's start/finish,
 *    ages its fade, and draws it via the HudRenderer.
 *  - AC#2: AutosaveController pulses `markSaving` at enqueue and `markSaved` after the write.
 *  - AC#3/#4 on-device correctness: the Android driver factory disables WAL via
 *    `setWriteAheadLoggingEnabled(false)` on the open-helper (not a PRAGMA the framework can override),
 *    so the single `.db` is authoritative for the probe + single-file backup.
 *  - AC#4 degraded boot: an UnsupportedNewer / Unreadable open routes to a non-crashing degraded menu.
 *  - AC#2 font safety: the indicator labels are ASCII and within the bundled font's glyph coverage.
 */
class Uc52AutosaveIndicatorSourceTest {
    // --- AC#2: PlayScreen polls → feeds → updates → draws the indicator --------------------------------

    @Test
    fun `PlayScreen polls the autosave signal each frame`() {
        assertTrue(
            "PlayScreen must poll the cross-thread autosave signal (autosaveSignal.poll()) (AC#2)",
            PLAY_SCREEN.contains(Regex("""autosaveSignal\.poll\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen feeds the polled start and finish into the indicator`() {
        assertTrue(
            "PlayScreen must feed a started save into autosaveIndicator.onSaveStarted() (AC#2)",
            PLAY_SCREEN.contains(Regex("""autosaveIndicator\.onSaveStarted\s*\(""")),
        )
        assertTrue(
            "PlayScreen must feed a finished save into autosaveIndicator.onSaveFinished() (AC#2)",
            PLAY_SCREEN.contains(Regex("""autosaveIndicator\.onSaveFinished\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen ages the indicator fade every frame`() {
        assertTrue(
            "PlayScreen must age the indicator's fade via autosaveIndicator.update(dt) (AC#2)",
            PLAY_SCREEN.contains(Regex("""autosaveIndicator\.update\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen draws the indicator through the HudRenderer`() {
        assertTrue(
            "PlayScreen must draw the indicator via hudRenderer.renderAutosaveIndicator(autosaveIndicator, ...) (AC#2)",
            PLAY_SCREEN.contains(Regex("""hudRenderer\.renderAutosaveIndicator\s*\(\s*autosaveIndicator""")),
        )
    }

    @Test
    fun `the HudRenderer fades the indicator by the indicator state alpha`() {
        assertTrue(
            "HudRenderer.renderAutosaveIndicator must exist (the draw-side of the indicator)",
            HUD_RENDERER.contains(Regex("""fun renderAutosaveIndicator\s*\(""")),
        )
        assertTrue(
            "the indicator draw must fade by the state's derived alpha (state.alpha)",
            HUD_RENDERER.contains(Regex("""state\.alpha""")),
        )
    }

    // --- AC#2: AutosaveController pulses markSaving at enqueue and markSaved after the write -----------

    @Test
    fun `AutosaveController pulses the activity signal on enqueue and after the write`() {
        assertTrue(
            "AutosaveController must pulse activitySignal.markSaving() at enqueue (render thread) (AC#2)",
            AUTOSAVE_CONTROLLER.contains(Regex("""activitySignal\.markSaving\s*\(""")),
        )
        assertTrue(
            "AutosaveController must pulse activitySignal.markSaved() after the write (executor thread) (AC#2)",
            AUTOSAVE_CONTROLLER.contains(Regex("""activitySignal\.markSaved\s*\(""")),
        )
    }

    @Test
    fun `AutosaveController calls markSaved inside the executor task, after the repository write`() {
        // markSaved must follow the repository.saveGameState call within the same execute { ... } block, so
        // "saved" is signalled only once the write is durable — assert that textual order in the source.
        val saveIdx = AUTOSAVE_CONTROLLER.indexOf("repository.saveGameState")
        val savedIdx = AUTOSAVE_CONTROLLER.indexOf("activitySignal.markSaved")
        assertTrue("both the write and markSaved must be present", saveIdx >= 0 && savedIdx >= 0)
        assertTrue("markSaved must come AFTER the repository write (saved only when durable)", savedIdx > saveIdx)
    }

    // --- AC#3/#4: WAL disabled on the Android driver so the single .db is authoritative ----------------

    @Test
    fun `the Android driver factory disables WAL on the open helper`() {
        assertTrue(
            "AndroidSqlDriverFactory must disable WAL via setWriteAheadLoggingEnabled(false) on the helper (UC52)",
            ANDROID_DRIVER_FACTORY.contains(Regex("""setWriteAheadLoggingEnabled\s*\(\s*false\s*\)""")),
        )
    }

    @Test
    fun `the Android driver factory resolves the single database file for the probe and backup`() {
        assertTrue(
            "AndroidSqlDriverFactory must expose the on-disk db file (databaseFile via getDatabasePath) (UC52)",
            ANDROID_DRIVER_FACTORY.contains(Regex("""getDatabasePath\s*\(""")),
        )
    }

    // --- AC#4: degraded boot routes UnsupportedNewer / Unreadable to a non-crashing menu --------------

    @Test
    fun `the game routes a newer-than-supported save to the degraded menu`() {
        assertTrue(
            "OrbitalFrontierGame must route UnsupportedNewer to bootWithoutDatabase (non-crashing) (AC#4)",
            GAME.contains(Regex("""bootWithoutDatabase\s*\(\s*SaveUnavailable\.UNSUPPORTED_NEWER""")),
        )
    }

    @Test
    fun `the game routes an unreadable save to the degraded menu`() {
        assertTrue(
            "OrbitalFrontierGame must route Unreadable to bootWithoutDatabase (non-crashing) (AC#4)",
            GAME.contains(Regex("""bootWithoutDatabase\s*\(\s*SaveUnavailable\.UNREADABLE""")),
        )
    }

    @Test
    fun `the robust-open pipeline is wired through SaveDatabaseOpener at create()`() {
        assertTrue(
            "OrbitalFrontierGame must open the save through SaveDatabaseOpener (probe → backup → rollback) (AC#3/#4)",
            GAME.contains(Regex("""SaveDatabaseOpener\s*\(""")),
        )
    }

    // --- AC#2: indicator labels are ASCII and within the bundled font's glyph coverage ----------------

    @Test
    fun `the indicator labels are ASCII and within the required font glyphs`() {
        val required = GameFont.REQUIRED_GLYPHS.toSet()
        for (label in listOf(AutosaveIndicatorState.SAVING_LABEL, AutosaveIndicatorState.SAVED_LABEL)) {
            for (ch in label) {
                assertTrue("indicator label '$label' must be ASCII", ch.code in 0x20..0x7E)
                assertTrue("glyph '$ch' must be in GameFont.REQUIRED_GLYPHS", ch.code in required)
            }
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
                    "src/main/kotlin/com/orbitalfrontier/render/HudRenderer.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/render/HudRenderer.kt",
                ),
            )
        private val AUTOSAVE_CONTROLLER =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/save/AutosaveController.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/save/AutosaveController.kt",
                ),
            )
        private val GAME =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                ),
            )
        private val ANDROID_DRIVER_FACTORY =
            stripComments(
                readSource(
                    "../android/src/main/kotlin/com/orbitalfrontier/android/AndroidSqlDriverFactory.kt",
                    "android/src/main/kotlin/com/orbitalfrontier/android/AndroidSqlDriverFactory.kt",
                ),
            )

        /** Strip Kotlin block + line comments so the guards inspect actual CODE only (mirrors Uc44 guard). */
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
                    "the UC52 autosave-indicator source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
