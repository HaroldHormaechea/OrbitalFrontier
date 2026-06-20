package com.orbitalfrontier.render

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.power.PowerSystem
import kotlin.math.roundToInt

/**
 * Expanded heads-up display: the flight readouts a player needs to decide without opening a menu —
 * speed, heading, fuel, credits, cargo fill, current sector and the active-mission objective — updated
 * every frame from a pure [HudViewModel] (UC34 AC#1/#2/#4; UC07 AC#3).
 *
 * This renderer is draw-only: all readout maths (heading normalisation, the objective line, the
 * display==turn-in cargo read) lives in the JVM-testable [HudViewModel]. Uses the bundled game font
 * ([GameFont], a [BitmapFont] loaded by [GameFontLoader]) drawn in screen space. A reusable
 * [StringBuilder] and integer formatting avoid per-frame String/`String.format` allocation, protecting
 * the 60 FPS budget (coding-guidelines § performance); the variable lines (SEC, OBJ) are ellipsized in
 * place to [HudLayout.MAX_LINE_CHARS] so a long sector/objective name can't run under the top-right
 * minimap. The fuel line turns amber and appends "LOW" while the tank is below the low-fuel threshold,
 * and an "IN COMBAT" cue shows in the danger colour during an encounter — the player-facing status cues.
 *
 * [uiScale] (ADR 0015) magnifies the whole overlay: the font is scaled by it once at construction and
 * every base layout constant (margins, line height) is multiplied by it at its use site — the constants
 * stay authored at their base (×1) values so [UiScale.factor] remains the single knob. The font is baked
 * at [GameFont.BAKE_CAP_PX] and *downscaled* by [GameFont.NORM] × [uiScale] with Linear filtering (UC28),
 * so the master glyphs are minified rather than bilinearly up-stretched like the old built-in font —
 * crisp text at any supported DPI (UC28 AC#2).
 */
class HudRenderer(
    private val uiScale: Float = UiScale.factor,
) : Disposable {
    private val batch = SpriteBatch()
    private val font =
        GameFontLoader.load().apply {
            data.setScale(GameFont.NORM * uiScale)
            color = TEXT_COLOR
        }
    private val line = StringBuilder(32)
    private val projection = Matrix4()

    fun render(
        model: HudViewModel,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        // Base layout constants scaled at the use site — UiScale.factor stays the single knob.
        val margin = MARGIN * uiScale
        val lineHeight = LINE_HEIGHT * uiScale

        projection.setToOrtho2D(0f, 0f, viewportWidth, viewportHeight)
        batch.projectionMatrix = projection
        batch.begin()

        // A running line index from the top so conditional lines (OBJ, IN COMBAT) close the gap rather
        // than leaving a hole; baseline of line `row` is margin + row line-heights below the top edge.
        var row = 0

        line.setLength(0)
        line.append("SPEED ").append(model.speed.roundToInt())
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
        row++

        line.setLength(0)
        line.append("HDG ").append(model.headingDegrees).append(DEGREE)
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
        row++

        line.setLength(0)
        line.append("FUEL ").append(model.fuelLevel.roundToInt()).append('/').append(model.fuelCapacity.roundToInt())
        if (model.lowFuel) line.append("  LOW")
        // UC27: design-system tokens — amber "warning" caution while low, reset to the steel readout text
        // colour afterwards so other lines stay neutral next frame (AC#8).
        font.color = if (model.lowFuel) Palette.WARNING else TEXT_COLOR
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
        font.color = TEXT_COLOR
        row++

        // UC07/UC49: reactor supply vs. budget demand as a whole-percent gauge (AC#3). While browned out the
        // line turns amber ("warning" token) and names the shed systems — the player-facing brownout cue.
        // The brownout/shed state is render-only (never recorded), assembled in the pure HudViewModel.
        line.setLength(0)
        val powerPct = if (model.reactorOutput > 0f) (model.powerDraw / model.reactorOutput * 100f).roundToInt() else 0
        line.append("PWR ").append(powerPct).append('%')
        if (model.brownout) {
            line.append("  BROWNOUT")
            for (system in model.shedSystems.sortedBy { it.shedPriority }) {
                line.append(' ').append(shedLabel(system))
            }
        }
        HudLayout.ellipsize(line)
        font.color = if (model.brownout) Palette.WARNING else TEXT_COLOR
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
        font.color = TEXT_COLOR
        row++

        // UC34: credits + cargo fill share one line — "CR <credits>  CRG <used>/<cap>" — keeping the
        // worst-case block to seven lines so it stays within the reserved HudLayout.BLOCK_HEIGHT.
        line.setLength(0)
        line.append("CR ").append(model.credits).append("  CRG ").append(model.cargoUsed).append('/').append(model.cargoCapacity)
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
        row++

        // UC34: current sector name; ellipsized in place so a long authored name can't run under the
        // top-right minimap at the supported sizes.
        line.setLength(0)
        line.append("SEC ").append(model.sectorName)
        HudLayout.ellipsize(line)
        font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
        row++

        // UC34: the active-mission objective (target + progress); hidden entirely when no mission is
        // active. The objective content is built in the pure HudViewModel; ellipsized here like SEC.
        val objective = model.objective
        if (objective != null) {
            line.setLength(0)
            line.append("OBJ ").append(objective)
            HudLayout.ellipsize(line)
            font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
            row++
        }

        // UC13: an "IN COMBAT" cue while an encounter is live, drawn alongside the per-section ship
        // schematic ([com.orbitalfrontier.render.ShipSchematicRenderer]); the schematic carries the detail.
        // UC27: the design-system "danger" signal colour (AC#8).
        if (model.inCombat) {
            line.setLength(0)
            line.append("IN COMBAT")
            font.color = Palette.DANGER
            font.draw(batch, line, margin, viewportHeight - margin - lineHeight * row)
            font.color = TEXT_COLOR
            row++
        }

        batch.end()
    }

    override fun dispose() {
        batch.dispose()
        font.dispose()
    }

    private companion object {
        const val MARGIN = 16f
        const val LINE_HEIGHT = 22f

        // Degree sign (U+00B0). In GameFont.REQUIRED_GLYPHS, so the bundled font always renders it (UC28).
        const val DEGREE = '°'

        // UC49: compact ASCII tags (bundled-font safe) for the brownout shed-system cue on the PWR line.
        private fun shedLabel(system: PowerSystem): String =
            when (system) {
                PowerSystem.WEAPONS -> "WPN"
                PowerSystem.SCANNER -> "SCN"
                PowerSystem.HELM -> "HLM"
            }

        // UC27: high-emphasis steel readout colour for normal HUD text (AC#8); status lines override
        // with the amber "warning" / red "danger" signal tokens at their use site.
        val TEXT_COLOR = Palette.STEEL_050
    }
}
