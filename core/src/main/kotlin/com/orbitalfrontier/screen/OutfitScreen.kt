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
import com.orbitalfrontier.outfit.OutfitMarket
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.UpgradeCatalog
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.ship.Fleet

/**
 * The station outfitting desk shown from the station hub while docked (UC09 AC#2/#3/#4).
 *
 * Intentionally a **thin view with no game logic** (SRP), mirroring [TradeScreen]: it lists the docked
 * station's offered upgrades (each with price + stat summary and an INSTALL button) and — at a
 * junkyard — the active ship's installed parts (each with a REMOVE/SELL button). A tap fires the
 * injected [onOutfit] intent (an [OutfitOrder]); the owner routes it to [PlayScreen.outfit], which runs
 * the **pure** [com.orbitalfrontier.outfit.Outfitting] resolver, re-derives the ship's capacities, and
 * autosaves. The screen then rebuilds its rows from the refreshed [fleetSupplier]/[creditsSupplier].
 *
 * Offered upgrades render in [UpgradeCatalog] authored order and installed parts in (category, slot)
 * order, so the layout is deterministic. Owns its GL-backed resources and releases them in [dispose].
 */
class OutfitScreen(
    private val logger: Logger,
    private val stationName: String,
    private val outfitMarket: OutfitMarket,
    private val isJunkyard: Boolean,
    private val creditsSupplier: () -> Long,
    private val fleetSupplier: () -> Fleet,
    private val onOutfit: (OutfitOrder) -> Unit,
    private val onBack: () -> Unit,
    private val catalog: UpgradeCatalog = UpgradeCatalog.MVP,
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })
    private val root = Table()

    init {
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel
        stage.addActor(root)
        rebuild()
    }

    /** Clear and repopulate the whole table from the current fleet + credits (called after each tap). */
    private fun rebuild() {
        root.clear()
        val fleet = fleetSupplier()
        val active = fleet.active
        val loadout = active.loadout

        root.add(Label(stationName, skin.titleLabelStyle)).colspan(COLSPAN).padBottom(TITLE_GAP).row()
        root.add(Label("OUTFITTING — ${active.type.displayName}", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()
        root.add(Label("CREDITS: ${creditsSupplier()}", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()

        // Offered upgrades (install), in catalog order.
        val offered = catalog.all.filter { outfitMarket.offers(it.id) }
        if (offered.isEmpty()) {
            root.add(Label("No upgrades offered here.", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()
        } else {
            for (upgrade in offered) {
                val installed = loadout.installedCount(upgrade.category)
                val slots = active.type.slotCount(upgrade.category)
                val info =
                    Label(
                        "${upgrade.displayName}  ${upgrade.price}cr  [${upgrade.category} $installed/$slots]",
                        skin.labelStyle,
                    )
                val installButton = TextButton("INSTALL", skin.settingsButtonStyle)
                installButton.addListener(
                    tapListener { onOutfit(OutfitOrder.BuyInstall(upgrade.id)) },
                )
                root.add(info).left().padRight(CELL_GAP).padBottom(ROW_GAP)
                root.add(installButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(ROW_GAP).row()
            }
        }

        // At a junkyard: installed parts the player can remove + sell (AC#4).
        if (isJunkyard) {
            root.add(Label("INSTALLED (sell used):", skin.labelStyle)).colspan(COLSPAN).padTop(SERVICE_GAP).padBottom(ROW_GAP).row()
            val installedRows =
                loadout.slots.entries
                    .sortedBy { it.key.name }
                    .flatMap { (category, slots) -> slots.toSortedMap().map { (index, upgradeId) -> Triple(category, index, upgradeId) } }
            if (installedRows.isEmpty()) {
                root.add(Label("Nothing installed.", skin.labelStyle)).colspan(COLSPAN).padBottom(ROW_GAP).row()
            } else {
                for ((category, index, upgradeId) in installedRows) {
                    val name = catalog.upgrade(upgradeId)?.displayName ?: upgradeId.value
                    val info = Label("$name  [$category #$index]", skin.labelStyle)
                    val removeButton = TextButton("REMOVE", skin.settingsButtonStyle)
                    removeButton.addListener(
                        tapListener { onOutfit(OutfitOrder.RemoveSell(category, index)) },
                    )
                    root.add(info).left().padRight(CELL_GAP).padBottom(ROW_GAP)
                    root.add(removeButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(ROW_GAP).row()
                }
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
        logger.info(TAG, "OutfitScreen shown")
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
