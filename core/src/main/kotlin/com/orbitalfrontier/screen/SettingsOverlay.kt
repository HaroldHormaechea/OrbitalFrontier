package com.orbitalfrontier.screen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.Handedness
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * In-flight settings panel: a handedness toggle (AC#8) plus the UC31 audio controls — master mute and
 * stepped SFX / music volume (UC31 AC#3). Every control follows the same two-step discipline the
 * handedness toggle established:
 *  1. apply the change **in memory + to the live [AudioService] immediately on the render thread**, so the
 *     effect (control layout flip, mute, volume) is instant with no I/O wait; and
 *  2. persist the new value through the injected [SaveExecutor] — the **same single writer** that handles
 *     autosave (UC04) — so the settings write never blocks the render thread and can't interleave with an
 *     autosave. The repository writes are transactional, per-field (handedness vs. audio never clobber each
 *     other, UC31 Risk 1) and swallow+log their own failures, so nothing needs marshalling back.
 *
 * The controls are stacked in a [Table] exposed as [actor] (the play screen positions/sizes/hides it as a
 * single unit, unchanged from when it was one button). The [SaveExecutor]'s lifecycle is owned by the app,
 * not this overlay, so the overlay holds no disposable resources of its own.
 */
class SettingsOverlay(
    skin: OrbitalUiSkin,
    private val repository: SettingsRepository,
    private val saveExecutor: SaveExecutor,
    initialHandedness: Handedness,
    initialAudio: AudioSettings,
    private val audio: AudioService,
    private val onHandednessChanged: (Handedness) -> Unit,
    // UC36 AC#3: replay the first-run tutorial. Invoked on the render thread when REPLAY TUTORIAL is
    // tapped; the play screen resets its tutorial state so the onboarding runs again. Defaults to a no-op
    // so headless/JVM contexts that build the overlay without a tutorial host need not wire it.
    private val onReplayTutorial: () -> Unit = {},
) {
    private var handedness: Handedness = initialHandedness
    private var audioSettings: AudioSettings = initialAudio.coerced()

    private val handednessButton = TextButton(handednessLabel(handedness), skin.settingsButtonStyle)
    private val muteButton = TextButton(muteLabel(audioSettings), skin.settingsButtonStyle)
    private val sfxButton = TextButton(sfxLabel(audioSettings), skin.settingsButtonStyle)
    private val musicButton = TextButton(musicLabel(audioSettings), skin.settingsButtonStyle)

    // UC36 AC#3: replay the first-run tutorial from settings. A plain action button (not a toggle) — its
    // label never changes; tapping it hands off to [onReplayTutorial] on the render thread.
    private val replayTutorialButton = TextButton(REPLAY_TUTORIAL_LABEL, skin.settingsButtonStyle)

    /** The stacked controls, laid out top-down. Positioned/sized/hidden by the play screen as one unit. */
    val actor: Table = Table()

    init {
        handednessButton.addListener(onTap { toggleHandedness() })
        muteButton.addListener(onTap { toggleMute() })
        sfxButton.addListener(onTap { cycleSfxVolume() })
        musicButton.addListener(onTap { cycleMusicVolume() })
        replayTutorialButton.addListener(onTap { onReplayTutorial() })

        actor.top()
        for (button in listOf(handednessButton, muteButton, sfxButton, musicButton, replayTutorialButton)) {
            actor.add(button).width(ROW_WIDTH).height(ROW_HEIGHT).padBottom(ROW_GAP)
            actor.row()
        }
    }

    private fun toggleHandedness() {
        handedness = handedness.toggled()
        handednessButton.setText(handednessLabel(handedness))
        onHandednessChanged(handedness) // instant, render-thread, no I/O
        saveExecutor.execute { repository.saveHandedness(handedness) }
    }

    private fun toggleMute() {
        audioSettings = audioSettings.copy(masterMuted = !audioSettings.masterMuted)
        audio.setMasterMuted(audioSettings.masterMuted) // instant, render-thread
        muteButton.setText(muteLabel(audioSettings))
        persistAudioAsync()
    }

    private fun cycleSfxVolume() {
        audioSettings = audioSettings.copy(sfxVolume = nextVolume(audioSettings.sfxVolume))
        audio.setSfxVolume(audioSettings.sfxVolume) // instant, render-thread
        sfxButton.setText(sfxLabel(audioSettings))
        persistAudioAsync()
    }

    private fun cycleMusicVolume() {
        audioSettings = audioSettings.copy(musicVolume = nextVolume(audioSettings.musicVolume))
        audio.setMusicVolume(audioSettings.musicVolume) // instant, render-thread
        musicButton.setText(musicLabel(audioSettings))
        persistAudioAsync()
    }

    private fun persistAudioAsync() {
        val snapshot = audioSettings
        saveExecutor.execute {
            // Off the render thread; the repository write is transactional and logs its own failures.
            repository.saveAudioSettings(snapshot)
        }
    }

    private fun onTap(action: () -> Unit): ChangeListener =
        object : ChangeListener() {
            override fun changed(
                event: ChangeEvent,
                target: Actor,
            ) {
                action()
            }
        }

    private fun handednessLabel(value: Handedness): String = "LAYOUT: " + if (value == Handedness.RIGHT_HANDED) "R" else "L"

    private fun muteLabel(value: AudioSettings): String = "AUDIO: " + if (value.masterMuted) "OFF" else "ON"

    private fun sfxLabel(value: AudioSettings): String = "SFX: ${percent(value.sfxVolume)}%"

    private fun musicLabel(value: AudioSettings): String = "MUSIC: ${percent(value.musicVolume)}%"

    private fun percent(volume: Float): Int = (volume * 100f).roundToInt()

    /** Next discrete volume level, wrapping 100% -> 0% (a single tappable button cycles the gain). */
    private fun nextVolume(current: Float): Float {
        val nearest = VOLUME_LEVELS.minByOrNull { abs(it - current) } ?: return VOLUME_LEVELS.first()
        val index = VOLUME_LEVELS.indexOf(nearest)
        return VOLUME_LEVELS[(index + 1) % VOLUME_LEVELS.size]
    }

    private companion object {
        /** Stepped gains a volume button cycles through (0 % .. 100 % in 25 % steps). */
        val VOLUME_LEVELS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        const val ROW_WIDTH = 200f
        const val ROW_HEIGHT = 44f
        const val ROW_GAP = 6f

        /** Fixed label for the UC36 replay-tutorial action button (ASCII for the UC28 game font). */
        const val REPLAY_TUTORIAL_LABEL = "REPLAY TUTORIAL"
    }
}
