package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.crew.HireOrder
import com.orbitalfrontier.crew.Hiring
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin

/**
 * The station crew-hire desk shown from the station hub while docked at a crew-hiring station (UC11
 * AC#2).
 *
 * Intentionally a **thin view with no game logic** (SRP), mirroring [TradeScreen]: it shows the
 * active ship's crew "N / capacity", the player's credit balance, the per-crew hire cost, and the
 * derived turret-operability flag (UC11 AC#3), plus a single HIRE button and a BACK button. A HIRE
 * tap fires the injected [onHire] intent (a [HireOrder.Hire]); the owner
 * ([com.orbitalfrontier.app.OrbitalFrontierGame]) routes it to the play screen, which performs the
 * **pure** [com.orbitalfrontier.crew.Hiring.resolve], folds the new credits + crew back in, and
 * autosaves. The screen then re-reads its suppliers and refreshes its readouts in place — keeping all
 * crew mutation in one place (the play screen) and this screen free of world/save coupling.
 *
 * Hiring [UNITS_PER_TAP] crew per tap; the pure resolver clamps to remaining capacity and what the
 * wallet affords (clamp-to-remaining), so an over-request is harmless.
 *
 * Owns its own GL-backed resources (a [OrbitalUiSkin] + [Stage]) and releases them in
 * [dispose] — the game disposes the screen explicitly when the player leaves (libGDX `setScreen`
 * only `hide()`s the previous screen).
 */
class HireScreen(
    private val logger: Logger,
    stationName: String,
    private val creditsSupplier: () -> Long,
    private val crewSupplier: () -> Int,
    private val crewCapacitySupplier: () -> Int,
    private val turretOperableSupplier: () -> Boolean,
    private val onHire: (HireOrder) -> Unit,
    private val onBack: () -> Unit,
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })

    // Readouts refreshed in place after each hire.
    private val crewLabel = Label("", skin.labelStyle)
    private val balanceLabel = Label("", skin.labelStyle)
    private val turretLabel = Label("", skin.labelStyle)

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        val root = Table()
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel

        root.add(Label(stationName, skin.titleLabelStyle)).padBottom(TITLE_GAP).row()
        root.add(Label("HIRE CREW", skin.labelStyle)).padBottom(SERVICE_GAP).row()

        crewLabel.setText(crewText())
        root.add(crewLabel).padBottom(SERVICE_GAP).row()

        balanceLabel.setText(balanceText())
        root.add(balanceLabel).padBottom(SERVICE_GAP).row()

        root.add(Label("cost ${Hiring.HIRE_COST_PER_CREW} / crew", skin.labelStyle)).padBottom(SERVICE_GAP).row()

        turretLabel.setText(turretText())
        root.add(turretLabel).padBottom(SERVICE_GAP).row()

        val hireButton = TextButton("HIRE", skin.settingsButtonStyle)
        hireButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onHire(HireOrder.Hire(UNITS_PER_TAP))
                    refresh()
                }
            },
        )
        root.add(hireButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(SERVICE_GAP).row()

        val backButton = TextButton("BACK", skin.settingsButtonStyle)
        backButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onBack()
                }
            },
        )
        root.add(backButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padTop(BACK_GAP).row()

        stage.addActor(root)
    }

    /** Re-read the crew/balance/turret readouts after a hire and update the labels in place. */
    private fun refresh() {
        crewLabel.setText(crewText())
        balanceLabel.setText(balanceText())
        turretLabel.setText(turretText())
    }

    private fun crewText(): String = "CREW: ${crewSupplier()} / ${crewCapacitySupplier()}"

    private fun balanceText(): String = "CREDITS: ${creditsSupplier()}"

    private fun turretText(): String = "TURRETS: ${if (turretOperableSupplier()) "OPERABLE" else "INOPERABLE"}"

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "HireScreen shown")
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(Palette.SURFACE_BASE.r, Palette.SURFACE_BASE.g, Palette.SURFACE_BASE.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        stage.viewport.update(width, height, true)
    }

    override fun hide() {
        if (Gdx.input.inputProcessor === stage) {
            Gdx.input.inputProcessor = null
        }
    }

    override fun dispose() {
        stage.dispose()
        skin.dispose()
    }

    private companion object {
        const val TAG = "Screen"

        /** Crew hired per HIRE tap. The pure resolver clamps to remaining capacity + wallet. [TUNE] */
        const val UNITS_PER_TAP = 1

        const val MARGIN = 32f
        const val TITLE_GAP = 24f
        const val SERVICE_GAP = 12f
        const val BUTTON_WIDTH = 220f
        const val BUTTON_HEIGHT = 64f
        const val BACK_GAP = 24f
    }
}
