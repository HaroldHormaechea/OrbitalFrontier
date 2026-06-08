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
import com.orbitalfrontier.mission.Mission
import com.orbitalfrontier.mission.MissionOrder
import com.orbitalfrontier.mission.MissionType
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin

/**
 * The station mission board shown from the station hub while docked (UC12 AC#2/#3).
 *
 * Intentionally a **thin view with no game logic** (SRP), mirroring [TradeScreen]/[HireScreen]: it
 * lists the docked station's currently-available **BOARD offers** (each with an ACCEPT button) and the
 * player's **active missions** (each with a TURN IN button), plus the credit balance and a BACK
 * button. An ACCEPT/TURN IN tap fires the injected [onMissionOrder] intent (a [MissionOrder]); the
 * owner ([com.orbitalfrontier.app.OrbitalFrontierGame]) routes it to the play screen, which performs
 * the **pure** [com.orbitalfrontier.mission.Missions.resolve], folds the new log/credits/cargo back in,
 * and autosaves. The screen then re-reads its suppliers and rebuilds its rows — keeping all mission
 * mutation in one place (the play screen) and this screen free of world/save coupling.
 *
 * The available offers come from the deterministic [com.orbitalfrontier.mission.MissionGenerator] (via
 * the play screen), already filtered against accepted/terminal ids, so an accepted offer disappears
 * from the list on the next rebuild. Mining turn-in requires the quota in the hold and a courier
 * delivery the destination station — the pure resolver no-ops a tap that can't apply, so an
 * over-optimistic TURN IN is harmless.
 *
 * Owns its own GL-backed resources (a [PlaceholderControlsSkin] + [Stage]) and releases them in
 * [dispose] — the game disposes the screen explicitly when the player leaves (libGDX `setScreen` only
 * `hide()`s the previous screen).
 */
class MissionBoardScreen(
    private val logger: Logger,
    private val stationName: String,
    private val availableSupplier: () -> List<Mission>,
    private val activeSupplier: () -> List<Mission>,
    private val creditsSupplier: () -> Long,
    private val onMissionOrder: (MissionOrder) -> Unit,
    private val onBack: () -> Unit,
) : ScreenAdapter() {
    private val skin = PlaceholderControlsSkin()
    private val stage = Stage(ScreenViewport())

    // The whole content table is rebuilt on each refresh: accepting/turning in changes which rows
    // exist (an offer leaves the available list; a turn-in flips an active mission terminal), so an
    // in-place label update is not enough — the row set itself changes.
    private val root = Table()

    init {
        root.setFillParent(true)
        stage.addActor(root)
        rebuild()
    }

    /** Tear down and repopulate every row from the current suppliers (offers, active log, balance). */
    private fun rebuild() {
        root.clearChildren()
        root.pad(MARGIN)

        root.add(Label(stationName, skin.labelStyle)).colspan(COLSPAN).padBottom(TITLE_GAP).row()
        root.add(Label("MISSION BOARD", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()
        root.add(Label("CREDITS: ${creditsSupplier()}", skin.labelStyle)).colspan(COLSPAN).padBottom(SERVICE_GAP).row()

        // Available board offers (ACCEPT). Empty ⇒ a "no offers" note.
        root.add(Label("AVAILABLE", skin.labelStyle)).colspan(COLSPAN).padBottom(ROW_GAP).row()
        val available = availableSupplier()
        if (available.isEmpty()) {
            root.add(Label("No offers right now.", skin.labelStyle)).colspan(COLSPAN).padBottom(ROW_GAP).row()
        } else {
            for (mission in available) {
                addMissionRow(mission, "ACCEPT") { onMissionOrder(MissionOrder.Accept(mission.id)) }
            }
        }

        // Active missions (TURN IN). Empty ⇒ a "none accepted" note.
        root.add(Label("ACTIVE", skin.labelStyle)).colspan(COLSPAN).padTop(SECTION_GAP).padBottom(ROW_GAP).row()
        val active = activeSupplier()
        if (active.isEmpty()) {
            root.add(Label("No active missions.", skin.labelStyle)).colspan(COLSPAN).padBottom(ROW_GAP).row()
        } else {
            for (mission in active) {
                addMissionRow(mission, "TURN IN") { onMissionOrder(MissionOrder.TurnIn(mission.id)) }
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

    /** Add one mission row: its description + an action button that fires [onTap] then refreshes. */
    private fun addMissionRow(
        mission: Mission,
        actionLabel: String,
        onTap: () -> Unit,
    ) {
        val button = TextButton(actionLabel, skin.settingsButtonStyle)
        button.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onTap()
                    rebuild()
                }
            },
        )
        root.add(Label(describe(mission), skin.labelStyle)).left().padRight(CELL_GAP).padBottom(ROW_GAP)
        root.add(button).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(ROW_GAP).row()
    }

    /** A short, human-readable one-line description of [mission] for the board row. */
    private fun describe(mission: Mission): String =
        when (mission.type) {
            MissionType.MINING ->
                "MINING  ${mission.quotaUnits} ${mission.quotaResource?.displayName ?: "?"}  →  ${mission.rewardCredits}cr"
            MissionType.COURIER -> {
                val carried = if (mission.pickedUp) "carrying" else "pickup ${mission.pickup?.value ?: "?"}"
                "COURIER  → ${mission.destination?.value ?: "?"}  ($carried, ${mission.remainingTicks}t)  ${mission.rewardCredits}cr"
            }
        }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "MissionBoardScreen shown")
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
        const val SECTION_GAP = 20f
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
