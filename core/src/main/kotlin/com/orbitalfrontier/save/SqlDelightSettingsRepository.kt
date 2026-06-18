package com.orbitalfrontier.save

import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.Handedness

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

    private companion object {
        const val TAG = "Save"
    }
}
