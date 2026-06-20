package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * A **distress-signal** POI (UC54 AC#1/#2) — a broadcasting beacon that, when the player flies into it,
 * triggers a one-shot mini-event branching into either a **reward** or an **ambush**
 * (docs/adr/0042-additional-poi-types.md; docs/design/world-and-sector.md).
 *
 * A distress signal is a [Poi] and a [Transponder] ([ContactKind.DISTRESS]): unlike a [Derelict] it
 * broadcasts, so it auto-shows on the minimap/HUD without a scan (the detection split of UC54 AC#3).
 *
 * The event is **edge-triggered**, mirroring [com.orbitalfrontier.combat.EncounterSpawner]'s natural
 * spawn: it fires once on the **outside→inside** crossing of [triggerRadius] (previous position outside,
 * new position inside), is suppressed while a fight is already active, and only on an **un-consumed**
 * signal. The branch is decided by a fresh [com.orbitalfrontier.common.DeterministicRng] namespace
 * (`"distress:$id"`) so it draws nothing from any existing stream (the zero-fixture-regen lever). A
 * triggered signal is marked **consumed** ([WorldState.consumedPois]) so it never fires again
 * (deterministic + persisted across save/reload, UC54 AC#4). See [com.orbitalfrontier.world.DistressEvent].
 *
 * Pure data — no engine types — so distress signals are part of the JVM-testable world model (ADR 0001).
 */
data class DistressSignal(
    override val id: PoiId,
    override val position: Vec2,
    /** Radius (world-units) of the trigger circle around [position] whose outside→inside crossing fires the event. */
    val triggerRadius: Float = DEFAULT_TRIGGER_RADIUS,
) : Poi, Transponder {
    override val contactKind: ContactKind get() = ContactKind.DISTRESS

    init {
        require(triggerRadius > 0f) { "DistressSignal $id triggerRadius must be positive: $triggerRadius" }
    }

    companion object {
        /** Default trigger radius (world-units) for a distress beacon. [TUNE] */
        const val DEFAULT_TRIGGER_RADIUS: Float = 120f
    }
}
