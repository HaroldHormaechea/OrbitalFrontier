package com.orbitalfrontier.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.orbitalfrontier.notify.NotificationQueue
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.NotificationRenderer
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.station.StationBuildCost
import com.orbitalfrontier.station.StationBuildOption
import com.orbitalfrontier.station.StationBuildOrder

/**
 * The station build/edit screen shown from the station hub at a build-capable station (UC51 AC#1) —
 * the deferred-from-UC15 build UI (ADR 0014), replacing the old direct default-build action.
 *
 * Intentionally a **thin view with no game logic** (SRP), mirroring [MissionBoardScreen]/[TradeScreen]:
 * it lists the build options the pure [com.orbitalfrontier.station.StationBuildMenu] produces (a
 * found-station option per module + an expansion option per owned station × module), each with a cost
 * preview and an affordability tag, plus a CONFIRM button. A CONFIRM tap fires the injected [onBuild]
 * intent (a [StationBuildOrder]); the owner ([com.orbitalfrontier.app.OrbitalFrontierGame]) routes it to
 * [com.orbitalfrontier.screen.PlayScreen.build], which performs the **pure**
 * [com.orbitalfrontier.station.StationBuilder.resolve], folds the result back in, and autosaves. The
 * screen then re-reads its suppliers and rebuilds its rows — keeping every build mutation in one place
 * (the play screen) and this screen free of world/save coupling.
 *
 * The affordability tag is advisory only — the pure [com.orbitalfrontier.station.StationBuilder] is the
 * real gate, so an over-optimistic CONFIRM on an unaffordable option is a harmless no-op.
 *
 * Owns its own GL-backed resources (a [OrbitalUiSkin] + [Stage]) and releases them in [dispose] — the
 * game disposes the screen explicitly when the player leaves (libGDX `setScreen` only `hide()`s the
 * previous screen).
 */
class StationBuildScreen(
    private val logger: Logger,
    private val stationName: String,
    private val optionsSupplier: () -> List<StationBuildOption>,
    private val creditsSupplier: () -> Long,
    private val onBuild: (StationBuildOrder) -> Unit,
    private val onBack: () -> Unit,
    // UC40: the shared transient notification queue (credit deltas + styled errors surface on this screen).
    private val notifications: NotificationQueue = NotificationQueue(),
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })

    // UC40: the device-side toast renderer (mirrors the other desks); draws the shared queue above this screen.
    private val notificationRenderer = NotificationRenderer()

    // The whole content table is rebuilt on each refresh: a build changes the wallet/cargo (so options'
    // affordability flips) and may found a new station (so fresh expansion options appear), so an
    // in-place label update is not enough — the row set itself changes. Hosted in a ScrollPane because
    // the option list can outgrow the screen (one found option per module + module×owned expansions).
    private val content = Table()

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        val root = Table()
        root.setFillParent(true)
        root.background = skin.panel
        val scroll = ScrollPane(content, skin.scrollPaneStyle)
        scroll.setFadeScrollBars(false)
        root.add(scroll).grow()
        stage.addActor(root)
        rebuild()
    }

    /** Tear down and repopulate every row from the current suppliers (options + balance). */
    private fun rebuild() {
        content.clearChildren()
        content.pad(MARGIN)
        content.top()

        content.add(Label(stationName, skin.titleLabelStyle)).colspan(COLSPAN).padBottom(TITLE_GAP).row()
        content.add(Label("BUILD STATION", skin.labelStyle)).colspan(COLSPAN).padBottom(ROW_GAP).row()
        content.add(Label("CREDITS: ${creditsSupplier()}", skin.labelStyle)).colspan(COLSPAN).padBottom(ROW_GAP).row()

        val options = optionsSupplier()
        if (options.isEmpty()) {
            content.add(Label("No build options here.", skin.labelStyle)).colspan(COLSPAN).padBottom(ROW_GAP).row()
        } else {
            for (option in options) {
                addOptionRow(option)
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
        content.add(backButton).colspan(COLSPAN).size(BACK_WIDTH, BUTTON_HEIGHT).padTop(BACK_GAP).row()
    }

    /** Add one option row: its label + cost + affordability tag, and a CONFIRM button that fires [onBuild]. */
    private fun addOptionRow(option: StationBuildOption) {
        val affordTag = if (option.affordable) "" else "  [need more]"
        val rowLabel = Label("${option.label}  —  ${describeCost(option.cost)}$affordTag", skin.labelStyle)
        val confirmButton = TextButton("CONFIRM", skin.settingsButtonStyle)
        confirmButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onBuild(option.order)
                    rebuild()
                }
            },
        )
        content.add(rowLabel).left().padRight(CELL_GAP).padBottom(ROW_GAP)
        content.add(confirmButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(ROW_GAP).row()
    }

    /** A compact one-line cost preview: the credit price plus each mined-resource line. */
    private fun describeCost(cost: StationBuildCost): String {
        val parts = ArrayList<String>()
        if (cost.credits > 0) parts.add("${cost.credits}cr")
        for ((resource, units) in cost.resources) {
            parts.add("$units ${resource.displayName}")
        }
        return if (parts.isEmpty()) "free" else parts.joinToString(" + ")
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "StationBuildScreen shown")
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(Palette.SURFACE_BASE.r, Palette.SURFACE_BASE.g, Palette.SURFACE_BASE.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
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
        const val MARGIN = 32f
        const val TITLE_GAP = 24f
        const val ROW_GAP = 10f
        const val CELL_GAP = 16f
        const val COLSPAN = 2
        const val BUTTON_WIDTH = 140f
        const val BUTTON_HEIGHT = 64f
        const val BACK_WIDTH = 200f
        const val BACK_GAP = 24f
    }
}
