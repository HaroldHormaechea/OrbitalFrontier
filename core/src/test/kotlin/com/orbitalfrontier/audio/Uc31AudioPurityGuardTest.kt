package com.orbitalfrontier.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static purity guard for the engine-free side of the UC31 audio system (AC#4/#5).
 *
 * The deterministic core must stay audio-device-free so the replay harness and JVM tests run
 * headlessly with no audio backend. The cue/track catalogues ([Sfx], [MusicTrack]), the injected
 * [com.orbitalfrontier.platform.AudioService] port (incl. [com.orbitalfrontier.platform.NoOpAudioService]),
 * and the persisted [com.orbitalfrontier.settings.AudioSettings] value type therefore must never import a
 * libGDX type (`com.badlogic.gdx.*`) — only the device-side [com.orbitalfrontier.render.LibGdxAudioService]
 * (under `render/`, deliberately OUTSIDE this scanned set) is allowed to touch the engine.
 *
 * The whole `audio` package is scanned **wholesale** (every `.kt` file, no exclusion list) so a future
 * file added to it inherits the ban automatically. A runtime check can't catch an *unused* engine import
 * and reflection can't see imports, so this scans the actual source `import` lines; comments that merely
 * *mention* libGDX in the contract docs are ignored.
 */
class Uc31AudioPurityGuardTest {
    @Test
    fun `every audio package source imports no libGDX type`() {
        val audioDir = locateMainPackageDir("audio")
        val sources =
            audioDir.listFiles { file -> file.isFile && file.name.endsWith(".kt") }
                ?.sortedBy { it.name }
                .orEmpty()
        assertTrue("expected audio package sources to scan under ${audioDir.absolutePath}", sources.isNotEmpty())
        for (file in sources) {
            assertNoLibGdxImport(file.readText(), "audio/${file.name}")
        }
    }

    @Test
    fun `the AudioService port imports no libGDX type`() {
        assertNoLibGdxImport(readMainSource("platform/AudioService.kt"), "platform/AudioService.kt")
    }

    @Test
    fun `the persisted AudioSettings value type imports no libGDX type`() {
        assertNoLibGdxImport(readMainSource("settings/AudioSettings.kt"), "settings/AudioSettings.kt")
    }

    /**
     * Sanity anchor: the device-side libGDX implementation lives under `render/` and is intentionally
     * NOT in the scanned, engine-free set above — so the ban applies to the pure side only, not to the
     * one class that is supposed to bind the engine.
     */
    @Test
    fun `the libGDX audio implementation lives outside the scanned engine-free set`() {
        val libGdxImpl = File(locateMainPackageDir("render"), "LibGdxAudioService.kt")
        assertTrue(
            "expected the device-side LibGdxAudioService under render/ (the only place libGDX audio is allowed)",
            libGdxImpl.isFile,
        )
        val audioDir = locateMainPackageDir("audio")
        assertFalse(
            "LibGdxAudioService must NOT live in the engine-free audio package",
            File(audioDir, "LibGdxAudioService.kt").exists(),
        )
    }

    private fun assertNoLibGdxImport(
        source: String,
        label: String,
    ) {
        val offenders =
            source.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .filter { it.contains("com.badlogic.gdx") }
                .toList()
        assertTrue(
            "engine-free audio source $label must not import a libGDX type, found: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun readMainSource(relativeToPackageRoot: String): String =
        locateSourceFile("src/main/kotlin/com/orbitalfrontier/$relativeToPackageRoot").readText()

    private fun locateSourceFile(relative: String): File {
        // Gradle runs the Test task with the module dir as its working dir; tolerate a couple of
        // alternate roots so the guard also resolves when invoked from the repository root.
        val candidates = listOf(File(relative), File("core", relative), File("../core", relative))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("could not locate source; tried: ${candidates.map { it.absolutePath }}")
    }

    private fun locateMainPackageDir(relativePackage: String): File {
        val relative = "src/main/kotlin/com/orbitalfrontier/$relativePackage"
        val candidates = listOf(File(relative), File("core", relative), File("../core", relative))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("could not locate package dir; tried: ${candidates.map { it.absolutePath }}")
    }
}
