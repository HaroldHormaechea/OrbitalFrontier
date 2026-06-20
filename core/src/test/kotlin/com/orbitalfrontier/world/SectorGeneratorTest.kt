package com.orbitalfrontier.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [SectorGenerator] (UC53; ADR 0041; docs/design/world-and-sector.md "Layout —
 * procedural, with hand-authored test maps").
 *
 * The load-bearing properties under test:
 *  - **Zero-fixture-regen anchor (AC, the overriding constraint).** `generate(WorldSeed.MVP)` is
 *    *structurally identical* to [MvpSectorMap.build] — the reserved zero seed returns the authored map
 *    verbatim, which is exactly why every existing replay fixture (which carries no world graph, only the
 *    combat/mission RNG seed) stays byte-identical. The comparison is **structural, not `==`**:
 *    [SectorWorld] has no `equals` (so `world == world` is identity), but every [Sector] / [Poi] is a
 *    `data class`, so `a.sectors == b.sectors` is a full field-by-field structural compare (sector
 *    ids+order, per-POI ids/positions/subtype/fields). See [structurallySameWorld].
 *  - **AC#2 connectivity (MANDATORY).** A BFS over jump gates from the start sector reaches EVERY sector
 *    — connectivity is a *generator invariant* (the ring construction), NOT enforced by [SectorWorld]
 *    (which only re-validates reciprocity + global id uniqueness), so this assertion is required.
 *  - **AC#4 determinism.** The same fixed non-MVP seed yields an identical world across two `generate`
 *    calls (field-by-field) — which is what lets a replay capture be re-pinned to a fixed seed.
 *  - **Variety sanity.** Different seeds produce different worlds (the generator is not a constant).
 *  - **Determinism purity.** [SectorGenerator]'s source draws ONLY from [DeterministicRng] — no wall
 *    clock, no `Math.random`, no `java.util.Random` — the precondition behind the determinism above.
 */
class SectorGeneratorTest {
    /** A spread of fixed non-MVP seeds covering every generated sector count N ∈ [3, 5] and the chord. */
    private val sampleSeeds: List<WorldSeed> = (1L..40L).map { WorldSeed(it) }

    // --- Zero-fixture-regen anchor: the MVP seed returns the authored map verbatim -----------------

    @Test
    fun `the MVP seed regenerates the hand-authored MvpSectorMap structurally`() {
        val authored = MvpSectorMap.build()
        val generated = SectorGenerator.generate(WorldSeed.MVP)

        // Structural (data-class) equality of the sector lists — NOT `==` on SectorWorld (no equals).
        // This is the zero-fixture-regen anchor: the default path is byte-identical to the authored map.
        assertTrue(
            "generate(MVP) must be structurally identical to MvpSectorMap.build() (zero-fixture-regen anchor)",
            structurallySameWorld(authored, generated),
        )
        // Belt-and-braces: the sector ids + order are exactly the authored alpha/beta/gamma triangle.
        assertEquals(
            listOf(SectorId("alpha"), SectorId("beta"), SectorId("gamma")),
            generated.sectors.map { it.id },
        )
    }

    @Test
    fun `the MVP seed is the reserved zero seed`() {
        assertEquals(0L, WorldSeed.MVP.value)
    }

    // --- AC#2: the generated world is a connected graph (every sector reachable via gates) ----------

    @Test
    fun `every generated world is fully connected via jump gates (AC#2 reachability)`() {
        for (seed in sampleSeeds) {
            val world = SectorGenerator.generate(seed)
            val reached = reachableSectors(world)
            assertEquals(
                "seed ${seed.value}: BFS over gates from the start sector must reach EVERY sector " +
                    "(connectivity is a generator invariant, ADR 0041)",
                world.sectors.map { it.id }.toSet(),
                reached,
            )
        }
    }

    @Test
    fun `generated worlds span the intended sector-count range`() {
        // Sanity that the seed spread actually exercises N = 3, 4 and 5 (so the ring+chord paths run).
        val counts = sampleSeeds.map { SectorGenerator.generate(it).sectors.size }.toSet()
        assertTrue("generated sector counts must stay within [3, 5]", counts.all { it in 3..5 })
        assertTrue("the seed spread should produce more than one sector count (variety)", counts.size > 1)
    }

    // --- AC#4: generation is pure / deterministic --------------------------------------------------

    @Test
    fun `the same non-MVP seed yields an identical world across two generate calls`() {
        for (seed in listOf(WorldSeed(1L), WorldSeed(7L), WorldSeed(42L), WorldSeed(123456789L))) {
            val first = SectorGenerator.generate(seed)
            val second = SectorGenerator.generate(seed)
            assertTrue(
                "seed ${seed.value}: two generate() calls must produce a field-by-field identical world",
                structurallySameWorld(first, second),
            )
        }
    }

    // --- Variety sanity: different seeds produce different worlds -----------------------------------

    @Test
    fun `different seeds produce different worlds`() {
        val worlds = sampleSeeds.map { SectorGenerator.generate(it) }
        // Not all identical — at least one pair differs structurally (the generator is not a constant).
        val allIdenticalToFirst = worlds.all { structurallySameWorld(it, worlds.first()) }
        assertFalse("different seeds must not all collapse to the same world", allIdenticalToFirst)
    }

    @Test
    fun `a non-MVP seed differs from the authored MVP map`() {
        // A procedural world (sectorN ids) is structurally distinct from the authored alpha/beta/gamma map.
        assertFalse(
            "a non-zero seed must not reproduce the authored MVP map",
            structurallySameWorld(MvpSectorMap.build(), SectorGenerator.generate(WorldSeed(99L))),
        )
    }

    // --- Determinism purity: the generator draws only from DeterministicRng -------------------------

    @Test
    fun `SectorGenerator derives randomness only from DeterministicRng`() {
        // Scan CODE only — strip KDoc/block + line comments first (the KDoc deliberately *mentions* the
        // banned APIs, e.g. "no Math.random", so a raw scan would false-positive on the doc text).
        val code = stripComments(readMainSource("world/SectorGenerator.kt"))
        assertFalse("generation must not use Math.random", code.contains("Math.random"))
        assertFalse("generation must not use java.util.Random", code.contains("java.util.Random"))
        assertFalse("generation must not construct a kotlin/JDK Random()", Regex("""\bRandom\(""").containsMatchIn(code))
        assertFalse("generation must not read the wall clock (System.currentTimeMillis)", code.contains("currentTimeMillis"))
        assertFalse("generation must not read the wall clock (System.nanoTime)", code.contains("nanoTime"))
        assertTrue("generation must derive its stream from DeterministicRng", code.contains("DeterministicRng"))
    }

    /** Strip block/KDoc (`/* … */`, `/** … */`) and line (`// …`) comments, leaving only code. */
    private fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    // --- helpers ------------------------------------------------------------------------------------

    /**
     * The set of sector ids reachable from the world's start sector (its first sector) by traversing
     * jump-gate links — a plain BFS. Used to assert the connectivity invariant (AC#2).
     */
    private fun reachableSectors(world: SectorWorld): Set<SectorId> {
        val start = world.sectors.first().id
        val seen = mutableSetOf(start)
        val frontier = ArrayDeque(listOf(start))
        while (frontier.isNotEmpty()) {
            val current = world.sector(frontier.removeFirst())
            for (gate in current.gates) {
                val dest = gate.link.destinationSector
                if (seen.add(dest)) frontier.addLast(dest)
            }
        }
        return seen
    }

    /**
     * Structural (field-by-field) equality of two worlds. [SectorWorld] has no `equals` (so `==` is
     * identity), but [Sector] and every [Poi] subtype are `data class`es, so comparing the sector lists
     * compares sector ids+order and, recursively, each POI's id, position and subtype-specific fields.
     */
    private fun structurallySameWorld(
        a: SectorWorld,
        b: SectorWorld,
    ): Boolean = a.sectors == b.sectors

    private fun readMainSource(relative: String): String {
        val candidates =
            listOf(
                "src/main/kotlin/com/orbitalfrontier/$relative",
                "core/src/main/kotlin/com/orbitalfrontier/$relative",
            )
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in candidates) {
                val f = File(dir, candidate)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate source for $relative")
    }
}
