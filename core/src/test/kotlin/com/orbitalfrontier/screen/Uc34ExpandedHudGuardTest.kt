package com.orbitalfrontier.screen

import com.orbitalfrontier.render.HudLayout
import com.orbitalfrontier.render.MinimapLayout
import com.orbitalfrontier.render.MinimapRenderer
import com.orbitalfrontier.screen.controls.ActionCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard for the expanded flight HUD (UC34): the GEOMETRIC half — the reserved top-left readout block
 * never collides with the UC22 top-right minimap nor the UC26 bottom action arc at the supported sizes
 * — plus the source-anchored half pinning the GL-bound wiring the headless backend cannot construct.
 *
 * The pure readout maths is covered by [com.orbitalfrontier.render.HudViewModelTest]; this guard pins
 * the *layout reservation* and the *wiring*.
 *
 * **The geometric checks read the REAL promoted constants** — [MinimapRenderer]'s
 * `DEFAULT_SIZE/DEFAULT_MARGIN/MIN_SIZE/CONTROL_GAP`, [com.orbitalfrontier.screen.PlayScreen]'s
 * `MARGIN/JOYSTICK_SIZE` and [ActionCluster.LAYOUT_HEIGHT] — never mirrored literals, since the whole
 * point of the guard is to catch the HUD block drifting into a neighbour if any of those move. UC34
 * promoted those companions from `private` to `internal` precisely so this guard can read them.
 *
 * ACs covered here:
 *  - **AC#3** — the new HUD elements respect the existing layout reservations and do not overlap the
 *    minimap or the action arc at the minimum supported (and a larger) resolution.
 *  - **AC#5** — the HUD layout guard is extended to the new elements; PlayScreen builds the view-model
 *    from the live credits/cargo/sector/missionLog, HudRenderer draws the new readout lines, and the
 *    drawn labels stay ASCII (the bundled font ships ASCII + `°` + `→` only, UC28).
 */
class Uc34ExpandedHudGuardTest {
    // --- AC#3: geometry — the reserved HUD block clears the minimap and the bottom control band -----

    @Test
    fun `the HUD block clears the top-right minimap at the minimum supported size`() {
        assertNoOverlap(MIN_VP_WIDTH, MIN_VP_HEIGHT)
    }

    @Test
    fun `the HUD block clears the top-right minimap at a larger viewport`() {
        assertNoOverlap(1280f, 720f)
    }

    @Test
    fun `the HUD block sits entirely above the worst-case bottom control band`() {
        // The block is measured down from the top edge; its bottom (rect.y) must stay at or above the
        // reserved bottom-control band (MARGIN + the taller of the joystick / action arc), so the expanded
        // readouts never reach down into the joystick / FIRE arc on either handedness.
        val block = HudLayout.blockRect(MIN_VP_WIDTH, MIN_VP_HEIGHT)
        assertTrue(
            "AC#3: HUD block bottom (${block.y}) must clear the reserved control band ($RESERVED_BOTTOM)",
            block.y >= RESERVED_BOTTOM,
        )
    }

    @Test
    fun `the HUD block is anchored flush into the top-left corner`() {
        val block = HudLayout.blockRect(MIN_VP_WIDTH, MIN_VP_HEIGHT)
        assertEquals("AC#3: left edge at x = 0", 0f, block.x, EPS)
        assertEquals("AC#3: top edge flush with the viewport top", MIN_VP_HEIGHT, block.y + block.height, EPS)
        assertEquals("AC#3: block uses the promoted BLOCK_WIDTH", HudLayout.BLOCK_WIDTH, block.width, EPS)
        assertEquals("AC#3: block uses the promoted BLOCK_HEIGHT", HudLayout.BLOCK_HEIGHT, block.height, EPS)
    }

    // --- AC#5: source-anchored wiring — PlayScreen builds the model from the live sim state ---------

    @Test
    fun `PlayScreen assembles the HUD view-model from the live credits, cargo, sector and mission log`() {
        val build = section(PLAY_SCREEN_SOURCE, "HudViewModel.build(")
        for (field in listOf("credits = credits", "cargo = cargo", "sectorName = sector.displayName", "missionLog = missionLog")) {
            assertTrue("AC#1/#4: PlayScreen must feed `$field` into HudViewModel.build", build.contains(field))
        }
        // The model is handed straight to the HUD renderer each frame (live update, AC#4).
        assertTrue("AC#4: the assembled model is drawn each frame", PLAY_SCREEN_SOURCE.contains("hudRenderer.render("))
    }

    @Test
    fun `the HUD reservation tracks HudLayout BLOCK_HEIGHT from a single source`() {
        // PlayScreen's HUD_BLOCK_HEIGHT (the top-left band reservation) must reference the pure
        // HudLayout.BLOCK_HEIGHT, not a mirrored literal, so the band and the drawn block can never drift.
        assertTrue(
            "AC#3/#5: HUD_BLOCK_HEIGHT is derived from HudLayout.BLOCK_HEIGHT",
            PLAY_SCREEN_SOURCE.contains("const val HUD_BLOCK_HEIGHT = HudLayout.BLOCK_HEIGHT"),
        )
    }

    // --- AC#5: HudRenderer draws the new readout lines, with ASCII-only labels ----------------------

    @Test
    fun `HudRenderer draws the credits, cargo, sector and objective lines`() {
        // Tie the check to the actually-drawn string literals (append("...") into the draw buffer), so a
        // label only living in a comment can't satisfy it.
        val drawn = drawnLabels()
        for (label in listOf("CR ", "CRG ", "SEC ", "OBJ ")) {
            assertTrue(
                "AC#1/#2: HudRenderer must draw a readout containing `$label`",
                drawn.any { it.contains(label) },
            )
        }
    }

    @Test
    fun `every drawn HUD label literal is ASCII`() {
        // The bundled font ships ASCII + `°` + `→` only (UC28); the `°` is a char literal and `→` comes
        // from the pure view-model, so every STRING label appended into the draw buffer must be ASCII.
        val labels = drawnLabels()
        assertTrue("expected to find drawn HUD label literals", labels.isNotEmpty())
        for (label in labels) {
            assertTrue(
                "AC#5: HUD label literal must be ASCII-only: <$label>",
                label.all { it.code in 0..127 },
            )
        }
    }

    // --- AC#5 (drift guard): the geometry reads the real promoted constants ------------------------

    @Test
    fun `the minimap geometry constants are reachable as the promoted internal companion`() {
        // If any of these were re-privatised, this guard would stop compiling — that is the intent: the
        // overlap check must read the SHIPPED minimap geometry, not a copy that can silently fall stale.
        assertTrue("DEFAULT_SIZE is the shipped panel size", MinimapRenderer.DEFAULT_SIZE > 0f)
        assertTrue("MIN_SIZE floor is below the default", MinimapRenderer.MIN_SIZE <= MinimapRenderer.DEFAULT_SIZE)
        assertTrue("CONTROL_GAP is a positive clearance", MinimapRenderer.CONTROL_GAP > 0f)
        assertTrue("DEFAULT_MARGIN is a positive inset", MinimapRenderer.DEFAULT_MARGIN > 0f)
        assertEquals(
            "reservedBottom is MARGIN + the worst-case bottom control",
            RESERVED_BOTTOM,
            PlayScreen.MARGIN + maxOf(PlayScreen.JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT),
            EPS,
        )
    }

    private companion object {
        const val EPS = 1e-3f

        // Minimum supported viewport in WORLD units: 1080p landscape at UiScale.factor = 2 → 1920x1080 ÷ 2.
        const val MIN_VP_WIDTH = 960f
        const val MIN_VP_HEIGHT = 540f

        // The worst-case bottom-control band, built from the REAL promoted constants (no mirrored literals).
        val RESERVED_BOTTOM = PlayScreen.MARGIN + maxOf(PlayScreen.JOYSTICK_SIZE, ActionCluster.LAYOUT_HEIGHT)

        /** The shipped minimap panel rectangle for the given world viewport, from the real geometry. */
        fun minimapPanel(
            vpWidth: Float,
            vpHeight: Float,
        ): MinimapLayout.Rect =
            MinimapLayout.panelRect(
                vpWidth = vpWidth,
                vpHeight = vpHeight,
                reservedBottom = RESERVED_BOTTOM,
                margin = MinimapRenderer.DEFAULT_MARGIN,
                maxSize = MinimapRenderer.DEFAULT_SIZE,
                minSize = MinimapRenderer.MIN_SIZE,
                gap = MinimapRenderer.CONTROL_GAP,
            )

        fun assertNoOverlap(
            vpWidth: Float,
            vpHeight: Float,
        ) {
            val hud = HudLayout.blockRect(vpWidth, vpHeight)
            val minimap = minimapPanel(vpWidth, vpHeight)
            assertFalse(
                "AC#3: the HUD block ($hud) must not overlap the minimap panel ($minimap) at ${vpWidth}x$vpHeight",
                hud.overlaps(minimap),
            )
        }

        private val PLAY_SCREEN_SOURCE: String = readSource("screen/PlayScreen.kt")
        private val HUD_RENDERER_SOURCE: String = readSource("render/HudRenderer.kt")

        /** Every string literal appended into the renderer's draw buffer (the text actually drawn). */
        private fun drawnLabels(): List<String> =
            Regex("""append\("([^"]*)"\)""").findAll(HUD_RENDERER_SOURCE).map { it.groupValues[1] }.toList()

        /**
         * The body from [header] to the first line that is a single closing brace at the declaration's
         * indentation — enough to scope an assertion without a full parser (mirrors the repo's existing
         * source-anchored guards, e.g. [Uc22MinimapTopRightGuardTest]).
         */
        private fun section(
            source: String,
            header: String,
        ): String {
            val start = source.indexOf(header)
            if (start < 0) throw AssertionError("Could not locate '$header' in source")
            val rest = source.substring(start)
            val end = Regex("""\n {8}}""").find(rest)?.range?.last ?: rest.length
            return rest.substring(0, end)
        }

        /**
         * Locate a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles the module dir, the repo root, or a git
         * worktree). Hard-fails rather than passing silently if the file cannot be found.
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
                    "the UC34 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
