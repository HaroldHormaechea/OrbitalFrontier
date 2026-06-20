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
import com.orbitalfrontier.crew.CrewMember
import com.orbitalfrontier.crew.CrewOrder
import com.orbitalfrontier.crew.CrewRole
import com.orbitalfrontier.crew.CrewRoster
import com.orbitalfrontier.notify.NotificationQueue
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.NotificationRenderer
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.ship.Fleet
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.ShipId

/**
 * The fleet & crew management screen shown from the station hub while docked (UC50 AC#3) — the **primary
 * ship-switch surface** and the place to assign crew to ships / roles.
 *
 * Intentionally a **thin view with no game logic** (SRP), mirroring [HireScreen] / [ShipyardScreen]: it
 * lists every owned ship (marking the active one and showing each ship's `crew / capacity`) and, under
 * each ship, every assigned crew member's name + role. Each row fires a pure intent that the owner
 * ([com.orbitalfrontier.app.OrbitalFrontierGame]) routes to the play screen:
 *
 *  - **SET ACTIVE** → [FleetOrder.SwitchActive] via [onFleetOrder] (the SAME pure
 *    [com.orbitalfrontier.ship.FleetResolver] path the shipyard switch uses — no duplicated switch logic;
 *    the bare shipyard switch is kept, this is just the primary surface, ADR 0038).
 *  - **MOVE →** → [CrewOrder.Reassign] via [onCrewOrder] (reassign the member to the next owned ship;
 *    the pure [com.orbitalfrontier.crew.CrewAssignment] clamps to the target's crew capacity).
 *  - **ROLE →** → [CrewOrder.ChangeRole] via [onCrewOrder] (cycle the member's role; inert metadata in
 *    the MVP).
 *
 * The screen holds no world/save state: after each intent it re-reads its [fleetSupplier] / [rosterSupplier]
 * and rebuilds its rows in place, so all crew/fleet mutation stays in one place (the play screen).
 *
 * Owns its own GL-backed resources (a [OrbitalUiSkin] + [Stage]) and releases them in [dispose] — the game
 * disposes the screen explicitly when the player leaves (libGDX `setScreen` only `hide()`s the previous).
 */
class FleetCrewScreen(
    private val logger: Logger,
    stationName: String,
    private val fleetSupplier: () -> Fleet,
    private val rosterSupplier: () -> CrewRoster,
    private val onFleetOrder: (FleetOrder) -> Unit,
    private val onCrewOrder: (CrewOrder) -> Unit,
    private val onBack: () -> Unit,
    // UC40: the shared transient notification queue (constructed once by the game), so a reassignment / switch
    // raised here surfaces any styled toast. Defaults to a fresh queue for JVM/tests.
    private val notifications: NotificationQueue = NotificationQueue(),
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })

    private val notificationRenderer = NotificationRenderer()

    // The dynamic ship/crew list, rebuilt in place after each intent (the fleet/roster changes).
    private val listTable = Table()

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        val root = Table()
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel

        root.add(Label(stationName, skin.titleLabelStyle)).padBottom(TITLE_GAP).row()
        root.add(Label("FLEET & CREW", skin.labelStyle)).padBottom(SERVICE_GAP).row()

        rebuildList()
        root.add(listTable).padBottom(SERVICE_GAP).row()

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

    /** Re-read the live fleet + roster and rebuild every ship/crew row in place (after an intent). */
    private fun rebuildList() {
        listTable.clearChildren()
        val fleet = fleetSupplier()
        val roster = rosterSupplier()
        for (ship in fleet.ships) {
            addShipHeader(fleet, ship.id)
            val crew = roster.forShip(ship.id)
            if (crew.isEmpty()) {
                listTable.add(Label("  (no crew)", skin.labelStyle)).left().padBottom(ROW_GAP).row()
            } else {
                for (member in crew) addCrewRow(fleet, member)
            }
        }
    }

    /** A ship header row: name, active marker, and crew/capacity, plus a SET ACTIVE button when inactive. */
    private fun addShipHeader(
        fleet: Fleet,
        shipId: ShipId,
    ) {
        val ship = fleet.ship(shipId) ?: return
        val active = fleet.activeShipId == shipId
        val marker = if (active) "[ACTIVE] " else ""
        val header = Table()
        header.add(Label("$marker${ship.type.displayName}  crew ${ship.crew}/${activeCapacity(ship.id, fleet)}", skin.labelStyle)).left()
        if (!active) {
            val setActive = TextButton("SET ACTIVE", skin.settingsButtonStyle)
            setActive.addListener(
                object : ClickListener() {
                    override fun clicked(
                        event: InputEvent?,
                        x: Float,
                        y: Float,
                    ) {
                        // Reuse the pure FleetResolver switch (no duplicated switch logic) — ADR 0038.
                        onFleetOrder(FleetOrder.SwitchActive(shipId))
                        rebuildList()
                    }
                },
            )
            header.add(setActive).padLeft(ROW_GAP)
        }
        listTable.add(header).left().padTop(SERVICE_GAP).padBottom(ROW_GAP).row()
    }

    /** A crew member row: name + role, a ROLE → (cycle role) button, and a MOVE → (next ship) button. */
    private fun addCrewRow(
        fleet: Fleet,
        member: CrewMember,
    ) {
        val row = Table()
        row.add(Label("  ${member.name} — ${member.role}", skin.labelStyle)).left()

        val roleButton = TextButton("ROLE →", skin.settingsButtonStyle)
        roleButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onCrewOrder(CrewOrder.ChangeRole(member.id, nextRole(member.role)))
                    rebuildList()
                }
            },
        )
        row.add(roleButton).padLeft(ROW_GAP)

        // MOVE → reassigns to the NEXT owned ship (wrapping); a single-ship fleet makes this a no-op.
        val target = nextShipId(fleet, member.assignedShipId)
        if (target != null) {
            val moveButton = TextButton("MOVE →", skin.settingsButtonStyle)
            moveButton.addListener(
                object : ClickListener() {
                    override fun clicked(
                        event: InputEvent?,
                        x: Float,
                        y: Float,
                    ) {
                        onCrewOrder(CrewOrder.Reassign(member.id, target))
                        rebuildList()
                    }
                },
            )
            row.add(moveButton).padLeft(ROW_GAP)
        }
        listTable.add(row).left().padBottom(ROW_GAP).row()
    }

    private fun activeCapacity(
        shipId: ShipId,
        fleet: Fleet,
    ): Int {
        val ship = fleet.ship(shipId) ?: return 0
        return com.orbitalfrontier.outfit.ShipStats.crewCapacity(ship.type, ship.loadout)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "FleetCrewScreen shown")
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
        const val SERVICE_GAP = 12f
        const val ROW_GAP = 8f
        const val BUTTON_WIDTH = 220f
        const val BUTTON_HEIGHT = 64f
        const val BACK_GAP = 24f

        /** The next role in the [CrewRole] cycle (inert metadata, MVP) — pure, so the row tap is reproducible. */
        fun nextRole(role: CrewRole): CrewRole {
            val all = CrewRole.entries
            return all[(role.ordinal + 1) % all.size]
        }

        /**
         * The next owned ship id after [from] in [fleet]'s (sorted) ship list, wrapping — or null when the
         * fleet has only one ship (so MOVE is hidden). A pure helper [com.orbitalfrontier.crew.CrewAssignment]
         * then clamps the actual move to the target's crew capacity.
         */
        fun nextShipId(
            fleet: Fleet,
            from: ShipId,
        ): ShipId? {
            if (!fleet.hasMultipleShips) return null
            val ids = fleet.ships.map { it.id }
            val idx = ids.indexOfFirst { it == from }
            if (idx < 0) return ids.first()
            return ids[(idx + 1) % ids.size]
        }
    }
}
