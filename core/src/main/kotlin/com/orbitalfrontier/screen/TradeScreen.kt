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
import com.orbitalfrontier.economy.Cargo
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin

/**
 * The station trade desk shown from the station hub while docked (UC08 AC#2/#3).
 *
 * Intentionally a **thin view with no game logic** (SRP), mirroring [StationHubScreen]: it lists the
 * docked station's authored [StationMarket] offers — each resource with its buy/sell price and the
 * units currently held — plus the player's credit balance, and a BUY / SELL button per row and a
 * BACK button. A BUY/SELL tap fires the injected [onTrade] intent (a [TradeOrder]); the owner
 * ([com.orbitalfrontier.app.OrbitalFrontierGame]) routes it to the play screen, which performs the
 * **pure** [com.orbitalfrontier.economy.Trading.resolve], folds the new credits + cargo back in, and
 * autosaves. The screen then re-reads [creditsSupplier]/[cargoSupplier] and refreshes its readouts in
 * place — keeping all economy mutation in one place (the play screen) and this screen free of
 * world/save coupling.
 *
 * Rows are listed in [ResourceType] declaration order (deterministic), so the same market always
 * renders the same layout. Buying / selling moves [UNITS_PER_TAP] units per tap; the pure resolver
 * clamps to what is affordable / fits / is held, so an over-request is harmless.
 *
 * Owns its own GL-backed resources (a [OrbitalUiSkin] + [Stage]) and releases them in
 * [dispose] — the game disposes the screen explicitly when the player leaves (libGDX `setScreen`
 * only `hide()`s the previous screen).
 */
class TradeScreen(
    private val logger: Logger,
    stationName: String,
    private val market: StationMarket,
    private val creditsSupplier: () -> Long,
    private val cargoSupplier: () -> Cargo,
    private val onTrade: (TradeOrder) -> Unit,
    private val onBack: () -> Unit,
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })

    // Credit balance readout, refreshed in place after each trade.
    private val balanceLabel = Label("", skin.labelStyle)

    // Per-resource "held: N" readouts, refreshed alongside the balance after each trade.
    private val heldLabels = LinkedHashMap<ResourceType, Label>()

    init {
        val root = Table()
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel

        root.add(Label(stationName, skin.titleLabelStyle)).colspan(COLSPAN).padBottom(TITLE_GAP).row()
        root.add(Label("TRADE", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()

        balanceLabel.setText(balanceText())
        root.add(balanceLabel).colspan(COLSPAN).padBottom(SERVICE_GAP).row()

        // Offers in ResourceType declaration order (deterministic). A station with an empty market
        // shows just the balance + BACK with a "no goods" note.
        val offered = ResourceType.entries.filter { market.offerFor(it) != null }
        if (offered.isEmpty()) {
            root.add(Label("This station trades no goods.", skin.labelStyle))
                .colspan(COLSPAN).padBottom(SERVICE_GAP).row()
        } else {
            for (resource in offered) {
                val offer = market.offerFor(resource) ?: continue
                val priceLabel =
                    Label("${resource.displayName}  buy ${offer.buyPrice}  sell ${offer.sellPrice}", skin.labelStyle)
                val heldLabel = Label(heldText(resource), skin.labelStyle)
                heldLabels[resource] = heldLabel

                val buyButton = TextButton("BUY", skin.settingsButtonStyle)
                buyButton.addListener(tradeListener { TradeOrder.Buy(resource, UNITS_PER_TAP) })
                val sellButton = TextButton("SELL", skin.settingsButtonStyle)
                sellButton.addListener(tradeListener { TradeOrder.Sell(resource, UNITS_PER_TAP) })

                root.add(priceLabel).left().padRight(CELL_GAP).padBottom(ROW_GAP)
                root.add(heldLabel).left().padRight(CELL_GAP).padBottom(ROW_GAP)
                root.add(buyButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padRight(CELL_GAP).padBottom(ROW_GAP)
                root.add(sellButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(ROW_GAP).row()
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

        stage.addActor(root)
    }

    /** A click listener that fires [onTrade] with the [order] built on tap, then refreshes readouts. */
    private fun tradeListener(order: () -> TradeOrder): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                onTrade(order())
                refresh()
            }
        }

    /** Re-read the balance + per-resource held counts after a trade and update the labels in place. */
    private fun refresh() {
        balanceLabel.setText(balanceText())
        for ((resource, label) in heldLabels) {
            label.setText(heldText(resource))
        }
    }

    private fun balanceText(): String = "CREDITS: ${creditsSupplier()}"

    private fun heldText(resource: ResourceType): String = "held: ${cargoSupplier().contents[resource] ?: 0}"

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "TradeScreen shown")
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

        /** Units bought/sold per BUY/SELL tap. The pure resolver clamps to what is possible. [TUNE] */
        const val UNITS_PER_TAP = 1

        const val COLSPAN = 4
        const val MARGIN = 32f
        const val TITLE_GAP = 24f
        const val SERVICE_GAP = 12f
        const val ROW_GAP = 8f
        const val CELL_GAP = 16f
        const val BUTTON_WIDTH = 120f
        const val BUTTON_HEIGHT = 56f
        const val BACK_WIDTH = 220f
        const val BACK_GAP = 24f
    }
}
