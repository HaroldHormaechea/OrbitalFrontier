package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for UC51's **owned-station surfacing + dock-to-use hub wiring** (AC#2/#3,
 * pitfall #4), plus the challenger's #3 hub default-button-set regression guard.
 *
 * Why source-anchored: [PlayScreen], [StationHubScreen] and [com.orbitalfrontier.app.OrbitalFrontierGame]
 * are GL-backed and not headlessly constructible (see [Uc51StationBuildSourceTest]); the pure surfacing /
 * routing logic is proven for real in [com.orbitalfrontier.station.OwnedStationProjectionTest] /
 * [com.orbitalfrontier.station.OwnedStationServicesTest] / [com.orbitalfrontier.world.Uc51OwnedStationDockingTest],
 * so here we pin the wiring at the source level over **comment-stripped CODE**.
 *
 * The contract pinned:
 *  - **AC#2 surfacing:** PlayScreen feeds `effectivePois` (= `OwnedStationProjection.poisIn(...)`) to all
 *    three world surfaces — the world-object renderer, the minimap and the map overlay;
 *  - **AC#3 docking:** PlayScreen makes owned projections dockable (`stationsIn` → `Docking.availableStation`)
 *    and resolves the docked market against the owned projection (`resolveDocked`);
 *  - **AC#3 hub:** the game computes an owned station's `enabledServices` as `hubServices(...) + UNDOCK`
 *    and routes TRADE/OUTFIT to the desks on the projected station;
 *  - **challenger #3 (no regression):** StationHubScreen defaults `enabledServices` to `HubService.ALL`
 *    (the full historical set) and gates every service button on membership in it.
 */
class Uc51OwnedStationHubSourceTest {
    // --- AC#2: effectivePois fed to all three world surfaces -------------------------------------------

    @Test
    fun `PlayScreen derives effectivePois from the owned-station projection`() {
        assertTrue(
            "PlayScreen must compute effectivePois via OwnedStationProjection.poisIn(...)",
            PLAY_SCREEN.contains(Regex("""effectivePois\s*=\s*OwnedStationProjection\.poisIn\s*\(""")),
        )
    }

    @Test
    fun `PlayScreen feeds effectivePois to the world renderer, the minimap and the map overlay`() {
        assertTrue(
            "the in-world object renderer must draw effectivePois",
            PLAY_SCREEN.contains(
                Regex("""worldObjectRenderer\.render\s*\(\s*worldCamera\s*,\s*effectivePois""", RegexOption.DOT_MATCHES_ALL),
            ),
        )
        assertTrue(
            "the minimap must render effectivePois",
            PLAY_SCREEN.contains(Regex("""minimap\.render\s*\(\s*effectivePois""", RegexOption.DOT_MATCHES_ALL)),
        )
        assertTrue(
            "the map overlay must render effectivePois",
            PLAY_SCREEN.contains(Regex("""mapOverlay\.render\s*\(\s*effectivePois""", RegexOption.DOT_MATCHES_ALL)),
        )
    }

    // --- AC#3: owned stations dockable + docked market resolved against the projection -----------------

    @Test
    fun `PlayScreen makes owned projections dockable`() {
        assertTrue(
            "PlayScreen must surface the sector's owned stations via OwnedStationProjection.stationsIn(...)",
            PLAY_SCREEN.contains(Regex("""OwnedStationProjection\.stationsIn\s*\(""")),
        )
        assertTrue(
            "the dock resolution must consider those owned projections (Docking.availableStation with the extras)",
            PLAY_SCREEN.contains(Regex("""Docking\.availableStation\s*\([^)]*ownedStationsHere""", RegexOption.DOT_MATCHES_ALL)),
        )
    }

    @Test
    fun `PlayScreen resolves the docked market against the owned-station projection`() {
        assertTrue(
            "dockedMarketOrNull must resolve via OwnedStationProjection.resolveDocked(...)",
            PLAY_SCREEN.contains(Regex("""OwnedStationProjection\.resolveDocked\s*\(""")),
        )
    }

    // --- AC#3 hub: owned enabledServices = hubServices(...) + UNDOCK, TRADE/OUTFIT routed ---------------

    @Test
    fun `the game computes an owned station's services as its module services plus UNDOCK`() {
        assertTrue(
            "owned-station enabledServices must be OwnedStationServices.hubServices(...) + HubService.UNDOCK (pitfall #4)",
            GAME.contains(
                Regex("""OwnedStationServices\.hubServices\s*\([^)]*\)\s*\+\s*HubService\.UNDOCK""", RegexOption.DOT_MATCHES_ALL),
            ),
        )
        assertTrue(
            "the docked-station resolution must go through OwnedStationProjection.resolveDocked(...)",
            GAME.contains(Regex("""OwnedStationProjection\.resolveDocked\s*\(""")),
        )
    }

    @Test
    fun `the game routes TRADE and OUTFIT to the desks on the projected station`() {
        assertTrue(
            "TRADE must open the trade desk for the (possibly owned) station",
            GAME.contains(Regex("""onTrade\s*=\s*\{\s*openTradeDesk\s*\(\s*station\s*\)""")),
        )
        assertTrue(
            "OUTFIT must open the outfit desk for the (possibly owned) station",
            GAME.contains(Regex("""onOutfit\s*=\s*\{\s*openOutfitDesk\s*\(\s*station\s*\)""")),
        )
        assertTrue(
            "the hub must receive the gated enabledServices",
            GAME.contains(Regex("""enabledServices\s*=\s*enabledServices""")),
        )
    }

    // --- challenger #3: the regular-station hub button set is unchanged (default = HubService.ALL) ------

    @Test
    fun `HubService ALL is the full service set`() {
        assertTrue(
            "HubService.ALL must be the full entry set (so the default reproduces the historical button set)",
            HUB_SERVICE.contains(Regex("""val\s+ALL\s*:\s*Set<HubService>\s*=\s*entries\.toSet\s*\(\s*\)""")),
        )
    }

    @Test
    fun `StationHubScreen defaults enabledServices to HubService ALL`() {
        assertTrue(
            "the hub must default enabledServices to HubService.ALL (an authored station keeps its full set, guard-pinned)",
            HUB_SCREEN.contains(Regex("""enabledServices\s*:\s*Set<HubService>\s*=\s*HubService\.ALL""")),
        )
    }

    @Test
    fun `every historical hub service button is gated on enabledServices`() {
        // The full regular-station button set the default HubService.ALL must reproduce unchanged: a button
        // is shown only when its service is `in enabledServices`. Gating EVERY one (rather than adding some
        // unconditionally) is what makes the owned-station subset possible without a forked screen.
        val historicalServices =
            listOf("TRADE", "OUTFIT", "SHIPS", "CREW", "FLEET", "MISSIONS", "BUILD", "REFUEL", "BUY_FUEL", "DISEMBARK", "UNDOCK")
        for (service in historicalServices) {
            assertTrue(
                "the $service button must be gated on `HubService.$service in enabledServices`",
                HUB_SCREEN.contains(Regex("""HubService\.$service\s+in\s+enabledServices""")),
            )
        }
    }

    private companion object {
        private val PLAY_SCREEN =
            stripComments(
                readSource(
                    "core/src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                    "src/main/kotlin/com/orbitalfrontier/screen/PlayScreen.kt",
                ),
            )
        private val GAME =
            stripComments(
                readSource(
                    "core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                    "src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                ),
            )
        private val HUB_SCREEN =
            stripComments(
                readSource(
                    "core/src/main/kotlin/com/orbitalfrontier/screen/StationHubScreen.kt",
                    "src/main/kotlin/com/orbitalfrontier/screen/StationHubScreen.kt",
                ),
            )
        private val HUB_SERVICE =
            stripComments(
                readSource(
                    "core/src/main/kotlin/com/orbitalfrontier/station/HubService.kt",
                    "src/main/kotlin/com/orbitalfrontier/station/HubService.kt",
                ),
            )

        private fun stripComments(source: String): String =
            source
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")

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
                    "the UC51 owned-station hub source guard cannot run (refusing to pass silently).",
            )
        }
    }
}
