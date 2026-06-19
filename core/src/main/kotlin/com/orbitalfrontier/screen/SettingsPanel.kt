package com.orbitalfrontier.screen

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.SaveExecutor
import com.orbitalfrontier.render.UiScale
import com.orbitalfrontier.save.SettingsRepository
import com.orbitalfrontier.screen.controls.OrbitalUiSkin
import com.orbitalfrontier.settings.AudioSettings
import com.orbitalfrontier.settings.Handedness
import com.orbitalfrontier.settings.JoystickTuning
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The shared, grouped settings panel (UC37) — the **single source of truth** for the settings UI, used
 * both by the in-flight [SettingsOverlay] (live-apply over the frozen game) and by the main-menu
 * [SettingsScreen]. It builds one [content] table of labelled rows grouped under section headers:
 *
 *  - **AUDIO** (UC31) — master mute, stepped SFX + music volume.
 *  - **CONTROLS** — handedness (AC#3), joystick sensitivity + deadzone (new in UC37).
 *  - **DISPLAY** — UI scale (AC#3; the ADR 0015 knob, now player-adjustable).
 *  - **GAMEPLAY** — replay the first-run tutorial (UC36).
 *
 * Two groups the use case lists are **deliberately omitted, not faked** (UC37 "Dependency" pitfall): an
 * **Accessibility** group (text size / colourblind palette) depends on the accessibility use case (UC39),
 * and a **Save Management** group depends on the save-slot use case (UC38). A short note row states this so
 * the gap is honest rather than stubbed; the groups slot in additively when those use cases land.
 *
 * Every control follows the same two-step discipline UC31 established:
 *  1. apply the change **in memory + live on the render thread** (control layout flip, mute, volume,
 *     joystick tuning, UI scale) so the effect is instant with no I/O wait, notifying the host via the
 *     `on…Changed` callbacks so it can re-apply to its own live actors; and
 *  2. persist the new value through the injected [SaveExecutor] — the **same single writer** that handles
 *     autosave — so the settings write never blocks the render thread and the per-field, transactional
 *     repository writes never clobber another column.
 *
 * The panel borrows the [OrbitalUiSkin] from its host (no disposable resources of its own); the host owns
 * the skin/stage lifecycle and wraps [content] in a [ScrollPane] (via [scrollPane]) so a long group list
 * stays reachable inside a fixed footprint. ASCII-only labels (the UC28 game font).
 */
class SettingsPanel(
    private val skin: OrbitalUiSkin,
    private val repository: SettingsRepository,
    private val saveExecutor: SaveExecutor,
    initialHandedness: Handedness,
    initialAudio: AudioSettings,
    initialJoystickTuning: JoystickTuning,
    initialUiScale: Float,
    private val audio: AudioService,
    private val onHandednessChanged: (Handedness) -> Unit,
    private val onJoystickTuningChanged: (JoystickTuning) -> Unit,
    private val onUiScaleChanged: (Float) -> Unit,
    private val onReplayTutorial: () -> Unit,
) {
    private var handedness: Handedness = initialHandedness
    private var audioSettings: AudioSettings = initialAudio.coerced()
    private var joystickTuning: JoystickTuning = initialJoystickTuning.coerced()
    private var uiScale: Float = UiScale.coerce(initialUiScale)

    private val muteButton = TextButton(muteLabel(audioSettings), skin.settingsButtonStyle)
    private val sfxButton = TextButton(sfxLabel(audioSettings), skin.settingsButtonStyle)
    private val musicButton = TextButton(musicLabel(audioSettings), skin.settingsButtonStyle)

    private val handednessButton = TextButton(handednessLabel(handedness), skin.settingsButtonStyle)
    private val sensitivityButton = TextButton(sensitivityLabel(joystickTuning), skin.settingsButtonStyle)
    private val deadzoneButton = TextButton(deadzoneLabel(joystickTuning), skin.settingsButtonStyle)

    private val uiScaleButton = TextButton(uiScaleLabel(uiScale), skin.settingsButtonStyle)

    private val replayTutorialButton = TextButton(REPLAY_TUTORIAL_LABEL, skin.settingsButtonStyle)

    /** The grouped controls, laid out top-down. The host wraps this in a [ScrollPane] (see [scrollPane]). */
    val content: Table = Table()

    init {
        muteButton.addListener(onTap { toggleMute() })
        sfxButton.addListener(onTap { cycleSfxVolume() })
        musicButton.addListener(onTap { cycleMusicVolume() })
        handednessButton.addListener(onTap { toggleHandedness() })
        sensitivityButton.addListener(onTap { cycleSensitivity() })
        deadzoneButton.addListener(onTap { cycleDeadzone() })
        uiScaleButton.addListener(onTap { cycleUiScale() })
        replayTutorialButton.addListener(onTap { onReplayTutorial() })

        content.top()
        section("AUDIO")
        row(muteButton)
        row(sfxButton)
        row(musicButton)
        section("CONTROLS")
        row(handednessButton)
        row(sensitivityButton)
        row(deadzoneButton)
        section("DISPLAY")
        row(uiScaleButton)
        section("GAMEPLAY")
        row(replayTutorialButton)
        // UC37 Dependency pitfall: Accessibility (UC39) + Save Management (UC38) groups are not yet
        // available — stated honestly, never stubbed with dead controls.
        note(OMITTED_GROUPS_NOTE)
    }

    /**
     * Wrap [content] in a vertically-scrolling [ScrollPane] (horizontal scrolling disabled, scrollbars
     * always shown via the design-system [OrbitalUiSkin.scrollPaneStyle]) so the full group list stays
     * reachable inside whatever fixed footprint the host sizes the pane to. Build once per host.
     */
    fun scrollPane(): ScrollPane =
        ScrollPane(content, skin.scrollPaneStyle).apply {
            setScrollingDisabled(true, false)
            fadeScrollBars = false
            setOverscroll(false, false)
        }

    // --- AUDIO ---------------------------------------------------------------------------------------

    private fun toggleMute() {
        audioSettings = audioSettings.copy(masterMuted = !audioSettings.masterMuted)
        audio.setMasterMuted(audioSettings.masterMuted) // instant, render-thread
        muteButton.setText(muteLabel(audioSettings))
        persistAudioAsync()
    }

    private fun cycleSfxVolume() {
        audioSettings = audioSettings.copy(sfxVolume = nextLevel(VOLUME_LEVELS, audioSettings.sfxVolume))
        audio.setSfxVolume(audioSettings.sfxVolume)
        sfxButton.setText(sfxLabel(audioSettings))
        persistAudioAsync()
    }

    private fun cycleMusicVolume() {
        audioSettings = audioSettings.copy(musicVolume = nextLevel(VOLUME_LEVELS, audioSettings.musicVolume))
        audio.setMusicVolume(audioSettings.musicVolume)
        musicButton.setText(musicLabel(audioSettings))
        persistAudioAsync()
    }

    private fun persistAudioAsync() {
        val snapshot = audioSettings
        saveExecutor.execute { repository.saveAudioSettings(snapshot) }
    }

    // --- CONTROLS ------------------------------------------------------------------------------------

    private fun toggleHandedness() {
        handedness = handedness.toggled()
        handednessButton.setText(handednessLabel(handedness))
        onHandednessChanged(handedness) // instant, render-thread, no I/O
        saveExecutor.execute { repository.saveHandedness(handedness) }
    }

    private fun cycleSensitivity() {
        val next = nextLevel(SENSITIVITY_LEVELS, joystickTuning.sensitivity)
        joystickTuning = joystickTuning.copy(sensitivity = next).coerced()
        sensitivityButton.setText(sensitivityLabel(joystickTuning))
        applyJoystickTuning()
    }

    private fun cycleDeadzone() {
        val next = nextLevel(DEADZONE_LEVELS, joystickTuning.deadzone)
        joystickTuning = joystickTuning.copy(deadzone = next).coerced()
        deadzoneButton.setText(deadzoneLabel(joystickTuning))
        applyJoystickTuning()
    }

    private fun applyJoystickTuning() {
        onJoystickTuningChanged(joystickTuning) // instant, render-thread (live on the joystick boundary)
        val snapshot = joystickTuning
        saveExecutor.execute { repository.saveJoystickTuning(snapshot) }
    }

    // --- DISPLAY -------------------------------------------------------------------------------------

    private fun cycleUiScale() {
        // Apply to the global knob first (coerced), then surface the stored value so the label + the host's
        // live viewport re-apply use exactly what took effect; persist off the render thread.
        uiScale = UiScale.set(nextLevel(UI_SCALE_LEVELS, uiScale))
        uiScaleButton.setText(uiScaleLabel(uiScale))
        onUiScaleChanged(uiScale) // host re-applies UiScale.factor to its own live Scene2D viewport
        val snapshot = uiScale
        saveExecutor.execute { repository.saveUiScale(snapshot) }
    }

    // --- Layout helpers ------------------------------------------------------------------------------

    private fun section(title: String) {
        content.add(Label(title, skin.titleLabelStyle)).left().padTop(SECTION_GAP).padBottom(ROW_GAP)
        content.row()
    }

    private fun row(button: TextButton) {
        content.add(button).width(ROW_WIDTH).height(ROW_HEIGHT).padBottom(ROW_GAP)
        content.row()
    }

    private fun note(text: String) {
        val label = Label(text, skin.labelStyle)
        label.wrap = true
        content.add(label).width(ROW_WIDTH).padTop(SECTION_GAP)
        content.row()
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

    // --- Labels (ASCII only — UC28 game font) --------------------------------------------------------

    private fun handednessLabel(value: Handedness): String = "LAYOUT: " + if (value == Handedness.RIGHT_HANDED) "R" else "L"

    private fun muteLabel(value: AudioSettings): String = "AUDIO: " + if (value.masterMuted) "OFF" else "ON"

    private fun sfxLabel(value: AudioSettings): String = "SFX: ${percent(value.sfxVolume)}%"

    private fun musicLabel(value: AudioSettings): String = "MUSIC: ${percent(value.musicVolume)}%"

    private fun sensitivityLabel(value: JoystickTuning): String = "SENSITIVITY: ${oneDecimal(value.sensitivity)}x"

    private fun deadzoneLabel(value: JoystickTuning): String = "DEADZONE: ${percent(value.deadzone)}%"

    private fun uiScaleLabel(value: Float): String = "UI SCALE: ${oneDecimal(value)}x"

    private fun percent(fraction: Float): Int = (fraction * 100f).roundToInt()

    private fun oneDecimal(value: Float): String {
        val tenths = (value * 10f).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    /** Next discrete level after [current] (nearest match), wrapping past the end back to the start. */
    private fun nextLevel(
        levels: List<Float>,
        current: Float,
    ): Float {
        val nearest = levels.minByOrNull { abs(it - current) } ?: return levels.first()
        val index = levels.indexOf(nearest)
        return levels[(index + 1) % levels.size]
    }

    private companion object {
        /** Stepped gains a volume button cycles through (0 % .. 100 % in 25 % steps). */
        val VOLUME_LEVELS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        /** Sensitivity steps within JoystickTuning's clamp range (0.25x .. 3.0x). */
        val SENSITIVITY_LEVELS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f)

        /** Deadzone steps within JoystickTuning's clamp range (15 % .. 90 %; 15 % = the model floor). */
        val DEADZONE_LEVELS = listOf(0.15f, 0.25f, 0.4f, 0.55f, 0.7f, 0.9f)

        /** UI-scale steps within UiScale's clamp range (1.0x .. 3.0x). */
        val UI_SCALE_LEVELS = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)

        // Fixed row width chosen to fit the SMALLER host (the in-flight overlay's fixed footprint) so the
        // content never clips horizontally; the wider main-menu screen simply centres the column. Matches
        // the pre-UC37 in-flight button width so the in-flight feel is unchanged.
        const val ROW_WIDTH = 210f
        const val ROW_HEIGHT = 44f
        const val ROW_GAP = 6f
        const val SECTION_GAP = 14f

        const val REPLAY_TUTORIAL_LABEL = "REPLAY TUTORIAL"

        /** Honest note for the use-case-dependent groups not yet built (UC38 / UC39). */
        const val OMITTED_GROUPS_NOTE = "ACCESSIBILITY AND SAVE MANAGEMENT COMING IN A LATER UPDATE"
    }
}
