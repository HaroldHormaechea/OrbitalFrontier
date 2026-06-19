package com.orbitalfrontier.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC40 — purchase confirmation & economy feedback (AC#1/#2/#3/#4).
 *
 * Why source-anchored, not behavioural: every economy screen ([TradeScreen], [OutfitScreen],
 * [ShipyardScreen], [HireScreen], [StationHubScreen]) and [PlayScreen] build live libGDX objects
 * (`OrthographicCamera`, scene2d `Stage`/`Label`/`Table`, the `NotificationRenderer`'s `SpriteBatch`) in
 * their field initializers, which need a real GL context. They are NOT headlessly constructible — so the
 * confirm-dialog wiring, the shared-queue rendering, and the styled-error routing cannot be driven from a
 * plain JVM unit test, and production must not be reshaped just to make them testable. The *decision* logic
 * is pure and is proven for real in [com.orbitalfrontier.economy.PurchaseGateTest] /
 * [com.orbitalfrontier.notify.GameNotificationsTest] / [com.orbitalfrontier.notify.NotificationQueueTest];
 * here we pin the wiring at the source level, mirroring the repo's existing guards
 * ([StationRefuelFeedbackSourceTest], [com.orbitalfrontier.app.Uc17StartingCreditsTest]).
 *
 * All assertions run over **comment-stripped CODE** (block + line comments removed), so a doc comment that
 * merely names `PurchaseGate`/`dialog`/`notifications` is prose and never satisfies the guard — only real
 * code does. The contract pinned:
 *  - AC#1: each of the four buy screens routes a buy tap through the pure `PurchaseGate.evaluate`, handles
 *    all three `SpendDecision` outcomes, and **mounts the confirm dialog on CONFIRM**.
 *  - AC#3: the unaffordable branch enqueues the styled INSUFFICIENT-CREDITS toast (not a bare string).
 *  - AC#2/#4: all four buy screens + the station hub render the shared queue (advance + animated draw).
 *  - AC#3/#4: `PlayScreen`'s four economy no-op branches and the refuel/buy-fuel statuses enqueue a styled
 *    error classified through the gate.
 *  - AC#4: `OrbitalFrontierGame` builds exactly ONE shared queue and injects it into every economy surface,
 *    so a delta/error raised on one screen surfaces on whichever screen is up.
 */
class Uc40EconomyFeedbackSourceTest {
    // --- AC#1/#3: the four buy screens gate the buy, confirm at/above threshold, and style the refusal ---

    @Test
    fun `each buy screen routes its buy tap through the pure PurchaseGate`() {
        for ((name, code) in BUY_SCREENS) {
            assertTrue(
                "$name must consult the pure PurchaseGate.evaluate for the spend decision (AC#1)",
                code.contains(Regex("""PurchaseGate\.evaluate\s*\(""")),
            )
        }
    }

    @Test
    fun `each buy screen handles all three SpendDecision outcomes`() {
        for ((name, code) in BUY_SCREENS) {
            for (decision in listOf("PROCEED", "CONFIRM", "INSUFFICIENT")) {
                assertTrue(
                    "$name must handle SpendDecision.$decision (no outcome silently dropped)",
                    code.contains("SpendDecision.$decision"),
                )
            }
        }
    }

    @Test
    fun `each buy screen mounts the confirm dialog on the CONFIRM decision`() {
        for ((name, code) in BUY_SCREENS) {
            assertTrue(
                "$name's CONFIRM branch must mount the PurchaseConfirmDialog before committing (AC#1)",
                code.contains(Regex("""SpendDecision\.CONFIRM\s*->\s*dialog\.show\s*\(""")),
            )
            // The details the dialog renders come from the pure gate (item, cost, resulting balance).
            assertTrue(
                "$name must build the dialog's details from the pure PurchaseGate.details (AC#1)",
                code.contains(Regex("""PurchaseGate\.details\s*\(""")),
            )
        }
    }

    @Test
    fun `each buy screen surfaces a styled error on the INSUFFICIENT decision`() {
        for ((name, code) in BUY_SCREENS) {
            assertTrue(
                "$name's INSUFFICIENT branch must enqueue the styled insufficient-credits toast, not a bare string (AC#3)",
                code.contains(
                    Regex(
                        "SpendDecision\\.INSUFFICIENT\\s*->\\s*notifications\\.enqueue" +
                            "\\s*\\(\\s*GameNotifications\\.insufficientCredits\\s*\\(\\s*\\)\\s*\\)",
                    ),
                ),
            )
        }
    }

    // --- AC#2/#4: the four buy screens + the hub take and animate the shared queue ----------------------

    @Test
    fun `every economy surface takes the shared queue through its constructor`() {
        for ((name, code) in QUEUE_RENDERING_SCREENS) {
            assertTrue(
                "$name must accept the shared NotificationQueue via its constructor (AC#4)",
                code.contains(Regex("""notifications\s*:\s*NotificationQueue""")),
            )
        }
    }

    @Test
    fun `every economy surface advances and renders the shared queue with animation`() {
        for ((name, code) in QUEUE_RENDERING_SCREENS) {
            assertTrue(
                "$name must advance the shared queue each frame (AC#2)",
                code.contains(Regex("""notifications\.update\s*\(""")),
            )
            assertTrue(
                "$name must draw the queue's life-fraction snapshot so toasts animate (AC#2)",
                code.contains(Regex("""notificationRenderer\.render\s*\(\s*\n?\s*notifications\.visibleWithProgress\s*\(\s*\)""")),
            )
        }
    }

    // --- AC#3/#4: PlayScreen routes every refused economy action to a gate-classified styled error -------

    @Test
    fun `PlayScreen classifies its economy errors through the pure gate`() {
        // The single styled-error helper decides INSUFFICIENT vs. generic rejection via the pure gate, so a
        // refused action always surfaces the right tier rather than the old bare status string.
        assertTrue(
            "PlayScreen must classify economy errors via PurchaseGate.evaluate (AC#3)",
            PLAY_SCREEN.contains(Regex("""PurchaseGate\.evaluate\s*\(""")),
        )
        assertTrue(
            "PlayScreen must surface the styled insufficient-credits cue",
            PLAY_SCREEN.contains(Regex("""GameNotifications\.insufficientCredits\s*\(""")),
        )
        assertTrue(
            "PlayScreen must surface the styled action-rejected cue",
            PLAY_SCREEN.contains(Regex("""GameNotifications\.actionRejected\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen routes all four economy no-op branches to the styled-error helper`() {
        // trade / outfit / fleet-command / hire each fall through to enqueueEconomyError(cost) on a no-op,
        // so none of the four economy actions can fail silently (AC#3/#4).
        val calls = Regex("""enqueueEconomyError\s*\(\s*cost\s*\)""").findAll(PLAY_SCREEN).count()
        assertEquals("exactly the four economy actions (trade/outfit/fleet/hire) must route to the styled-error helper", 4, calls)
    }

    @Test
    fun `PlayScreen replaces the bare refuel and buy-fuel strings with styled errors`() {
        // AC#4: the refuel flow shares the same styled feedback layer — its no-op buy-fuel statuses now
        // enqueue a styled error rather than returning a plain status string.
        assertTrue(
            "the full-tank refuel no-op must surface a styled rejection",
            PLAY_SCREEN.contains(Regex("""GameNotifications\.actionRejected\s*\(\s*"TANK FULL"\s*\)""")),
        )
        assertTrue(
            "the no-fuel-sold refuel no-op must surface a styled rejection",
            PLAY_SCREEN.contains(Regex("""GameNotifications\.actionRejected\s*\(\s*"NO FUEL SOLD HERE"\s*\)""")),
        )
    }

    // --- AC#4: exactly one shared queue, injected into every economy surface -----------------------------

    @Test
    fun `OrbitalFrontierGame builds exactly one shared notification queue`() {
        val constructions = Regex("""NotificationQueue\s*\(\s*\)""").findAll(GAME).count()
        assertEquals("there must be a single shared NotificationQueue in the game (AC#4)", 1, constructions)
        assertTrue(
            "the single shared queue must be the named sharedNotifications field",
            GAME.contains(Regex("""val\s+sharedNotifications\b""")),
        )
    }

    @Test
    fun `OrbitalFrontierGame injects the shared queue into every economy surface`() {
        // PlayScreen, StationHubScreen, both TradeScreen constructions, Outfit, Shipyard, Hire — seven
        // injection sites in all — must each be wired to the SAME sharedNotifications instance, so no screen
        // silently falls back to its own default queue (which would split the feed). AC#4.
        val injections = Regex("""notifications\s*=\s*sharedNotifications\b""").findAll(GAME).count()
        assertTrue(
            "every economy surface must receive the shared queue (found $injections injection sites, expected >= 7)",
            injections >= 7,
        )
    }

    private companion object {
        private val TRADE_SCREEN = screen("TradeScreen.kt")
        private val OUTFIT_SCREEN = screen("OutfitScreen.kt")
        private val SHIPYARD_SCREEN = screen("ShipyardScreen.kt")
        private val HIRE_SCREEN = screen("HireScreen.kt")
        private val HUB_SCREEN = screen("StationHubScreen.kt")
        private val PLAY_SCREEN = screen("PlayScreen.kt")
        private val GAME =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                    "core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                ),
            )

        /** The four buy screens that gate a buy through PurchaseGate + the confirm dialog. */
        private val BUY_SCREENS =
            listOf(
                "TradeScreen" to TRADE_SCREEN,
                "OutfitScreen" to OUTFIT_SCREEN,
                "ShipyardScreen" to SHIPYARD_SCREEN,
                "HireScreen" to HIRE_SCREEN,
            )

        /** Every surface that takes + renders the shared queue (the four buy screens plus the hub). */
        private val QUEUE_RENDERING_SCREENS = BUY_SCREENS + ("StationHubScreen" to HUB_SCREEN)

        /** Read a screen source by file name and return it comment-stripped. */
        private fun screen(fileName: String): String =
            stripComments(
                readSource(
                    "src/main/kotlin/com/orbitalfrontier/screen/$fileName",
                    "core/src/main/kotlin/com/orbitalfrontier/screen/$fileName",
                ),
            )

        /**
         * Strip Kotlin block (`/* … */`) and line (`// …`) comments so the guards inspect actual CODE only —
         * a doc comment naming PurchaseGate/dialog/notifications must not satisfy a wiring assertion. Mirrors
         * the comment-stripping in [com.orbitalfrontier.app.Uc17StartingCreditsTest].
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
                    "the UC40 economy-feedback source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
