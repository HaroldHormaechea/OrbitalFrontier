package com.orbitalfrontier.tutorial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static purity guard for the engine-free first-run-tutorial model (UC36 AC#4).
 *
 * AC#4 requires the tutorial to gate/annotate input WITHOUT altering the deterministic simulation — it
 * only observes events the sim already produced. The enabling invariant is that the whole `tutorial`
 * package (the [TutorialEvent]/[TutorialHighlight] catalogues, the [TutorialStep] table, and the
 * [TutorialState] progression machine) stays libGDX-device-free, exactly like the UC31 `audio` and UC35
 * `notify` model packages. Only the device-side views — the Scene2D [com.orbitalfrontier.screen.controls.TutorialOverlay]
 * (the draw-only band) and the pure-geometry [com.orbitalfrontier.render.TutorialOverlayLayout] — bind or
 * feed the engine, and both live OUTSIDE this scanned package. Keeping the model pure is what lets the
 * deterministic replay harness and these JVM tests run headlessly with no GL backend (ADR 0001).
 *
 * The whole `tutorial` package is scanned **wholesale** (every `.kt` file, no exclusion list) so a future
 * file added to it inherits the ban automatically. A runtime check can't catch an *unused* engine import
 * and reflection can't see imports, so this scans the actual source `import` lines; comments that merely
 * *mention* libGDX in the contract docs are ignored. Cloned from
 * [com.orbitalfrontier.notify.NotifyPurityGuardTest].
 */
class TutorialPurityGuardTest {
    @Test
    fun `every tutorial package source imports no libGDX type`() {
        val tutorialDir = locateMainPackageDir("tutorial")
        val sources =
            tutorialDir.listFiles { file -> file.isFile && file.name.endsWith(".kt") }
                ?.sortedBy { it.name }
                .orEmpty()
        assertTrue("expected tutorial package sources to scan under ${tutorialDir.absolutePath}", sources.isNotEmpty())
        for (file in sources) {
            assertNoLibGdxImport(file.readText(), "tutorial/${file.name}")
        }
    }

    /**
     * Sanity anchor: the device-side libGDX views live OUTSIDE the scanned, engine-free `tutorial`
     * package — the Scene2D overlay under `screen/controls/`, the pure geometry under `render/` — so the
     * ban applies to the pure model only, not to the classes that are supposed to bind / feed the engine.
     */
    @Test
    fun `the libGDX tutorial views live outside the scanned engine-free package`() {
        val overlay = File(locateMainPackageDir("screen/controls"), "TutorialOverlay.kt")
        assertTrue(
            "expected the device-side TutorialOverlay under screen/controls/ (where libGDX may bind the band)",
            overlay.isFile,
        )
        val layout = File(locateMainPackageDir("render"), "TutorialOverlayLayout.kt")
        assertTrue("expected the pure TutorialOverlayLayout under render/", layout.isFile)

        val tutorialDir = locateMainPackageDir("tutorial")
        assertFalse(
            "TutorialOverlay must NOT live in the engine-free tutorial package",
            File(tutorialDir, "TutorialOverlay.kt").exists(),
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
            "engine-free tutorial source $label must not import a libGDX type, found: $offenders",
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
