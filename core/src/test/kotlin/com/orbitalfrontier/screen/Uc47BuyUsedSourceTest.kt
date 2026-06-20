package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC47 — the junkyard buy-used wiring inside [OutfitScreen] (AC#1/#2) and
 * [PlayScreen] (AC#2/#3).
 *
 * Why source-anchored, not behavioural: [OutfitScreen] / [PlayScreen] build live libGDX/scene2d objects
 * in their field initializers, which need a real GL context — they are NOT headlessly constructible, so
 * the UI wiring cannot be driven from a plain JVM unit test, and production must not be reshaped just to
 * test it. The *pure* used-part logic (the discount curve, the deterministic stock, the resolver) is
 * proven for real in [com.orbitalfrontier.outfit.UsedPartPricingTest] /
 * [com.orbitalfrontier.outfit.JunkyardStockTest] / [com.orbitalfrontier.outfit.OutfittingTest] and the
 * end-to-end replay in [com.orbitalfrontier.playthrough.Uc47BuyUsedReplayTest]; here we pin the screen
 * wiring at the source level, mirroring the repo's existing guards
 * ([Uc44CombatHudSourceTest], [Uc40EconomyFeedbackSourceTest]).
 *
 * All assertions run over **comment-stripped CODE** (block + line comments removed), so a doc comment
 * that merely names `UsedPartPricing`/`BuyUsed`/`junkyardStock` is prose and never satisfies the guard —
 * only real code does. The contract pinned:
 *  - AC#1: OutfitScreen renders the USED PARTS list only at a junkyard, deriving each displayed price via
 *    the SAME pure `UsedPartPricing.usedPrice(upgrade.price, usedPartParams)` the resolver charges with
 *    (so the shown price IS the charged price — no second number can drift), and shows remaining stock
 *    from the live supplier.
 *  - AC#2: a BUY USED tap fires `OutfitOrder.BuyUsed(upgrade.id)`, and PlayScreen routes it through
 *    `Outfitting.resolve(...)` threading the junkyard desk + durable depletion + station key + tunables.
 *  - AC#3: PlayScreen folds the (possibly mutated) depletion back (`junkyardStock = result.junkyardStock`)
 *    and persists it into the autosave snapshot, and derives the live available count as the deterministic
 *    baseline minus persisted purchases, floored at 0 — the same availability the resolver gates on.
 */
class Uc47BuyUsedSourceTest {
    // --- AC#1: OutfitScreen used-parts list (price shown == price charged) ------------------------------

    @Test
    fun `OutfitScreen renders the used-parts list only at a junkyard, from its used-part desk`() {
        assertTrue(
            "the USED PARTS list must be gated on isJunkyard",
            OUTFIT_SCREEN.contains(Regex("""if\s*\(\s*isJunkyard\s*\)""")),
        )
        assertTrue(
            "the used list must be built from the junkyard's usedPartMarket (offers)",
            OUTFIT_SCREEN.contains(Regex("""usedPartMarket\.offers""")),
        )
    }

    @Test
    fun `OutfitScreen derives the displayed used price via the pure UsedPartPricing curve`() {
        // UC48 compose-on-base: the used price is now derived on top of the FACTION-ADJUSTED catalog price
        // (faction-adjust the base, then the used discount) — the SAME composition the resolver charges, so
        // shown==charged still holds (byte-identical at neutral standing where adjustedPrice == catalog price).
        assertTrue(
            "the displayed used price must compose on the faction-adjusted base: UsedPartPricing.usedPrice(factionBase, usedPartParams)",
            OUTFIT_SCREEN.contains(Regex("""UsedPartPricing\.usedPrice\s*\(\s*factionBase\s*,\s*usedPartParams\s*\)""")),
        )
        assertTrue(
            "that factionBase must itself be the faction-adjusted catalog price (FactionPricing.adjustedPrice(upgrade.price, ...))",
            OUTFIT_SCREEN.contains(Regex("""factionBase\s*=\s*FactionPricing\.adjustedPrice\s*\(\s*upgrade\.price\s*,""")),
        )
    }

    @Test
    fun `OutfitScreen shows remaining stock from the live supplier`() {
        assertTrue(
            "the remaining-stock readout must come from the injected usedStockSupplier (baseline − purchased)",
            OUTFIT_SCREEN.contains(Regex("""usedStockSupplier\s*\(\s*upgrade\.id\s*\)""")),
        )
    }

    @Test
    fun `OutfitScreen fires a BuyUsed order with the SAME price it displayed`() {
        // The displayed `usedPrice` value is the one handed to the BUY action — there is no separate
        // number, so the price the player sees is exactly the price the resolver deducts (the AC#1 promise).
        assertTrue(
            "a BUY USED tap must carry the displayed usedPrice and fire OutfitOrder.BuyUsed(upgrade.id)",
            OUTFIT_SCREEN.contains(
                Regex("""usedPrice\s*\)\s*\{\s*OutfitOrder\.BuyUsed\s*\(\s*upgrade\.id\s*\)""", RegexOption.DOT_MATCHES_ALL),
            ),
        )
    }

    // --- AC#2: PlayScreen routes the tap through the pure resolver -------------------------------------

    @Test
    fun `PlayScreen threads the junkyard desk, depletion, station key and tunables into the resolver`() {
        assertTrue(
            "outfit() must pass the junkyard's used-part desk into Outfitting.resolve",
            PLAY_SCREEN.contains(Regex("""usedPartMarket\s*=\s*station\.usedPartMarket""")),
        )
        assertTrue(
            "outfit() must pass the durable depletion into the resolver",
            PLAY_SCREEN.contains(Regex("""junkyardStock\s*=\s*junkyardStock""")),
        )
        assertTrue(
            "outfit() must pass the per-junkyard stock key (stationId) into the resolver",
            PLAY_SCREEN.contains(Regex("""stationId\s*=\s*stationId""")),
        )
        assertTrue(
            "outfit() must pass the used-part tunables into the resolver",
            PLAY_SCREEN.contains(Regex("""usedPartParams\s*=\s*usedPartParams""")),
        )
    }

    // --- AC#3: PlayScreen folds the depletion back, persists it, and derives available stock -----------

    @Test
    fun `PlayScreen folds the resolved depletion back after an outfit order`() {
        assertTrue(
            "the (possibly mutated) depletion must be written back from the result (only a BuyUsed grows it)",
            PLAY_SCREEN.contains(Regex("""junkyardStock\s*=\s*result\.junkyardStock""")),
        )
    }

    @Test
    fun `PlayScreen persists the depletion into the autosave snapshot`() {
        assertTrue(
            "the world snapshot the autosave writes must carry the junkyard depletion",
            PLAY_SCREEN.contains(Regex("""junkyardStock\s*=\s*junkyardStock\s*,""")),
        )
    }

    @Test
    fun `PlayScreen derives available stock as the deterministic baseline minus persisted purchases`() {
        assertTrue(
            "available stock must use the deterministic baseline (UsedPartPricing.baselineStock)",
            PLAY_SCREEN.contains(Regex("""UsedPartPricing\.baselineStock\s*\(""")),
        )
        assertTrue(
            "available = baseline − purchased, floored at 0 (matches the resolver's availability gate)",
            PLAY_SCREEN.contains(
                Regex(
                    """baseline\s*-\s*junkyardStock\.purchasedCount\s*\([^)]*\)\s*\)\s*\.coerceAtLeast\s*\(\s*0\s*\)""",
                    RegexOption.DOT_MATCHES_ALL,
                ),
            ),
        )
    }

    private companion object {
        private val OUTFIT_SCREEN =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/screen/OutfitScreen.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/screen/OutfitScreen.kt",
                ),
            )
        private val PLAY_SCREEN =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                ),
            )

        /**
         * Strip Kotlin block (`/* … */`) and line (`// …`) comments so the guards inspect actual CODE only.
         * Mirrors the comment-stripping in [Uc44CombatHudSourceTest].
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
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate ${candidates.firstOrNull()} from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC47 buy-used source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
