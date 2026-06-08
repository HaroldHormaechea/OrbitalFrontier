package com.orbitalfrontier.playthrough

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.economy.RefuelAction
import com.orbitalfrontier.economy.ResourceType
import com.orbitalfrontier.economy.TradeKind
import com.orbitalfrontier.outfit.OutfitOrder
import com.orbitalfrontier.outfit.SlotCategory
import com.orbitalfrontier.outfit.UpgradeId
import com.orbitalfrontier.ship.FleetOrder
import com.orbitalfrontier.ship.MovementInput
import com.orbitalfrontier.ship.ShipId
import com.orbitalfrontier.ship.ShipTypeId
import com.orbitalfrontier.world.DockAction
import com.orbitalfrontier.world.MineAction
import com.orbitalfrontier.world.ScanAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One recorded input, stamped with the [tick] it applies to (UC02 AC#3).
 *
 * A `sealed` polymorphic hierarchy (Open/Closed — coding-guidelines "O"): later use cases add new
 * input kinds by adding a new subtype, not by editing a central `when`. kotlinx.serialization emits
 * a `"type"` discriminator so the on-disk form stays self-describing and forward-readable.
 *
 * The recorder and runner support **0..N events per tick** from the start — multiple distinct
 * inputs (e.g. a movement vector *and* an action tap) can share one tick.
 */
@Serializable
sealed class InputEvent {
    /** The simulation tick this event is applied at (0-based). */
    abstract val tick: Int
}

/**
 * A movement-stick sample for one tick — the joystick vector, deflection magnitude, and whether
 * the stick was released. Mirrors [MovementInput] (the per-frame value [com.orbitalfrontier.sim.Simulation]
 * consumes) as flat serializable fields so the domain type stays annotation-free.
 */
@Serializable
@SerialName("movement")
data class MovementEvent(
    override val tick: Int,
    val dirX: Float,
    val dirY: Float,
    val magnitude: Float,
    val released: Boolean,
) : InputEvent() {
    /** Reconstruct the domain [MovementInput] this event recorded. */
    fun toMovementInput(): MovementInput =
        MovementInput(
            targetDirection = Vec2(dirX, dirY),
            magnitude = magnitude,
            released = released,
        )

    companion object {
        /** Capture [input] at [tick] as a serializable event. */
        fun from(
            tick: Int,
            input: MovementInput,
        ): MovementEvent =
            MovementEvent(
                tick = tick,
                dirX = input.targetDirection.x,
                dirY = input.targetDirection.y,
                magnitude = input.magnitude,
                released = input.released,
            )
    }
}

/**
 * A dock/undock control sample for one tick (UC05 AC#6) — the discrete [DockAction] the play screen
 * feeds in when the context button is tapped. Carried alongside [MovementEvent] in the same tick-
 * stamped script, so a recorded session can thrust *and* dock; [com.orbitalfrontier.playthrough.ReplayRunner]
 * dispatches it to [com.orbitalfrontier.sim.Simulation.step] each tick.
 *
 * [DockAction] is a plain (annotation-free) domain enum; kotlinx.serialization emits an enum by its
 * constant name, so the on-disk form is the stable, diffable string `"DOCK"` / `"UNDOCK"` / `"NONE"`.
 * A tick with no [DockActionEvent] defaults to [DockAction.NONE] in the runner, so older artifacts
 * (which carry none) replay unchanged.
 */
@Serializable
@SerialName("dock")
data class DockActionEvent(
    override val tick: Int,
    val action: DockAction,
) : InputEvent()

/**
 * A mine control sample for one tick (UC06 AC#2) — the discrete [MineAction] the play screen feeds
 * in while the context mine button is held. Carried alongside [MovementEvent] in the same tick-
 * stamped script, so a recorded session can thrust *and* mine; [com.orbitalfrontier.playthrough.ReplayRunner]
 * dispatches it to [com.orbitalfrontier.sim.Simulation.step] each tick.
 *
 * [MineAction] is a plain (annotation-free) domain enum; kotlinx.serialization emits an enum by its
 * constant name, so the on-disk form is the stable, diffable string `"MINE"` / `"NONE"`. A tick with
 * no [MineEvent] defaults to [MineAction.NONE] in the runner, so older artifacts (which carry none)
 * replay unchanged.
 */
@Serializable
@SerialName("mine")
data class MineEvent(
    override val tick: Int,
    val action: MineAction,
) : InputEvent()

/**
 * A scan control sample for one tick (UC10 AC#3/#6) — the discrete [ScanAction] the play screen feeds
 * in when the context SCAN button is tapped. Carried alongside [MovementEvent] in the same tick-
 * stamped script, so a recorded session can thrust *and* scan; [com.orbitalfrontier.playthrough.ReplayRunner]
 * dispatches it to [com.orbitalfrontier.sim.Simulation.step] each tick, where it is resolved against the
 * active ship's effective sensor range (so the reveal radius reflects any sensors upgrade).
 *
 * [ScanAction] is a plain (annotation-free) domain enum; kotlinx.serialization emits an enum by its
 * constant name, so the on-disk form is the stable, diffable string `"SCAN"` / `"NONE"`. A tick with
 * no [ScanEvent] defaults to [ScanAction.NONE] in the runner, so older artifacts (which carry none)
 * replay unchanged.
 */
@Serializable
@SerialName("scan")
data class ScanEvent(
    override val tick: Int,
    val action: ScanAction,
) : InputEvent()

/**
 * A refuel control sample for one tick (UC07 AC#5) — the discrete [RefuelAction] the station hub
 * feeds in when the REFUEL button is tapped (converting hydrogen cargo into fuel). Carried alongside
 * [MovementEvent]/[DockActionEvent] in the same tick-stamped script, so a recorded session can dock
 * *and* refuel; [com.orbitalfrontier.playthrough.ReplayRunner] dispatches it to
 * [com.orbitalfrontier.sim.Simulation.step] each tick — and because refuelling is resolved **before**
 * the docked-freeze short-circuit, it works while docked.
 *
 * [RefuelAction] is a plain (annotation-free) domain enum; kotlinx.serialization emits an enum by its
 * constant name, so the on-disk form is the stable, diffable string `"REFUEL"` / `"NONE"`. A tick
 * with no [RefuelEvent] defaults to [RefuelAction.NONE] in the runner, so older artifacts (which
 * carry none) replay unchanged.
 */
@Serializable
@SerialName("refuel")
data class RefuelEvent(
    override val tick: Int,
    val action: RefuelAction,
) : InputEvent()

/**
 * A trade control sample for one tick (UC08 AC#3) — one BUY/SELL the station trade desk feeds in when
 * the player taps a trade row while docked. Carried alongside [MovementEvent]/[DockActionEvent]/
 * [RefuelEvent] in the same tick-stamped script, so a recorded session can dock, refuel *and* trade;
 * [com.orbitalfrontier.playthrough.ReplayRunner] reconstructs a [com.orbitalfrontier.economy.TradeOrder]
 * from it and dispatches it to [com.orbitalfrontier.sim.Simulation.step] each tick — and because
 * trading is resolved inside the docked-freeze branch, it only has an effect while docked.
 *
 * [kind] / [resource] are plain (annotation-free) domain enums; kotlinx.serialization emits each by its
 * constant name, so the on-disk form is the stable, diffable string `"BUY"`/`"SELL"` and the resource
 * name (e.g. `"TITANIUM"`). [units] is the requested quantity; [com.orbitalfrontier.economy.Trading]
 * clamps it to what is affordable / fits / is held. A tick with no [TradeEvent] reconstructs as
 * [com.orbitalfrontier.economy.TradeOrder.None] in the runner, so older artifacts (which carry none)
 * replay unchanged.
 */
@Serializable
@SerialName("trade")
data class TradeEvent(
    override val tick: Int,
    val kind: TradeKind,
    val resource: ResourceType,
    val units: Int,
) : InputEvent()

/** Which [OutfitOrder] kind an [OutfitEvent] carries — the serialized discriminator for the order. */
enum class OutfitOrderKind {
    /** No outfitting (maps to [OutfitOrder.None]). */
    NONE,

    /** Buy + install an upgrade (maps to [OutfitOrder.BuyInstall]); [OutfitEvent.upgradeId] is set. */
    BUY_INSTALL,

    /** Remove + sell a used part at a junkyard (maps to [OutfitOrder.RemoveSell]); category+index set. */
    REMOVE_SELL,
}

/**
 * An outfitting control sample for one tick (UC09 AC#2/#3/#4) — one buy-install / remove-sell the
 * station outfit desk feeds in while docked. Carried alongside the other per-tick events;
 * [com.orbitalfrontier.playthrough.ReplayRunner] reconstructs an [OutfitOrder] from it and dispatches
 * it to [com.orbitalfrontier.sim.Simulation.step], where it is resolved inside the docked-freeze
 * branch (so it only has an effect while docked).
 *
 * Flat serializable fields keep the domain [OutfitOrder] (a non-serializable sealed hierarchy)
 * annotation-free: [kind] is the discriminator, [upgradeId] is the part slug for a BUY_INSTALL, and
 * [slotCategory]/[slotIndex] address the slot for a REMOVE_SELL ([SlotCategory] is a plain enum,
 * emitted by its constant name). A tick with no [OutfitEvent] reconstructs as [OutfitOrder.None].
 */
@Serializable
@SerialName("outfit")
data class OutfitEvent(
    override val tick: Int,
    val kind: OutfitOrderKind,
    val upgradeId: String? = null,
    val slotCategory: SlotCategory? = null,
    val slotIndex: Int? = null,
) : InputEvent() {
    /** Reconstruct the domain [OutfitOrder] this event recorded. */
    fun toOutfitOrder(): OutfitOrder =
        when (kind) {
            OutfitOrderKind.NONE -> OutfitOrder.None
            OutfitOrderKind.BUY_INSTALL ->
                OutfitOrder.BuyInstall(UpgradeId(requireNotNull(upgradeId) { "BUY_INSTALL requires an upgradeId" }))
            OutfitOrderKind.REMOVE_SELL ->
                OutfitOrder.RemoveSell(
                    requireNotNull(slotCategory) { "REMOVE_SELL requires a slotCategory" },
                    requireNotNull(slotIndex) { "REMOVE_SELL requires a slotIndex" },
                )
        }

    companion object {
        /** Snapshot an [OutfitOrder] into its serializable event form at [tick]. */
        fun from(
            tick: Int,
            order: OutfitOrder,
        ): OutfitEvent =
            when (order) {
                OutfitOrder.None -> OutfitEvent(tick, OutfitOrderKind.NONE)
                is OutfitOrder.BuyInstall -> OutfitEvent(tick, OutfitOrderKind.BUY_INSTALL, upgradeId = order.upgradeId.value)
                is OutfitOrder.RemoveSell ->
                    OutfitEvent(tick, OutfitOrderKind.REMOVE_SELL, slotCategory = order.category, slotIndex = order.slotIndex)
            }
    }
}

/** Which [FleetOrder] kind a [FleetEvent] carries — the serialized discriminator for the order. */
enum class FleetOrderKind {
    /** No fleet action (maps to [FleetOrder.None]). */
    NONE,

    /** Buy a hull from the docked shipyard (maps to [FleetOrder.BuyShip]); [FleetEvent.shipType] is set. */
    BUY_SHIP,

    /** Switch the active ship (maps to [FleetOrder.SwitchActive]); [FleetEvent.shipId] is set. */
    SWITCH_ACTIVE,
}

/**
 * A fleet control sample for one tick (UC09 AC#5) — one buy-ship / switch-active the station shipyard
 * / hub feeds in while docked. Carried alongside the other per-tick events;
 * [com.orbitalfrontier.playthrough.ReplayRunner] reconstructs a [FleetOrder] from it and dispatches it
 * to [com.orbitalfrontier.sim.Simulation.step], where it is resolved inside the docked-freeze branch
 * (so buying / switching only has an effect while docked).
 *
 * Flat serializable fields keep the domain [FleetOrder] annotation-free: [kind] is the discriminator,
 * [shipType] is the [ShipTypeId] slug for a BUY_SHIP, and [shipId] is the [ShipId] value for a
 * SWITCH_ACTIVE. A tick with no [FleetEvent] reconstructs as [FleetOrder.None].
 */
@Serializable
@SerialName("fleet")
data class FleetEvent(
    override val tick: Int,
    val kind: FleetOrderKind,
    val shipType: String? = null,
    val shipId: Long? = null,
) : InputEvent() {
    /** Reconstruct the domain [FleetOrder] this event recorded. */
    fun toFleetOrder(): FleetOrder =
        when (kind) {
            FleetOrderKind.NONE -> FleetOrder.None
            FleetOrderKind.BUY_SHIP ->
                FleetOrder.BuyShip(ShipTypeId(requireNotNull(shipType) { "BUY_SHIP requires a shipType" }))
            FleetOrderKind.SWITCH_ACTIVE ->
                FleetOrder.SwitchActive(ShipId(requireNotNull(shipId) { "SWITCH_ACTIVE requires a shipId" }))
        }

    companion object {
        /** Snapshot a [FleetOrder] into its serializable event form at [tick]. */
        fun from(
            tick: Int,
            order: FleetOrder,
        ): FleetEvent =
            when (order) {
                FleetOrder.None -> FleetEvent(tick, FleetOrderKind.NONE)
                is FleetOrder.BuyShip -> FleetEvent(tick, FleetOrderKind.BUY_SHIP, shipType = order.typeId.value)
                is FleetOrder.SwitchActive -> FleetEvent(tick, FleetOrderKind.SWITCH_ACTIVE, shipId = order.shipId.value)
            }
    }
}
