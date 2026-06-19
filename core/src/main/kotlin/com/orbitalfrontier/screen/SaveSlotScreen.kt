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
import com.orbitalfrontier.menu.SaveSlotModel
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.save.SaveSlotSummary
import com.orbitalfrontier.save.SlotId
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The save/load slot screen (UC38 AC#1/#2): a list of every slot with its name, last-saved timestamp and
 * a short state summary (credits, sector, play time), plus the per-slot actions — **load**, **delete**
 * (with confirmation), **save** and **new game into an empty slot** — depending on the [SaveSlotModel.Mode]
 * it was opened in. The existing single autosave appears here as the legacy slot (UC38 AC#3).
 *
 * Intentionally a **thin view over the pure [SaveSlotModel]**: it renders the slot rows from the injected
 * [slotsSupplier], forwards taps to the model, and acts on the returned [SaveSlotModel.Action] by invoking
 * one of the owner-wired callbacks. All the transition logic — when a tap acts immediately vs. needs a
 * delete / overwrite confirmation — lives in the model (JVM-unit-tested), so this class holds no game
 * logic (SRP). The owner ([com.orbitalfrontier.app.OrbitalFrontierGame]) performs the actual load / save /
 * delete / new-game (and the DB threading) in the callbacks; the screen only signals intent and, after a
 * delete, re-reads [slotsSupplier] so the list reflects the change.
 *
 * Mirrors [MainMenuScreen]/[StationHubScreen]: owns a [OrbitalUiSkin] + [Stage] and releases them in
 * [dispose] (the game disposes every owned screen explicitly, since libGDX `setScreen` only `hide()`s the
 * previous screen).
 */
class SaveSlotScreen(
    private val logger: Logger,
    mode: SaveSlotModel.Mode,
    private val slotsSupplier: () -> List<SaveSlotSummary>,
    private val onLoad: (SlotId) -> Unit,
    private val onDelete: (SlotId) -> Unit,
    private val onSave: (SlotId) -> Unit,
    private val onNewGameInto: (SlotId) -> Unit,
    private val onBack: () -> Unit,
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })
    private val model = SaveSlotModel(mode)

    // Locale-fixed last-saved formatter (display-only; the pure model never reads a clock). minSdk-24-safe
    // (SimpleDateFormat predates API 1, unlike java.time which would need desugaring on API 24/25).
    private val lastSavedFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private val root = Table()

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel
        stage.addActor(root)
        rebuild()
    }

    /** Redraw [root] for the model's current phase: the slot list, or a confirmation step. */
    private fun rebuild() {
        root.clearChildren()
        when (model.phase) {
            SaveSlotModel.Phase.LIST -> buildList()
            SaveSlotModel.Phase.CONFIRM_DELETE ->
                buildConfirm(
                    "Delete this save? This cannot be undone.",
                )
            SaveSlotModel.Phase.CONFIRM_OVERWRITE ->
                buildConfirm(
                    "WARNING: This slot already holds a save. Overwrite it?",
                )
        }
    }

    /** The slot list: a title + one row per slot (primary action button, plus DELETE for occupied slots). */
    private fun buildList() {
        val title = if (model.mode == SaveSlotModel.Mode.SAVE) "SAVE GAME" else "LOAD GAME"
        root.add(Label(title, skin.titleLabelStyle)).colspan(2).padBottom(TITLE_GAP).row()

        for (summary in slotsSupplier()) {
            val occupied = summary.hasSave
            val primary = TextButton(rowLabel(summary), skin.settingsButtonStyle)
            primary.label.setWrap(false)
            primary.addListener(click { act(model.onSelect(summary.slotId, occupied)) })
            root.add(primary).size(ROW_WIDTH, ROW_HEIGHT).pad(BTN_GAP)

            if (occupied) {
                val delete = TextButton("DELETE", skin.settingsButtonStyle)
                delete.addListener(click { act(model.onDeleteRequest(summary.slotId)) })
                root.add(delete).size(DELETE_WIDTH, ROW_HEIGHT).pad(BTN_GAP)
            } else {
                root.add() // keep the grid rectangular when there is no delete affordance
            }
            root.row()
        }

        root.add(menuButton("BACK") { onBack() }).colspan(2).size(BTN_WIDTH, ROW_HEIGHT).padTop(TITLE_GAP).row()
    }

    /** A confirmation step: the [warning] + CONFIRM (commit) / CANCEL (back to the list). */
    private fun buildConfirm(warning: String) {
        val label = Label(warning, skin.labelStyle)
        label.wrap = true
        root.add(label).width(WARNING_WIDTH).colspan(2).padBottom(TITLE_GAP).row()
        root.add(menuButton("CONFIRM") { act(model.onConfirm()) }).size(BTN_WIDTH, ROW_HEIGHT).pad(BTN_GAP)
        root.add(menuButton("CANCEL") { act(model.onCancel()) }).size(BTN_WIDTH, ROW_HEIGHT).pad(BTN_GAP)
        root.row()
    }

    /** The single-line label for a slot row: name + summary for an occupied slot, an "empty" hint otherwise. */
    private fun rowLabel(summary: SaveSlotSummary): String =
        when (summary) {
            is SaveSlotSummary.Occupied -> {
                val saved = if (summary.lastSavedEpochMillis > 0L) lastSavedFormat.format(Date(summary.lastSavedEpochMillis)) else "—"
                val playTime = SaveSlotModel.formatPlayTime(summary.playTimeSeconds)
                "${summary.name}  —  CR ${summary.credits} · ${summary.sector.value} · $playTime · $saved"
            }
            is SaveSlotSummary.Empty ->
                if (model.mode == SaveSlotModel.Mode.SAVE) "[ Empty slot — save here ]" else "[ Empty slot — new game ]"
        }

    /**
     * Act on a model transition: invoke the owner callback for a committing action (the owner navigates
     * away for load/save/new-game, or stays here for delete), or redraw for a phase change ([Action.None]).
     * A delete redraws the list afterwards so the removed slot shows as empty immediately.
     */
    private fun act(action: SaveSlotModel.Action) {
        when (action) {
            is SaveSlotModel.Action.Load -> onLoad(action.slot)
            is SaveSlotModel.Action.Save -> onSave(action.slot)
            is SaveSlotModel.Action.NewGameInto -> onNewGameInto(action.slot)
            is SaveSlotModel.Action.Delete -> {
                onDelete(action.slot)
                rebuild()
            }
            is SaveSlotModel.Action.None -> rebuild()
        }
    }

    /** A labelled menu button that runs [onTap] when clicked. */
    private fun menuButton(
        label: String,
        onTap: () -> Unit,
    ): TextButton {
        val button = TextButton(label, skin.settingsButtonStyle)
        button.addListener(click { onTap() })
        return button
    }

    private fun click(onClick: () -> Unit): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                onClick()
            }
        }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "SaveSlotScreen shown (mode=${model.mode})")
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
        const val MARGIN = 32f
        const val TITLE_GAP = 24f
        const val BTN_GAP = 8f
        const val BTN_WIDTH = 200f
        const val ROW_WIDTH = 460f
        const val ROW_HEIGHT = 56f
        const val DELETE_WIDTH = 120f
        const val WARNING_WIDTH = 420f
    }
}
