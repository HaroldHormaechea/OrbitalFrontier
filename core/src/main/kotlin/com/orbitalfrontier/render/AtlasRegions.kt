package com.orbitalfrontier.render

/**
 * The canonical names of every region in the design-system art atlas (`orbital.atlas`), UC27.
 *
 * Engine-free on purpose: these are plain `String` constants with **no** libGDX dependency, so they can
 * be referenced from pure `core` resolvers ([WorldGlyphs]) and asserted by JVM tests without a GL context
 * (the headless region-existence guard parses the `.atlas` text and checks each of these resolves).
 *
 * Each constant's value matches a region declared in the atlas exactly; a typo would surface as a missing
 * region at load (see [GameAssets.region]) and in the guard test, not as a silent blank sprite. Adding art
 * means adding a region to the atlas *and* a constant here.
 */
object AtlasRegions {
    // Action-arc glyph buttons (UC26 / AC#2).
    const val ACTION_FIRE = "action-fire"
    const val ACTION_DOCK = "action-dock"
    const val ACTION_MINE = "action-mine"
    const val ACTION_SCAN = "action-scan"
    const val ACTION_RADIO = "action-radio"
    const val ACTION_POINT_AND_GO = "action-point-and-go"

    // Movement joystick (AC#3).
    const val JOYSTICK_BASE = "joystick-base"
    const val JOYSTICK_KNOB = "joystick-knob"

    // World objects (AC#4).
    const val SHIP_PLAYER = "ship-player"
    const val SHIP_HOSTILE = "ship-hostile"
    const val STATION = "station"
    const val ASTEROID_FIELD = "asteroid-field"
    const val JUMP_GATE = "jump-gate"
    const val CONTACT_HIDDEN = "contact-hidden"
    const val PROJECTILE = "projectile"

    // Minimap markers (AC#5).
    const val MM_PLAYER = "mm-player"
    const val MM_STATION = "mm-station"
    const val MM_GATE = "mm-gate"
    const val MM_ASTEROID = "mm-asteroid"
    const val MM_CONTACT = "mm-contact"

    // Ship-schematic module states (AC#6).
    const val MODULE_HEALTHY = "module-healthy"
    const val MODULE_WARN = "module-warn"
    const val MODULE_CRITICAL = "module-critical"

    // On-foot walk-around (AC#7).
    const val AVATAR_PLAYER = "avatar-player"
    const val NPC_SHOPKEEPER = "npc-shopkeeper"
    const val FLOOR_TILE = "floor-tile"
    const val WALL_TILE = "wall-tile"

    /** Every region name above — the guard test asserts each one resolves in the loaded atlas (AC#10). */
    val ALL: List<String> =
        listOf(
            ACTION_FIRE, ACTION_DOCK, ACTION_MINE, ACTION_SCAN, ACTION_RADIO, ACTION_POINT_AND_GO,
            JOYSTICK_BASE, JOYSTICK_KNOB,
            SHIP_PLAYER, SHIP_HOSTILE, STATION, ASTEROID_FIELD, JUMP_GATE, CONTACT_HIDDEN, PROJECTILE,
            MM_PLAYER, MM_STATION, MM_GATE, MM_ASTEROID, MM_CONTACT,
            MODULE_HEALTHY, MODULE_WARN, MODULE_CRITICAL,
            AVATAR_PLAYER, NPC_SHOPKEEPER, FLOOR_TILE, WALL_TILE,
        )
}
