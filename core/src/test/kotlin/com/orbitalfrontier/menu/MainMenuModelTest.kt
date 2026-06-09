package com.orbitalfrontier.menu

import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.ship.singleShipFleet
import com.orbitalfrontier.world.SectorId
import com.orbitalfrontier.world.WorldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure, JVM-only behavioural tests for the UC21 main-menu state machine [MainMenuModel] (ADR 0001 —
 * the transition table is deliberately engine/UI-free so it is unit-testable without a GL context).
 *
 * These are the *real* coverage for UC21's logic ACs; the GL-bound view wiring (AC#1 menu-on-launch,
 * the live visual flow) is pinned structurally in [com.orbitalfrontier.screen.Uc21MainMenuGuardTest]
 * and confirmed by a separate emulator pass.
 *
 * AC map:
 *  - AC#2 — Continue resumes the existing save iff one is usable.
 *  - AC#3 — Start over a usable save requires BOTH confirmations; cancelling either backs out to the
 *    menu with the save intact (no BEGIN_NEW_GAME emitted).
 *  - AC#4 — with no usable save, Start begins immediately with no warnings and Continue is unavailable;
 *    a corrupt save (folded to null via [MainMenuModel.fromLoadedState]) is treated as "no usable save".
 */
class MainMenuModelTest {
    // --- AC#4: no usable save -> Start is immediate (no warning phases), Continue unavailable -------

    @Test
    fun `with no save, Start begins a new game immediately with no warning phases`() {
        val model = MainMenuModel(saveUsable = false)

        val action = model.onStart()

        assertEquals("AC#4: Start with no save commits straight to a new game", MainMenuModel.MenuAction.BEGIN_NEW_GAME, action)
        assertEquals("AC#4: no confirmation phase is entered when there is nothing to lose", MainMenuModel.Phase.MENU, model.phase)
    }

    @Test
    fun `with no save, Continue is unavailable and is a no-op`() {
        val model = MainMenuModel(saveUsable = false)

        assertFalse("AC#4: Continue is disabled when there is no usable save", model.continueEnabled)
        assertEquals("AC#4: a stray Continue tap is a safe no-op without a save", MainMenuModel.MenuAction.NONE, model.onContinue())
    }

    // --- AC#3: a usable save -> Start double-confirms; BOTH confirmations are required --------------

    @Test
    fun `with a save, Start enters the first confirmation rather than starting`() {
        val model = MainMenuModel(saveUsable = true)

        val action = model.onStart()

        assertEquals("AC#3: Start over a save must not begin a new game yet", MainMenuModel.MenuAction.NONE, action)
        assertEquals("AC#3: Start over a save enters the first warning", MainMenuModel.Phase.CONFIRM_FIRST, model.phase)
    }

    @Test
    fun `with a save, both confirmations are required before a new game begins`() {
        val model = MainMenuModel(saveUsable = true)

        assertEquals(MainMenuModel.MenuAction.NONE, model.onStart())
        assertEquals("first warning shown", MainMenuModel.Phase.CONFIRM_FIRST, model.phase)

        // First confirm only advances to the SECOND warning — it must NOT commit yet (AC#3).
        assertEquals("AC#3: one confirmation is not enough", MainMenuModel.MenuAction.NONE, model.onConfirm())
        assertEquals("second warning shown", MainMenuModel.Phase.CONFIRM_SECOND, model.phase)

        // Second confirm commits to wiping the save and starting over.
        assertEquals("AC#3: both confirmations commit", MainMenuModel.MenuAction.BEGIN_NEW_GAME, model.onConfirm())
    }

    // --- AC#3: cancelling either confirmation backs out to the menu, save intact (no new game) ------

    @Test
    fun `cancelling the first confirmation returns to the menu without starting`() {
        val model = MainMenuModel(saveUsable = true)
        model.onStart()

        val action = model.onCancel()

        assertEquals("AC#3: cancel never begins a new game", MainMenuModel.MenuAction.NONE, action)
        assertEquals("AC#3: cancel from the first warning returns to the menu", MainMenuModel.Phase.MENU, model.phase)
    }

    @Test
    fun `cancelling the second confirmation returns to the menu without starting`() {
        val model = MainMenuModel(saveUsable = true)
        model.onStart()
        model.onConfirm() // now on the second warning

        val action = model.onCancel()

        assertEquals("AC#3: cancel at the second warning never begins a new game", MainMenuModel.MenuAction.NONE, action)
        assertEquals("AC#3: cancel from the second warning backs all the way out", MainMenuModel.Phase.MENU, model.phase)
    }

    @Test
    fun `after cancelling, Start can begin the confirmation flow again from the top`() {
        val model = MainMenuModel(saveUsable = true)
        model.onStart()
        model.onConfirm()
        model.onCancel()

        // The save is intact, so Start once more re-enters the first warning (the model is reusable).
        assertEquals(MainMenuModel.MenuAction.NONE, model.onStart())
        assertEquals(MainMenuModel.Phase.CONFIRM_FIRST, model.phase)
    }

    // --- AC#2: Continue resumes iff a usable save exists -------------------------------------------

    @Test
    fun `with a save, Continue resumes the saved game`() {
        val model = MainMenuModel(saveUsable = true)

        assertTrue("AC#2/#4: Continue is enabled when a usable save exists", model.continueEnabled)
        assertEquals("AC#2: Continue resumes the saved game", MainMenuModel.MenuAction.RESUME_SAVED_GAME, model.onContinue())
        assertEquals("AC#2: Continue does not disturb the menu phase", MainMenuModel.Phase.MENU, model.phase)
    }

    // --- AC#4: continueEnabled mirrors saveUsable exactly -------------------------------------------

    @Test
    fun `continueEnabled mirrors saveUsable`() {
        assertTrue("a usable save enables Continue", MainMenuModel(saveUsable = true).continueEnabled)
        assertFalse("no usable save disables Continue", MainMenuModel(saveUsable = false).continueEnabled)
    }

    // --- AC#4 (corrupt save pitfall): fromLoadedState folds null/corrupt -> "no usable save" --------

    @Test
    fun `fromLoadedState(null) yields an unusable-save model`() {
        // A null loaded state is "no save, or a corrupt save the repository degraded to null".
        val model = MainMenuModel.fromLoadedState(null)

        assertFalse("AC#4: a null/corrupt save means Continue is unavailable", model.continueEnabled)
        assertEquals("AC#4: Start with no usable save is immediate", MainMenuModel.MenuAction.BEGIN_NEW_GAME, model.onStart())
    }

    @Test
    fun `fromLoadedState(state) yields a usable-save model that double-confirms Start`() {
        val loaded =
            WorldState(
                currentSector = SectorId("beta"),
                fleet = singleShipFleet(),
            )

        val model = MainMenuModel.fromLoadedState(loaded)

        assertTrue("a non-null loaded save is usable -> Continue enabled", model.continueEnabled)
        assertEquals("Continue resumes the loaded save", MainMenuModel.MenuAction.RESUME_SAVED_GAME, model.onContinue())
        // And Start over a usable save still requires the double confirmation (AC#3).
        assertEquals(MainMenuModel.MenuAction.NONE, model.onStart())
        assertEquals(MainMenuModel.Phase.CONFIRM_FIRST, model.phase)
    }

    @Test
    fun `fromLoadedState ignores world contents and keys only on null-ness`() {
        // The position/contents of the loaded world are irrelevant to the menu — only "is there a save".
        val loaded = WorldState(fleet = singleShipFleet(kinematics = com.orbitalfrontier.ship.ShipKinematics(position = Vec2(9f, 9f))))
        assertTrue(MainMenuModel.fromLoadedState(loaded).continueEnabled)
    }

    // --- Defensive no-ops: confirm / cancel while on the menu do nothing ---------------------------

    @Test
    fun `confirm while on the menu is a no-op`() {
        val model = MainMenuModel(saveUsable = true)
        assertEquals("there is nothing to confirm on the menu", MainMenuModel.MenuAction.NONE, model.onConfirm())
        assertEquals(MainMenuModel.Phase.MENU, model.phase)
    }

    @Test
    fun `cancel while on the menu is a harmless no-op`() {
        val model = MainMenuModel(saveUsable = true)
        assertEquals(MainMenuModel.MenuAction.NONE, model.onCancel())
        assertEquals(MainMenuModel.Phase.MENU, model.phase)
    }
}
