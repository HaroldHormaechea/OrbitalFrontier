package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.notify.NotificationSeverity
import com.orbitalfrontier.notify.VisibleNotification

/**
 * Draws the transient notification toasts (UC35 AC#2/#4) — the visual half of the notification system, the
 * device-side mirror of the pure [com.orbitalfrontier.notify.NotificationQueue].
 *
 * Draw-only and deliberately thin, exactly like [HudRenderer]: all of the *what* (which events become
 * toasts, how they coalesce, when they dismiss) lives in the engine-free `notify` package; this only
 * formats the [com.orbitalfrontier.notify.NotificationQueue.visible] snapshot. Each toast is stacked in the
 * top-centre band by the pure [NotificationLayout] and tinted by its [NotificationSeverity] from the
 * design-system [Palette] — neutral steel for INFO, amber "warning" for WARNING (UC27 AC#8) — so a caution
 * (low fuel, a loss, a timeout) reads differently from a routine confirmation (AC#2).
 *
 * Uses the bundled game font ([GameFont]) drawn in screen space; a reusable [StringBuilder] and in-place
 * ellipsis ([HudLayout.ellipsize]) avoid per-frame allocation, protecting the 60 FPS budget. [uiScale]
 * (ADR 0015) magnifies the overlay exactly as [HudRenderer] does: the font is scaled once at construction.
 * The font is downscaled from its bake cap with Linear filtering for crisp text at any DPI (UC28).
 */
class NotificationRenderer(
    private val uiScale: Float = UiScale.factor,
) : Disposable {
    private val batch = SpriteBatch()
    private val font: BitmapFont =
        GameFontLoader.load().apply {
            data.setScale(GameFont.NORM * uiScale)
        }
    private val line = StringBuilder(32)
    private val projection = Matrix4()

    /**
     * Draw [notifications] (the queue's visible snapshot with life fractions, top-of-stack first) as a
     * downward-stacking column of severity-tinted toasts in the top-centre band (UC35 AC#2/#4; UC40 AC#2).
     * [viewportWidth]/[viewportHeight] are the **pixel** viewport. A no-op when the list is empty, so a quiet
     * moment costs only the empty-check (no batch begin/end).
     *
     * **Animated feedback (UC40 AC#2).** Each toast's [VisibleNotification.lifeFraction] (pure, from
     * [com.orbitalfrontier.notify.NotificationQueue.visibleWithProgress]) drives a fade-in over its first
     * sliver of life, a fade-out over its last, full opacity between — plus a slight upward drift across its
     * lifetime — so a `+N`/`-N CR` delta reads as a flash that rises and fades rather than a static line. The
     * curve is computed here from the pure fraction; the timing itself stays engine-free in the queue.
     *
     * Unit handling mirrors [MinimapRenderer] (ADR 0015): the pure [NotificationLayout] is authored in world
     * units (so its constants line up with [HudLayout]/[MinimapLayout]), so the pixel viewport is divided by
     * [uiScale] to lay out, then each returned rect is multiplied back up to pixels for the actual draw.
     */
    fun render(
        notifications: List<VisibleNotification>,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (notifications.isEmpty()) return

        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        batch.projectionMatrix = projection
        batch.begin()

        val worldWidth = viewportWidth / uiScale
        val worldHeight = viewportHeight / uiScale
        val padding = TEXT_PADDING * uiScale
        notifications.forEachIndexed { index, visible ->
            val notification = visible.notification
            val rect = NotificationLayout.toastRect(worldWidth, worldHeight, index)
            val alpha = alphaFor(visible.lifeFraction)
            // Slight upward drift across the toast's life (world units -> pixels), so the flash rises.
            val drift = visible.lifeFraction * DRIFT_WORLD * uiScale
            // World units -> pixels for the draw (the projection is in pixel space).
            val pixelX = rect.x * uiScale
            val pixelTop = (rect.y + rect.height) * uiScale + drift
            line.setLength(0)
            line.append(notification.message)
            HudLayout.ellipsize(line)
            val tint =
                when (notification.severity) {
                    NotificationSeverity.INFO -> Palette.STEEL_050
                    NotificationSeverity.WARNING -> Palette.WARNING
                    // A refusal is the distinct DANGER colour — never the amber a routine spend uses (UC40).
                    NotificationSeverity.ERROR -> Palette.DANGER
                }
            // Apply the life-fraction alpha without mutating the shared Palette colour reference.
            font.setColor(tint.r, tint.g, tint.b, alpha)
            // Baseline a touch below the rect's top edge so the glyph cap sits inside the row band.
            font.draw(batch, line, pixelX + padding, pixelTop - padding)
        }
        font.color = Palette.STEEL_050

        batch.end()
    }

    /**
     * Map a toast's life fraction `0..1` to a draw alpha (UC40 AC#2): ramp up over the first [FADE_IN_FRAC],
     * hold at full opacity, then ramp down over the final [FADE_OUT_FRAC] before auto-dismiss. Pure of GL.
     */
    private fun alphaFor(lifeFraction: Float): Float {
        val f = lifeFraction.coerceIn(0f, 1f)
        return when {
            f < FADE_IN_FRAC -> f / FADE_IN_FRAC
            f > 1f - FADE_OUT_FRAC -> (1f - f) / FADE_OUT_FRAC
            else -> 1f
        }.coerceIn(0f, 1f)
    }

    override fun dispose() {
        batch.dispose()
        font.dispose()
    }

    private companion object {
        /** World-space inset of the text from the toast rectangle's left/top edge (× uiScale at use). */
        const val TEXT_PADDING = 6f

        /** Fraction of a toast's life spent fading IN from transparent (UC40 AC#2). */
        const val FADE_IN_FRAC = 0.12f

        /** Fraction of a toast's life spent fading OUT to transparent before auto-dismiss (UC40 AC#2). */
        const val FADE_OUT_FRAC = 0.25f

        /** World-space upward drift applied across a toast's full life (× uiScale at use) — the rise. */
        const val DRIFT_WORLD = 10f
    }
}
