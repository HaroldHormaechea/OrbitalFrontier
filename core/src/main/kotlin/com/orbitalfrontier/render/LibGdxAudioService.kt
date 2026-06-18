package com.orbitalfrontier.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.orbitalfrontier.audio.MusicTrack
import com.orbitalfrontier.audio.Sfx
import com.orbitalfrontier.platform.AudioService
import com.orbitalfrontier.platform.Logger

/**
 * libGDX-backed [AudioService] (UC31) — the device/desktop implementation of the pure audio port.
 *
 * It lives in `render/` (the engine-bound layer), deliberately **outside** the engine-free `audio`
 * package the UC31 purity guard scans, because it imports libGDX `Sound`/`Music`. The single instance is
 * built once on the GL/audio thread by [com.orbitalfrontier.app.OrbitalFrontierGame.create] via [load]
 * and [dispose]d exactly once on shutdown (single owner, single dispose — the same discipline as
 * [GameAssets]). The deterministic core never touches it: sound is driven by the events the pure model
 * emits, played by the screen layer, so the core stays audio-free and headless-testable (AC#4/#5).
 *
 * **Channels & mute.** SFX and music have independent gains; the *effective* gain applied to libGDX is
 * `if (masterMuted) 0 else volume`, so muting silences both channels without losing their levels, and a
 * volume/mute change is pushed live to the playing music and the thrust loop.
 *
 * **THRUST is a looping Sound** (continuous engine rumble): [play] of [Sfx.THRUST] starts the loop on the
 * thrust rising edge and tracks its instance id so [stopSfx] can end it on the falling edge; every other
 * cue is a one-shot for which [stopSfx] is a no-op (ADR 0020).
 *
 * **Graceful loading.** A clip that fails to load (missing/corrupt placeholder asset) is logged and
 * skipped, never fatal — playing or stopping a missing cue is a silent no-op, so the game runs with
 * whatever audio is present.
 */
class LibGdxAudioService private constructor(
    private val logger: Logger,
    private val sounds: Map<Sfx, Sound>,
    private val music: Map<MusicTrack, Music>,
) : AudioService {
    private var masterMuted = false
    private var sfxVolume = 1f
    private var musicVolume = 0.5f

    private var currentTrack: MusicTrack? = null
    private var thrustLoopId: Long? = null

    private fun effectiveSfxVolume(): Float = if (masterMuted) 0f else sfxVolume

    private fun effectiveMusicVolume(): Float = if (masterMuted) 0f else musicVolume

    override fun play(sfx: Sfx) {
        val sound = sounds[sfx] ?: return
        if (sfx == Sfx.THRUST) {
            // Looping engine sound: start once on the rising edge; ignore repeat calls while already
            // looping so the loop isn't restarted every frame the stick is held.
            if (thrustLoopId == null) {
                thrustLoopId = sound.loop(effectiveSfxVolume())
            }
            return
        }
        // One-shot cue: skip the playback entirely while silenced (nothing to hear, no wasted voice).
        val volume = effectiveSfxVolume()
        if (volume > 0f) {
            sound.play(volume)
        }
    }

    override fun stopSfx(sfx: Sfx) {
        if (sfx == Sfx.THRUST) {
            val id = thrustLoopId ?: return
            sounds[Sfx.THRUST]?.stop(id)
            thrustLoopId = null
        }
        // One-shot cues have nothing sustained to stop — let them finish (intentional no-op).
    }

    override fun playMusic(track: MusicTrack) {
        // Idempotent: re-asserting the current track (every frame / on a screen change) is a no-op, so the
        // loop is never restarted and STATION can span the docked/walkaround/desk screens gap-free (AC#2).
        if (track == currentTrack) return
        currentTrack?.let { music[it]?.let { m -> runCatching { m.stop() } } }
        currentTrack = track
        val next = music[track] ?: return // missing asset: tracked as current so we don't retry every frame
        next.isLooping = true
        next.volume = effectiveMusicVolume()
        next.play()
    }

    override fun stopMusic() {
        currentTrack?.let { music[it]?.let { m -> runCatching { m.stop() } } }
        currentTrack = null
    }

    override fun setMasterMuted(muted: Boolean) {
        masterMuted = muted
        applyLiveVolumes()
    }

    override fun setSfxVolume(volume: Float) {
        sfxVolume = volume.coerceIn(0f, 1f)
        applyLiveVolumes()
    }

    override fun setMusicVolume(volume: Float) {
        musicVolume = volume.coerceIn(0f, 1f)
        applyLiveVolumes()
    }

    override fun pauseMusic() {
        currentTrack?.let { music[it]?.let { m -> runCatching { m.pause() } } }
    }

    override fun resumeMusic() {
        currentTrack?.let { music[it]?.let { m -> runCatching { m.play() } } }
    }

    override fun dispose() {
        thrustLoopId?.let { sounds[Sfx.THRUST]?.stop(it) }
        thrustLoopId = null
        currentTrack = null
        music.values.forEach { runCatching { it.stop() } }
        sounds.values.forEach { runCatching { it.dispose() } }
        music.values.forEach { runCatching { it.dispose() } }
    }

    /** Push the current effective gains to the live music track and the live thrust loop (if any). */
    private fun applyLiveVolumes() {
        currentTrack?.let { music[it]?.volume = effectiveMusicVolume() }
        thrustLoopId?.let { id -> sounds[Sfx.THRUST]?.setVolume(id, effectiveSfxVolume()) }
    }

    companion object {
        private const val TAG = "Audio"

        /** Internal asset path for the clip backing [sfx] (under the libGDX asset root). */
        private fun sfxPath(sfx: Sfx): String = "audio/sfx/${sfx.name.lowercase()}.wav"

        /** Internal asset path for the clip backing [track] (under the libGDX asset root). */
        private fun musicPath(track: MusicTrack): String = "audio/music/${track.name.lowercase()}.wav"

        /**
         * Build the audio service on the GL/audio thread, loading every available clip. A clip that fails
         * to load is logged and skipped (never fatal), so the game still runs with partial/placeholder
         * audio. Never call on a JVM test thread — there is no audio device there (use NoOpAudioService).
         */
        fun load(logger: Logger): AudioService {
            val sounds = HashMap<Sfx, Sound>()
            for (sfx in Sfx.entries) {
                val path = sfxPath(sfx)
                try {
                    sounds[sfx] = Gdx.audio.newSound(Gdx.files.internal(path))
                } catch (e: Exception) {
                    logger.warn(TAG, "Could not load SFX clip '$path' for $sfx; cue will be silent", e)
                }
            }
            val music = HashMap<MusicTrack, Music>()
            for (track in MusicTrack.entries) {
                val path = musicPath(track)
                try {
                    music[track] = Gdx.audio.newMusic(Gdx.files.internal(path))
                } catch (e: Exception) {
                    logger.warn(TAG, "Could not load music track '$path' for $track; track will be silent", e)
                }
            }
            logger.info(
                TAG,
                "Audio service loaded (${sounds.size}/${Sfx.entries.size} SFX, " +
                    "${music.size}/${MusicTrack.entries.size} tracks)",
            )
            return LibGdxAudioService(logger, sounds, music)
        }
    }
}
