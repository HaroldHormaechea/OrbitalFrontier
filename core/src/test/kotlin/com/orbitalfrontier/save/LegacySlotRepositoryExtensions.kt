package com.orbitalfrontier.save

import com.orbitalfrontier.world.WorldState

/**
 * Test-only backward-compatibility shims for the pre-UC38 single-autosave [GameStateRepository] API.
 *
 * Before UC38 the repository exposed `loadGameState()` / `saveGameState(state)` / `hasSave()` /
 * `clearSave()` over a single autosave. UC38 made every call slot-scoped (each takes a [SlotId]). The
 * legacy round-trip / replay / persistence tests assert that the **single autosave** still works — which
 * UC38 preserves as the [SlotId.LEGACY] slot (slot 0, the slot every pre-UC38 save migrates into, UC38
 * AC#3). These overloads default those calls to [SlotId.LEGACY], so the legacy tests keep exercising the
 * exact single-autosave behaviour without threading `SlotId.LEGACY` through every historical call site.
 *
 * New, UC38-specific tests (slot isolation, per-slot round-trip, the slot list, rename / delete, the
 * active-slot pointer) call the slot-taking members directly and never go through these shims.
 */
internal fun GameStateRepository.loadGameState(): WorldState? = loadGameState(SlotId.LEGACY)

internal fun GameStateRepository.saveGameState(state: WorldState) = saveGameState(SlotId.LEGACY, state)

internal fun GameStateRepository.hasSave(): Boolean = hasSave(SlotId.LEGACY)

internal fun GameStateRepository.clearSave() = clearSave(SlotId.LEGACY)
