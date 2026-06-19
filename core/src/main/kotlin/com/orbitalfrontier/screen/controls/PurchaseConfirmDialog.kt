package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.orbitalfrontier.economy.ConfirmationDetails

/**
 * The reusable confirm-purchase modal shared by all five economy flows (UC40 AC#1) — one component for
 * Trade, Outfit, Shipyard, Hire, and refuel, so a significant spend is confirmed identically everywhere.
 *
 * Intentionally a **dumb renderer with no decision logic** (SRP): the pure
 * [com.orbitalfrontier.economy.PurchaseGate] already decided that a confirmation is needed and built the
 * [ConfirmationDetails]; this only draws them (item, cost, resulting balance) with CONFIRM / CANCEL and
 * routes the tap to the caller's callbacks. It owns no game/world state and never mutates the wallet.
 *
 * **Modal blocking.** [show] mounts a full-screen, input-capturing backdrop onto the host screen's existing
 * [Stage] with the confirmation panel centred on top, so taps on the desk behind it are swallowed while the
 * dialog is up (a single confirm gate per user tap, UC40 pitfall). Either button dismisses the dialog before
 * firing its callback, so the modal is always torn down exactly once. Built from the shared [OrbitalUiSkin]
 * (panel + title/label styles + button style), so it matches the design system (UC29) with no new chrome.
 *
 * The dialog adds/removes only Scene2D actors it created on the host stage — it allocates no GL resources of
 * its own (the skin is borrowed), so there is nothing to dispose; leaving a screen disposes the stage.
 */
class PurchaseConfirmDialog(
    private val skin: OrbitalUiSkin,
) {
    /** The mounted backdrop actor while the dialog is showing, or null when dismissed. */
    private var backdrop: Table? = null

    /** Whether the dialog is currently mounted (a confirmation is awaiting the player). */
    fun isShowing(): Boolean = backdrop != null

    /**
     * Mount the modal over [stage] for [details], wiring [onConfirm] to the CONFIRM button and [onCancel] to
     * CANCEL (and to a tap on the dimmed backdrop outside the panel — an intuitive cancel). Re-mounting while
     * one is already up first dismisses the previous, so at most one dialog is ever live.
     */
    fun show(
        stage: Stage,
        details: ConfirmationDetails,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ) {
        dismiss()

        val overlay = Table()
        overlay.setFillParent(true)
        // Capture all input across the whole screen so the desk behind the modal is inert while it is up.
        overlay.touchable = Touchable.enabled
        // A tap on the backdrop (outside the panel) cancels — the panel below stops taps from reaching here.
        overlay.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    dismiss()
                    onCancel()
                }
            },
        )

        val panel = Table()
        panel.background = skin.panel
        panel.pad(PANEL_PAD)
        // Swallow taps on the panel itself so they don't fall through to the backdrop's cancel listener.
        panel.touchable = Touchable.enabled

        panel.add(Label("CONFIRM PURCHASE", skin.titleLabelStyle)).padBottom(TITLE_GAP).row()
        panel.add(Label(details.item, skin.labelStyle)).padBottom(ROW_GAP).row()
        panel.add(Label("COST: ${details.cost} CR", skin.labelStyle)).padBottom(ROW_GAP).row()
        panel.add(Label("BALANCE AFTER: ${details.resultingBalance} CR", skin.labelStyle)).padBottom(BUTTON_GAP).row()

        val buttons = Table()
        val confirmButton = TextButton("CONFIRM", skin.settingsButtonStyle)
        confirmButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    dismiss()
                    onConfirm()
                }
            },
        )
        val cancelButton = TextButton("CANCEL", skin.settingsButtonStyle)
        cancelButton.addListener(
            object : ClickListener() {
                override fun clicked(
                    event: InputEvent?,
                    x: Float,
                    y: Float,
                ) {
                    dismiss()
                    onCancel()
                }
            },
        )
        buttons.add(confirmButton).size(BUTTON_WIDTH, BUTTON_HEIGHT).padRight(BUTTON_GAP)
        buttons.add(cancelButton).size(BUTTON_WIDTH, BUTTON_HEIGHT)
        panel.add(buttons).row()

        overlay.add(panel)
        stage.addActor(overlay)
        backdrop = overlay
    }

    /** Remove the mounted modal from its stage (idempotent — a no-op when nothing is showing). */
    fun dismiss() {
        backdrop?.remove()
        backdrop = null
    }

    private companion object {
        const val PANEL_PAD = 32f
        const val TITLE_GAP = 24f
        const val ROW_GAP = 12f
        const val BUTTON_GAP = 24f
        const val BUTTON_WIDTH = 160f
        const val BUTTON_HEIGHT = 56f
    }
}
