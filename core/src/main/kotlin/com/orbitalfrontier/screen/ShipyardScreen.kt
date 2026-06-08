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
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.ShipRoster
import com.orbitalfrontier.ship.Shipyard

/**
 * The station shipyard / ship-switch screen shown from the station hub while docked (UC09 AC#5).
 *
 * Intentionally a **thin view with no game logic** (SRP), mirroring [TradeScreen]/[OutfitScreen]: it
 * lists the docked station's offered ship types (each with price + role and a BUY button) and the
 * player's owned ships (each with a SWITCH button, the active one marked). A tap fires the injected
 * [onFleet] intent (a [FleetOrder]); the owner routes it to [PlayScreen.fleetCommand], which runs the
 * **pure** [com.orbitalfrontier.ship.FleetResolver], then autosaves. The screen rebuilds from the
 * refreshed [fleetSupplier]/[creditsSupplier].
 *
 * Offered ships render in [ShipRoster] authored order and owned ships in fleet (sorted) order, so the
 * layout is deterministic. Owns its GL-backed resources and releases them in [dispose].
 */
class ShipyardScreen(
    private val logger: Logger,
    private val stationName: String,
    private val shipyard: Shipyard,
    private val creditsSupplier: () -> Long,
    private val fleetSupplier: () -> Fleet,
    private val onFleet: (FleetOrder) -> Unit,
    private val onBack: () -> Unit,
) : ScreenAdapter() {
    private val skin = PlaceholderControlsSkin()
    private val stage = Stage(ScreenViewport())
    private val root = Table()

    init {
        root.setFillParent(true)
        root.pad(MARGIN)
        stage.addActor(root)
        rebuild()
    }

    /** Clear and repopulate the whole table from the current fleet + credits (called after each tap). */
    private fun rebuild() {
        root.clear()
        val fleet = fleetSupplier()

        root.add(Label(stationName, skin.labelStyle)).colspan(COLSPAN).padBottom(TITLE_GAP).row()
        root.add(Label("SHIPYARD", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()
        root.add(Label("CREDITS: ${creditsSupplier()}", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()

        // Ships for sale, in roster order.
        val forSale = ShipRoster.all.filter { shipyard.offers(it.id) }
        if (forSale.isEmpty()) {
            root.add(Label("No ships for sale here.", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()
        } else {
            for (type in forSale) {
                val info = Label("${type.displayName}  ${type.price}cr  [${type.role}]", skin.labelStyle)
                val buyButton = TextButton("BUY", skin.settingsButtonStyle)
                buyButton.addListener(tapListener { onFleet(FleetOrder.BuyShip(type.id)) })
                root.add(info).left().padRight(CELL_GAP).padBottom(ROW_GAP)
                root.add(buyButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(ROW_GAP).row()
            }
        }

        // Owned ships (switch active), in fleet order; the active one is marked and not switchable.
        root.add(Label("YOUR SHIPS:", skin.labelStyle)).colspan(COLSPAN).padTop(SERVICE_GAP).padBottom(ROW_GAP).row()
        for (ship in fleet.ships) {
            val isActive = ship.id == fleet.activeShipId
            val marker = if (isActive) "  (active)" else ""
            val info = Label("#${ship.id.value} ${ship.type.displayName}$marker", skin.labelStyle)
            root.add(info).left().padRight(CELL_GAP).padBottom(ROW_GAP)
            if (isActive) {
                root.add(Label("", skin.labelStyle)).padBottom(ROW_GAP).row()
            } else {
                val switchButton = TextButton("SWITCH", skin.settingsButtonStyle)
                switchButton.addListener(tapListener { onFleet(FleetOrder.SwitchActive(ship.id)) })
                root.add(switchButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(ROW_GAP).row()
            }
        }

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
        root.add(backButton).colspan(COLSPAN).size(BACK_WIDTH, BUTTON_HEIGHT).padTop(BACK_GAP).row()
    }

    /** A click listener that fires [action] then rebuilds the table from the refreshed state. */
    private fun tapListener(action: () -> Unit): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                action()
                rebuild()
            }
        }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "ShipyardScreen shown")
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
        const val COLSPAN = 2
        const val MARGIN = 32f
        const val TITLE_GAP = 24f
        const val SERVICE_GAP = 12f
        const val ROW_GAP = 8f
        const val CELL_GAP = 16f
        const val BUTTON_WIDTH = 140f
        const val BUTTON_HEIGHT = 56f
        const val BACK_WIDTH = 220f
        const val BACK_GAP = 24f
        const val BG_R = 0.04f
        const val BG_G = 0.06f
        const val BG_B = 0.10f
    }
}
