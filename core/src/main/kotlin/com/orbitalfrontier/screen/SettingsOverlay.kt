package com.orbitalfrontier.screen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Disposable
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.PlaceholderControlsSkin
import com.orbitalfrontier.settings.Handedness
import java.util.concurrent.Executors

/**
 * Handedness toggle (AC#8). Tapping the button:
 *  1. swaps handedness **in memory immediately** and notifies [onHandednessChanged] on the render
 *     thread, so the control layout flips instantly with no I/O wait; and
 *  2. persists the new value on a **single background thread** (real SQLite I/O must never block
 *     the render thread — coding-guidelines § concurrency). The single-thread executor serializes
 *     saves (single-writer) so rapid toggles can't interleave. The repository write is itself
 *     transactional and swallows+logs failures, so no result needs marshalling back.
 */
class SettingsOverlay(
    skin: PlaceholderControlsSkin,
    private val logger: Logger,
    private val repository: SettingsRepository,
    initial: Handedness,
    private val onHandednessChanged: (Handedness) -> Unit,
) : Disposable {
    private val saveExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "settings-save").apply { isDaemon = true } }

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

    override fun dispose() {
        saveExecutor.shutdown()
    }
}
