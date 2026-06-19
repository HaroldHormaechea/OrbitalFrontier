package com.orbitalfrontier.save

import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.MotionPreference
import com.orbitalfrontier.render.TextScale
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.ColorVisionMode
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning

/**
 * SQLDelight-backed [SettingsRepository] (ADR 0003).
 *
 * Constructor-injected with the generated [OrbitalFrontier] database and a [Logger] (DIP) — the
 * `SqlDriver` behind the database is supplied per platform, so this class is exercised in JVM
 * tests against an in-memory driver. All writes run inside `transaction { }` so a failure rolls
 * back and never leaves a half-written row (AC#13, coding-guidelines § "Transactional saves").
 *
 * UC31: the single settings row is seeded with defaults up front (in [ensureInitialized] and again,
 * idempotently, before every targeted write), and each preference is written through its own
 * column-scoped UPDATE, so toggling handedness can never clobber the audio columns and vice-versa
 * (Risk 1).
 */
class SqlDelightSettingsRepository(
    private val database: OrbitalFrontier,
    private val logger: Logger,
) : SettingsRepository {
    private val queries get() = database.orbitalFrontierQueries

    override fun ensureInitialized() {
        try {
            queries.transaction {
                queries.initMeta(SaveVersion.CURRENT)
                seedDefaultSettingsRow()
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to initialize save metadata (save_version=${SaveVersion.CURRENT})", e)
        }
    }

    override fun loadHandedness(): Handedness {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            if (stored == null) {
                logger.info(TAG, "No settings row; seeding default handedness=${Handedness.DEFAULT}")
                saveHandedness(Handedness.DEFAULT)
                Handedness.DEFAULT
            } else {
                parseHandedness(stored.handedness)
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load handedness; using default=${Handedness.DEFAULT}", e)
            Handedness.DEFAULT
        }
    }

    override fun saveHandedness(handedness: Handedness) {
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the handedness column (audio columns untouched).
                seedDefaultSettingsRow()
                queries.updateHandedness(handedness.name)
            }
            logger.info(TAG, "Persisted handedness=$handedness")
        } catch (e: Exception) {
            // Graceful degradation: keep the last good save, log, and do not crash the app.
            logger.error(TAG, "Failed to persist handedness=$handedness; last good value kept", e)
        }
    }

    override fun loadAudioSettings(): AudioSettings {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            if (stored == null) {
                logger.info(TAG, "No settings row; using default audio settings=${AudioSettings.DEFAULT}")
                AudioSettings.DEFAULT
            } else {
                AudioSettings(
                    masterMuted = stored.master_muted != 0L,
                    sfxVolume = stored.sfx_volume.toFloat(),
                    musicVolume = stored.music_volume.toFloat(),
                ).coerced()
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load audio settings; using default=${AudioSettings.DEFAULT}", e)
            AudioSettings.DEFAULT
        }
    }

    override fun saveAudioSettings(settings: AudioSettings) {
        val coerced = settings.coerced()
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the audio columns (handedness untouched).
                seedDefaultSettingsRow()
                queries.updateAudioSettings(
                    if (coerced.masterMuted) 1L else 0L,
                    coerced.sfxVolume.toDouble(),
                    coerced.musicVolume.toDouble(),
                )
            }
            logger.info(TAG, "Persisted audio settings=$coerced")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to persist audio settings=$coerced; last good value kept", e)
        }
    }

    override fun loadTutorialCompleted(): Boolean {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            // No row yet (fresh save) reads as "not completed" so the onboarding runs on first launch.
            stored != null && stored.tutorial_completed != 0L
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load tutorial-completed flag; assuming not completed", e)
            false
        }
    }

    override fun saveTutorialCompleted(completed: Boolean) {
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the tutorial_completed column (handedness +
                // audio columns untouched), mirroring the per-field discipline UC31 established.
                seedDefaultSettingsRow()
                queries.updateTutorialCompleted(if (completed) 1L else 0L)
            }
            logger.info(TAG, "Persisted tutorial_completed=$completed")
        } catch (e: Exception) {
            // Graceful degradation: keep the last good value, log, and do not crash the app.
            logger.error(TAG, "Failed to persist tutorial_completed=$completed; last good value kept", e)
        }
    }

    override fun loadJoystickTuning(): JoystickTuning {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            if (stored == null) {
                logger.info(TAG, "No settings row; using default joystick tuning=${JoystickTuning.DEFAULT}")
                JoystickTuning.DEFAULT
            } else {
                JoystickTuning(
                    sensitivity = stored.joystick_sensitivity.toFloat(),
                    deadzone = stored.joystick_deadzone.toFloat(),
                ).coerced()
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load joystick tuning; using default=${JoystickTuning.DEFAULT}", e)
            JoystickTuning.DEFAULT
        }
    }

    override fun saveJoystickTuning(tuning: JoystickTuning) {
        val coerced = tuning.coerced()
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the joystick columns (everything else untouched).
                seedDefaultSettingsRow()
                queries.updateJoystickTuning(coerced.sensitivity.toDouble(), coerced.deadzone.toDouble())
            }
            logger.info(TAG, "Persisted joystick tuning=$coerced")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to persist joystick tuning=$coerced; last good value kept", e)
        }
    }

    override fun loadUiScale(): Float {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            if (stored == null) {
                logger.info(TAG, "No settings row; using default UI scale=${UiScale.DEFAULT_FACTOR}")
                UiScale.DEFAULT_FACTOR
            } else {
                // Coerce on read so a corrupt/out-of-range stored value degrades to a safe in-range factor.
                UiScale.coerce(stored.ui_scale.toFloat())
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load UI scale; using default=${UiScale.DEFAULT_FACTOR}", e)
            UiScale.DEFAULT_FACTOR
        }
    }

    override fun saveUiScale(factor: Float) {
        val coerced = UiScale.coerce(factor)
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the ui_scale column (everything else untouched).
                seedDefaultSettingsRow()
                queries.updateUiScale(coerced.toDouble())
            }
            logger.info(TAG, "Persisted UI scale=$coerced")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to persist UI scale=$coerced; last good value kept", e)
        }
    }

    override fun loadColorVisionMode(): ColorVisionMode {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            if (stored == null) {
                logger.info(TAG, "No settings row; using default colour-vision mode=${ColorVisionMode.DEFAULT}")
                ColorVisionMode.DEFAULT
            } else {
                // Safe parse: an unknown/corrupt stored value degrades to the standard palette (+WARN).
                parseColorVisionMode(stored.colorblind_mode)
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load colour-vision mode; using default=${ColorVisionMode.DEFAULT}", e)
            ColorVisionMode.DEFAULT
        }
    }

    override fun saveColorVisionMode(mode: ColorVisionMode) {
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the colorblind_mode column (everything else untouched).
                seedDefaultSettingsRow()
                queries.updateColorblindMode(mode.name)
            }
            logger.info(TAG, "Persisted colour-vision mode=$mode")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to persist colour-vision mode=$mode; last good value kept", e)
        }
    }

    override fun loadTextScale(): Float {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            if (stored == null) {
                logger.info(TAG, "No settings row; using default text scale=${TextScale.DEFAULT_FACTOR}")
                TextScale.DEFAULT_FACTOR
            } else {
                // Coerce on read so a corrupt/out-of-range stored value degrades to a safe in-range factor.
                TextScale.coerce(stored.text_scale.toFloat())
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load text scale; using default=${TextScale.DEFAULT_FACTOR}", e)
            TextScale.DEFAULT_FACTOR
        }
    }

    override fun saveTextScale(factor: Float) {
        val coerced = TextScale.coerce(factor)
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the text_scale column (everything else untouched).
                seedDefaultSettingsRow()
                queries.updateTextScale(coerced.toDouble())
            }
            logger.info(TAG, "Persisted text scale=$coerced")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to persist text scale=$coerced; last good value kept", e)
        }
    }

    override fun loadReducedMotion(): Boolean {
        return try {
            val stored = queries.selectSettings().executeAsOneOrNull()
            // No row yet (fresh save) reads as "motion on" (the default full-parallax behaviour).
            stored != null && stored.reduced_motion != 0L
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load reduced-motion flag; assuming motion on", e)
            MotionPreference.DEFAULT_REDUCED
        }
    }

    override fun saveReducedMotion(reduced: Boolean) {
        try {
            queries.transaction {
                // Ensure the row exists, then write ONLY the reduced_motion column (everything else untouched).
                seedDefaultSettingsRow()
                queries.updateReducedMotion(if (reduced) 1L else 0L)
            }
            logger.info(TAG, "Persisted reduced_motion=$reduced")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to persist reduced_motion=$reduced; last good value kept", e)
        }
    }

    /**
     * Seed the single settings row with defaults if it is absent (INSERT OR IGNORE). Idempotent and
     * side-effect-free on an existing row, so it is safe to call inside every write transaction; it
     * guarantees the targeted UPDATE writes below always hit a row. `tutorial_completed` is omitted from
     * the seed INSERT, so a freshly-seeded row takes its schema DEFAULT 0 ("tutorial not yet shown").
     */
    private fun seedDefaultSettingsRow() {
        queries.seedSettings(
            Handedness.DEFAULT.name,
            if (AudioSettings.DEFAULT.masterMuted) 1L else 0L,
            AudioSettings.DEFAULT.sfxVolume.toDouble(),
            AudioSettings.DEFAULT.musicVolume.toDouble(),
        )
    }

    private fun parseHandedness(value: String): Handedness =
        runCatching { Handedness.valueOf(value) }
            .getOrElse {
                logger.warn(TAG, "Unrecognized handedness '$value'; using default=${Handedness.DEFAULT}")
                Handedness.DEFAULT
            }

    private fun parseColorVisionMode(value: String): ColorVisionMode {
        val parsed = ColorVisionMode.parse(value)
        if (parsed.name != value) {
            logger.warn(TAG, "Unrecognized colour-vision mode '$value'; using default=${ColorVisionMode.DEFAULT}")
        }
        return parsed
    }

    private companion object {
        const val TAG = "Save"
    }
}
