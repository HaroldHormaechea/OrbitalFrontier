package com.orbitalfrontier.combat

/**
 * The player's per-tick weapon intent (UC13 AC#1) — the combat analogue of
 * [com.orbitalfrontier.world.DockAction] / [com.orbitalfrontier.world.MineAction]. A closed `enum`
 * (coding-guidelines § O).
 *
 * [NONE] is the rest state: the player is not firing this tick. [FIRE] fires the ship's ready fixed
 * weapon(s) along hull facing. Turrets auto-fire independently of this intent (they need no player
 * action, only crew — UC13 AC#2); this action gates only the **fixed/forward** weapon.
 *
 * **Anchor contract (UC13).** When the combat sim is inactive ([CombatState.active] = false) a [NONE]
 * step is a total no-op — [com.orbitalfrontier.combat.Combat.step] returns the SAME instances, advances
 * no RNG and emits no events — so every pre-UC13 recorded fixture (which never fires and is never in
 * combat) replays byte-for-byte.
 */
enum class FireAction {
    /** Hold fire this tick. */
    NONE,

    /** Fire the ready fixed/forward weapon(s) along the hull facing. */
    FIRE,
}
