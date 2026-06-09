package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC24 (render each named map item's name as a
 * text label beside its marker, on the HUD minimap and the zoomed overlay). The deterministic half —
 * *which* markers get a label — is covered behaviourally by the pure
 * [com.orbitalfrontier.render.MapLabelsTest]. The glue that *draws* the labels (a `SpriteBatch` +
 * `BitmapFont` + `GlyphLayout` pass) lives in libGDX-touching code the headless backend cannot
 * construct, so the structural contract is pinned at the source level, mirroring the repo's existing
 * guards ([Uc23MapOverlayGuardTest], [Uc22MinimapTopRightGuardTest]).
 *
 * ACs covered here (structural/wiring half):
 *  - **AC#1/#2** — both renderers gate their label pass on [com.orbitalfrontier.render.MapLabels.shouldLabel],
 *    each passing its own surface (minimap labels stations; the overlay labels every visible named POI).
 *  - **AC#1/#5** — each renderer draws the label via the font over a [GlyphLayout]-measured name,
 *    centred above the *same* (clamped/projected) marker position, so a label tracks its marker.
 *  - **AC#3** — the label predicate ([com.orbitalfrontier.render.MapLabels]) rejects un-named and
 *    blank-named POIs (the branches the pure test cannot reach with the real POI types are pinned here).
 *  - **AC#4** — the anti-clutter surface rule: `MINIMAP` labels only STATION-kind contacts, `OVERLAY`
 *    labels every visible named contact. This divergence is *only* verifiable here, because the one
 *    Named type today ([com.orbitalfrontier.world.Station]) is always STATION-kind.
 *  - **UC23 no-regression** — the markers' revealed-contacts filter survives unchanged in BOTH renderers.
 *  - **Resource hygiene** — both renderers dispose the label font.
 */
class Uc24MapLabelsGuardTest {
    // --- AC#1/#2: both renderers gate their label pass on MapLabels.shouldLabel, per surface ---------

    @Test
    fun `the minimap gates its label pass on MapLabels for the minimap surface`() {
        assertTrue(
            "AC#1/#2: the minimap label pass is decided by MapLabels.shouldLabel",
            MINIMAP_RENDERER_SOURCE.contains("MapLabels.shouldLabel("),
        )
        assertTrue(
            "AC#4: the minimap asks for the MINIMAP surface decision",
            MINIMAP_RENDERER_SOURCE.contains("MapLabels.Surface.MINIMAP"),
        )
    }

    @Test
    fun `the overlay gates its label pass on MapLabels for the overlay surface`() {
        assertTrue(
            "AC#1/#2: the overlay label pass is decided by MapLabels.shouldLabel",
            MAP_OVERLAY_RENDERER_SOURCE.contains("MapLabels.shouldLabel("),
        )
        assertTrue(
            "AC#4: the overlay asks for the OVERLAY surface decision",
            MAP_OVERLAY_RENDERER_SOURCE.contains("MapLabels.Surface.OVERLAY"),
        )
    }

    // --- AC#1/#5: a font label pass, measured by GlyphLayout, draws over the marker pass -------------

    @Test
    fun `both renderers draw a GlyphLayout-measured label with the font`() {
        for ((name, src) in renderers()) {
            assertTrue(
                "AC#1: $name measures the name with a GlyphLayout",
                src.contains("glyphLayout.setText(labelFont,"),
            )
            assertTrue(
                "AC#1: $name draws the label via the font",
                src.contains("labelFont.draw("),
            )
        }
    }

    @Test
    fun `the label pass runs after the marker pass so labels sit over their markers`() {
        // AC#5: the label re-walks the markers and draws each name above the SAME clamped/projected
        // marker position. Anchored structurally: the label decision must come after the marker pass
        // (whose tell is the revealed-contacts filter), so a label can never be drawn without its marker.
        for ((name, src) in renderers()) {
            val markerFilterIdx = src.indexOf(MARKER_FILTER)
            val labelDecisionIdx = src.indexOf("MapLabels.shouldLabel(")
            assertTrue("$name still has the marker pass", markerFilterIdx >= 0)
            assertTrue("$name has a label pass", labelDecisionIdx >= 0)
            assertTrue(
                "AC#5: $name draws labels AFTER the marker pass",
                labelDecisionIdx > markerFilterIdx,
            )
        }
    }

    // --- AC#3 / AC#4: the label predicate itself rejects un-named/blank and keys clutter off surface -

    @Test
    fun `MapLabels rejects non-contact, un-named and blank-named POIs`() {
        assertTrue(
            "AC#3: only contacts can be labelled",
            MAP_LABELS_SOURCE.contains("if (poi !is Contact) return false"),
        )
        assertTrue(
            "AC#3: un-named or blank-named POIs are rejected (no empty/placeholder labels)",
            MAP_LABELS_SOURCE.contains("if (poi !is Named || poi.displayName.isBlank()) return false"),
        )
    }

    @Test
    fun `MapLabels applies the per-surface anti-clutter rule`() {
        // AC#4: the small HUD minimap labels ONLY stations; the roomy overlay labels every visible named
        // contact. This is the divergence the pure test cannot exercise (Station is the only Named type
        // and is always STATION-kind), so it is pinned at the source here.
        assertTrue(
            "AC#4: the overlay labels every visible named contact",
            MAP_LABELS_SOURCE.contains("Surface.OVERLAY -> true"),
        )
        assertTrue(
            "AC#4: the minimap labels only STATION-kind contacts",
            MAP_LABELS_SOURCE.contains("Surface.MINIMAP -> poi.contactKind == ContactKind.STATION"),
        )
    }

    // --- UC23 no-regression: the markers' revealed-contacts filter survives in BOTH renderers --------

    @Test
    fun `the UC23 marker visibility filter is preserved in both renderers`() {
        for ((name, src) in renderers()) {
            assertTrue(
                "UC23 no-regression: $name still skips unrevealed non-transponder contacts",
                src.contains(MARKER_FILTER),
            )
        }
    }

    // --- Resource hygiene: both renderers dispose the label font ------------------------------------

    @Test
    fun `both renderers dispose the label font`() {
        for ((name, src) in renderers()) {
            assertTrue(
                "$name disposes its label font to avoid a native texture leak",
                src.contains("labelFont.dispose()"),
            )
        }
    }

    private companion object {
        private val MINIMAP_RENDERER_SOURCE: String = readSource("render/MinimapRenderer.kt")
        private val MAP_OVERLAY_RENDERER_SOURCE: String = readSource("render/MapOverlayRenderer.kt")
        private val MAP_LABELS_SOURCE: String = readSource("render/MapLabels.kt")

        /** The UC23 marker-skip filter both renderers (and [com.orbitalfrontier.render.MapLabels]) share. */
        private const val MARKER_FILTER = "poi !is Transponder && poi.id !in revealedContacts"

        private fun renderers(): List<Pair<String, String>> =
            listOf(
                "MinimapRenderer" to MINIMAP_RENDERER_SOURCE,
                "MapOverlayRenderer" to MAP_OVERLAY_RENDERER_SOURCE,
            )

        /**
         * Locates a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles running from the module dir, the repo root,
         * or a git worktree). Hard-fails rather than passing silently if the file cannot be found
         * (mirrors the repo's existing source-anchored guards).
         */
        private fun readSource(relative: String): String {
            val candidates =
                listOf(
                    "src/main/kotlin/com/orbitalfrontier/$relative",
                    "core/src/main/kotlin/com/orbitalfrontier/$relative",
                )
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in candidates) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate $relative from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC24 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
