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
import com.orbitalfrontier.economy.PurchaseGate
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.SpendDecision
import com.orbitalfrontier.economy.StationMarket
import com.orbitalfrontier.economy.TradeOrder
import com.orbitalfrontier.notify.GameNotifications
import com.orbitalfrontier.notify.NotificationQueue
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.NotificationRenderer
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.screen.controls.PurchaseConfirmDialog

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
    // UC46: a SUPPLIER of the effective (dynamic) market rather than a fixed snapshot — the docked
    // station's living buy/sell prices, re-read after every trade so the player sees the price move as
    // their supply/demand pressure shifts. The offered RESOURCE SET is fixed (pricing only scales prices,
    // never adds/removes offers), so the row layout is built once from the first read; only the prices update.
    private val marketSupplier: () -> StationMarket,
    private val creditsSupplier: () -> Long,
    private val cargoSupplier: () -> Cargo,
    private val onTrade: (TradeOrder) -> Unit,
    private val onBack: () -> Unit,
    // UC40: the shared transient notification queue (constructed once by the game), so a credit delta or a
    // styled error raised by a buy here surfaces on this desk. Defaults to a fresh queue for JVM/tests.
    private val notifications: NotificationQueue = NotificationQueue(),
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })

    // UC40: the device-side toast renderer (mirrors PlayScreen) + the reusable confirm-purchase modal.
    private val notificationRenderer = NotificationRenderer()
    private val dialog = PurchaseConfirmDialog(skin)

    // Credit balance readout, refreshed in place after each trade.
    private val balanceLabel = Label("", skin.labelStyle)

    // Per-resource "held: N" readouts, refreshed alongside the balance after each trade.
    private val heldLabels = LinkedHashMap<ResourceType, Label>()

    // UC46: per-resource price readouts, refreshed after each trade so a moved price shows immediately.
    private val priceLabels = LinkedHashMap<ResourceType, Label>()

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        // UC46: snapshot the market once to lay out the (fixed) offered-resource rows; prices update live.
        val market = marketSupplier()
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
                val priceLabel = Label(priceText(resource), skin.labelStyle)
                priceLabels[resource] = priceLabel
                val heldLabel = Label(heldText(resource), skin.labelStyle)
                heldLabels[resource] = heldLabel

                val buyButton = TextButton("BUY", skin.settingsButtonStyle)
                buyButton.addListener(
                    // UC46: the confirm-dialog cost is computed from the LIVE price at tap time (not this
                    // build-time snapshot), so the confirmed amount always matches the price now on screen.
                    buyListener(resource.displayName, { currentBuyCost(resource) }) {
                        TradeOrder.Buy(resource, UNITS_PER_TAP)
                    },
                )
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

    /**
     * A click listener that routes a buy of [item] through [attemptPurchase]. [cost] is a supplier
     * evaluated at tap time (UC46) so the confirm-dialog amount reflects the LIVE price, not a stale
     * build-time snapshot.
     */
    private fun buyListener(
        item: String,
        cost: () -> Long,
        order: () -> TradeOrder,
    ): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                attemptPurchase(item, cost()) { onTrade(order()) }
            }
        }

    /**
     * UC40 AC#1/#3: route one BUY tap through the pure [PurchaseGate]. Below the threshold the [fire] intent
     * runs immediately; at/above it the reusable [dialog] confirms first (CONFIRM fires, CANCEL dismisses);
     * unaffordable raises a styled INSUFFICIENT-CREDITS toast and fires nothing. SELL taps bypass this gate.
     */
    private fun attemptPurchase(
        item: String,
        cost: Long,
        fire: () -> Unit,
    ) {
        val balance = creditsSupplier()
        when (PurchaseGate.evaluate(cost, balance)) {
            SpendDecision.PROCEED -> {
                fire()
                refresh()
            }
            SpendDecision.CONFIRM ->
                dialog.show(
                    stage,
                    PurchaseGate.details(item, cost, balance),
                    onConfirm = {
                        fire()
                        refresh()
                    },
                    onCancel = {},
                )
            SpendDecision.INSUFFICIENT -> notifications.enqueue(GameNotifications.insufficientCredits())
        }
    }

    /**
     * Re-read the balance, per-resource held counts AND the live prices after a trade and update the
     * labels in place. UC46: the price labels track the effective (dynamic) market, so the player sees a
     * buy/sell move the price as their supply/demand pressure shifts.
     */
    private fun refresh() {
        balanceLabel.setText(balanceText())
        for ((resource, label) in heldLabels) {
            label.setText(heldText(resource))
        }
        for ((resource, label) in priceLabels) {
            label.setText(priceText(resource))
        }
    }

    private fun balanceText(): String = "CREDITS: ${creditsSupplier()}"

    private fun heldText(resource: ResourceType): String = "held: ${cargoSupplier().contents[resource] ?: 0}"

    /** The current buy/sell readout for [resource] from the effective (dynamic) market (UC46). */
    private fun priceText(resource: ResourceType): String {
        val offer = marketSupplier().offerFor(resource)
        return if (offer != null) {
            "${resource.displayName}  buy ${offer.buyPrice}  sell ${offer.sellPrice}"
        } else {
            "${resource.displayName}  —"
        }
    }

    /** The live cost of one BUY tap of [resource] from the effective market (UC46), 0 if unavailable. */
    private fun currentBuyCost(resource: ResourceType): Long = (marketSupplier().offerFor(resource)?.buyPrice ?: 0L) * UNITS_PER_TAP

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "TradeScreen shown")
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(Palette.SURFACE_BASE.r, Palette.SURFACE_BASE.g, Palette.SURFACE_BASE.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        // UC40: advance + draw the shared toast queue above the desk (after the stage) so the +N/-N CR delta
        // and any styled error surface here, animated by the renderer (AC#2).
        notifications.update(delta)
        stage.act(delta)
        stage.draw()
        notificationRenderer.render(
            notifications.visibleWithProgress(),
            Gdx.graphics.width.toFloat(),
            Gdx.graphics.height.toFloat(),
        )
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
        notificationRenderer.dispose()
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
