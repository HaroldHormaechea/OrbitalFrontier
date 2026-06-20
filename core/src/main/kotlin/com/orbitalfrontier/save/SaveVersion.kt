package com.orbitalfrontier.save

/**
 * Single source of truth for the save-format / schema version (ADR 0002).
 *
 * Previously each repository carried its own private `SAVE_VERSION` const, which would silently
 * drift as the schema grew. It now lives here once and every repository references it. The [init]
 * check ties the constant to the generated SQLDelight schema version, so adding a migration (which
 * bumps [OrbitalFrontier.Schema.version]) without updating [CURRENT] — or vice-versa — fails fast
 * at first access instead of seeding a stale `meta.save_version`.
 */
internal object SaveVersion {
    /** Current save-format/schema version; mirrors the SQLDelight schema and `meta.save_version`. */
    const val CURRENT: Long = 24L

    init {
        check(CURRENT == OrbitalFrontier.Schema.version) {
            "SaveVersion.CURRENT ($CURRENT) != generated SQLDelight schema version " +
                "(${OrbitalFrontier.Schema.version}); update SaveVersion.CURRENT when you add a migration"
        }
    }
}
