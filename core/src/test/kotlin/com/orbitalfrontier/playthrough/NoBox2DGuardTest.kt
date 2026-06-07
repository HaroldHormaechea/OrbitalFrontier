package com.orbitalfrontier.playthrough

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for the replay path's purity (UC02 AC#5; ADR 0005/0006).
 *
 * Replay must run headlessly on the JVM with **no native Box2D** and no on-device physics binding.
 * [ReplayRunner] and [com.orbitalfrontier.sim.Simulation] therefore must never import
 * `com.badlogic.gdx.physics.box2d.*`, any other libGDX type, or `ShipPhysics` (the on-device
 * integrator). A runtime check can't catch an *unused* import and reflection can't see imports, so
 * this scans the actual source `import` lines — comments (which legitimately *mention* these names
 * in the contract docs) are ignored.
 */
class NoBox2DGuardTest {
    @Test
    fun `ReplayRunner imports no Box2D, libGDX, or ShipPhysics types`() {
        assertNoForbiddenImports("playthrough/ReplayRunner.kt")
    }

    @Test
    fun `Simulation imports no Box2D, libGDX, or ShipPhysics types`() {
        assertNoForbiddenImports("sim/Simulation.kt")
    }

    private fun assertNoForbiddenImports(relativeToPackageRoot: String) {
        val source = readTestSource(relativeToPackageRoot)
        val imports =
            source.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .toList()

        val offenders =
            imports.filter { line ->
                line.contains("com.badlogic.gdx") || line.contains("box2d") || line.contains("ShipPhysics")
            }
        assertTrue(
            "replay-path source $relativeToPackageRoot must not import Box2D/libGDX/ShipPhysics, found: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun readTestSource(relativeToPackageRoot: String): String {
        val relative = "src/test/kotlin/com/orbitalfrontier/$relativeToPackageRoot"
        // Gradle runs the Test task with the module dir as its working dir; tolerate a couple of
        // alternate roots so the guard also resolves when invoked from the repository root.
        val candidates = listOf(File(relative), File("core", relative), File("../core", relative))
        val file =
            candidates.firstOrNull { it.isFile }
                ?: throw AssertionError("could not locate source; tried: ${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}
