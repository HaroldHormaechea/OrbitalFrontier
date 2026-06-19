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
import com.orbitalfrontier.economy.PurchaseGate
import com.orbitalfrontier.economy.SpendDecision
import com.orbitalfrontier.notify.GameNotifications
import com.orbitalfrontier.notify.NotificationQueue
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.NotificationRenderer
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.screen.controls.PurchaseConfirmDialog
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
    // UC40: the shared transient notification queue (constructed once by the game), so a credit delta or a
    // styled error raised by a buy here surfaces on this desk. Defaults to a fresh queue for JVM/tests.
    private val notifications: NotificationQueue = NotificationQueue(),
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })
    private val root = Table()

    // UC40: the device-side toast renderer (mirrors PlayScreen) + the reusable confirm-purchase modal.
    private val notificationRenderer = NotificationRenderer()
    private val dialog = PurchaseConfirmDialog(skin)

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel
        stage.addActor(root)
        rebuild()
    }

    /**
     * UC40 AC#1/#3: route one buy tap through the pure [PurchaseGate]. Below the threshold the [fire] intent
     * runs immediately (today's behaviour); at/above it the reusable [dialog] confirms first (CONFIRM fires
     * [fire], CANCEL dismisses); unaffordable raises a styled INSUFFICIENT-CREDITS toast and fires nothing.
     * [item]/[cost] feed the dialog; the resulting balance is `creditsSupplier() - cost`.
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
                rebuild()
            }
            SpendDecision.CONFIRM ->
                dialog.show(
                    stage,
                    PurchaseGate.details(item, cost, balance),
                    onConfirm = {
                        fire()
                        rebuild()
                    },
                    onCancel = {},
                )
            SpendDecision.INSUFFICIENT -> notifications.enqueue(GameNotifications.insufficientCredits())
        }
    }

    /** A click listener that routes a buy of [item] costing [cost] credits through [attemptPurchase]. */
    private fun buyListener(
        item: String,
        cost: Long,
        order: () -> FleetOrder,
    ): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                attemptPurchase(item, cost) { onFleet(order()) }
            }
        }

    /** Clear and repopulate the whole table from the current fleet + credits (called after each tap). */
    private fun rebuild() {
        root.clear()
        val fleet = fleetSupplier()

        root.add(Label(stationName, skin.titleLabelStyle)).colspan(COLSPAN).padBottom(TITLE_GAP).row()
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
                buyButton.addListener(buyListener(type.displayName, type.price) { FleetOrder.BuyShip(type.id) })
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
        Gdx.gl.glClearColor(Palette.SURFACE_BASE.r, Palette.SURFACE_BASE.g, Palette.SURFACE_BASE.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        // UC40: advance the shared toast queue and draw it above the desk (after the stage) — the +N/-N CR
        // delta from a buy and any styled error surface here, animated by the renderer (AC#2).
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
    }
}
