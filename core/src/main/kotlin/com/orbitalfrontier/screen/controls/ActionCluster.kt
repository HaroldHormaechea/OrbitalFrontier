package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.orbitalfrontier.settings.ScreenSide

/**
 * Bottom-corner semicircular **action arc** (UC26): every on-screen player action laid out as circular
 * icon-plus-label buttons sweeping from a bottom corner, following the natural arc of a thumb. The
 * always-present **FIRE** control anchors a fixed endpoint of the arc; the contextual actions (DOCK,
 * MINE, SCAN, RADIO accept, and the debug point-and-go toggle) appear only while available and the arc
 * reflows to stay evenly spaced as that set changes.
 *
 * Geometry is delegated to the pure [ActionArcLayout]; this widget only owns the Scene2D actors and the
 * availability/press wiring. The backing [actor] is a plain [Group] sized to the fixed arc footprint
 * ([LAYOUT_WIDTH] × [LAYOUT_HEIGHT]) regardless of how many buttons are visible, so the corner anchor and
 * the minimap's bottom reservation (UC22) stay stable. There is deliberately **no** hittable container
 * background, so FIRE composes with the movement joystick under multi-touch (UC26 AC#4) exactly as the
 * individual buttons did.
 *
 * FIRE and MINE are **held** actions — the screen polls [isFirePressed]/[isMinePressed] each frame. DOCK,
 * SCAN, RADIO-accept and the point-and-go toggle are **edge-triggered** — a tap invokes the matching
 * callback ([onDock]/[onScan]/[onAcceptRadio]/[onPointAndGoToggle]), which the screen turns into the same
 * one-shot, deterministically-consumed intent the retired context panels produced (UC26 AC#8).
 */
class ActionCluster(skin: OrbitalUiSkin) {
    /** The set of player actions that can live on the arc, in arc priority order after FIRE. */
    enum class Action { FIRE, DOCK, MINE, SCAN, RADIO, POINT_AND_GO }

    val actor: Group = Group()

    /** Footprint, exposed for the corner placement + minimap reservation (mirrors the old LAYOUT_* consts). */
    val prefWidth: Float get() = LAYOUT_WIDTH
    val prefHeight: Float get() = LAYOUT_HEIGHT

    // Edge-triggered callbacks — defaulted to no-ops; the screen wires them to its one-shot intents.
    var onDock: () -> Unit = {}
    var onScan: () -> Unit = {}
    var onAcceptRadio: () -> Unit = {}
    var onPointAndGoToggle: () -> Unit = {}

    private val buttons: Map<Action, ImageButton>
    private val labels: Map<Action, Label>

    // Availability of each action this frame. FIRE is always available (UC26 AC#3); the rest start hidden.
    private val available =
        Action.entries.associateWith { it == Action.FIRE }.toMutableMap()

    private var side: ScreenSide = ScreenSide.RIGHT
    private var dirty = true

    init {
        actor.setSize(LAYOUT_WIDTH, LAYOUT_HEIGHT)
        actor.isTransform = false

        buttons =
            Action.entries.associateWith { action ->
                ImageButton(skin.actionGlyphStyle(glyphFor(action)))
            }
        labels =
            Action.entries.associateWith { action ->
                Label(defaultLabel(action), skin.labelStyle).apply {
                    setAlignment(Align.center)
                    // The label is decorative — it must never steal a touch from the button under it.
                    touchable = Touchable.disabled
                }
            }

        // Edge-triggered taps fire their callback; FIRE/MINE are read as held state and get no listener.
        buttons.getValue(Action.DOCK).addListener(clickListener { onDock() })
        buttons.getValue(Action.SCAN).addListener(clickListener { onScan() })
        buttons.getValue(Action.RADIO).addListener(clickListener { onAcceptRadio() })
        buttons.getValue(Action.POINT_AND_GO).addListener(clickListener { onPointAndGoToggle() })

        // Add buttons then labels so every label draws above its circle.
        Action.entries.forEach { actor.addActor(buttons.getValue(it)) }
        Action.entries.forEach { actor.addActor(labels.getValue(it)) }

        relayout()
    }

    /** True while FIRE is held this frame (UC26 AC#3) — drives the combat tick's fire intent. */
    fun isFirePressed(): Boolean = buttons.getValue(Action.FIRE).isPressed

    /** True while MINE is held this frame (UC06 held-action semantics, preserved on the arc). */
    fun isMinePressed(): Boolean = buttons.getValue(Action.MINE).isPressed

    /**
     * Mark whether a contextual [action] is currently available; the button shows on the arc only while
     * available (UC26 AC#6). FIRE is always available and ignores this. Cheap and allocation-free on the
     * common no-change path — only flips the [dirty] flag so the next [relayout] reflows.
     */
    fun setActionAvailable(
        action: Action,
        isAvailable: Boolean,
    ) {
        if (action == Action.FIRE) return
        if (available[action] != isAvailable) {
            available[action] = isAvailable
            dirty = true
        }
    }

    /** Set the corner the arc pivots on (driven by the handedness setting). Reflows on a change. */
    fun setSide(side: ScreenSide) {
        if (this.side != side) {
            this.side = side
            dirty = true
        }
    }

    /** Reflect the debug point-and-go armed state on its button label (mirrors the old ON/OFF text). */
    fun setPointAndGoArmed(armed: Boolean) {
        labels.getValue(Action.POINT_AND_GO).setText(if (armed) POINT_AND_GO_ON_LABEL else POINT_AND_GO_OFF_LABEL)
    }

    /**
     * Reposition the currently-available buttons along the arc and hide the rest (UC26 AC#1/#7). The
     * visible set is FIRE followed by the available contextual actions in [Action] order; [ActionArcLayout]
     * places FIRE at the pinned endpoint and spreads the rest evenly. Idempotent and dirty-gated, so the
     * screen can call it every frame for free when nothing changed.
     */
    fun relayout() {
        if (!dirty) return
        dirty = false

        val visible = Action.entries.filter { available[it] == true }
        val arc =
            ActionArcLayout.compute(
                side = side,
                viewportWidth = LAYOUT_WIDTH,
                viewportHeight = LAYOUT_HEIGHT,
                margin = 0f,
                radius = RADIUS,
                buttonDiameter = BUTTON_DIAMETER,
                spanStartDegrees = SPAN_START_DEGREES,
                spanEndDegrees = SPAN_END_DEGREES,
                count = visible.size,
            )

        // Hide everything first, then show + place the visible buttons in arc order.
        Action.entries.forEach { action ->
            val show = available[action] == true
            buttons.getValue(action).isVisible = show
            labels.getValue(action).isVisible = show
        }
        visible.forEachIndexed { i, action ->
            val rect = arc.buttons[i]
            buttons.getValue(action).setBounds(rect.x, rect.y, rect.width, rect.height)
            // Centre the label over its circle (placement chosen for on-device legibility, UC26 AC#2).
            labels.getValue(action).setBounds(rect.x, rect.y, rect.width, rect.height)
        }
    }

    private fun clickListener(onClick: () -> Unit): ClickListener =
        object : ClickListener() {
            override fun clicked(
                event: InputEvent?,
                x: Float,
                y: Float,
            ) {
                onClick()
            }
        }

    private fun glyphFor(action: Action): OrbitalUiSkin.ActionGlyph =
        when (action) {
            Action.FIRE -> OrbitalUiSkin.ActionGlyph.FIRE
            Action.DOCK -> OrbitalUiSkin.ActionGlyph.DOCK
            Action.MINE -> OrbitalUiSkin.ActionGlyph.MINE
            Action.SCAN -> OrbitalUiSkin.ActionGlyph.SCAN
            Action.RADIO -> OrbitalUiSkin.ActionGlyph.RADIO
            Action.POINT_AND_GO -> OrbitalUiSkin.ActionGlyph.POINT_AND_GO
        }

    private fun defaultLabel(action: Action): String =
        when (action) {
            Action.FIRE -> "FIRE"
            Action.DOCK -> "DOCK"
            Action.MINE -> "MINE"
            Action.SCAN -> "SCAN"
            Action.RADIO -> "ACCEPT"
            Action.POINT_AND_GO -> POINT_AND_GO_OFF_LABEL
        }

    companion object {
        const val BUTTON_DIAMETER = 64f

        /**
         * Distance from the corner pivot to every button centre. With [BUTTON_DIAMETER] = 64 and a 90°
         * span, six buttons give an adjacent-centre chord of ≈75 world units (~11 of clearance over the
         * diameter), so the set stays non-overlapping and thumb-reachable at the 960×540 floor. The
         * geometry invariants (non-overlap, in-bounds, FIRE pinned) are the contract; this value is tuned
         * to satisfy them and may be retuned as long as they hold.
         */
        const val RADIUS = 240f

        /** FIRE's pinned angle and the far end of the sweep (math convention: 90° = straight up, 180° = left). */
        const val SPAN_START_DEGREES = 90f
        const val SPAN_END_DEGREES = 180f

        /** Minimum centre-to-centre clearance over [BUTTON_DIAMETER] the arc keeps between adjacent buttons. */
        const val MIN_GAP = 8f

        /**
         * The arc footprint in world units — a [RADIUS] + [BUTTON_DIAMETER] square anchored at the corner
         * (304 for the current params). It is independent of how many buttons are visible, so the corner
         * anchor in [com.orbitalfrontier.screen.PlayScreen] and the minimap's bottom reservation (UC22)
         * never drift. Equal on both axes because the sweep is a quarter turn.
         */
        const val LAYOUT_WIDTH = RADIUS + BUTTON_DIAMETER
        const val LAYOUT_HEIGHT = RADIUS + BUTTON_DIAMETER

        private const val POINT_AND_GO_OFF_LABEL = "P&G"
        private const val POINT_AND_GO_ON_LABEL = "P&G ON"
    }
}
