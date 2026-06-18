package com.orbitalfrontier.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static purity guard for the engine-free notification model (UC35 AC#3 hardening).
 *
 * AC#3 requires the feed to be driven by events emitted from the *pure core*, not by polling — so the
 * `notify` package (the kind/severity catalogues, the [GameNotification] value, the [GameNotifications]
 * mapping, and the [NotificationQueue]/[NotificationPolicy] state machine) must stay
 * libGDX-device-free, exactly like the UC31 `audio` package. Only the device-side renderer
 * ([com.orbitalfrontier.render.NotificationRenderer], deliberately OUTSIDE this scanned set) is allowed to
 * touch the engine. Keeping the model pure is what lets the deterministic replay harness and these JVM
 * tests run headlessly with no GL/audio backend (ADR 0001).
 *
 * The whole `notify` package is scanned **wholesale** (every `.kt` file, no exclusion list) so a future
 * file added to it inherits the ban automatically. A runtime check can't catch an *unused* engine import
 * and reflection can't see imports, so this scans the actual source `import` lines; comments that merely
 * *mention* libGDX in the contract docs are ignored. Cloned from
 * [com.orbitalfrontier.audio.Uc31AudioPurityGuardTest].
 */
class NotifyPurityGuardTest {
    @Test
    fun `every notify package source imports no libGDX type`() {
        val notifyDir = locateMainPackageDir("notify")
        val sources =
            notifyDir.listFiles { file -> file.isFile && file.name.endsWith(".kt") }
                ?.sortedBy { it.name }
                .orEmpty()
        assertTrue("expected notify package sources to scan under ${notifyDir.absolutePath}", sources.isNotEmpty())
        for (file in sources) {
            assertNoLibGdxImport(file.readText(), "notify/${file.name}")
        }
    }

    /**
     * Sanity anchor: the device-side libGDX renderer lives under `render/` and is intentionally NOT in the
     * scanned, engine-free set above — so the ban applies to the pure side only, not to the class that is
     * supposed to bind the engine.
     */
    @Test
    fun `the libGDX notification renderer lives outside the scanned engine-free set`() {
        val renderer = File(locateMainPackageDir("render"), "NotificationRenderer.kt")
        assertTrue(
            "expected the device-side NotificationRenderer under render/ (the only place libGDX may bind the toasts)",
            renderer.isFile,
        )
        val notifyDir = locateMainPackageDir("notify")
        assertFalse(
            "NotificationRenderer must NOT live in the engine-free notify package",
            File(notifyDir, "NotificationRenderer.kt").exists(),
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
            "engine-free notify source $label must not import a libGDX type, found: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun locateMainPackageDir(relativePackage: String): File {
        val relative = "src/main/kotlin/com/orbitalfrontier/$relativePackage"
        val candidates = listOf(File(relative), File("core", relative), File("../core", relative))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("could not locate package dir; tried: ${candidates.map { it.absolutePath }}")
    }
}
