package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Table

/**
 * Right-side action cluster (AC#7): a placeholder group of buttons shown opposite the movement
 * stick. Intentionally **inert** in this slice — no listeners are attached, so the buttons have
 * no gameplay effect. They exist so the layout and multi-touch behaviour can be exercised now and
 * real actions (fire, ability, dock, …) can be wired in later without moving the UI.
 */
class ActionCluster(skin: PlaceholderControlsSkin) {
    val actor: Table = Table()

    init {
        repeat(ACTION_BUTTON_COUNT) {
            // No click listener: purely visual placeholder.
            val button = ImageButton(skin.actionButtonStyle)
            actor.add(button).size(BUTTON_SIZE).pad(BUTTON_PAD).row()
        }
    }

    private companion object {
        const val ACTION_BUTTON_COUNT = 3
        const val BUTTON_SIZE = 96f
        const val BUTTON_PAD = 8f
    }
}
