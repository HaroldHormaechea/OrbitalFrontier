package com.orbitalfrontier.save

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence round-trip + error-path tests for [SqlDelightSettingsRepository], exercised
 * against an in-memory [JdbcSqliteDriver] (ADR 0003 — the same `core` code that runs on the
 * Android driver on device). Covers AC#8 (persist/reload handedness) and AC#13 (save_version,
 * transactional writes, graceful first-run / corrupt-row handling), plus UC31 AC#3 — the audio
 * preferences (master mute + per-channel volume) round-trip, default on first run, coerce a corrupt
 * stored value, and — Risk 1 — never clobber the handedness column (and vice-versa) since each
 * preference is written through its own column-scoped UPDATE. UC37 adds the joystick tuning
 * (sensitivity + deadzone) and the UI scale: same round-trip / default-on-first-run / coerce-on-read /
 * per-field-isolation contract, each via its own column-scoped UPDATE.
 *
 * "App restart" is simulated by constructing a fresh repository over the *same* live driver
 * (the in-memory DB persists for the lifetime of the connection), so the reload genuinely goes
 * back through SQL rather than reading an in-process field.
 */
class SqlDelightSettingsRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier
    private lateinit var logger: CapturingLogger

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitalFrontier.Schema.create(driver)
        database = OrbitalFrontier(driver)
        logger = CapturingLogger()
    }

    @After
    fun tearDown() {
        // Some tests close the driver themselves (graceful-degradation case); ignore a double close.
        runCatching { driver.close() }
    }

    private fun newRepository() = SqlDelightSettingsRepository(database, logger)

    // --- AC#13: save metadata / save_version ---

    @Test
    fun `ensureInitialized seeds the save_version row at the current version`() {
        newRepository().ensureInitialized()

        val version = database.orbitalFrontierQueries.selectSaveVersion().executeAsOne()
        // UC37 bumped the schema to v16 (the settings joystick_sensitivity/joystick_deadzone/ui_scale
        // columns on top of the UC36 tutorial flag); ensureInitialized seeds SaveVersion.CURRENT (16L),
        // pinned to OrbitalFrontier.Schema.version.
        assertEquals(OrbitalFrontier.Schema.version, version)
        assertEquals(16L, version)
    }

    @Test
    fun `ensureInitialized is idempotent across repeated launches`() {
        val repo = newRepository()
        repo.ensureInitialized()
        repo.ensureInitialized()

        val version = database.orbitalFrontierQueries.selectSaveVersion().executeAsOne()
        assertEquals("repeated init keeps a single version-16 row", 16L, version)
    }

    // --- AC#13: first-run / missing settings row handled gracefully ---

    @Test
    fun `first run with no settings row returns the default and seeds it`() {
        val repo = newRepository()

        val loaded = repo.loadHandedness()

        assertEquals(Handedness.DEFAULT, loaded)
        val persisted = database.orbitalFrontierQueries.selectSettings().executeAsOneOrNull()
        assertEquals("missing row is seeded with the default", Handedness.DEFAULT.name, persisted?.handedness)
    }

    // --- AC#8: persist + reload across a simulated restart ---

    @Test
    fun `saved handedness survives a reload`() {
        newRepository().saveHandedness(Handedness.LEFT_HANDED)

        // Fresh repository over the same DB == app restart.
        val reloaded = newRepository().loadHandedness()

        assertEquals(Handedness.LEFT_HANDED, reloaded)
    }

    @Test
    fun `the latest saved handedness wins`() {
        val repo = newRepository()
        repo.saveHandedness(Handedness.LEFT_HANDED)
        repo.saveHandedness(Handedness.RIGHT_HANDED)

        assertEquals(Handedness.RIGHT_HANDED, newRepository().loadHandedness())
    }

    // --- Error path: corrupt / unknown stored value ---

    @Test
    fun `an unrecognized stored handedness falls back to the default and warns`() {
        // UC31 replaced the whole-row upsertHandedness with seed + per-field UPDATE; inject the corrupt
        // value the same way the repository writes (seed the row first, then the targeted handedness UPDATE).
        val queries = database.orbitalFrontierQueries
        queries.seedSettings(
            Handedness.DEFAULT.name,
            if (AudioSettings.DEFAULT.masterMuted) 1L else 0L,
            AudioSettings.DEFAULT.sfxVolume.toDouble(),
            AudioSettings.DEFAULT.musicVolume.toDouble(),
        )
        queries.updateHandedness("NOT_A_REAL_VALUE")

        val loaded = newRepository().loadHandedness()

        assertEquals(Handedness.DEFAULT, loaded)
        assertTrue("corruption should be logged at WARN", logger.warnings.isNotEmpty())
    }

    // --- Graceful degradation: a write failure must not crash the app ---

    @Test
    fun `saveHandedness does not throw when the driver is unavailable`() {
        val repo = newRepository()
        driver.close() // subsequent SQL will fail inside the transaction

        // Autosave-style: the failure is caught and logged, never propagated.
        repo.saveHandedness(Handedness.LEFT_HANDED)

        assertTrue("the write failure should be logged at ERROR", logger.errors.isNotEmpty())
    }

    // --- UC31 AC#3: audio preferences persist + reload ---

    @Test
    fun `first run with no settings row returns the default audio settings`() {
        // No row written yet → a graceful default read, not an exception.
        val loaded = newRepository().loadAudioSettings()

        assertEquals(AudioSettings.DEFAULT, loaded)
    }

    @Test
    fun `saved audio settings survive a reload`() {
        val saved = AudioSettings(masterMuted = true, sfxVolume = 0.25f, musicVolume = 0.75f)
        newRepository().saveAudioSettings(saved)

        // Fresh repository over the same DB == app restart.
        val reloaded = newRepository().loadAudioSettings()

        assertEquals(saved, reloaded)
    }

    @Test
    fun `the latest saved audio settings win`() {
        val repo = newRepository()
        repo.saveAudioSettings(AudioSettings(masterMuted = true, sfxVolume = 0.1f, musicVolume = 0.2f))
        val latest = AudioSettings(masterMuted = false, sfxVolume = 0.9f, musicVolume = 0.4f)
        repo.saveAudioSettings(latest)

        assertEquals(latest, newRepository().loadAudioSettings())
    }

    @Test
    fun `loadAudioSettings coerces an out-of-range stored volume back into range`() {
        // Inject out-of-range gains directly (a corrupt save or a future control), bypassing
        // saveAudioSettings (which would coerce on write) to prove the read path also coerces. Both
        // bounds are exercised: an over-1.0 SFX gain and a negative music gain. (NaN can't be stored —
        // SQLite collapses it to NULL, which the NOT NULL column rejects — so NaN coercion is covered at
        // the unit level in AudioSettingsTest.)
        val queries = database.orbitalFrontierQueries
        queries.seedSettings(
            Handedness.DEFAULT.name,
            0L,
            AudioSettings.DEFAULT.sfxVolume.toDouble(),
            AudioSettings.DEFAULT.musicVolume.toDouble(),
        )
        queries.updateAudioSettings(0L, 4.2, -0.5)

        val loaded = newRepository().loadAudioSettings()

        assertEquals("an over-1.0 SFX gain clamps to 1.0", 1.0f, loaded.sfxVolume, 0f)
        assertEquals("a negative music gain clamps to 0.0", 0.0f, loaded.musicVolume, 0f)
    }

    @Test
    fun `saveAudioSettings does not throw when the driver is unavailable`() {
        val repo = newRepository()
        driver.close() // subsequent SQL will fail inside the transaction

        repo.saveAudioSettings(AudioSettings(masterMuted = true, sfxVolume = 0.5f, musicVolume = 0.5f))

        assertTrue("the write failure should be logged at ERROR", logger.errors.isNotEmpty())
    }

    // --- UC31 Risk 1: per-field writes never clobber the other preference ---

    @Test
    fun `saving handedness does not clobber previously-saved audio settings`() {
        val repo = newRepository()
        val audio = AudioSettings(masterMuted = true, sfxVolume = 0.3f, musicVolume = 0.6f)
        repo.saveAudioSettings(audio)

        // A later handedness toggle must leave the audio columns untouched.
        repo.saveHandedness(Handedness.RIGHT_HANDED)

        val freshRepo = newRepository()
        assertEquals("handedness landed", Handedness.RIGHT_HANDED, freshRepo.loadHandedness())
        assertEquals("audio columns survive a handedness write", audio, freshRepo.loadAudioSettings())
    }

    @Test
    fun `saving audio settings does not clobber previously-saved handedness`() {
        val repo = newRepository()
        repo.saveHandedness(Handedness.LEFT_HANDED)

        // A later audio change must leave the handedness column untouched.
        val audio = AudioSettings(masterMuted = true, sfxVolume = 0.2f, musicVolume = 0.8f)
        repo.saveAudioSettings(audio)

        val freshRepo = newRepository()
        assertEquals("handedness survives an audio write", Handedness.LEFT_HANDED, freshRepo.loadHandedness())
        assertEquals("audio landed", audio, freshRepo.loadAudioSettings())
        // Sanity: the two never collapse into one another.
        assertFalse("mute really persisted", AudioSettings.DEFAULT.masterMuted == freshRepo.loadAudioSettings().masterMuted)
    }

    // --- UC36 AC#3: first-run tutorial flag persists + reloads ---

    @Test
    fun `first run with no settings row reports the tutorial as not completed`() {
        // No row written yet → the onboarding must run, so the flag reads back false (not an exception).
        assertFalse(newRepository().loadTutorialCompleted())
    }

    @Test
    fun `a freshly-seeded settings row still reports the tutorial as not completed`() {
        // ensureInitialized seeds the row but omits tutorial_completed, so it takes its DEFAULT 0 — the
        // onboarding runs on a brand-new save even after the row exists (AC#1).
        val repo = newRepository()
        repo.ensureInitialized()
        assertFalse("a seeded-but-unflagged row reads back as not completed", repo.loadTutorialCompleted())
    }

    @Test
    fun `a saved tutorial-completed flag survives a reload`() {
        newRepository().saveTutorialCompleted(true)

        // Fresh repository over the same DB == app restart: the flag must stop the tutorial re-triggering (AC#3).
        assertTrue(newRepository().loadTutorialCompleted())
    }

    @Test
    fun `the latest saved tutorial flag wins (replay clears it back to not-completed)`() {
        val repo = newRepository()
        repo.saveTutorialCompleted(true)
        // Replaying the tutorial from settings persists "not completed" again.
        repo.saveTutorialCompleted(false)

        assertFalse("the latest flag write wins", newRepository().loadTutorialCompleted())
    }

    @Test
    fun `saveTutorialCompleted does not throw when the driver is unavailable`() {
        val repo = newRepository()
        driver.close() // subsequent SQL will fail inside the transaction

        // Autosave-style graceful degradation: the failure is caught and logged, never propagated.
        repo.saveTutorialCompleted(true)

        assertTrue("the write failure should be logged at ERROR", logger.errors.isNotEmpty())
    }

    // --- UC36 Risk (per-field writes, mirroring UC31 Risk 1): the tutorial flag never clobbers, and is
    //     never clobbered by, the handedness / audio columns, since each is a column-scoped UPDATE ---

    @Test
    fun `saving the tutorial flag does not clobber previously-saved handedness or audio settings`() {
        val repo = newRepository()
        val audio = AudioSettings(masterMuted = true, sfxVolume = 0.3f, musicVolume = 0.6f)
        repo.saveHandedness(Handedness.RIGHT_HANDED)
        repo.saveAudioSettings(audio)

        // A later tutorial-completion write must leave handedness + the audio columns untouched.
        repo.saveTutorialCompleted(true)

        val freshRepo = newRepository()
        assertTrue("tutorial flag landed", freshRepo.loadTutorialCompleted())
        assertEquals("handedness survives a tutorial-flag write", Handedness.RIGHT_HANDED, freshRepo.loadHandedness())
        assertEquals("audio columns survive a tutorial-flag write", audio, freshRepo.loadAudioSettings())
    }

    @Test
    fun `saving handedness or audio settings does not clobber a previously-saved tutorial flag`() {
        val repo = newRepository()
        repo.saveTutorialCompleted(true)

        // Later handedness + audio changes must leave the tutorial_completed column untouched.
        repo.saveHandedness(Handedness.LEFT_HANDED)
        repo.saveAudioSettings(AudioSettings(masterMuted = false, sfxVolume = 0.9f, musicVolume = 0.4f))

        val freshRepo = newRepository()
        assertTrue("tutorial flag survives handedness + audio writes", freshRepo.loadTutorialCompleted())
        assertEquals("handedness landed", Handedness.LEFT_HANDED, freshRepo.loadHandedness())
    }

    // --- UC37 AC#2: joystick tuning persists + reloads ---------------------------------------------

    @Test
    fun `first run with no settings row returns the default joystick tuning`() {
        // No row written yet → a graceful default read, not an exception.
        assertEquals(JoystickTuning.DEFAULT, newRepository().loadJoystickTuning())
    }

    @Test
    fun `saved joystick tuning survives a reload`() {
        val saved = JoystickTuning(sensitivity = 1.5f, deadzone = 0.4f)
        newRepository().saveJoystickTuning(saved)

        // Fresh repository over the same DB == app restart.
        assertEquals(saved, newRepository().loadJoystickTuning())
    }

    @Test
    fun `the latest saved joystick tuning wins`() {
        val repo = newRepository()
        repo.saveJoystickTuning(JoystickTuning(sensitivity = 0.5f, deadzone = 0.2f))
        val latest = JoystickTuning(sensitivity = 2.5f, deadzone = 0.55f)
        repo.saveJoystickTuning(latest)

        assertEquals(latest, newRepository().loadJoystickTuning())
    }

    @Test
    fun `loadJoystickTuning coerces an out-of-range stored value back into range`() {
        // Inject out-of-range values directly (a corrupt save or a future control), bypassing
        // saveJoystickTuning (which coerces on write) to prove the read path also coerces. Both bounds
        // are exercised: a zero sensitivity + sub-floor deadzone, then over-max values. (NaN can't be
        // stored — SQLite collapses it to NULL, which the NOT NULL column rejects — so NaN coercion is
        // covered at the unit level in JoystickTuningTest.)
        val queries = database.orbitalFrontierQueries
        seedRow()
        queries.updateJoystickTuning(0.0, 0.05)
        val low = newRepository().loadJoystickTuning()
        assertEquals("a zero sensitivity clamps to the minimum", JoystickTuning.MIN_SENSITIVITY, low.sensitivity, 0f)
        assertEquals("a sub-floor deadzone clamps to the model floor", JoystickTuning.MIN_DEADZONE, low.deadzone, 0f)

        queries.updateJoystickTuning(99.0, 9.0)
        val high = newRepository().loadJoystickTuning()
        assertEquals("an over-max sensitivity clamps to the maximum", JoystickTuning.MAX_SENSITIVITY, high.sensitivity, 0f)
        assertEquals("an over-max deadzone clamps to the maximum", JoystickTuning.MAX_DEADZONE, high.deadzone, 0f)
    }

    @Test
    fun `saveJoystickTuning does not throw when the driver is unavailable`() {
        val repo = newRepository()
        driver.close() // subsequent SQL will fail inside the transaction

        repo.saveJoystickTuning(JoystickTuning(sensitivity = 1.5f, deadzone = 0.3f))

        assertTrue("the write failure should be logged at ERROR", logger.errors.isNotEmpty())
    }

    // --- UC37 AC#2: UI scale persists + reloads ----------------------------------------------------

    @Test
    fun `first run with no settings row returns the default UI scale`() {
        assertEquals(UiScale.DEFAULT_FACTOR, newRepository().loadUiScale(), 0f)
    }

    @Test
    fun `a saved UI scale survives a reload`() {
        newRepository().saveUiScale(2.5f)

        assertEquals(2.5f, newRepository().loadUiScale(), 0f)
    }

    @Test
    fun `the latest saved UI scale wins`() {
        val repo = newRepository()
        repo.saveUiScale(1.5f)
        repo.saveUiScale(3f)

        assertEquals(3f, newRepository().loadUiScale(), 0f)
    }

    @Test
    fun `loadUiScale coerces an out-of-range stored value back into range`() {
        // Inject out-of-range factors directly to prove the read path coerces (both bounds).
        val queries = database.orbitalFrontierQueries
        seedRow()
        queries.updateUiScale(9.0)
        assertEquals("an over-max factor clamps to the maximum", UiScale.MAX_FACTOR, newRepository().loadUiScale(), 0f)
        queries.updateUiScale(0.25)
        assertEquals("a below-min factor clamps to the minimum", UiScale.MIN_FACTOR, newRepository().loadUiScale(), 0f)
    }

    @Test
    fun `saveUiScale does not throw when the driver is unavailable`() {
        val repo = newRepository()
        driver.close() // subsequent SQL will fail inside the transaction

        repo.saveUiScale(2.5f)

        assertTrue("the write failure should be logged at ERROR", logger.errors.isNotEmpty())
    }

    // --- UC37 Risk (per-field writes, mirroring UC31 Risk 1): each new preference is column-scoped, so
    //     writing one never clobbers the others (and they are never clobbered by handedness/audio/tutorial) ---

    @Test
    fun `each preference write leaves every sibling preference intact`() {
        val repo = newRepository()
        // Establish a distinct, non-default value for every column.
        val audio = AudioSettings(masterMuted = true, sfxVolume = 0.3f, musicVolume = 0.6f)
        val tuning = JoystickTuning(sensitivity = 2.0f, deadzone = 0.55f)
        repo.saveHandedness(Handedness.RIGHT_HANDED)
        repo.saveAudioSettings(audio)
        repo.saveTutorialCompleted(true)
        repo.saveJoystickTuning(tuning)
        repo.saveUiScale(2.5f)

        // A later joystick write must touch ONLY the joystick columns.
        val newTuning = JoystickTuning(sensitivity = 0.75f, deadzone = 0.25f)
        repo.saveJoystickTuning(newTuning)
        var fresh = newRepository()
        assertEquals("joystick tuning landed", newTuning, fresh.loadJoystickTuning())
        assertEquals("handedness untouched by a joystick write", Handedness.RIGHT_HANDED, fresh.loadHandedness())
        assertEquals("audio untouched by a joystick write", audio, fresh.loadAudioSettings())
        assertTrue("tutorial flag untouched by a joystick write", fresh.loadTutorialCompleted())
        assertEquals("UI scale untouched by a joystick write", 2.5f, fresh.loadUiScale(), 0f)

        // A later UI-scale write must touch ONLY the ui_scale column.
        repo.saveUiScale(1.5f)
        fresh = newRepository()
        assertEquals("UI scale landed", 1.5f, fresh.loadUiScale(), 0f)
        assertEquals("joystick tuning untouched by a UI-scale write", newTuning, fresh.loadJoystickTuning())
        assertEquals("handedness untouched by a UI-scale write", Handedness.RIGHT_HANDED, fresh.loadHandedness())
        assertEquals("audio untouched by a UI-scale write", audio, fresh.loadAudioSettings())
        assertTrue("tutorial flag untouched by a UI-scale write", fresh.loadTutorialCompleted())
    }

    @Test
    fun `a handedness write does not clobber the joystick tuning or UI scale`() {
        val repo = newRepository()
        val tuning = JoystickTuning(sensitivity = 1.5f, deadzone = 0.4f)
        repo.saveJoystickTuning(tuning)
        repo.saveUiScale(2.5f)

        repo.saveHandedness(Handedness.LEFT_HANDED)

        val fresh = newRepository()
        assertEquals("handedness landed", Handedness.LEFT_HANDED, fresh.loadHandedness())
        assertEquals("joystick tuning survives a handedness write", tuning, fresh.loadJoystickTuning())
        assertEquals("UI scale survives a handedness write", 2.5f, fresh.loadUiScale(), 0f)
        // Sanity: the new preferences really persisted, not just defaulted to the same values.
        assertNotEquals("the persisted tuning is genuinely non-default", JoystickTuning.DEFAULT, fresh.loadJoystickTuning())
    }

    /** Seed the single settings row the same way the repository does, so a targeted UPDATE hits a row. */
    private fun seedRow() {
        database.orbitalFrontierQueries.seedSettings(
            Handedness.DEFAULT.name,
            if (AudioSettings.DEFAULT.masterMuted) 1L else 0L,
            AudioSettings.DEFAULT.sfxVolume.toDouble(),
            AudioSettings.DEFAULT.musicVolume.toDouble(),
        )
    }

    /** Logger that records WARN/ERROR messages so error-path tests can assert on them. */
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun debug(
            tag: String,
            message: String,
        ) = Unit

        override fun info(
            tag: String,
            message: String,
        ) = Unit

        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            warnings += message
        }

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) {
            errors += message
        }
    }
}
