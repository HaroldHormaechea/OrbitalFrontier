package com.orbitalfrontier.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.orbitalfrontier.platform.Logger
import com.orbitalfrontier.save.OrbitalFrontier
import com.orbitalfrontier.save.SqlDelightGameStateRepository
import com.orbitalfrontier.world.MvpSectorMap
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * UC17 — "Starting cash set to 50k". Guards the four acceptance criteria of the use case.
 *
 * The new-game starting balance lives in `OrbitalFrontierGame.STARTING_CREDITS` (a `private const`)
 * and is applied inline in `create()`'s New-Game branch (`credits = STARTING_CREDITS`). `create()`
 * cannot be driven headlessly without a full libGDX backend (GL, screens, textures), and the
 * analyst/challenger explicitly advised against relaxing the constant's visibility just to read a
 * balance tunable from a test. So AC#1/#2/#4 — which are *value/structure* contracts on that
 * constant — are pinned by a **source-anchored regression guard** that reads the production source
 * and asserts on the constant's declaration and its use in the new-game seed. This fails loudly if
 * the value is changed back, hidden behind a debug flag, or divorced from the seed — without forcing
 * any production change.
 *
 * AC#3 — "loading an existing save is unaffected" — is a *behavioural* contract on the load path, so
 * it is exercised for real against an in-memory [JdbcSqliteDriver] (ADR 0003), proving a persisted
 * wallet that is NOT 50k reloads byte-for-byte unchanged (the load branch never re-seeds).
 */
class Uc17StartingCreditsTest {
    // --- AC#1 / AC#2 / AC#4: source-anchored guard on the STARTING_CREDITS constant ---

    /**
     * The production source of [OrbitalFrontierGame], located by walking up from the test working
     * directory. Failing to find it is a hard error (never a silent pass), so a moved file surfaces
     * as a clear failure rather than a vacuously-green test.
     */
    private val gameSource: String by lazy { readGameSource() }

    /**
     * [gameSource] with Kotlin comments (block `/* … */` and line `// …`) stripped, so the debug-gate
     * guard below inspects actual CODE only. A doc/comment merely *mentioning* `BuildConfig.DEBUG`
     * (e.g. the UC25 note that the launcher forwards the debug build flag) is prose, not a gate on the
     * starting balance, and must not trip the guard — only a real code-level gate should.
     */
    private val gameCode: String by lazy {
        gameSource
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `AC1_AC4 - new-game starting credits is a single clearly-named constant equal to 50,000`() {
        val declarations = STARTING_CREDITS_DECL.findAll(gameSource).toList()
        assertEquals(
            "AC#4: STARTING_CREDITS must be a single, clearly-named constant (exactly one declaration)",
            1,
            declarations.size,
        )
        // AC#1: the declared value is exactly 50,000 (underscore-grouped or plain, with/without the L suffix).
        val literal = declarations.single().groupValues[1].replace("_", "").removeSuffix("L").removeSuffix("l")
        assertEquals("AC#1: a new game must seed exactly 50,000 credits", 50_000L, literal.toLong())
    }

    @Test
    fun `AC2 - the new-game seed uses STARTING_CREDITS directly, not a debug or dev-gated value`() {
        // The New-Game branch seeds the wallet straight from the named constant…
        assertTrue(
            "AC#2: the new-game WorldState seed must use STARTING_CREDITS (credits = STARTING_CREDITS)",
            Regex("""credits\s*=\s*STARTING_CREDITS""").containsMatchIn(gameSource),
        )
        // …the constant is a plain compile-time `const val` (the permanent default), reached
        // unconditionally by the new-game branch — not a function/property that could vary at runtime.
        assertTrue(
            "AC#2: STARTING_CREDITS must be a plain compile-time const (the permanent default), not a runtime toggle",
            STARTING_CREDITS_DECL.containsMatchIn(gameSource),
        )
        // …and the starting balance must not be gated behind any debug/dev build flag. Scanned over
        // comment-stripped CODE so a doc note merely naming BuildConfig.DEBUG (UC25) is not a gate.
        assertTrue(
            "AC#2: the starting balance must not be gated behind a debug/dev/BuildConfig flag",
            DEBUG_GATE.containsMatchIn(gameCode).not(),
        )
    }

    // --- AC#3: loading an existing save is unaffected — only new-game init changed ---

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: OrbitalFrontier

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrbitalFrontier.Schema.create(driver)
        database = OrbitalFrontier(driver)
    }

    @After
    fun tearDown() {
        runCatching { driver.close() }
    }

    private fun newRepository() = SqlDelightGameStateRepository(database, NoopLogger)

    @Test
    fun `AC3 - an existing save's credit balance reloads unchanged and is never re-seeded to 50k`() {
        // A pre-UC17 save with a small, deliberately-not-50k wallet (e.g. an old 500-credit start that
        // has since been partly spent). Loading it must return exactly what was stored.
        val storedCredits = 1_234L
        assertNotEquals("precondition: the stored balance is not the new-game default", 50_000L, storedCredits)

        val existing = WorldState(currentSector = SectorId("beta"), credits = storedCredits)
        newRepository().saveGameState(existing)

        // Fresh repository over the same in-memory DB == an app restart; the reload goes back through SQL.
        val reloaded = newRepository().loadGameState()

        assertEquals("AC#3: the whole saved state must reload unchanged", existing, reloaded)
        assertEquals("AC#3: the saved wallet persists as stored", storedCredits, reloaded?.credits)
        assertNotEquals(
            "AC#3: loading must NOT apply the new-game starting balance to an existing save",
            50_000L,
            reloaded?.credits,
        )
    }

    @Test
    fun `AC3 - a balance saved at exactly 50k still round-trips as a stored value, not a re-seed`() {
        // Even a coincidental 50k balance must come back as the *stored* value through the load path,
        // confirming the load branch returns the save verbatim rather than seeding.
        val existing = WorldState(currentSector = MvpSectorMap.START_SECTOR, credits = 50_000L)
        newRepository().saveGameState(existing)

        val reloaded = newRepository().loadGameState()

        assertEquals(existing, reloaded)
        assertEquals(50_000L, reloaded?.credits)
    }

    @Test
    fun `a fresh database reports no save (the trigger for new-game seeding)`() {
        // Sanity anchor: the New-Game branch in create() is taken precisely when loadGameState() is null.
        assertNull("a brand-new install has no save to load", newRepository().loadGameState())
    }

    private companion object {
        /** Matches the constant declaration and captures its numeric literal (e.g. `50_000L`). */
        private val STARTING_CREDITS_DECL =
            Regex("""const\s+val\s+STARTING_CREDITS\s*:\s*Long\s*=\s*([0-9_]+[Ll]?)""")

        /** A starting balance gated behind any debug/dev build flag would violate AC#2. */
        private val DEBUG_GATE = Regex("""(BuildConfig|DEBUG|isDebug|devMode|debugMode)""")

        /** Relative paths to the production source, tried at each ancestor of the test working dir. */
        private val SOURCE_CANDIDATES =
            listOf(
                "src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
                "core/src/main/kotlin/com/orbitalfrontier/app/OrbitalFrontierGame.kt",
            )

        private fun readGameSource(): String {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                for (candidate in SOURCE_CANDIDATES) {
                    val f = File(dir, candidate)
                    if (f.isFile) return f.readText()
                }
                dir = dir.parentFile
            }
            throw AssertionError(
                "Could not locate OrbitalFrontierGame.kt from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC17 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }

    /** A no-op logger; these tests assert on returned state, not on log output. */
    private object NoopLogger : Logger {
        override fun debug(
            tag: String,
            message: String,
        ) = Unit

        override fun info(
            tag: String,
            message: String,
        ) = Unit

        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) = Unit

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) = Unit
    }
}
