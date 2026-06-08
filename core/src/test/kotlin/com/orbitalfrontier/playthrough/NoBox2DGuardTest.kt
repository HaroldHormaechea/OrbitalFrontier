package com.orbitalfrontier.playthrough

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for the replay path's purity (UC02 AC#5; UC03 AC#8; ADR 0005/0006).
 *
 * Replay must run headlessly on the JVM with **no native Box2D** and no on-device physics binding.
 * [ReplayRunner] and [com.orbitalfrontier.sim.Simulation] therefore must never import
 * `com.badlogic.gdx.physics.box2d.*`, any other libGDX type, or `ShipPhysics` (the on-device
 * integrator). UC03 folds the **production world model** (the `world` package under
 * `core/src/main`) into the replay path — the pure [com.orbitalfrontier.world.GateTraversal] runs
 * inside [Simulation] — so the
 * world sources must stay engine-free too (UC03 AC#8 purity; challenger rec #1). A runtime check
 * can't catch an *unused* import and reflection can't see imports, so this scans the actual source
 * `import` lines — comments (which legitimately *mention* these names in the contract docs) are ignored.
 */
class NoBox2DGuardTest {
    @Test
    fun `ReplayRunner imports no Box2D, libGDX, or ShipPhysics types`() {
        assertNoForbiddenImports(readTestSource("playthrough/ReplayRunner.kt"), "playthrough/ReplayRunner.kt")
    }

    @Test
    fun `Simulation imports no Box2D, libGDX, or ShipPhysics types`() {
        assertNoForbiddenImports(readTestSource("sim/Simulation.kt"), "sim/Simulation.kt")
    }

    @Test
    fun `world model sources import no Box2D, libGDX, or ShipPhysics types`() {
        val worldDir = locateMainPackageDir("world")
        val sources =
            worldDir.listFiles { file -> file.isFile && file.name.endsWith(".kt") }
                ?.sortedBy { it.name }
                .orEmpty()
        assertTrue("expected world model sources to scan under ${worldDir.absolutePath}", sources.isNotEmpty())
        for (file in sources) {
            assertNoForbiddenImports(file.readText(), "world/${file.name}")
        }
    }

    /**
     * UC13: the whole `combat` package is the seeded **pure model** the replay harness steps (AC#7), so it
     * must be engine-free AND determinism-safe. Beyond the Box2D/libGDX/ShipPhysics ban, it must not import
     * a **non-deterministic RNG** (`kotlin.random.Random` / `java.util.Random` — the only sanctioned source
     * is the functional [com.orbitalfrontier.combat.CombatRng]) nor any **wall-clock** type (`java.time.*` /
     * `System` time), which would break record/replay reproducibility.
     */
    @Test
    fun `combat model sources import no Box2D, libGDX, non-deterministic RNG, or wall-clock types`() {
        val combatDir = locateMainPackageDir("combat")
        val sources =
            combatDir.listFiles { file -> file.isFile && file.name.endsWith(".kt") }
                ?.sortedBy { it.name }
                .orEmpty()
        assertTrue("expected combat model sources to scan under ${combatDir.absolutePath}", sources.isNotEmpty())
        for (file in sources) {
            val source = file.readText()
            assertNoForbiddenImports(source, "combat/${file.name}")

            val imports =
                source.lineSequence().map { it.trim() }.filter { it.startsWith("import ") }.toList()
            val nonDeterministic =
                imports.filter { line ->
                    line.contains("kotlin.random") ||
                        line.contains("java.util.Random") ||
                        line.contains("java.time") ||
                        Regex("\\bimport\\s+java\\.lang\\.System\\b").containsMatchIn(line)
                }
            assertTrue(
                "combat/${file.name} must not import a non-deterministic RNG or wall-clock type, found: $nonDeterministic",
                nonDeterministic.isEmpty(),
            )
        }
    }

    private fun assertNoForbiddenImports(
        source: String,
        label: String,
    ) {
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
            "replay-path source $label must not import Box2D/libGDX/ShipPhysics, found: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun readTestSource(relativeToPackageRoot: String): String =
        locateSourceFile("src/test/kotlin/com/orbitalfrontier/$relativeToPackageRoot").readText()

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
