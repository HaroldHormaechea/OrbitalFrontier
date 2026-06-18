package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** wiring of UC28 (replace libGDX's built-in `BitmapFont` with
 * the bundled scalable game font across the HUD and Scene2D screens). Constructing a [BitmapFont] / GL
 * context is impossible on the headless JVM test backend, so — exactly like the repo's existing screen
 * guards ([Uc24MapLabelsGuardTest], [Uc23MapOverlayGuardTest]) — the structural contract is pinned at the
 * source level: each text consumer must (a) source its font from [GameFontLoader.load], (b) downscale the
 * baked master by [GameFont.NORM], and (c) contain **no** no-arg `BitmapFont()` — the regression we are
 * guarding against, where a consumer falls back to the old built-in font.
 *
 * ACs covered (structural/wiring half):
 *  - **AC#1** — the runtime built-in `BitmapFont` is replaced by the bundled font in HudRenderer and the
 *    controls skin (PlaceholderControlsSkin), and by extension the map-label renderers.
 *  - **AC#4** — font sizing flows through `UiScale.factor`: the screen-space overlays (HUD, map labels)
 *    multiply the downscale by `uiScale`; the Scene2D skin relies on the already-magnified viewport and so
 *    applies `GameFont.NORM` alone — pinned per consumer so the single-knob discipline can't silently break.
 *
 * The no-arg `BitmapFont()` ban targets the empty-parens form specifically: [GameFontLoader] legitimately
 * constructs `BitmapFont(Gdx.files.internal(...))` (the arg form) and is intentionally NOT in this set.
 */
class Uc28FontWiringGuardTest {
    @Test
    fun `every text consumer loads the bundled game font via GameFontLoader`() {
        for ((name, src) in consumers()) {
            assertTrue(
                "AC#1: $name must load the bundled font via GameFontLoader.load() (not a built-in BitmapFont)",
                src.contains("GameFontLoader.load("),
            )
        }
    }

    @Test
    fun `no text consumer falls back to the no-arg built-in BitmapFont`() {
        for ((name, src) in consumers()) {
            assertTrue(
                "AC#1: $name must NOT construct a no-arg BitmapFont() — that is the old built-in font UC28 removes",
                !src.contains("BitmapFont()"),
            )
        }
    }

    @Test
    fun `every text consumer downscales the baked master through GameFont NORM`() {
        for ((name, src) in consumers()) {
            assertTrue(
                "AC#1/#4: $name must downscale the baked master via GameFont.NORM",
                src.contains("GameFont.NORM"),
            )
        }
    }

    @Test
    fun `the screen-space overlays flow font size through UiScale factor`() {
        // AC#4: HUD + the two map-label renderers draw in raw screen space, so they multiply the NORM
        // downscale by uiScale (the single knob). The pure scale math is pinned in
        // com.orbitalfrontier.render.Uc28FontScaleInvariantTest; here we pin that the multiplication exists.
        for (name in listOf("render/HudRenderer.kt", "render/MinimapRenderer.kt", "render/MapOverlayRenderer.kt")) {
            val src = readSource(name)
            assertTrue(
                "AC#4: ${name.substringAfterLast('/')} must scale the font by GameFont.NORM × uiScale",
                src.contains("GameFont.NORM * uiScale"),
            )
        }
    }

    @Test
    fun `the controls skin scales by NORM alone because the viewport already magnifies`() {
        // AC#4: the Scene2D screens render through a ×UiScale.factor viewport (ADR 0015), so the skin must
        // NOT double-apply uiScale — it scales by GameFont.NORM only. Pinned so a future edit can't
        // accidentally re-introduce a uiScale multiply here and double-magnify the menu text.
        val src = readSource("screen/controls/PlaceholderControlsSkin.kt")
        assertTrue(
            "AC#4: PlaceholderControlsSkin must scale by GameFont.NORM (viewport already applies uiScale)",
            src.contains("setScale(GameFont.NORM)"),
        )
    }

    private companion object {
        private fun consumers(): List<Pair<String, String>> =
            listOf(
                "render/HudRenderer.kt",
                "render/MinimapRenderer.kt",
                "render/MapOverlayRenderer.kt",
                "screen/controls/PlaceholderControlsSkin.kt",
            ).map { it to readSource(it) }

        /**
         * Locates a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles running from the module dir, the repo root,
         * or a git worktree). Hard-fails rather than passing silently if the file cannot be found
         * (mirrors [Uc24MapLabelsGuardTest]).
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
                    "the UC28 source-anchored wiring guard cannot run (refusing to pass silently).",
            )
        }
    }
}
