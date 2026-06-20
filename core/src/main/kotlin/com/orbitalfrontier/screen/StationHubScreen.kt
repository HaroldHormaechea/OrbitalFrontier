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
import com.orbitalfrontier.notify.NotificationQueue
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.render.NotificationRenderer
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.render.applyUiScale
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.screen.layout.GridCell
import com.orbitalfrontier.screen.layout.MenuGrid

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
 * Owns its own GL-backed resources (a [OrbitalUiSkin] + [Stage]) and releases them in
 * [dispose] — the game disposes both screens explicitly, so there is no leaked context when the
 * other screen is the active one (libGDX `setScreen` only `hide()`s the previous screen).
 */
class StationHubScreen(
    private val logger: Logger,
    stationName: String,
    private val onUndock: () -> Unit,
    private val onTrade: () -> Unit,
    private val onOutfit: () -> Unit,
    private val onShipyard: () -> Unit,
    private val onCrew: () -> Unit,
    // UC50: opens the fleet & crew management screen (list ships + crew, reassign / change role, switch the
    // active ship — the primary ship-switch surface). Defaulted to a no-op so existing call sites / tests
    // need not supply it; the FLEET row only fires this intent and is purely additive.
    private val onFleetCrew: () -> Unit = {},
    private val onMissions: () -> Unit,
    // UC07 hydrogen-conversion refuel; UC18 adds a credits-based fuel purchase. Each returns a short
    // feedback line the hub shows so neither refuel path fails silently (UC18 AC#1/#4). [onBuyFuel]
    // defaults to an empty no-op so existing call sites / tests need not supply it.
    private val onRefuel: () -> String,
    private val onBuyFuel: () -> String = { "" },
    private val fuelStatus: () -> String,
    // UC14: optional owning-faction display name; null for an unaligned station. Purely cosmetic and
    // defaulted so existing call sites / tests need not supply it (must not crash when absent).
    factionName: String? = null,
    // UC15: BUILD action + whether this station is build-capable. The BUILD row is shown only at a
    // build-capable station (the one MVP station with buildsStations=true); both default so existing
    // call sites / tests need not supply them. Per the deferred-build-UI decision (ADR 0014) BUILD is a
    // direct hub action (no dedicated build screen) routed to the play screen's pure StationBuilder.
    private val onBuild: () -> Unit = {},
    buildsStations: Boolean = false,
    // UC19: optional on-foot walk-around. Defaulted to a no-op so existing call sites / tests need not
    // supply it; the EXIT SHIP row only fires this intent and is purely additive — every existing
    // menu/row stays exactly as-is (AC#1).
    private val onDisembark: () -> Unit = {},
    // UC40: the shared transient notification queue (constructed once by the game). The hub itself raises no
    // toasts, but PlayScreen.buyFuel/refuel enqueue the +N/-N CR delta and styled buy-fuel errors here, so
    // the hub renders the shared queue to surface them. Defaults to a fresh queue for JVM/tests.
    private val notifications: NotificationQueue = NotificationQueue(),
) : ScreenAdapter() {
    private val skin = OrbitalUiSkin()
    private val stage = Stage(ScreenViewport().apply { applyUiScale() })

    // UC40: the device-side toast renderer (mirrors PlayScreen); draws the shared queue above the hub.
    private val notificationRenderer = NotificationRenderer()

    // Fuel readout (UC07): seeded from the current tank and refreshed in place after each refuel tap.
    private val fuelLabel = Label("", skin.labelStyle)

    // Refuel feedback line (UC18 AC#1/#4): shows the outcome of the last refuel/buy-fuel tap (e.g.
    // "Refueled N units", "Tank full", "Insufficient credits", "No fuel sold here") so neither refuel
    // path is a silent no-op. Empty until the first tap.
    private val refuelFeedbackLabel = Label("", skin.labelStyle)

    // UC20: the action buttons live in a child grid capped at MenuGrid.DEFAULT_MAX_ROWS rows, growing
    // into extra columns as items are added. Held as a field so [resize] can re-fit the column width
    // to the live viewport. [gridColumns] caches the column count so resize need not recount items.
    private val grid = Table()
    private var gridColumns = 0

    init {
        skin.installTapSound(stage) // UC31: UI-tap cue on button taps (AC#1)
        val root = Table()
        root.setFillParent(true)
        root.pad(MARGIN)
        root.background = skin.panel

        root.add(Label(stationName, skin.titleLabelStyle)).padBottom(TITLE_GAP).row()
        // UC14: show the owning faction when the station has one (cosmetic).
        if (factionName != null) {
            root.add(Label("FACTION: $factionName", skin.labelStyle)).padBottom(SERVICE_GAP).row()
        }
        root.add(Label("STATION SERVICES", skin.labelStyle)).padBottom(SERVICE_GAP).row()

        // UC20: collect every action button in display order, each built EXACTLY as before (same label,
        // same click listener → same action), then arrange them into a capped grid below. The order is
        // the historical one: TRADE, OUTFIT, SHIPS, CREW, MISSIONS, BUILD (build-capable only), the two
        // refuel buttons, EXIT SHIP, UNDOCK. UNDOCK now folds into the uniform grid (no padTop emphasis).
        val buttons = ArrayList<TextButton>()

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
        buttons.add(tradeButton)

        // Active OUTFIT service (UC09 AC#2/#3/#4): opens the outfitting desk (buy/install upgrades; at a
        // junkyard, also remove/sell used parts). The play screen owns the pure Outfitting resolver.
        buttons.add(serviceButton("OUTFIT", onOutfit))

        // Active SHIPS service (UC09 AC#5): opens the shipyard / ship-switch screen (buy a ship where a
        // shipyard exists; switch the active ship anywhere while docked). Pure FleetResolver behind it.
        buttons.add(serviceButton("SHIPS", onShipyard))

        // Active CREW service (UC11 AC#2): opens the crew-hire desk (hire crew where the station hires
        // them; the desk shows crew/capacity + turret operability anywhere). Pure Hiring behind it.
        buttons.add(serviceButton("CREW", onCrew))

        // Active FLEET service (UC50 AC#3): opens the fleet & crew management screen — list ships + crew,
        // reassign crew to ships / roles, and switch the active ship (the primary switch surface; the
        // shipyard's bare switch is kept). Pure CrewAssignment / FleetResolver behind it.
        buttons.add(serviceButton("FLEET", onFleetCrew))

        // Active MISSIONS service (UC12 AC#2/#3): opens the station mission board (accept board offers,
        // turn in active missions). The play screen owns the pure Missions.resolve + MissionGenerator;
        // this button just fires the intent so the game switches to the MissionBoardScreen.
        buttons.add(serviceButton("MISSIONS", onMissions))

        // Active BUILD service (UC15 AC#1): only at a build-capable station. Founds/expands a personal
        // station via the play screen's pure StationBuilder. Per ADR 0014 there is NO dedicated build
        // screen yet — the action fires a default build order directly; the full build UI is deferred.
        if (buildsStations) {
            buttons.add(serviceButton("BUILD", onBuild))
        }

        // Active refuel services. The play screen owns both pure resolvers; these are two DISTINCTLY-
        // LABELLED buttons (UC18 has two refuel concepts, so the UI must not conflate them):
        // "Refuel (H₂)" converts hydrogen cargo into fuel (UC07 AC#5, Refueling.resolve) and
        // "Buy Fuel (credits)" pays the docked station for fuel (UC18, StationRefuel.resolve). Each
        // fires its intent, shows the returned feedback line, then re-reads the tank readout. The
        // listeners are UNCHANGED so the UC18 feedback guard stays green.
        val refuelButton = TextButton("Refuel (H₂)", skin.settingsButtonStyle)
        refuelButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    refuelFeedbackLabel.setText(onRefuel())
                    fuelLabel.setText(fuelStatus())
                }
            },
        )
        buttons.add(refuelButton)

        val buyFuelButton = TextButton("Buy Fuel (credits)", skin.settingsButtonStyle)
        buyFuelButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    refuelFeedbackLabel.setText(onBuyFuel())
                    fuelLabel.setText(fuelStatus())
                }
            },
        )
        buttons.add(buyFuelButton)

        // Active EXIT SHIP action (UC19 AC#1): optionally leave the ship and walk the station interior
        // on foot. Purely additive — every menu above is unchanged and still reachable; this just opens
        // the walk-around view. The owner re-shows this hub untouched when the player re-boards (AC#7).
        buttons.add(serviceButton("EXIT SHIP", onDisembark))

        // The one active control: leave the station and return to flight. Now a uniform grid cell.
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
        buttons.add(undockButton)

        // Fuel readout (UC07) sits full-width ABOVE the grid; the refuel feedback line (UC18) sits
        // full-width BELOW it. Both are status text, not buttons, so they stay out of the grid.
        fuelLabel.setText(fuelStatus())
        root.add(fuelLabel).padBottom(SERVICE_GAP).row()

        layOutGrid(buttons)
        root.add(grid).row()

        // Shared feedback line for both refuel paths (UC18 AC#1/#4).
        root.add(refuelFeedbackLabel).padBottom(SERVICE_GAP).row()

        stage.addActor(root)
    }

    /**
     * Arrange [buttons] into [grid], capped at [MenuGrid.DEFAULT_MAX_ROWS] rows and growing into extra
     * columns (UC20). Fill is column-major, so only the last column may be short; short positions get
     * an empty filler cell to keep the grid rectangular. Column width is fitted to the live viewport.
     */
    private fun layOutGrid(buttons: List<TextButton>) {
        val itemCount = buttons.size
        val columns = MenuGrid.columnCount(itemCount)
        val rows = MenuGrid.rowCount(itemCount)
        gridColumns = columns

        grid.clearChildren()
        val width = currentCellWidth()
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val index = MenuGrid.indexAt(GridCell(r, c))
                if (index < itemCount) {
                    grid.add(buttons[index]).size(width, GRID_BTN_HEIGHT).pad(GRID_GAP)
                } else {
                    grid.add().size(width, GRID_BTN_HEIGHT).pad(GRID_GAP)
                }
            }
            grid.row()
        }
    }

    /** Column width fitted to the live viewport, clamped to [MIN_BTN_WIDTH]..[MAX_BTN_WIDTH]. */
    private fun currentCellWidth(): Float =
        MenuGrid.cellWidth(
            stage.viewport.worldWidth - 2 * MARGIN,
            gridColumns,
            GRID_GAP,
            MIN_BTN_WIDTH,
            MAX_BTN_WIDTH,
        )

    /** A labelled service button that fires [onTap] when clicked (UC09 hub services). */
    private fun serviceButton(
        label: String,
        onTap: () -> Unit,
    ): TextButton {
        val button = TextButton(label, skin.settingsButtonStyle)
        button.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    onTap()
                }
            },
        )
        return button
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        logger.info(TAG, "StationHubScreen shown")
    }

    override fun render(delta: Float) {
        Gdx.gl.glClearColor(Palette.SURFACE_BASE.r, Palette.SURFACE_BASE.g, Palette.SURFACE_BASE.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        // UC40: advance + draw the shared toast queue above the hub (after the stage) so the +N/-N CR delta
        // and the styled buy-fuel errors PlayScreen enqueues surface here, animated by the renderer (AC#2).
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
        // UC20 AC#5: re-fit each grid cell to the new viewport width so the menu never clips or
        // overflows when the screen size / orientation changes.
        val cellWidth = currentCellWidth()
        for (cell in grid.cells) {
            cell.width(cellWidth)
        }
        grid.invalidateHierarchy()
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

        // UC20 grid cell metrics (rename of the old UNDOCK_* button sizing). Buttons are uniform
        // GRID_BTN_HEIGHT tall; width is fitted live to the viewport, clamped to MIN..MAX; GRID_GAP
        // pads every cell on all sides (and is the inter-/outer-column spacing used in cellWidth).
        const val GRID_BTN_HEIGHT = 64f
        const val MIN_BTN_WIDTH = 88f
        const val MAX_BTN_WIDTH = 220f
        const val GRID_GAP = 8f
    }
}
