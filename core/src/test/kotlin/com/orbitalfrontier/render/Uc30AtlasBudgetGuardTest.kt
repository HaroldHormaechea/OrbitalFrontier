package com.orbitalfrontier.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Headless texture-memory-budget guard for the design-system art atlas (UC30 AC#4).
 *
 * UC30 packs the final world sprites (ship, hostile, station, asteroid field, jump gate, contact, …) into
 * the shared `orbital.atlas`. AC#4 requires that art to stay **within the min-spec texture-memory budget**:
 * a single atlas page no larger than one 1024×1024 RGBA8888 texture (~4 MB once the GPU rounds the page up
 * to power-of-two dimensions). A regression here — a second page, an oversized page, or a wider colour
 * format — would silently inflate VRAM on the min-spec device; this guard turns that into a build failure.
 *
 * Like [AtlasRegionGuardTest] it parses the packed `.atlas` **text** and never instantiates
 * [com.badlogic.gdx.graphics.g2d.TextureAtlas] / [com.badlogic.gdx.graphics.Texture] (those need a GL
 * context and crash on the JVM test thread). It reads only the **page header** — the un-indented
 * `orbital.png` page-image line and the column-0 `size:` / `format:` attribute lines that follow it — and
 * deliberately ignores the indented per-region `size:` lines (those are sub-rect dimensions, not the page).
 *
 * The candidate-root locator mirrors [AtlasRegionGuardTest]: `:core:test` runs with `core/` as its working
 * dir, so the repo-root atlas is reached via `../assets/orbital.atlas`; alternate roots are tolerated so the
 * guard also resolves when invoked from the repository root.
 */
class Uc30AtlasBudgetGuardTest {
    /** A packed atlas page: its image file plus the header-declared pixel dimensions and colour format. */
    private data class AtlasPage(val image: String, val width: Int, val height: Int, val format: String)

    @Test
    fun `the atlas packs into a single page`() {
        val pages = parsePages(readAtlasText())
        assertEquals(
            "UC30 art must pack into ONE atlas page to stay in the min-spec texture budget; pages: " +
                pages.map { it.image },
            1,
            pages.size,
        )
    }

    @Test
    fun `the single page fits within a 1024x1024 RGBA8888 texture`() {
        val page = parsePages(readAtlasText()).single()
        assertTrue(
            "atlas page ${page.image} width ${page.width} exceeds the $MAX_DIM min-spec limit",
            page.width <= MAX_DIM,
        )
        assertTrue(
            "atlas page ${page.image} height ${page.height} exceeds the $MAX_DIM min-spec limit",
            page.height <= MAX_DIM,
        )
        assertEquals(
            "atlas page ${page.image} must use the budgeted RGBA8888 colour format (4 bytes/pixel)",
            "RGBA8888",
            page.format,
        )
    }

    @Test
    fun `the GPU-resident page size stays within the ~4 MB budget`() {
        val page = parsePages(readAtlasText()).single()
        // The GPU rounds a non-POT page up to power-of-two dimensions, so the actual VRAM cost is computed
        // from the rounded dimensions (e.g. 1024×748 → 1024×1024), not the packed pixel count.
        val potW = nextPowerOfTwo(page.width)
        val potH = nextPowerOfTwo(page.height)
        val residentBytes = potW.toLong() * potH.toLong() * bytesPerPixel(page.format)
        assertTrue(
            "atlas page ${page.image} rounds up to ${potW}x$potH ${page.format} = $residentBytes bytes, " +
                "over the $MAX_BUDGET_BYTES-byte (~4 MB) min-spec budget",
            residentBytes <= MAX_BUDGET_BYTES,
        )
    }

    /**
     * Parse the page headers from libGDX atlas text. A page is a column-0 line ending in `.png`, immediately
     * followed by column-0 (un-indented) `key: value` attribute lines (`size:`, `format:`, `filter:`,
     * `repeat:`). Region blocks that follow are column-0 names with **indented** attributes, so the indented
     * per-region `size:` lines are never read as a page size.
     */
    private fun parsePages(atlasText: String): List<AtlasPage> {
        val lines = atlasText.lines()
        val pages = mutableListOf<AtlasPage>()
        var idx = 0
        while (idx < lines.size) {
            val raw = lines[idx]
            // A page-image line: column 0, non-blank, ends with `.png`.
            if (raw.isNotBlank() && !raw[0].isWhitespace() && raw.trim().endsWith(".png")) {
                val image = raw.trim()
                var width = -1
                var height = -1
                var format = "UNKNOWN"
                var j = idx + 1
                // Consume the page header: consecutive column-0 `key: value` lines (stop at a region name,
                // which is a column-0 line with no colon, or another page image, or EOF).
                while (j < lines.size) {
                    val attr = lines[j]
                    if (attr.isBlank()) {
                        j++
                        continue
                    }
                    if (attr[0].isWhitespace()) break // indented = region attribute, header is over
                    val trimmed = attr.trim()
                    if (!trimmed.contains(':')) break // column-0, no colon = region name (header is over)
                    if (trimmed.endsWith(".png")) break // the next page image
                    val key = trimmed.substringBefore(':').trim()
                    val value = trimmed.substringAfter(':').trim()
                    when (key) {
                        "size" -> {
                            width = value.substringBefore(',').trim().toInt()
                            height = value.substringAfter(',').trim().toInt()
                        }
                        "format" -> format = value
                    }
                    j++
                }
                pages.add(AtlasPage(image, width, height, format))
                idx = j
            } else {
                idx++
            }
        }
        return pages
    }

    /** Bytes per pixel for the colour formats libGDX can pack into; RGBA8888 is the budgeted format. */
    private fun bytesPerPixel(format: String): Int =
        when (format) {
            "RGBA8888", "RGBA4444" -> if (format == "RGBA8888") 4 else 2
            "RGB888" -> 3
            "RGB565", "LuminanceAlpha" -> 2
            "Alpha", "Intensity" -> 1
            else -> 4 // unknown/worst-case: assume 4 bytes/pixel so the budget check stays conservative
        }

    /** Smallest power of two ≥ [n] (the dimension a GPU rounds a non-POT texture page up to). */
    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
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
        const val MAX_DIM = 1024
        const val MAX_BUDGET_BYTES = 1024L * 1024L * 4L // one 1024×1024 RGBA8888 page ≈ 4 MB
    }
}
