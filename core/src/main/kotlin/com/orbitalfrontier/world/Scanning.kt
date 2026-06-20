package com.orbitalfrontier.world

import com.orbitalfrontier.common.Vec2

/**
 * The player's intent for the scan control on a given frame (UC10 AC#3) — the scanning analogue of
 * [DockAction]/[MineAction].
 *
 * [NONE] is the common per-frame case (no scan requested); [SCAN] is the discrete edge-triggered
 * action the play screen feeds in when the context SCAN button is tapped.
 */
enum class ScanAction {
    NONE,
    SCAN,
}

/**
 * Pure, deterministic active-scan resolution (UC10 AC#3/#4/#5) — the scanning analogue of [Docking]
 * and [Mining].
 *
 * Both functions are side-effect-free functions of (world, current sector, ship position, scan
 * range, …): identical inputs always yield an identical result, with no I/O and no engine types, so
 * they slot into the deterministic simulation stepper and are fully JVM-unit-testable (UC10 AC#5/#6).
 * They do **not** mutate anything — the caller (the play screen on device, the replay harness in
 * tests) applies the returned revealed-id set.
 *
 * Scanning is **explicit action + sensor range**: [contactsInRange] reports which scan-only contacts a
 * scan from a given point would reach (so the model/UI can reason about it), and [resolve] only
 * widens the revealed set when the player actually issues a [ScanAction.SCAN].
 *
 * **Scan-only is any non-[Transponder] [Contact]** (UC54). Detection reveals any [Contact] that does NOT
 * broadcast a transponder — a [HiddenContact] (UC10) *and* a [Derelict] (UC54) — into the same monotonic
 * [revealed] set; a broadcasting [Transponder] (gate / station / distress / hazard) auto-shows and is never
 * part of this set. Generalizing here means derelicts plug into detection through the shared [Contact]
 * capability rather than a per-type branch (coding-guidelines § O, Open/Closed).
 *
 * **Monotonic — revealed contacts never re-hide** (UC10 AC#4 / pitfall). [resolve] only ever *unions*
 * newly-in-range contacts into the revealed set; it never removes one, even when the ship later flies
 * out of range. The set is keyed by [PoiId] (globally unique across the sector graph), so a revealed
 * id stays revealed across sector changes and across save/reload.
 */
object Scanning {
    /**
     * The **scan-only contacts** in [currentSector] within [scanRange] world-units of [shipPosition] —
     * those a scan from that point would reveal. A scan-only contact is any [Poi] that is a [Contact] but
     * not a broadcasting [Transponder] (a [HiddenContact] or a [Derelict], UC54). Returned in the sector's
     * authored POI order (deterministic by construction). A non-positive [scanRange] reaches nothing.
     */
    fun contactsInRange(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        scanRange: Float,
    ): List<Poi> {
        if (scanRange <= 0f) return emptyList()
        return world.sector(currentSector).pois
            .filter { it is Contact && it !is Transponder }
            .filter { (shipPosition - it.position).length <= scanRange }
    }

    /**
     * Resolve the next revealed-contact set from the current one and the player's [action].
     *
     * On a [ScanAction.SCAN] it **unions** every scan-only contact in [currentSector] within [scanRange]
     * of [shipPosition] into [revealed] (UC10 AC#3; derelicts too, UC54). On [ScanAction.NONE] — or a scan that reveals
     * nothing new (no contact in range, or every in-range contact already known) — it returns the
     * **same [revealed] instance** unchanged, so the caller can cheaply detect "nothing changed" with
     * a reference (`!==`) check and skip the autosave. It never removes an id (monotonic, UC10 AC#4).
     *
     * @param revealed the ids of hidden contacts already known to the player.
     * @param scannerPowered UC49 — false when the power-budget [com.orbitalfrontier.power.Brownout] has
     *   shed the SCANNER this tick; the scan is then suppressed (treated like [ScanAction.NONE], the same
     *   instance returned). Defaults true, so every pre-UC49 caller and full-power play is byte-identical.
     * @return the revealed set after the action — the same instance when nothing new was revealed.
     */
    fun resolve(
        world: SectorWorld,
        currentSector: SectorId,
        shipPosition: Vec2,
        scanRange: Float,
        revealed: Set<PoiId>,
        action: ScanAction,
        scannerPowered: Boolean = true,
    ): Set<PoiId> {
        if (action == ScanAction.NONE || !scannerPowered) return revealed
        val newlyRevealed =
            contactsInRange(world, currentSector, shipPosition, scanRange)
                .asSequence()
                .map { it.id }
                .filter { it !in revealed }
                .toList()
        if (newlyRevealed.isEmpty()) return revealed
        return revealed + newlyRevealed
    }
}
