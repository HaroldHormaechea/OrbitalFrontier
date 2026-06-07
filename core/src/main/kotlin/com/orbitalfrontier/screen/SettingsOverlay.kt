package com.orbitalfrontier.screen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin
import com.orbitalfrontier.settings.Handedness

/**
 * Handedness toggle (AC#8). Tapping the button:
 *  1. swaps handedness **in memory immediately** and notifies [onHandednessChanged] on the render
 *     thread, so the control layout flips instantly with no I/O wait; and
 *  2. persists the new value through the injected [SaveExecutor] — the **same single writer** that
 *     handles autosave (UC04). Real SQLite I/O never blocks the render thread (coding-guidelines §
 *     concurrency), and because every persistence write shares this one serial queue, the settings
 *     write can't interleave with an autosave. The repository write is itself transactional and
 *     swallows+logs failures, so no result needs marshalling back.
 *
 * The [SaveExecutor]'s lifecycle is owned by the app (built in the launcher, shut down on dispose),
 * not by this overlay — so the overlay holds no disposable resources of its own.
 */
class SettingsOverlay(
    skin: PlaceholderControlsSkin,
    private val repository: SettingsRepository,
    private val saveExecutor: SaveExecutor,
    initial: Handedness,
    private val onHandednessChanged: (Handedness) -> Unit,
) {
    private var handedness: Handedness = initial
    val actor: TextButton = TextButton(label(initial), skin.settingsButtonStyle)

    init {
        actor.addListener(
            object : ChangeListener() {
                override fun changed(
                    event: ChangeEvent,
                    target: Actor,
                ) {
                    toggle()
                }
            },
        )
    }

    private fun toggle() {
        handedness = handedness.toggled()
        actor.setText(label(handedness))
        onHandednessChanged(handedness) // instant, render-thread, no I/O
        persistAsync(handedness)
    }

    private fun persistAsync(value: Handedness) {
        saveExecutor.execute {
            // Off the render thread; repository write is transactional and logs its own failures.
            repository.saveHandedness(value)
        }
    }

    private fun label(value: Handedness): String = "LAYOUT: " + if (value == Handedness.RIGHT_HANDED) "R" else "L"
}
