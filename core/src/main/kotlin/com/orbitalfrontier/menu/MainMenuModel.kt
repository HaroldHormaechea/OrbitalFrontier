package com.orbitalfrontier.menu

import com.orbitalfrontier.world.WorldState

/**
 * Pure, libGDX-free state machine for the main/title menu (UC21).
 *
 * The menu shows **Start** (begin a new game) and **Continue** (resume the existing save). Because
 * Start discards an existing save, when a usable save exists the player must pass a **double
 * confirmation** (two sequential warnings) before the save is wiped and a new game begins; cancelling
 * either step returns to the menu with the save intact (UC21 AC#3). With no usable save, Start begins
 * a new game immediately with no warnings and Continue is unavailable (UC21 AC#4).
 *
 * Kept deliberately free of engine/UI types so the whole transition table is JVM-unit-testable (ADR
 * 0001): the [MainMenuScreen] view drives it by calling [onStart] / [onConfirm] / [onCancel] /
 * [onContinue] and acts on the returned [MenuAction]; this class never renders or touches the world —
 * the actual new-game seed / save wipe / resume happen in the bootstrap wiring (SRP).
 *
 * @param saveUsable whether a usable save is present (a corrupt/partial save is treated as "no usable
 *   save" by the caller — see [fromLoadedState] — so Continue is disabled and Start needs no warnings,
 *   matching the UC21 corrupt-save pitfall).
 * @param requireNewGameConfirm whether **Start** must pass the double confirmation before wiping. Defaults
 *   to [saveUsable] (warn iff a usable save would be lost). UC52 sets it true *independently* when a save
 *   is present on disk but could not be opened (a NEWER schema, or unreadable/corrupt): Continue stays
 *   disabled ([saveUsable] false) yet New Game still double-confirms, so a newer save is never silently
 *   clobbered — only an explicit, confirmed New Game discards it.
 */
class MainMenuModel(
    val saveUsable: Boolean,
    private val requireNewGameConfirm: Boolean = saveUsable,
) {
    /** Where the menu currently is: the menu itself, or one of the two Start confirmation steps. */
    enum class Phase { MENU, CONFIRM_FIRST, CONFIRM_SECOND }

    /** The transition each input may yield: nothing to do, or commit to a new / resumed game. */
    enum class MenuAction { NONE, BEGIN_NEW_GAME, RESUME_SAVED_GAME }

    /** Whether Continue should be offered (shown enabled) — true iff a usable save exists (AC#4). */
    val continueEnabled: Boolean = saveUsable

    /** The current menu phase; starts on the menu and advances through the Start confirmations. */
    var phase: Phase = Phase.MENU
        private set

    /**
     * Player tapped **Start**. With no usable save, begins a new game at once (no warnings, AC#4);
     * otherwise enters the first of two warning confirmations (AC#3) and waits for [onConfirm].
     */
    fun onStart(): MenuAction =
        if (!requireNewGameConfirm) {
            // Nothing that needs protecting -> straight to a new game; the phase stays on the menu.
            MenuAction.BEGIN_NEW_GAME
        } else {
            phase = Phase.CONFIRM_FIRST
            MenuAction.NONE
        }

    /**
     * Player confirmed the current warning. The first confirmation advances to the second; confirming
     * the second commits to wiping the save and starting over (AC#3). A confirm while on the menu is a
     * no-op (there is nothing to confirm there).
     */
    fun onConfirm(): MenuAction =
        when (phase) {
            Phase.CONFIRM_FIRST -> {
                phase = Phase.CONFIRM_SECOND
                MenuAction.NONE
            }
            Phase.CONFIRM_SECOND -> {
                // Both warnings accepted: commit. The phase is reset so the model is reusable, though
                // the view navigates away into the game on this action.
                phase = Phase.MENU
                MenuAction.BEGIN_NEW_GAME
            }
            Phase.MENU -> MenuAction.NONE
        }

    /**
     * Player cancelled a warning. Returns to the menu with the save **intact** (AC#3) — cancelling at
     * either confirmation step backs all the way out. A cancel while already on the menu is a no-op.
     */
    fun onCancel(): MenuAction {
        phase = Phase.MENU
        return MenuAction.NONE
    }

    /**
     * Player tapped **Continue**. Resumes the existing save iff one is usable (AC#2); otherwise a
     * no-op (Continue is disabled in that case, but guard the transition regardless).
     */
    fun onContinue(): MenuAction = if (saveUsable) MenuAction.RESUME_SAVED_GAME else MenuAction.NONE

    companion object {
        /**
         * Build a model from the loaded save: a non-null [WorldState] means a usable save (Continue
         * enabled, Start double-confirms); a null one — no save, or a corrupt/unreadable one the
         * repository already degraded to null — means no usable save (Continue disabled, Start is
         * immediate). Folding "corrupt" into "null" here matches the UC21 corrupt-save pitfall.
         */
        fun fromLoadedState(loaded: WorldState?): MainMenuModel = MainMenuModel(saveUsable = loaded != null)
    }
}
