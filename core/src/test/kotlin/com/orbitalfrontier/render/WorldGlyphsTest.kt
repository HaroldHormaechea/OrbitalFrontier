package com.orbitalfrontier.render

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.world.AsteroidField
import com.orbitalfrontier.world.GateLink
import com.orbitalfrontier.world.HiddenContact
import com.orbitalfrontier.world.JumpGate
import com.orbitalfrontier.world.Poi
import com.orbitalfrontier.world.PoiId
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [WorldGlyphs] (ADR 0015) — the pure resolver that guarantees **every** POI has an in-world
 * graphic. This is the heart of the change: the reported bug was that stations (and any POI without a
 * per-type renderer) drew as *nothing*. These tests assert the structural "every object renders
 * something" guarantee per concrete [Poi] subtype, plus the station-label and hot-path-caching
 * properties the review called out.
 *
 * UC27: the glyph descriptor swapped its generated `GlyphShape` + RGBA floats for an atlas [WorldGlyph.regionName]
 * (an [AtlasRegions] constant). These assertions follow the API change — they verify each POI resolves to the
 * **right atlas region** and that its authored **world size is preserved** (AC#4 — positions/collision/camera
 * unchanged) — instead of the old shape/colour checks. The station-label contract (ADR-0015) is unchanged.
 *
 * Representative instances mirror how existing world/economy tests construct POI fixtures
 * (e.g. DockingTest's `Station(PoiId(...), Vec2(...), "Name")`, MiningTest's `AsteroidField(...)`).
 *
 * Note: the project ships no `kotlin-reflect`, so we deliberately do **not** reflect over
 * `sealedSubclasses` — each concrete subtype is covered by an explicit per-instance assertion. The
 * exhaustiveness of `WorldGlyphs.forPoi`'s `when` is already enforced at compile time; these tests
 * verify the resolved *values*, not the exhaustiveness.
 */
class WorldGlyphsTest {
    private fun jumpGate(): JumpGate =
        JumpGate(
            id = PoiId("test-gate"),
            position = Vec2(0f, 0f),
            triggerRadius = 50f,
            link = GateLink(destinationSector = SectorId("beta"), destinationGate = PoiId("beta-gate")),
        )

    private fun station(name: String = "Test Station"): Station =
        Station(
            id = PoiId("test-station"),
            position = Vec2(0f, 0f),
            displayName = name,
        )

    private fun asteroidField(): AsteroidField =
        AsteroidField(
            id = PoiId("test-belt"),
            position = Vec2(0f, 0f),
            deposits = mapOf(ResourceType.HYDROGEN to 10),
        )

    private fun hiddenContact(): HiddenContact =
        HiddenContact(
            id = PoiId("test-ghost"),
            position = Vec2(0f, 0f),
        )

    /** Asserts the glyph is a "real" graphic: a non-blank atlas region name and a strictly positive size. */
    private fun assertRendersSomething(poi: Poi) {
        val glyph = WorldGlyphs.forPoi(poi)
        assertNotNull("forPoi must return a glyph for ${poi::class.simpleName}", glyph)
        // Every glyph now names an atlas region — a non-blank name is the "renders something" guarantee.
        assertTrue(
            "glyph for ${poi::class.simpleName} must name a non-blank atlas region, was '${glyph.regionName}'",
            glyph.regionName.isNotBlank(),
        )
        assertTrue(
            "glyph for ${poi::class.simpleName} must have a positive world size, was ${glyph.sizeWorldUnits}",
            glyph.sizeWorldUnits > 0f,
        )
    }

    // --- The structural guarantee: every concrete POI subtype renders SOMETHING (no object = nothing) ---

    @Test
    fun jumpGate_rendersSomething() {
        assertRendersSomething(jumpGate())
    }

    @Test
    fun station_rendersSomething() {
        assertRendersSomething(station())
    }

    @Test
    fun asteroidField_rendersSomething() {
        assertRendersSomething(asteroidField())
    }

    @Test
    fun hiddenContact_rendersSomething() {
        assertRendersSomething(hiddenContact())
    }

    // --- Each POI resolves to its delivered design-system atlas region (AC#4) ---

    @Test
    fun jumpGate_resolvesToGateRegion() {
        assertEquals(AtlasRegions.JUMP_GATE, WorldGlyphs.forPoi(jumpGate()).regionName)
    }

    @Test
    fun station_resolvesToStationRegion() {
        assertEquals(AtlasRegions.STATION, WorldGlyphs.forPoi(station()).regionName)
    }

    @Test
    fun asteroidField_resolvesToAsteroidRegion() {
        assertEquals(AtlasRegions.ASTEROID_FIELD, WorldGlyphs.forPoi(asteroidField()).regionName)
    }

    @Test
    fun hiddenContact_resolvesToContactRegion() {
        assertEquals(AtlasRegions.CONTACT_HIDDEN, WorldGlyphs.forPoi(hiddenContact()).regionName)
    }

    @Test
    fun everyGlyphRegionIsADeclaredAtlasRegion() {
        val pois: List<Poi> = listOf(jumpGate(), station(), asteroidField(), hiddenContact())
        for (poi in pois) {
            val region = WorldGlyphs.forPoi(poi).regionName
            assertTrue(
                "glyph region '$region' for ${poi::class.simpleName} must be a declared AtlasRegions constant",
                region in AtlasRegions.ALL,
            )
        }
    }

    // --- World-unit sizes are preserved across the art swap so geometry is unchanged (AC#3/#4) ---

    /**
     * UC30 AC#3 extracted the per-type half-extents into [WorldSpriteSizes] (the sizing single source of
     * truth, ADR 0019). This asserts each POI's glyph resolves its world size **from that SSOT** — proving
     * the renderers read the one auditable place rather than a re-introduced inline literal.
     */
    @Test
    fun glyphWorldSizesResolveFromWorldSpriteSizes() {
        assertEquals("gate world size", WorldSpriteSizes.GATE, WorldGlyphs.forPoi(jumpGate()).sizeWorldUnits, 0f)
        assertEquals("station world size", WorldSpriteSizes.STATION, WorldGlyphs.forPoi(station()).sizeWorldUnits, 0f)
        assertEquals(
            "asteroid world size",
            WorldSpriteSizes.ASTEROID_FIELD,
            WorldGlyphs.forPoi(asteroidField()).sizeWorldUnits,
            0f,
        )
        assertEquals(
            "contact world size",
            WorldSpriteSizes.HIDDEN_CONTACT,
            WorldGlyphs.forPoi(hiddenContact()).sizeWorldUnits,
            0f,
        )
    }

    /**
     * The desync safety net for AC#3: the authored half-extents must stay **pinned to their pre-UC30
     * values** so the extraction into [WorldSpriteSizes] caused zero geometry drift (changing collision /
     * dock / mine / jump ranges relative to the visuals). Asserting the SSOT constants against literals here
     * — not against themselves — is what keeps this a real pin rather than a tautology.
     */
    @Test
    fun authoredWorldSizesArePinnedToTheirPreUc30Values() {
        assertEquals("gate half-extent", 28f, WorldSpriteSizes.GATE, 0f)
        assertEquals("station half-extent", 22f, WorldSpriteSizes.STATION, 0f)
        assertEquals("asteroid half-extent", 26f, WorldSpriteSizes.ASTEROID_FIELD, 0f)
        assertEquals("contact half-extent", 16f, WorldSpriteSizes.HIDDEN_CONTACT, 0f)
    }

    // --- Station label: the reported bug was stations rendering as nothing; their glyph must carry name ---

    @Test
    fun station_glyphCarriesDisplayNameAsLabel() {
        val glyph = WorldGlyphs.forPoi(station(name = "Alpha Hub"))
        assertEquals("station glyph must carry the station display name as its label", "Alpha Hub", glyph.label)
    }

    @Test
    fun station_labelReflectsTheSpecificStation() {
        // Two differently-named stations must yield differently-labelled glyphs (label is per-station).
        val a = WorldGlyphs.forPoi(station(name = "Station A"))
        val b = WorldGlyphs.forPoi(station(name = "Station B"))
        assertEquals("Station A", a.label)
        assertEquals("Station B", b.label)
    }

    // --- Non-station glyphs carry no label (they are shared unlabelled constants) ---

    @Test
    fun nonStationGlyphs_haveNoLabel() {
        assertEquals(null, WorldGlyphs.forPoi(jumpGate()).label)
        assertEquals(null, WorldGlyphs.forPoi(asteroidField()).label)
        assertEquals(null, WorldGlyphs.forPoi(hiddenContact()).label)
    }

    // --- Hot-path discipline: fixed POI kinds return a CACHED, constant glyph (no per-frame allocation) ---

    @Test
    fun jumpGate_glyphIsCachedConstantAcrossCalls() {
        assertSame(
            "jump-gate glyph must be the same cached instance across calls (no per-frame allocation)",
            WorldGlyphs.forPoi(jumpGate()),
            WorldGlyphs.forPoi(jumpGate()),
        )
    }

    @Test
    fun asteroidField_glyphIsCachedConstantAcrossCalls() {
        assertSame(
            "asteroid-field glyph must be the same cached instance across calls",
            WorldGlyphs.forPoi(asteroidField()),
            WorldGlyphs.forPoi(asteroidField()),
        )
    }

    @Test
    fun hiddenContact_glyphIsCachedConstantAcrossCalls() {
        assertSame(
            "hidden-contact glyph must be the same cached instance across calls",
            WorldGlyphs.forPoi(hiddenContact()),
            WorldGlyphs.forPoi(hiddenContact()),
        )
    }
}
