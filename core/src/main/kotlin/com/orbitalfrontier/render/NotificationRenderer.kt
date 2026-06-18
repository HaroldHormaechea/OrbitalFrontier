package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.notify.GameNotification
import com.orbitalfrontier.notify.NotificationSeverity

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
     * Draw [notifications] (the queue's visible snapshot, top-of-stack first) as a downward-stacking column
     * of severity-tinted toasts in the top-centre band. [viewportWidth]/[viewportHeight] are the **pixel**
     * viewport. A no-op when the list is empty, so a quiet moment costs only the empty-check (no batch
     * begin/end).
     *
     * Unit handling mirrors [MinimapRenderer] (ADR 0015): the pure [NotificationLayout] is authored in world
     * units (so its constants line up with [HudLayout]/[MinimapLayout]), so the pixel viewport is divided by
     * [uiScale] to lay out, then each returned rect is multiplied back up to pixels for the actual draw.
     */
    fun render(
        notifications: List<GameNotification>,
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
        notifications.forEachIndexed { index, notification ->
            val rect = NotificationLayout.toastRect(worldWidth, worldHeight, index)
            // World units -> pixels for the draw (the projection is in pixel space).
            val pixelX = rect.x * uiScale
            val pixelTop = (rect.y + rect.height) * uiScale
            line.setLength(0)
            line.append(notification.message)
            HudLayout.ellipsize(line)
            font.color =
                when (notification.severity) {
                    NotificationSeverity.INFO -> Palette.STEEL_050
                    NotificationSeverity.WARNING -> Palette.WARNING
                }
            // Baseline a touch below the rect's top edge so the glyph cap sits inside the row band.
            font.draw(batch, line, pixelX + padding, pixelTop - padding)
        }
        font.color = Palette.STEEL_050

        batch.end()
    }

    override fun dispose() {
        batch.dispose()
        font.dispose()
    }

    private companion object {
        /** World-space inset of the text from the toast rectangle's left/top edge (× uiScale at use). */
        const val TEXT_PADDING = 6f
    }
}
