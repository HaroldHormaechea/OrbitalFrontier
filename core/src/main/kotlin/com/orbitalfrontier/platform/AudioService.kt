package com.orbitalfrontier.platform

import com.orbitalfrontier.audio.MusicTrack
import com.orbitalfrontier.audio.Sfx
import com.orbitalfrontier.settings.AudioSettings

/**
 * Injected audio output port (DIP — the audio analogue of [Logger] / [SaveExecutor]).
 *
 * `core` and the screen layer drive audio through this interface only; the `android`/desktop backend
 * supplies a libGDX-backed implementation ([com.orbitalfrontier.render.LibGdxAudioService]) while
 * headless contexts (the replay harness, JVM tests) use [NoOpAudioService]. The deterministic
 * simulation never calls it — sound is triggered from the **events** the pure core emits, played by the
 * screen layer, so the core stays audio-free and JVM-testable with no audio device (UC31 AC#4/#5).
 *
 * Implementations apply the persisted [AudioSettings] (master mute + per-channel volume) and own their
 * native handles; the single owner is built and [dispose]d by the app ([com.orbitalfrontier.app
 * .OrbitalFrontierGame]).
 */
interface AudioService {
    /** Play the one-shot sound effect [sfx] (no-op when muted or the clip is unavailable). */
    fun play(sfx: Sfx)

    /**
     * Start (or cross to) the background music [track]. **Idempotent**: a no-op when [track] is already
     * the current track, so callers can assert the desired ambience every frame / on every screen
     * transition without restarting the loop (UC31 AC#2).
     */
    fun playMusic(track: MusicTrack)

    /** Stop the background music entirely (no track current afterwards). */
    fun stopMusic()

    /** Toggle the master mute; muting silences both channels without losing their volume levels. */
    fun setMasterMuted(muted: Boolean)

    /** Set the SFX channel gain (clamped to `0f..1f` by the implementation). */
    fun setSfxVolume(volume: Float)

    /** Set the music channel gain (clamped to `0f..1f` by the implementation). */
    fun setMusicVolume(volume: Float)

    /** Pause the current music (app backgrounded / audio focus lost) — resumable via [resumeMusic]. */
    fun pauseMusic()

    /** Resume music previously paused by [pauseMusic] (app foregrounded). */
    fun resumeMusic()

    /** Release all native audio resources. Called once by the app on shutdown. */
    fun dispose()
}

/**
 * Audio service that does nothing — the safe default for headless / JVM-test contexts and the field
 * default in the app before the GL-thread libGDX service is built (UC31 AC#5). Every method is a no-op,
 * so the replay harness and unit tests run with no audio device.
 */
object NoOpAudioService : AudioService {
    override fun play(sfx: Sfx) = Unit

    override fun playMusic(track: MusicTrack) = Unit

    override fun stopMusic() = Unit

    override fun setMasterMuted(muted: Boolean) = Unit

    override fun setSfxVolume(volume: Float) = Unit

    override fun setMusicVolume(volume: Float) = Unit

    override fun pauseMusic() = Unit

    override fun resumeMusic() = Unit

    override fun dispose() = Unit
}
