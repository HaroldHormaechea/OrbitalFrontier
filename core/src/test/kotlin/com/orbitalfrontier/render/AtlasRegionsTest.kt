package com.orbitalfrontier.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [AtlasRegions] registry (UC27) — the engine-free list of every region name in the
 * design-system art atlas. The registry is the contract the renderers/skin reference and the headless
 * guard ([AtlasRegionGuardTest]) checks against the packaged atlas, so it must enumerate exactly the
 * expected 27 names, with no typos and no duplicates.
 *
 * The expected list is spelled out literally here (not derived from [AtlasRegions]) so a rename or a
 * dropped/added constant is caught by a failing assertion rather than silently re-deriving itself.
 */
class AtlasRegionsTest {
    private val expected =
        listOf(
            // Action-arc glyph buttons (AC#2)
            "action-fire", "action-dock", "action-mine", "action-scan", "action-radio", "action-point-and-go",
            // Movement joystick (AC#3)
            "joystick-base", "joystick-knob",
            // World objects (AC#4)
            "ship-player", "ship-hostile", "station", "asteroid-field", "jump-gate", "contact-hidden", "projectile",
            // Minimap markers (AC#5)
            "mm-player", "mm-station", "mm-gate", "mm-asteroid", "mm-contact",
            // Ship-schematic module states (AC#6)
            "module-healthy", "module-warn", "module-critical",
            // On-foot walk-around (AC#7)
            "avatar-player", "npc-shopkeeper", "floor-tile", "wall-tile",
        )

    @Test
    fun registryHasExactlyTheExpectedNames() {
        assertEquals(
            "AtlasRegions.ALL must list exactly the expected design-system region names",
            expected.toSet(),
            AtlasRegions.ALL.toSet(),
        )
    }

    @Test
    fun registryHasTwentySevenRegions() {
        assertEquals(27, AtlasRegions.ALL.size)
    }

    @Test
    fun registryHasNoDuplicates() {
        assertEquals(
            "AtlasRegions.ALL must contain no duplicate names",
            AtlasRegions.ALL.size,
            AtlasRegions.ALL.toSet().size,
        )
    }

    @Test
    fun everyNameIsNonBlankAndKebabCase() {
        val kebab = Regex("[a-z]+(-[a-z]+)*")
        for (name in AtlasRegions.ALL) {
            assertTrue("region name must be non-blank", name.isNotBlank())
            assertTrue("region name '$name' must be lower-kebab-case", kebab.matches(name))
        }
    }

    @Test
    fun namedConstantsMatchTheirRegionStrings() {
        // Spot-check the constants the renderers/skin reference by name resolve to the expected strings.
        assertEquals("action-fire", AtlasRegions.ACTION_FIRE)
        assertEquals("ship-player", AtlasRegions.SHIP_PLAYER)
        assertEquals("station", AtlasRegions.STATION)
        assertEquals("jump-gate", AtlasRegions.JUMP_GATE)
        assertEquals("mm-player", AtlasRegions.MM_PLAYER)
        assertEquals("module-critical", AtlasRegions.MODULE_CRITICAL)
        assertEquals("npc-shopkeeper", AtlasRegions.NPC_SHOPKEEPER)
    }
}
