package com.orbitalfrontier.screen.controls

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Touchpad
import com.orbitalfrontier.common.Vec2
import com.orbitalfrontier.render.Palette
import com.orbitalfrontier.settings.JoystickTuning
import com.orbitalfrontier.ship.MovementInput
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Left virtual joystick (AC#2): a Scene2D [Touchpad] adapted to the pure [MovementInput] the
 * movement model consumes. The knob deflection becomes the target direction + magnitude; when
 * the stick is not touched it reports [MovementInput.NONE] so the model applies drift decay.
 */
class MovementJoystick(skin: OrbitalUiSkin) {
    val actor: Touchpad = Touchpad(TOUCHPAD_DEADZONE, skin.touchpadStyle)

    /**
     * The player's joystick tuning (UC37), applied to the raw magnitude in [currentInput] — the SINGLE
     * point this preference takes effect. Defaults to [JoystickTuning.DEFAULT] (neutral sensitivity,
     * deadzone at the model floor), so an un-wired joystick behaves exactly as before. Settable so the
     * play screen can update it live when the player changes it in settings; the pure movement model
     * and the record/replay harness never see this — they consume the resulting [MovementInput] only,
     * so determinism is unaffected (ADR 0006).
     */
    var tuning: JoystickTuning = JoystickTuning.DEFAULT

    /**
     * Tint the stick to draw the player's eye to it during the tutorial's STEER step (UC36 AC#2) —
     * **visual only**: it changes the draw colour and never touches [currentInput], so input stays
     * byte-identical whether highlighted or not (AC#4). Idempotent (Scene2D just stores the colour).
     */
    fun setHighlighted(highlighted: Boolean) {
        actor.color = if (highlighted) Palette.ACCENT else Color.WHITE
    }

    /**
     * Read this frame's intent from the touchpad, applying the player's [tuning] (UC37) to the raw
     * deflection magnitude — gate below the tuned deadzone, then scale by sensitivity (capped at 1).
     * This is the ONLY place the tuning is applied; the *direction* is untouched (tuning shapes how
     * hard, not which way), and a tuned magnitude of `0f` reports [MovementInput.NONE] so the model
     * applies drift decay exactly as for an untouched stick.
     */
    fun currentInput(): MovementInput {
        if (!actor.isTouched) return MovementInput.NONE
        val x = actor.knobPercentX
        val y = actor.knobPercentY
        val rawMagnitude = min(1f, sqrt(x * x + y * y))
        val magnitude = tuning.apply(rawMagnitude)
        if (magnitude <= 0f) return MovementInput.NONE
        return MovementInput(targetDirection = Vec2(x, y), magnitude = magnitude, released = false)
    }

    private companion object {
        const val TOUCHPAD_DEADZONE = 10f
    }
}
