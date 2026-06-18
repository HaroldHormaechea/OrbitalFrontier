package com.orbitalfrontier.render

/**
 * Single source of truth for the **world-space half-extents** (in world units) of every sprite the live
 * world renders (UC30 AC#3). Each value is the distance from a sprite's centre to its edge, so the drawn
 * sprite spans `2 × halfExtent` per side, centred on the object's world position.
 *
 * These sizes are deliberately authored **per object type** here — not inherited as a shared default and
 * not scattered as inline literals across the renderers. [ShipRenderer], [HostileRenderer] and
 * [WorldGlyphs] all read from this object, so the relative on-screen scale of the world's objects is
 * decided in one auditable place. See ADR 0019 for the per-type sizing rationale.
 *
 * **These are purely visual half-extents.** They are intentionally decoupled from the gameplay ranges that
 * live in the world/combat model — [com.orbitalfrontier.world.JumpGate.triggerRadius],
 * [com.orbitalfrontier.world.Station.dockingRadius], [com.orbitalfrontier.world.AsteroidField.miningRadius]
 * and [com.orbitalfrontier.combat.CombatParams.hitRadius]. A sprite's drawn size does not change where the
 * player can dock, mine, jump, or land a shot; those circles are owned by the model and rendered as
 * separate ring overlays. ADR 0019 documents why, in particular, the ship's combat `hitRadius` (28) is
 * intentionally larger than its visual half-extent (18).
 *
 * **Engine-free** (only `Float` constants), so it stays JVM-testable per ADR 0001.
 *
 * The current values reproduce exactly the half-extents the renderers used before this object existed, so
 * extracting them here is a zero-behaviour-change refactor.
 */
object WorldSpriteSizes {
    /** Player ship hull sprite ([AtlasRegions.SHIP_PLAYER]). */
    const val SHIP = 18f

    /** Hostile ship sprite ([AtlasRegions.SHIP_HOSTILE]); matches [SHIP] so allies/enemies read at one scale. */
    const val HOSTILE = 18f

    /** In-flight projectile sprite ([AtlasRegions.PROJECTILE]); small so shots read as ordnance, not ships. */
    const val PROJECTILE = 5f

    /** Jump-gate marker sprite ([AtlasRegions.JUMP_GATE]); the largest world object, a clear navigation beacon. */
    const val GATE = 28f

    /** Asteroid-field marker sprite ([AtlasRegions.ASTEROID_FIELD]); a sizeable cluster, smaller than a gate. */
    const val ASTEROID_FIELD = 26f

    /** Station marker sprite ([AtlasRegions.STATION]); a prominent destination, below gate/asteroid scale. */
    const val STATION = 22f

    /** Revealed hidden-contact marker sprite ([AtlasRegions.CONTACT_HIDDEN]); deliberately the smallest icon. */
    const val HIDDEN_CONTACT = 16f
}
