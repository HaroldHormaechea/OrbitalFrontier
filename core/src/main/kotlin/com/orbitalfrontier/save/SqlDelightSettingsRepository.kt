package com.orbitalfrontier.save

import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.settings.Handedness

/**
 * SQLDelight-backed [SettingsRepository] (ADR 0003).
 *
 * Constructor-injected with the generated [OrbitalFrontier] database and a [Logger] (DIP) — the
 * `SqlDriver` behind the database is supplied per platform, so this class is exercised in JVM
 * tests against an in-memory driver. All writes run inside `transaction { }` so a failure rolls
 * back and never leaves a half-written row (AC#13, coding-guidelines § "Transactional saves").
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
                parseHandedness(stored)
            }
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load handedness; using default=${Handedness.DEFAULT}", e)
            Handedness.DEFAULT
        }
    }

    override fun saveHandedness(handedness: Handedness) {
        try {
            queries.transaction {
                queries.upsertHandedness(handedness.name)
            }
            logger.info(TAG, "Persisted handedness=$handedness")
        } catch (e: Exception) {
            // Graceful degradation: keep the last good save, log, and do not crash the app.
            logger.error(TAG, "Failed to persist handedness=$handedness; last good value kept", e)
        }
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
