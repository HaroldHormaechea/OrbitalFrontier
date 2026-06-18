package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.combat.DestructionSummary

/**
 * Full-screen modal **destruction / game-over overlay** (UC33): a dimming backdrop that swallows every
 * tap (so no flight control underneath fires while the simulation is frozen) plus a centred consequence
 * panel — a "SHIP DESTROYED" title, three readout lines (cargo lost, insurance/credit penalty, respawn
 * location) and a single **CONTINUE** button the player must press to respawn (UC33 AC#1/#2/#3).
 *
 * Pure Scene2D view, mirroring [PauseOverlay]: it owns its actors and forwards the CONTINUE tap to the
 * [onContinue] callback the screen wires; all gate state lives in [com.orbitalfrontier.render.DestructionState]
 * on the screen. The backing [actor] is added to the stage **last** (above the pause overlay) so its
 * backdrop holds the top z-order over the HUD, the map/pause overlays and their tap targets. The backdrop
 * is a generated 1×1 translucent texture this widget owns and releases in [dispose] (no atlas art —
 * mirrors the runtime-pixmap chrome in [PauseOverlay] / [OrbitalUiSkin]). [setSummary] refreshes the
 * readout lines from the pure [DestructionSummary] the screen builds at the moment of destruction.
 */
class DestructionOverlay(skin: OrbitalUiSkin) : Disposable {
    /** The full-screen group (backdrop + consequence column); positioned/sized by the screen. */
    val actor: Group = Group()

    /** Edge-triggered CONTINUE callback — defaulted to a no-op; the screen wires it to its respawn-resume. */
    var onContinue: () -> Unit = {}

    private val backdropTexture: Texture
    private val backdrop: Image
    private val column = Table()

    private val titleLabel = Label("SHIP DESTROYED", skin.titleLabelStyle)
    private val cargoLabel = Label("", skin.labelStyle)
    private val penaltyLabel = Label("", skin.labelStyle)
    private val respawnLabel = Label("", skin.labelStyle)
    private val continueButton = TextButton("CONTINUE", skin.settingsButtonStyle)

    init {
        // Generated translucent-black fill for the dim backdrop (a single texel stretched to fill the
        // stage); owned + disposed here, never an atlas region.
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        try {
            pixmap.setColor(0f, 0f, 0f, BACKDROP_ALPHA)
            pixmap.fill()
            backdropTexture = Texture(pixmap)
        } finally {
            pixmap.dispose()
        }
        backdrop = Image(TextureRegionDrawable(backdropTexture))
        // Swallow every tap so the modal backdrop blocks the flight controls underneath while frozen
        // (a ClickListener consumes the touchDown by default). The CONTINUE button sits above it.
        backdrop.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) = Unit
            },
        )
        continueButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onContinue()
                }
            },
        )

        column.add(titleLabel).padBottom(TITLE_GAP).row()
        column.add(cargoLabel).padBottom(LINE_GAP).row()
        column.add(penaltyLabel).padBottom(LINE_GAP).row()
        column.add(respawnLabel).padBottom(BUTTON_GAP).row()
        column.add(continueButton).width(BUTTON_WIDTH).height(BUTTON_HEIGHT)

        actor.addActor(backdrop)
        actor.addActor(column)
    }

    /**
     * Refresh the consequence readout from [summary] (UC33 AC#2): cargo jettisoned, the insurance-covered
     * credit penalty, and the respawn location. Re-packs + re-centres the column so the panel fits the new
     * text. Called by the screen at the instant of destruction, before the overlay is shown.
     */
    fun setSummary(summary: DestructionSummary) {
        cargoLabel.setText("Cargo lost: ${summary.cargoUnitsLost} units")
        penaltyLabel.setText("Insurance: covered — ${summary.creditPenalty} credits")
        respawnLabel.setText("Respawn: ${summary.respawnLocationName}")
        column.pack()
        recentre()
    }

    /** Resize to fill the stage and re-centre the consequence column. Called from the screen's layout pass. */
    fun resize(
        width: Float,
        height: Float,
    ) {
        actor.setBounds(0f, 0f, width, height)
        backdrop.setBounds(0f, 0f, width, height)
        column.pack()
        recentre()
    }

    private fun recentre() {
        column.setPosition((actor.width - column.width) / 2f, (actor.height - column.height) / 2f)
    }

    override fun dispose() {
        backdropTexture.dispose()
    }

    private companion object {
        const val BACKDROP_ALPHA = 0.7f
        const val BUTTON_WIDTH = 320f
        const val BUTTON_HEIGHT = 56f
        const val TITLE_GAP = 24f
        const val LINE_GAP = 12f
        const val BUTTON_GAP = 24f
    }
}
