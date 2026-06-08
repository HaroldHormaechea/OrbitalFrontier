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

    /** Asserts the glyph is a "real" graphic: a non-null shape and a strictly positive world size. */
    private fun assertRendersSomething(poi: Poi) {
        val glyph = WorldGlyphs.forPoi(poi)
        assertNotNull("forPoi must return a glyph for ${poi::class.simpleName}", glyph)
        // GlyphShape is a non-null enum value — every glyph names a concrete primitive to draw.
        assertNotNull("glyph for ${poi::class.simpleName} must name a shape", glyph.shape)
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

    // --- Glyph descriptors are well-formed colours (RGBA components in [0,1]) ---

    @Test
    fun allGlyphColourComponentsAreInUnitRange() {
        val pois: List<Poi> = listOf(jumpGate(), station(), asteroidField(), hiddenContact())
        for (poi in pois) {
            val g = WorldGlyphs.forPoi(poi)
            val name = poi::class.simpleName
            assertTrue("$name red in [0,1], was ${g.red}", g.red in 0f..1f)
            assertTrue("$name green in [0,1], was ${g.green}", g.green in 0f..1f)
            assertTrue("$name blue in [0,1], was ${g.blue}", g.blue in 0f..1f)
            assertTrue("$name alpha in [0,1], was ${g.alpha}", g.alpha in 0f..1f)
        }
    }
}
