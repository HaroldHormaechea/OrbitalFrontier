package com.orbitalfrontier.screen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-anchored guard for the **GL-bound** UC21 acceptance criteria that live in the bootstrap
 * wiring ([com.orbitalfrontier.app.OrbitalFrontierGame]) and the menu view
 * ([com.orbitalfrontier.screen.MainMenuScreen]).
 *
 * Why source-anchored, not behavioural: both [MainMenuScreen] (a libGDX `Stage` / scene2d
 * `Table`/`Label`/`TextButton`) and `OrbitalFrontierGame.create()` (`setScreen`, real screens) need a
 * GL context the headless backend does not provide, so neither is headlessly constructible — and
 * production must not be reshaped just to make it so. The structural contract for the ACs that live in
 * this glue is therefore pinned at the source level, mirroring the repo's existing guards
 * ([Uc20StationGridGuardTest], [Uc19WalkaroundGuardTest]). The *behavioural* coverage of the menu
 * transition table (AC#2/#3/#4) lives in [com.orbitalfrontier.menu.MainMenuModelTest], and of the
 * save wipe in [com.orbitalfrontier.save.Uc21ClearSaveTest].
 *
 * ACs covered here:
 *  - **AC#1/#5** — the menu is the FIRST screen shown in `create()` (every launch), and gameplay is
 *    entered only via `enterGame(...)`, never unconditionally at startup.
 *  - **AC#3/#4 (delegation)** — the screen delegates all transition logic to [MainMenuModel] (no inline
 *    confirm-counting in the view), and Continue is gated by `continueEnabled`.
 *  - **AC#3** — the new-game path wipes the save with `clearSave()` and does so UNCONDITIONALLY (not
 *    gated on `loaded != null`), so a corrupt-but-present save is still cleaned before a fresh game.
 *  - **UC17 no-regression** — a brand-new game still seeds `STARTING_CREDITS`.
 */
class Uc21MainMenuGuardTest {
    // --- AC#1/#5: the menu is the first screen, gameplay entered only via enterGame ---------------

    @Test
    fun `create() shows the main menu first, before any gameplay`() {
        val create = section(GAME_SOURCE, "override fun create(")
        assertTrue(
            "AC#1: create() must build a MainMenuScreen",
            create.contains("MainMenuScreen("),
        )
        assertTrue(
            "AC#1/#5: create() must show the menu (setScreen(menu)) on every launch",
            create.contains("setScreen(menu)"),
        )
        // The play screen must NOT be constructed directly inside create(); it is built in enterGame().
        assertTrue(
            "AC#1: create() must not enter gameplay directly — PlayScreen is built in enterGame(), not create()",
            !create.contains("PlayScreen("),
        )
    }

    @Test
    fun `gameplay is entered through enterGame, routed from the menu callbacks`() {
        assertTrue(
            "AC#1: a dedicated enterGame(...) is the single entry into gameplay",
            GAME_SOURCE.contains("private fun enterGame("),
        )
        assertTrue("AC#3: the Start callback routes through enterGame", GAME_SOURCE.contains("onStartNewGame ="))
        assertTrue("AC#2: the Continue callback routes through enterGame", GAME_SOURCE.contains("onContinue ="))
        // PlayScreen is constructed inside enterGame, not at startup.
        val enterGame = section(GAME_SOURCE, "private fun enterGame(")
        assertTrue("AC#1: PlayScreen is built inside enterGame()", enterGame.contains("PlayScreen("))
    }

    // --- AC#4: Continue availability is driven by whether a save loaded -----------------------------

    @Test
    fun `the menu's Continue is gated on whether a save was loaded`() {
        assertTrue(
            "AC#4: continueEnabled is derived from the loaded save (loaded != null)",
            GAME_SOURCE.contains("continueEnabled = loaded != null"),
        )
    }

    // --- AC#3: the new-game path wipes the save UNCONDITIONALLY -------------------------------------

    @Test
    fun `the new-game path calls clearSave unconditionally`() {
        val onStart = section(GAME_SOURCE, "onStartNewGame =")
        assertTrue(
            "AC#3: starting a new game must wipe the existing save via clearSave()",
            onStart.contains("clearSave()"),
        )
        // The wipe must NOT be guarded on a non-null loaded save — a corrupt-but-present save (loaded
        // == null yet rows on disk) must still be cleaned. So no `if (loaded != null)` around it.
        assertTrue(
            "AC#3: clearSave() must be unconditional (not gated on loaded != null) so a corrupt save is still wiped",
            !Regex("""if\s*\(\s*loaded\s*!=\s*null\s*\)""").containsMatchIn(onStart),
        )
    }

    // --- UC17 no-regression: a fresh game still seeds the starting wallet --------------------------

    @Test
    fun `a brand-new game still seeds STARTING_CREDITS`() {
        val onStart = section(GAME_SOURCE, "onStartNewGame =")
        assertTrue(
            "UC17: a new game must seed the starting credits",
            onStart.contains("credits = STARTING_CREDITS"),
        )
        assertTrue(
            "UC17: STARTING_CREDITS must remain 50_000",
            Regex("""STARTING_CREDITS\s*:\s*Long\s*=\s*50_000L""").containsMatchIn(GAME_SOURCE),
        )
    }

    // --- AC#3/#4: the screen is a thin view over MainMenuModel (no inline confirm logic) -----------

    @Test
    fun `the screen delegates its transition logic to MainMenuModel`() {
        assertTrue(
            "AC#3/#4: the screen must use the pure MainMenuModel",
            SCREEN_SOURCE.contains("import com.orbitalfrontier.menu.MainMenuModel") &&
                SCREEN_SOURCE.contains("MainMenuModel("),
        )
        // The screen forwards taps to the model rather than counting confirmations itself.
        for (call in listOf("model.onStart()", "model.onConfirm()", "model.onCancel()", "model.onContinue()")) {
            assertTrue("AC#3: the screen must forward taps via $call (no inline transition logic)", SCREEN_SOURCE.contains(call))
        }
        // The screen must not implement its own confirm counter / phase enum — that logic is the model's.
        assertTrue(
            "AC#3: confirmation phases must come from MainMenuModel.Phase, not a screen-local copy",
            SCREEN_SOURCE.contains("MainMenuModel.Phase"),
        )
        assertTrue(
            "AC#3: the screen must not declare its own Phase enum (single source of truth is the model)",
            !Regex("""enum\s+class\s+Phase""").containsMatchIn(SCREEN_SOURCE),
        )
    }

    @Test
    fun `Continue is shown disabled and greyed (never hidden) when there is no save`() {
        // AC#4 chose "disabled + greyed", not "hidden": the button is always added, then disabled.
        assertTrue("AC#4: a CONTINUE button is always present", SCREEN_SOURCE.contains("\"CONTINUE\""))
        val buildMenu = section(SCREEN_SOURCE, "private fun buildMenu(")
        assertTrue(
            "AC#4: Continue is disabled (not hidden) when there is no usable save",
            buildMenu.contains("!continueEnabled") && buildMenu.contains("isDisabled = true"),
        )
        assertTrue(
            "AC#4: the disabled Continue is greyed via a tint",
            buildMenu.contains("DISABLED_TINT"),
        )
    }

    @Test
    fun `the screen renders both Start confirmation steps`() {
        // AC#3 is a *double* confirmation: the screen must render two distinct warning phases.
        assertTrue(
            "AC#3: the screen builds the first confirmation phase",
            SCREEN_SOURCE.contains("MainMenuModel.Phase.CONFIRM_FIRST"),
        )
        assertTrue(
            "AC#3: the screen builds the second confirmation phase",
            SCREEN_SOURCE.contains("MainMenuModel.Phase.CONFIRM_SECOND"),
        )
    }

    private companion object {
        private val GAME_SOURCE: String = readSource("app/OrbitalFrontierGame.kt")
        private val SCREEN_SOURCE: String = readSource("screen/MainMenuScreen.kt")

        /**
         * The body of the declaration whose header is [header], from the header to the first line that
         * is a single closing brace at the declaration's indentation. Good enough to scope an assertion
         * to one method/lambda without a full parser (mirrors [Uc20StationGridGuardTest.section]).
         */
        private fun section(
            source: String,
            header: String,
        ): String {
            val start = source.indexOf(header)
            if (start < 0) throw AssertionError("Could not locate '$header' in source")
            val rest = source.substring(start)
            val end = Regex("""\n {4}}""").find(rest)?.range?.last ?: rest.length
            return rest.substring(0, end)
        }

        /**
         * Locates a production source file by walking up from the test working directory and trying the
         * candidate relative path at every ancestor (handles running from the module dir, the repo root,
         * or a git worktree). Refuses to pass silently if the file cannot be found (mirrors the repo's
         * existing source-anchored guards).
         */
        private fun readSource(relative: String): String {
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
            throw AssertionError(
                "Could not locate $relative from user.dir=${System.getProperty("user.dir")}; " +
                    "the UC21 source-anchored guard cannot run (refusing to pass silently).",
            )
        }
    }
}
