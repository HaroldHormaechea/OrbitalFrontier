package com.orbitalfrontier.combat

/**
 * Maps the player's progression — modelled as the **count of installed ship upgrades** — to a coarse
 * integer **difficulty level** the [SpawnDirector] scales by (UC45 AC#3). A pure function of an `Int`, so
 * it is trivially deterministic and shared verbatim by the device ([com.orbitalfrontier.screen.PlayScreen]
 * `runCombat`) and the replay mirror (`sim.Simulation.step`) — the lockstep that keeps natural spawns
 * byte-identical across record/replay (AC#4).
 *
 * **Why a count, not the `Loadout` itself.** The upgrade [com.orbitalfrontier.outfit.Loadout] lives in the
 * `outfit` module, which already depends on `combat` ([com.orbitalfrontier.outfit.ShipStats] returns combat
 * types); having `combat` import `outfit` would close that dependency cycle. So the call sites pass
 * `loadout.allInstalled().size` and this stays a pure `Int → Int` map in `combat`. Progression (loadout)
 * was chosen over reputation as the difficulty input specifically so new archetypes stay **unaligned**
 * (keeping `CombatReputationTest` green) and difficulty seeds deterministically from the fixture's loadout.
 *
 * The [THRESHOLDS] are authored `[TUNE]` placeholders; the returned level is simply how many thresholds the
 * installed-upgrade count has reached (0 when below the first threshold).
 */
object ProgressionLevel {
    /** Installed-upgrade counts at which the difficulty level steps up, ascending. [TUNE] */
    val THRESHOLDS: List<Int> = listOf(2, 4)

    /** The difficulty level for [installedUpgradeCount] installed upgrades — the count of reached [THRESHOLDS]. */
    fun of(installedUpgradeCount: Int): Int = THRESHOLDS.count { installedUpgradeCount >= it }
}
