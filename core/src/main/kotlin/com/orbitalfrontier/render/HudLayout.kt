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
 * reserved region at the supported sizes. [BLOCK_HEIGHT] is the worst-case height (seven readout lines:
 * SPEED, HDG, FUEL, CR+CRG, SEC, OBJ, IN COMBAT); [PlayScreen]'s `HUD_BLOCK_HEIGHT` references it so the
 * settings/handedness band below the HUD tracks the real reservation from a single source.
 */
object HudLayout {
    /** Worst-case world-space height of the readout block, measured down from the top edge. */
    const val BLOCK_HEIGHT = 170f

    /** World-space width the readout block reserves from the left edge. */
    const val BLOCK_WIDTH = 360f

    /** The maximum characters a variable readout line (SEC, OBJ) is ellipsized to before drawing. */
    const val MAX_LINE_CHARS = 28

    /** ASCII ellipsis — the bundled font ships ASCII + `°` + `→` only, so no `…` glyph (UC28). */
    private const val ELLIPSIS = "..."

    /**
     * The HUD readout block as a top-left-anchored rectangle for the given viewport, in the same
     * coordinate space as [MinimapLayout.panelRect] so the two reserved regions can be checked for
     * non-overlap directly. Anchored at the top-left corner: `x = 0`, top edge at `vpHeight`.
     */
    fun blockRect(
        vpWidth: Float,
        vpHeight: Float,
    ): MinimapLayout.Rect = MinimapLayout.Rect(0f, vpHeight - BLOCK_HEIGHT, BLOCK_WIDTH, BLOCK_HEIGHT)

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
