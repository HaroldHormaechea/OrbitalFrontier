package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.orbitalfrontier.render.TutorialOverlayLayout
import com.orbitalfrontier.tutorial.TutorialStep

/**
 * Thin **draw-only** first-run tutorial hint overlay (UC36): the current step's ASCII copy plus a SKIP
 * (this step) and a SKIP ALL button, laid out in the bottom-centre hint band by the pure
 * [TutorialOverlayLayout]. It owns no progression state — [com.orbitalfrontier.render.TutorialState]
 * lives on the play screen, which feeds this widget the active [TutorialStep] each frame via [setStep]
 * and wires [onSkip]/[onSkipAll] to the state transitions. The control *emphasis* (highlighting the
 * joystick / arc button the step points at) is driven separately by the screen tinting those controls,
 * so this overlay stays purely informational and never gates input (AC#4).
 *
 * Pure Scene2D view, mirroring [PauseOverlay]/[DestructionOverlay]: it owns its actors and forwards taps.
 * The copy label is [Touchable.disabled] so it never steals a touch; only the two buttons are hittable.
 * The backing [actor] is added to the stage **below** the pause/destruction backdrops (so a pause frame
 * covers it) but above the HUD/controls (so the band reads over the playfield). The text stays plain
 * ASCII because the bundled game font ships ASCII + `°` + `→` only (UC28); over-long copy wraps.
 */
class TutorialOverlay(skin: OrbitalUiSkin) {
    /** The hint band group (copy + SKIP / SKIP ALL); positioned/sized by the screen's layout pass. */
    val actor: Group =
        Group().apply {
            // Non-transform group: children are placed in local coords offset by the group position,
            // exactly like ActionCluster — keeps placement + touch hit-testing simple.
            isTransform = false
        }

    // Edge-triggered callbacks — defaulted to no-ops; the screen wires them to its tutorial transitions.
    var onSkip: () -> Unit = {}
    var onSkipAll: () -> Unit = {}

    private val hintLabel =
        Label("", skin.labelStyle).apply {
            setWrap(true)
            setAlignment(Align.center)
            // Decorative — it must never steal a touch from the playfield/controls beneath the band.
            touchable = Touchable.disabled
        }
    private val skipButton = TextButton("SKIP STEP", skin.settingsButtonStyle)
    private val skipAllButton = TextButton("SKIP ALL", skin.settingsButtonStyle)

    init {
        skipButton.addListener(clickListener { onSkip() })
        skipAllButton.addListener(clickListener { onSkipAll() })
        actor.addActor(hintLabel)
        actor.addActor(skipButton)
        actor.addActor(skipAllButton)
    }

    /** Update the displayed copy for the active [step]; a null step blanks it (the screen also hides us). */
    fun setStep(step: TutorialStep?) {
        hintLabel.setText(step?.copy ?: "")
    }

    /**
     * Place the band for the current viewport via the pure [TutorialOverlayLayout] (bottom-centre, above
     * the corner controls). Mirrors the screen's other layout calls; called from its layout pass.
     */
    fun resize(
        width: Float,
        height: Float,
    ) {
        val band = TutorialOverlayLayout.bandRect(width, height)
        actor.setBounds(band.x, band.y, band.width, band.height)

        // Local coords (origin = band bottom-left). Button row floored at the band bottom, centred; copy
        // fills the space above it and wraps to the band width.
        val rowWidth = BUTTON_WIDTH * 2f + BUTTON_GAP
        val rowX = (band.width - rowWidth) / 2f
        skipButton.setBounds(rowX, 0f, BUTTON_WIDTH, BUTTON_HEIGHT)
        skipAllButton.setBounds(rowX + BUTTON_WIDTH + BUTTON_GAP, 0f, BUTTON_WIDTH, BUTTON_HEIGHT)

        val copyY = BUTTON_HEIGHT + BUTTON_GAP
        hintLabel.setBounds(0f, copyY, band.width, (band.height - copyY).coerceAtLeast(0f))
    }

    private fun clickListener(onClick: () -> Unit): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                onClick()
            }
        }

    private companion object {
        const val BUTTON_WIDTH = 120f
        const val BUTTON_HEIGHT = 36f
        const val BUTTON_GAP = 8f
    }
}
