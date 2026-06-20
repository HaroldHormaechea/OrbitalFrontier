package com.orbitalfrontier.save

import com.orbitalfrontier.render.MotionPreference
import com.orbitalfrontier.render.TextScale
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.ColorVisionMode
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Behavioural coverage for the **degraded-mode** [DefaultSettingsRepository] (UC56) and the wiring that
 * makes main-menu SETTINGS work when the on-disk save is unopenable.
 *
 * The original user-reported bug: with an unreadable/unsupported save the app booted in degraded mode and
 * tapping SETTINGS from the main menu was a **silent no-op** (logged "Settings unavailable until a new game
 * is started"). The fix decouples the global UI/accessibility preferences from save state — degraded mode
 * gets this in-memory repository and the menu opens settings unconditionally.
 *
 * This guard pins the two halves of that fix:
 *  - **Behavioural** — a fresh [DefaultSettingsRepository] returns every preference's default without
 *    throwing, round-trips a written value within the session, and coerces out-of-range writes on read
 *    (the degrade-to-default contract of [SettingsRepository], honoured purely in memory; ADR 0001).
 *  - **Wiring (source-anchored)** — [com.orbitalfrontier.app.OrbitalFrontierGame] installs a
 *    [DefaultSettingsRepository] on the degraded boot path and wires the main menu's settings callback
 *    UNCONDITIONALLY (no save-state gate), so the "Settings unavailable" no-op cannot return.
 */
class DefaultSettingsRepositoryTest {
    private fun newRepository() = DefaultSettingsRepository()

    // --- Defaults on a fresh (no-DB) instance: every read degrades to its documented default -----------

    @Test
    fun `a fresh repository returns every preference default and ensureInitialized is a safe no-op`() {
        val repo = newRepository()
        // ensureInitialized() must not throw — there is no row to seed in degraded mode.
        repo.ensureInitialized()

        assertEquals("handedness defaults", Handedness.DEFAULT, repo.loadHandedness())
        assertEquals("audio defaults (coerced)", AudioSettings.DEFAULT.coerced(), repo.loadAudioSettings())
        assertFalse("tutorial-completed defaults to false (onboarding still runs)", repo.loadTutorialCompleted())
        assertEquals("joystick tuning defaults (coerced)", JoystickTuning.DEFAULT.coerced(), repo.loadJoystickTuning())
        assertEquals("ui-scale defaults", UiScale.DEFAULT_FACTOR, repo.loadUiScale(), EPS)
        assertEquals("colour-vision mode defaults", ColorVisionMode.DEFAULT, repo.loadColorVisionMode())
        assertEquals("text-scale defaults", TextScale.DEFAULT_FACTOR, repo.loadTextScale(), EPS)
        assertEquals("reduced-motion defaults", MotionPreference.DEFAULT_REDUCED, repo.loadReducedMotion())
    }

    // --- Session round-trip: a write is visible to the next read on the SAME instance ------------------

    @Test
    fun `every preference round-trips in memory within the session`() {
        val repo = newRepository()

        repo.saveHandedness(Handedness.LEFT_HANDED)
        assertEquals(Handedness.LEFT_HANDED, repo.loadHandedness())

        val audio = AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f)
        repo.saveAudioSettings(audio)
        assertEquals(audio, repo.loadAudioSettings())

        repo.saveTutorialCompleted(true)
        assertTrue(repo.loadTutorialCompleted())

        val tuning = JoystickTuning(sensitivity = 1.5f, deadzone = 0.3f)
        repo.saveJoystickTuning(tuning)
        assertEquals(tuning, repo.loadJoystickTuning())

        repo.saveUiScale(2.5f)
        assertEquals(2.5f, repo.loadUiScale(), EPS)

        repo.saveColorVisionMode(ColorVisionMode.COLORBLIND_SAFE)
        assertEquals(ColorVisionMode.COLORBLIND_SAFE, repo.loadColorVisionMode())

        repo.saveTextScale(1.2f)
        assertEquals(1.2f, repo.loadTextScale(), EPS)

        repo.saveReducedMotion(true)
        assertTrue(repo.loadReducedMotion())
    }

    // --- Coercion: an out-of-range write is clamped on read (never throws) -----------------------------

    @Test
    fun `out-of-range writes are coerced on read, matching the persistent repository contract`() {
        val repo = newRepository()

        // Volumes clamp to 0f..1f.
        repo.saveAudioSettings(AudioSettings(masterMuted = false, sfxVolume = 9f, musicVolume = -3f))
        val audio = repo.loadAudioSettings()
        assertEquals("sfx clamps to 1f", 1f, audio.sfxVolume, EPS)
        assertEquals("music clamps to 0f", 0f, audio.musicVolume, EPS)

        // Joystick tuning clamps to its valid ranges.
        repo.saveJoystickTuning(JoystickTuning(sensitivity = 99f, deadzone = -1f))
        val tuning = repo.loadJoystickTuning()
        assertEquals("sensitivity clamps to MAX", JoystickTuning.MAX_SENSITIVITY, tuning.sensitivity, EPS)
        assertEquals("deadzone clamps to MIN", JoystickTuning.MIN_DEADZONE, tuning.deadzone, EPS)

        // UI scale + text scale clamp into their factor ranges.
        repo.saveUiScale(100f)
        assertEquals(UiScale.coerce(100f), repo.loadUiScale(), EPS)
        repo.saveTextScale(0f)
        assertEquals(TextScale.coerce(0f), repo.loadTextScale(), EPS)
    }

    // --- Per-field isolation: writing one preference never clobbers another ----------------------------

    @Test
    fun `writing one preference leaves the others untouched`() {
        val repo = newRepository()
        repo.saveHandedness(Handedness.LEFT_HANDED)
        repo.saveAudioSettings(AudioSettings(masterMuted = true, sfxVolume = 0.1f, musicVolume = 0.2f))

        // Mutating the joystick tuning must not disturb handedness or audio.
        repo.saveJoystickTuning(JoystickTuning(sensitivity = 2f, deadzone = 0.4f))

        assertEquals(Handedness.LEFT_HANDED, repo.loadHandedness())
        assertEquals(
            AudioSettings(masterMuted = true, sfxVolume = 0.1f, musicVolume = 0.2f),
            repo.loadAudioSettings(),
        )
    }

    // --- Wiring (source-anchored): degraded boot installs this repo + ungates the menu's settings ------

    @Test
    fun `the degraded boot path installs DefaultSettingsRepository and opens settings unconditionally`() {
        val game = readSource("app/OrbitalFrontierGame.kt")
        assertTrue(
            "UC56: degraded boot (bootWithoutDatabase) installs the in-memory DefaultSettingsRepository",
            game.contains("settingsRepository = DefaultSettingsRepository()"),
        )
        // The main menu's settings callback is wired UNCONDITIONALLY — not gated behind the degraded flag,
        // unlike onLoadGame which IS (correctly) inert without a DB. This is the fix for the silent no-op.
        assertTrue(
            "UC56: the menu's settings callback opens settings with no save-state gate",
            game.contains("onOpenSettings = { openSettings() }"),
        )
    }

    private companion object {
        const val EPS = 1e-4f

        /**
         * Locate a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles the module dir, the repo root, or a git
         * worktree). Hard-fails rather than passing silently (mirrors the repo's source-anchored guards).
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
                    "the UC56 degraded-settings guard cannot run (refusing to pass silently).",
            )
        }
    }
}
