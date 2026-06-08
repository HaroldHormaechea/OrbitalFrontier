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
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin

/**
 * The station-hub screen shown while the ship is docked (UC05 AC#3).
 *
 * Intentionally a **thin view with no game logic**: it shows the docked station's name, an **active
 * TRADE** button that opens the station trade desk (UC08), a column of remaining **inert** service
 * entries (OUTFIT / MISSIONS — labels with no listeners) that later UCs (upgrades UC09, missions
 * UC12) will wire up, an **active REFUEL** service (UC07 AC#5), and a single **active** UNDOCK
 * button. Tapping UNDOCK fires [onUndock], TRADE fires [onTrade], and REFUEL fires [onRefuel]; the
 * owner ([com.orbitalfrontier.app.OrbitalFrontierGame]) routes them — UNDOCK/REFUEL to the play
 * screen's pure transitions (dock state / [Refueling.resolve]) and TRADE to opening the
 * [com.orbitalfrontier.screen.TradeScreen]. Keeping that mutation in one place (the play screen)
 * keeps this screen free of world/save coupling (SRP); the screen only renders the [fuelStatus]
 * readout and re-reads it after a refuel tap.
 *
 * Owns its own GL-backed resources (a [PlaceholderControlsSkin] + [Stage]) and releases them in
 * [dispose] — the game disposes both screens explicitly, so there is no leaked context when the
 * other screen is the active one (libGDX `setScreen` only `hide()`s the previous screen).
 */
class StationHubScreen(
    private val logger: Logger,
    stationName: String,
    private val onUndock: () -> Unit,
    private val onTrade: () -> Unit,
    private val onRefuel: () -> Unit,
    private val fuelStatus: () -> String,
) : ScreenAdapter() {
    private val skin = PlaceholderControlsSkin()
    private val stage = Stage(ScreenViewport())

    // Fuel readout (UC07): seeded from the current tank and refreshed in place after each REFUEL tap.
    private val fuelLabel = Label("", skin.labelStyle)

    init {
        val root = Table()
        root.setFillParent(true)
        root.pad(MARGIN)

        root.add(Label(stationName, skin.labelStyle)).padBottom(TITLE_GAP).row()
        root.add(Label("STATION SERVICES", skin.labelStyle)).padBottom(SERVICE_GAP).row()

        // Active TRADE service (UC08): opens the station trade desk. The play screen owns the pure
        // Trading.resolve; this button just fires the intent so the game switches to the TradeScreen.
        val tradeButton = TextButton("TRADE", skin.settingsButtonStyle)
        tradeButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onTrade()
                }
            },
        )
        root.add(tradeButton).size(UNDOCK_WIDTH, UNDOCK_HEIGHT).padBottom(SERVICE_GAP).row()

        // Inert service stubs — no listeners; wired by later UCs (upgrades/missions).
        for (service in INERT_SERVICES) {
            root.add(Label("$service  (coming soon)", skin.labelStyle)).padBottom(SERVICE_GAP).row()
        }

        // Active REFUEL service (UC07 AC#5): the play screen owns the pure Refueling.resolve; this row
        // shows the current tank and a button that fires the intent, then re-reads the readout.
        fuelLabel.setText(fuelStatus())
        root.add(fuelLabel).padBottom(SERVICE_GAP).row()
        val refuelButton = TextButton("REFUEL", skin.settingsButtonStyle)
        refuelButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onRefuel()
                    fuelLabel.setText(fuelStatus())
                }
            },
        )
        root.add(refuelButton).size(UNDOCK_WIDTH, UNDOCK_HEIGHT).padBottom(SERVICE_GAP).row()

        // The one active control: leave the station and return to flight.
        val undockButton = TextButton("UNDOCK", skin.settingsButtonStyle)
        undockButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onUndock()
                }
            },
        )
        root.add(undockButton).size(UNDOCK_WIDTH, UNDOCK_HEIGHT).padTop(UNDOCK_GAP).row()

        stage.addActor(root)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "StationHubScreen shown")
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(BG_R, BG_G, BG_B, 1f)
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
        val INERT_SERVICES = listOf("OUTFIT", "MISSIONS")
        const val MARGIN = 32f
        const val TITLE_GAP = 24f
        const val SERVICE_GAP = 12f
        const val UNDOCK_GAP = 32f
        const val UNDOCK_WIDTH = 220f
        const val UNDOCK_HEIGHT = 64f
        const val BG_R = 0.04f
        const val BG_G = 0.06f
        const val BG_B = 0.10f
    }
}
