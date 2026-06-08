package com.orbitalfrontier.mission

/**
 * The player's mission log (UC12 AC#3) — the immutable record of what is offered and what has been
 * taken on.
 *
 * Split into two collections by persistence concern (ADR 0011):
 *  - [available] — the **currently surfaced offers** (board offers while docked, radio offers while
 *    in flight). These are **transient and NOT persisted**: they are regenerated from the static
 *    authored world by [MissionGenerator] each context and filtered against [takenIds]. A save never
 *    stores them, so the held log's [available] is typically empty (the device recomputes it on
 *    demand for the board screen).
 *  - [accepted] — the **persisted** missions: [MissionStatus.ACTIVE] (in progress) plus the terminal
 *    [MissionStatus.COMPLETED]/[MissionStatus.FAILED] outcomes. Terminal missions are kept so their
 *    ids keep an already-resolved offer from ever re-appearing (the regenerate-and-filter invariant).
 *
 * Pure, immutable value with no engine types, so the mission system stays fully JVM-testable
 * (UC12 AC#6) and composes into the immutable [com.orbitalfrontier.world.WorldState] snapshot.
 */
data class MissionLog(
    val available: List<Mission> = emptyList(),
    val accepted: List<Mission> = emptyList(),
) {
    /** The ids of every accepted / terminal mission — the set an offer must NOT be in to be surfaced. */
    val takenIds: Set<MissionId> get() = accepted.mapTo(LinkedHashSet()) { it.id }

    /** The accepted missions currently in progress (ACTIVE) — the "active" view of the mission log. */
    val activeMissions: List<Mission> get() = accepted.filter { it.status == MissionStatus.ACTIVE }

    companion object {
        /** An empty log: nothing offered, nothing accepted. The [com.orbitalfrontier.world.WorldState] default. */
        val EMPTY: MissionLog = MissionLog(emptyList(), emptyList())
    }
}
