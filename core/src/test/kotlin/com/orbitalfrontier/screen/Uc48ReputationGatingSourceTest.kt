package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC48 — the reputation-gated acquisition wiring inside [OutfitScreen] /
 * [ShipyardScreen] (AC#1/#2/#4), [PlayScreen] (the resolver wiring + the live suppliers), and
 * [com.orbitalfrontier.app.OrbitalFrontierGame] (which injects the suppliers).
 *
 * Why source-anchored, not behavioural: the screens build live libGDX/scene2d objects in their field
 * initializers, which need a real GL context — they are NOT headlessly constructible, so the UI wiring
 * cannot be driven from a plain JVM unit test, and production must not be reshaped just to test it. The
 * *pure* gate + price logic is proven for real in [com.orbitalfrontier.faction.StandingGateTest],
 * [com.orbitalfrontier.economy.FactionPricingTest], [com.orbitalfrontier.outfit.OutfittingTest],
 * [com.orbitalfrontier.ship.FleetTest] and the deterministic transition in
 * [com.orbitalfrontier.faction.Uc48ReputationGatedAcquisitionTest]; here we pin the screen wiring at
 * the source level, mirroring the repo's existing guards
 * ([Uc47BuyUsedSourceTest], [Uc44CombatHudSourceTest], [Uc40EconomyFeedbackSourceTest]).
 *
 * All assertions run over **comment-stripped CODE** (block + line comments removed), so a doc comment
 * that merely names `StandingGate`/`FactionPricing`/`adjustedPrice` is prose and never satisfies the
 * guard — only real code does. The contract pinned:
 *  - AC#1/#4: each shop/shipyard row consults `StandingGate.status(item.unlockThreshold, factionId,
 *    reputation)` and, when locked, renders a "why locked" reason row instead of the buy button (the
 *    item stays visible, the action is withheld).
 *  - AC#2: the price shown AND the price the buy action carries both come from
 *    `FactionPricing.adjustedPrice(...)` — the same helper the resolvers charge with (display==charge).
 *  - PlayScreen threads `factionId`/`reputation`/`pricingParams` into BOTH `Outfitting.resolve` and
 *    `FleetResolver.resolve`, and exposes the live `reputationSnapshot()` / `pricingConfig()` suppliers.
 *  - OrbitalFrontierGame injects the docked station's faction + those live suppliers into both screens.
 */
class Uc48ReputationGatingSourceTest {
    // --- AC#1/#4: OutfitScreen consults the gate and renders a locked-with-reason row ------------------

    @Test
    fun `OutfitScreen consults StandingGate per offered part`() {
        assertTrue(
            "each row must compute StandingGate.status(upgrade.unlockThreshold, factionId, reputation)",
            OUTFIT_SCREEN.contains(
                Regex("""StandingGate\.status\s*\(\s*upgrade\.unlockThreshold\s*,\s*factionId\s*,\s*reputation\s*\)"""),
            ),
        )
    }

    @Test
    fun `OutfitScreen renders a locked-with-reason row instead of the install button when locked`() {
        assertTrue(
            "the locked branch must render the lock reason label (item stays visible, AC#4)",
            OUTFIT_SCREEN.contains(
                Regex(
                    """if\s*\(\s*status\.locked\s*\)\s*\{\s*root\.add\s*\(\s*Label\s*\(\s*lockReason""",
                    RegexOption.DOT_MATCHES_ALL,
                ),
            ),
        )
        assertTrue(
            "the lock reason must surface the standing requirement and the player's current standing",
            OUTFIT_SCREEN.contains(Regex("""Requires .*standing .*requiredStanding.*you: .*currentStanding""")),
        )
    }

    @Test
    fun `OutfitScreen shows and charges the faction-adjusted price (display==charge)`() {
        assertTrue(
            "the displayed price comes from FactionPricing.adjustedPrice(upgrade.price, factionId, reputation, pricingParams)",
            OUTFIT_SCREEN.contains(
                Regex("""FactionPricing\.adjustedPrice\s*\(\s*upgrade\.price\s*,\s*factionId\s*,\s*reputation\s*,\s*pricingParams\s*\)"""),
            ),
        )
        assertTrue(
            "the INSTALL action must carry the SAME adjusted `price`, not the raw catalog price",
            OUTFIT_SCREEN.contains(Regex("""installListener\s*\(\s*upgrade\.displayName\s*,\s*price\s*\)""")),
        )
    }

    // --- AC#1/#4: ShipyardScreen consults the gate and renders a locked-with-reason row ----------------

    @Test
    fun `ShipyardScreen consults StandingGate per offered hull`() {
        assertTrue(
            "each row must compute StandingGate.status(type.unlockThreshold, factionId, reputation)",
            SHIPYARD_SCREEN.contains(
                Regex("""StandingGate\.status\s*\(\s*type\.unlockThreshold\s*,\s*factionId\s*,\s*reputation\s*\)"""),
            ),
        )
    }

    @Test
    fun `ShipyardScreen renders a locked-with-reason row instead of the buy button when locked`() {
        assertTrue(
            "the locked branch must render the lock reason label (hull stays visible, AC#4)",
            SHIPYARD_SCREEN.contains(
                Regex(
                    """if\s*\(\s*status\.locked\s*\)\s*\{\s*root\.add\s*\(\s*Label\s*\(\s*lockReason""",
                    RegexOption.DOT_MATCHES_ALL,
                ),
            ),
        )
    }

    @Test
    fun `ShipyardScreen shows and charges the faction-adjusted price (display==charge)`() {
        assertTrue(
            "the displayed price comes from FactionPricing.adjustedPrice(type.price, factionId, reputation, pricingParams)",
            SHIPYARD_SCREEN.contains(
                Regex("""FactionPricing\.adjustedPrice\s*\(\s*type\.price\s*,\s*factionId\s*,\s*reputation\s*,\s*pricingParams\s*\)"""),
            ),
        )
        assertTrue(
            "the BUY action must carry the SAME adjusted `price`, not the raw catalog price",
            SHIPYARD_SCREEN.contains(Regex("""buyListener\s*\(\s*type\.displayName\s*,\s*price\s*\)""")),
        )
    }

    // --- PlayScreen threads the gate inputs into BOTH resolvers and exposes the live suppliers ---------

    @Test
    fun `PlayScreen threads faction, reputation and pricing into the outfit and fleet resolvers`() {
        // Both the outfit() and the fleetCommand() resolver calls must pass these three; require >= 2 of each.
        assertTrue(
            "both resolver calls must pass factionId = station.factionId",
            PLAY_SCREEN.findAll(Regex("""factionId\s*=\s*station\.factionId""")) >= 2,
        )
        assertTrue(
            "both resolver calls must pass the live reputation",
            PLAY_SCREEN.findAll(Regex("""reputation\s*=\s*reputation""")) >= 2,
        )
        assertTrue(
            "both resolver calls must pass the active pricingParams",
            PLAY_SCREEN.findAll(Regex("""pricingParams\s*=\s*pricingParams""")) >= 2,
        )
    }

    @Test
    fun `PlayScreen exposes the live reputation and pricing suppliers the screens read`() {
        assertTrue(
            "reputationSnapshot() exposes the live standing",
            PLAY_SCREEN.contains(Regex("""fun\s+reputationSnapshot\s*\(\s*\)\s*:\s*Reputation""")),
        )
        assertTrue(
            "pricingConfig() exposes the active pricing tunables",
            PLAY_SCREEN.contains(Regex("""fun\s+pricingConfig\s*\(\s*\)\s*:\s*PricingParams""")),
        )
    }

    // --- OrbitalFrontierGame injects the docked station's faction + the live suppliers into both screens

    @Test
    fun `OrbitalFrontierGame injects the faction and live suppliers into the outfit and shipyard screens`() {
        assertTrue(
            "both screen constructions must inject factionId = station.factionId",
            GAME.findAll(Regex("""factionId\s*=\s*station\.factionId""")) >= 2,
        )
        assertTrue(
            "both screens must receive a live reputation supplier off the play screen",
            GAME.findAll(Regex("""reputationSupplier\s*=\s*\{\s*playScreen\?\.reputationSnapshot\s*\(\s*\)""")) >= 2,
        )
        assertTrue(
            "both screens must receive the active pricing tunables off the play screen",
            GAME.findAll(Regex("""pricingParams\s*=\s*playScreen\?\.pricingConfig\s*\(\s*\)""")) >= 2,
        )
    }

    private companion object {
        private val OUTFIT_SCREEN =
            stripComments(readSource("core/src/main/kotlin/com/orbitalfrontier/screen/OutfitScreen.kt"))
        private val SHIPYARD_SCREEN =
            stripComments(readSource("core/src/main/kotlin/com/orbitalfrontier/screen/ShipyardScreen.kt"))
        private val PLAY_SCREEN =
            stripComments(readSource("core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt"))
        private val GAME =
            stripComments(readSource("core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt"))

        /** Count non-overlapping matches of [regex] in this string. */
        private fun String.findAll(regex: Regex): Int = regex.findAll(this).count()

        /**
         * Strip Kotlin block (`/* … */`) and line (`// …`) comments so the guards inspect actual CODE only.
         * Mirrors the comment-stripping in [Uc47BuyUsedSourceTest].
         */
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
                // Also try the candidate with the leading "core/" stripped (running from the module dir).
                for (candidate in candidates) {
                    val f = File(dir, candidate.removePrefix("core/"))
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate ${candidates.firstOrNull()} from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC48 reputation-gating source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
