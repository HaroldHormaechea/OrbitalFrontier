package com.orbitalfrontier.render

/**
 * Pure, libGDX-free geometry + text bounds for the top-left flight HUD readout block (UC34).
 *
 * The mirror of [MinimapLayout] for the HUD's own reserved corner: it expresses the block the
 * [HudRenderer] draws into as a [MinimapLayout.Rect] (sharing the one rectangle type, so the HUD/minimap
 * overlap-free placement can be asserted directly against each other) and owns the single line-length
 * cap the variable readouts ellipsize to. Kept engine-free (no libGDX types) so it stays JVM-testable
 * (ADR 0001) and the layout guard can assert the reservation without a GL context.
 *
 * The block is top-left anchored — the conventional flight-readout location — opposite the top-right
 * minimap (UC22) and clear of the bottom action arc (UC26), so the expanded HUD never overlaps either
 * reserved region at the supported sizes. [BLOCK_HEIGHT] is the worst-case height (eight readout lines:
 * SPEED, HDG, FUEL, PWR, CR+CRG, SEC, OBJ, IN COMBAT); [PlayScreen]'s `HUD_BLOCK_HEIGHT` references it so
 * the settings/handedness band below the HUD tracks the real reservation from a single source.
 */
object HudLayout {
    /**
     * Worst-case world-space height of the readout block, measured down from the top edge.
     * UC49: bumped from 170f to 192f (= one extra 22f line) for the added PWR power readout line.
     */
    const val BLOCK_HEIGHT = 192f

    /**
     * World-space LEFT INSET of the readout block (UC56). The block no longer hugs the screen's left edge:
     * a 72² Settings ball (UC56) sits in the top-left corner at x[24,96], so the readout block starts at
     * this inset (x = 96) and edge-touches the ball without overlapping it.
     *
     * **§5 BINDING — single origin source.** This is the ONE value that both [blockRect]'s left edge AND
     * the [HudRenderer]'s text draw x-origin read, so the reservation rect and the actually-drawn text can
     * never diverge (if they did, an overlap guard could pass while the on-screen text still collided with
     * the ball). The Settings-ball/readout-block clearance guard asserts against THIS constant, not a
     * literal. [NotificationLayout] likewise reserves the band's left edge from `BLOCK_X + BLOCK_WIDTH`.
     */
    const val BLOCK_X = 96f

    /** World-space width of the readout block, measured from [BLOCK_X] (its right edge is `BLOCK_X + this`). */
    const val BLOCK_WIDTH = 360f

    /** The maximum characters a variable readout line (SEC, OBJ) is ellipsized to before drawing. */
    const val MAX_LINE_CHARS = 28

    /** ASCII ellipsis — the bundled font ships ASCII + `°` + `→` only, so no `…` glyph (UC28). */
    private const val ELLIPSIS = "..."

    /**
     * The HUD readout block as a top-left-anchored rectangle for the given viewport, in the same
     * coordinate space as [MinimapLayout.panelRect] so the two reserved regions can be checked for
     * non-overlap directly. Left edge inset to [BLOCK_X] (UC56 — clears the top-left Settings ball); top
     * edge flush with `vpHeight`.
     */
    fun blockRect(
        vpWidth: Float,
        vpHeight: Float,
    ): MinimapLayout.Rect = MinimapLayout.Rect(BLOCK_X, vpHeight - BLOCK_HEIGHT, BLOCK_WIDTH, BLOCK_HEIGHT)

    /**
     * Truncate [line] in place to at most [maxChars] characters, appending an ASCII [ELLIPSIS] when it
     * was too long. In-place on the renderer's reused [StringBuilder] so a normal (short) line allocates
     * nothing — only an over-long line touches the buffer (UC34 small-screen pitfall; 60 FPS budget).
     */
    fun ellipsize(
        line: StringBuilder,
        maxChars: Int = MAX_LINE_CHARS,
    ) {
        if (line.length <= maxChars) return
        val keep = (maxChars - ELLIPSIS.length).coerceAtLeast(0)
        line.setLength(keep)
        line.append(ELLIPSIS)
    }
}
