package com.orbitalfrontier.render

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Headless coverage guard for the bundled game font asset (`assets/fonts/orbital.fnt` + its page PNG),
 * UC28. Pure-JVM: it parses the AngelCode `.fnt` **text** and asserts the asset's structural contract
 * without a GL context — exactly as the design-system atlas guard parses the `.atlas` (ADR 0017), and
 * mirroring the source-anchored screen guards' file-locator discipline.
 *
 * ACs covered:
 *  - **AC#3** — the `.fnt` declares a `char` for **every** codepoint the UI draws today
 *    ([GameFont.REQUIRED_GLYPHS]: printable ASCII + the degree sign `°` + the rightwards arrow `→`).
 *    A missing glyph fails the build instead of silently rendering a blank box on device.
 *  - **AC#5 / asset integrity** — the font declares exactly one page PNG, and that PNG file actually
 *    exists on disk beside the `.fnt`, so the bundled asset is loadable (no dangling page reference).
 *
 * Hard-fails (refuses to pass silently) if the asset cannot be located, so a renamed/moved font can
 * never make this guard vacuously green.
 */
class FontGlyphCoverageGuardTest {
    @Test
    fun `the bundled font declares a char for every required glyph`() {
        val declared = declaredCharIds(FNT_TEXT)
        val missing = GameFont.REQUIRED_GLYPHS.filter { it !in declared }
        assertTrue(
            "AC#3: orbital.fnt is missing char entries for required codepoints " +
                missing.joinToString { "U+%04X".format(it) } +
                " — these would render as blank glyphs on device.",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the degree sign and rightwards arrow are present (the historically-risky non-ASCII glyphs)`() {
        // Pinned explicitly: HudRenderer's old built-in font rendered ° as a blank box (UC28 summary),
        // and → is the mission-board row marker. Both are the reason a real face was bundled.
        val declared = declaredCharIds(FNT_TEXT)
        assertTrue("AC#3: ° (U+00B0) must be declared", 0x00B0 in declared)
        assertTrue("AC#3: → (U+2192) must be declared", 0x2192 in declared)
    }

    @Test
    fun `the font declares exactly one page and the page PNG exists on disk`() {
        val pages = PAGE_FILE_REGEX.findAll(FNT_TEXT).map { it.groupValues[1] }.toList()
        assertTrue(
            "AC#5: orbital.fnt must declare exactly one page PNG (found: $pages)",
            pages.size == 1,
        )
        val pageFile = File(FNT_FILE.parentFile, pages.single())
        assertTrue(
            "AC#5: the declared page PNG '${pages.single()}' must exist beside the .fnt at " +
                "${pageFile.absolutePath} (dangling page reference = unloadable font).",
            pageFile.isFile,
        )
    }

    private companion object {
        /** `char id=<N> ...` — one per declared glyph. The `chars count=` header line is NOT matched. */
        private val CHAR_ID_REGEX = Regex("""(?m)^char\s+id=(\d+)""")

        /** `page id=0 file="orbital.png"` — captures the page's PNG file name. */
        private val PAGE_FILE_REGEX = Regex("""(?m)^page\b[^\n]*\bfile="([^"]+)"""")

        private val FNT_FILE: File = locateAsset("assets/fonts/orbital.fnt")
        private val FNT_TEXT: String = FNT_FILE.readText()

        private fun declaredCharIds(fnt: String): Set<Int> = CHAR_ID_REGEX.findAll(fnt).map { it.groupValues[1].toInt() }.toSet()

        /**
         * Locates a bundled asset by walking up from the test working directory and trying the candidate
         * relative path at every ancestor (handles running from the module dir, the repo root, or a git
         * worktree), mirroring [com.orbitalfrontier.screen.Uc24MapLabelsGuardTest]'s source locator. Hard-
         * fails rather than passing silently if the asset cannot be found.
         */
        private fun locateAsset(relative: String): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                val f = File(dir, relative)
                if (f.isFile) return f
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate $relative from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC28 font coverage guard cannot run (refusing to pass silently).",
            )
        }
    }
}
