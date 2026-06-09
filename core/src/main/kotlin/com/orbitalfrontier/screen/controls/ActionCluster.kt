package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton

/**
 * Right-side action cluster: the player's combat **FIRE** control (UC13 AC#1) above a couple of inert
 * placeholder buttons, shown opposite the movement stick.
 *
 * FIRE is a **held** action — the screen reads [isFirePressed] each frame and passes
 * [com.orbitalfrontier.combat.FireAction.FIRE] into the combat tick while it is down (per-weapon cooldowns
 * in the pure model gate the actual rate, so holding doesn't fire every frame). It is just another Scene2D
 * actor in the same multi-touch cluster, so it composes with the movement stick (fire while steering) and
 * the rest of the layout the same way the placeholder buttons did. The remaining placeholder buttons stay
 * inert (no listeners) so later actions (ability, dock, …) can wire in without moving the UI.
 */
class ActionCluster(skin: PlaceholderControlsSkin) {
    val actor: Table = Table()

    // The FIRE button: a labelled TextButton (reuses the settings button style). Held-action — the screen
    // polls its pressed state per frame rather than reacting to a discrete tap.
    private val fireButton = TextButton("FIRE", skin.settingsButtonStyle)

    init {
        actor.add(fireButton).size(FIRE_SIZE, FIRE_SIZE).pad(BUTTON_PAD).row()
        repeat(PLACEHOLDER_BUTTON_COUNT) {
            // No click listener: purely visual placeholder, as before.
            val button = ImageButton(skin.actionButtonStyle)
            actor.add(button).size(BUTTON_SIZE).pad(BUTTON_PAD).row()
        }
    }

    /** True while the FIRE button is held this frame (UC13 AC#1) — drives the combat tick's fire intent. */
    fun isFirePressed(): Boolean = fireButton.isPressed

    companion object {
        const val PLACEHOLDER_BUTTON_COUNT = 2
        const val BUTTON_SIZE = 96f
        const val FIRE_SIZE = 96f
        const val BUTTON_PAD = 8f

        /**
         * The cluster's laid-out height in world units — the single source of truth for both the Table
         * rows built in [init] and the bottom-band reservation the minimap keeps above the controls
         * (UC22). Each row is its button size plus padding on both sides: one FIRE row above
         * [PLACEHOLDER_BUTTON_COUNT] equal placeholder rows. Derived from the same constants that build
         * the Table so the two can never drift; equals `3 * (96 + 2*8) = 336` for the current layout — a
         * value the UC22 guard test asserts against the actor's real `prefHeight`.
         */
        const val LAYOUT_HEIGHT =
            (FIRE_SIZE + 2f * BUTTON_PAD) + PLACEHOLDER_BUTTON_COUNT * (BUTTON_SIZE + 2f * BUTTON_PAD)
    }
}
