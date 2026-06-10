package com.orbitalfrontier.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Headless region-existence guard for the design-system art atlas (UC27 AC#1/AC#10).
 *
 * The shared [GameAssets] atlas wires every renderer and the control skin to regions named by
 * [AtlasRegions]; a typo or a missing region would surface only as a blank sprite at runtime (or a crash
 * inside [GameAssets.region] on device). This guard makes that a **build-time** failure instead: it parses
 * the packaged `orbital.atlas` **text** and asserts every [AtlasRegions.ALL] name is declared in it.
 *
 * **It deliberately does not instantiate [com.badlogic.gdx.graphics.g2d.TextureAtlas] or
 * [com.badlogic.gdx.graphics.Texture]** — those need a GL context and would crash on the JVM test thread
 * (UC27 pitfall; precedent: [com.orbitalfrontier.playthrough.NoBox2DGuardTest] scans source text rather than
 * loading native bindings). Parsing the libGDX atlas format is trivial: a region is a line at column 0 that
 * is not the leading page-image line and is followed by indented `key: value` attribute lines.
 *
 * The `:core:test` task runs with the module dir (`core/`) as its working dir, so the atlas at the repo root
 * is reached via `../assets/orbital.atlas`; a couple of alternate roots are tolerated so the guard also
 * resolves when invoked from the repository root (mirrors `NoBox2DGuardTest`'s candidate-root approach).
 */
class AtlasRegionGuardTest {
    @Test
    fun `every AtlasRegions name is declared in the packaged atlas`() {
        val declared = parseRegionNames(readAtlasText())
        val missing = AtlasRegions.ALL.filter { it !in declared }
        assertTrue(
            "atlas $ATLAS_RELATIVE is missing regions referenced by AtlasRegions: $missing " +
                "(declared regions: ${declared.sorted()})",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the atlas declares no region unknown to AtlasRegions`() {
        // The atlas and the registry must stay in lock-step: art added to the atlas but not registered in
        // AtlasRegions would never be referenced (dead art) and would slip past the missing-region guard.
        val declared = parseRegionNames(readAtlasText())
        val unregistered = declared.filter { it !in AtlasRegions.ALL }
        assertTrue(
            "atlas $ATLAS_RELATIVE declares regions not present in AtlasRegions.ALL: $unregistered",
            unregistered.isEmpty(),
        )
    }

    @Test
    fun `atlas region count matches the registry`() {
        val declared = parseRegionNames(readAtlasText())
        assertEquals(
            "atlas region count must equal AtlasRegions.ALL size",
            AtlasRegions.ALL.size,
            declared.size,
        )
    }

    /**
     * Extract the region names from libGDX atlas text. The first non-blank line is the page image file
     * (`orbital.png`); it is followed by indented page metadata (`size:`, `format:`, `filter:`, `repeat:`).
     * Every subsequent column-0 line that contains no `:` is a region name; its own attributes are indented.
     */
    private fun parseRegionNames(atlasText: String): Set<String> {
        val lines = atlasText.lines()
        val firstContentIdx = lines.indexOfFirst { it.isNotBlank() }
        if (firstContentIdx < 0) return emptySet()
        val pageImage = lines[firstContentIdx].trim()

        val regions = LinkedHashSet<String>()
        for ((idx, raw) in lines.withIndex()) {
            if (idx <= firstContentIdx) continue // skip the page-image line and anything before it
            if (raw.isBlank()) continue
            // Region names sit at column 0 (no leading whitespace) and carry no `key: value` colon.
            if (raw[0].isWhitespace()) continue
            val name = raw.trim()
            if (name.contains(':')) continue // a page-level attribute line (e.g. a second page header)
            if (name == pageImage) continue // a page-image line for an additional page
            regions.add(name)
        }
        return regions
    }

    private fun readAtlasText(): String = locateAtlas().readText()

    private fun locateAtlas(): File {
        // Candidate roots: repo root, then core/ (the usual :core:test working dir), then one level deeper.
        val candidates =
            listOf(
                File(ATLAS_RELATIVE),
                File("..", ATLAS_RELATIVE),
                File("../..", ATLAS_RELATIVE),
            )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "could not locate $ATLAS_RELATIVE; tried: ${candidates.map { it.absolutePath }}",
            )
    }

    private companion object {
        const val ATLAS_RELATIVE = "assets/orbital.atlas"
    }
}
