package com.orbitalfrontier.world

/**
 * The seed a [SectorWorld] is generated from (UC53; docs/design/world-and-sector.md "Layout —
 * procedural, with hand-authored test maps").
 *
 * A thin [Long] value type so a world seed is type-safe and cheap — never a bare `Long` at call
 * sites. Pure: no engine types, so the whole `world` package stays JVM-testable (ADR 0001) and
 * passes the replay-path purity guard.
 *
 * **[MVP] is the reserved zero seed.** [SectorGenerator.generate] returns the hand-authored
 * [MvpSectorMap] for [MVP] and only procedurally generates for any non-zero seed. A brand-new game,
 * a pre-UC53 save (whose `game_state.world_seed` column DEFAULTs to 0), and every existing replay
 * fixture all resolve to [MVP] ⇒ the authored map ⇒ byte-identical existing behaviour (the
 * zero-fixture-regen lever, ADR 0041). [value] is what `game_state.world_seed` persists.
 */
@JvmInline
value class WorldSeed(val value: Long) {
    companion object {
        /** The reserved seed that yields the hand-authored [MvpSectorMap] verbatim (ADR 0041). */
        val MVP: WorldSeed = WorldSeed(0L)
    }
}
