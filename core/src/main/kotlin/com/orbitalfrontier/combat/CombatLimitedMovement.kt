package com.orbitalfrontier.combat

import com.orbitalfrontier.ship.ShipMovementParams

/**
 * Composes **engine damage** with movement tuning (UC13 AC#3) the same way
 * [com.orbitalfrontier.ship.FuelLimitedMovement] composes fuel: it derives an effective
 * [ShipMovementParams] whose speed caps are scaled by the engine section's remaining HP, then the
 * unchanged movement model runs against those params. The model itself stays combat-agnostic
 * (SRP / Open-Closed); device and replay both call [effectiveParams], so live and replayed
 * engine-limited motion are identical.
 *
 * **Byte-identical when undamaged:** with a pristine engine (current HP == max HP) the factor is exactly
 * `1.0f` and this returns [base] **unchanged** (same instance) — so a ship that has taken no engine
 * damage moves exactly as before, and only an engine hit alters motion (mirroring `FuelLimitedMovement`
 * at full fuel). A fully-wrecked engine still crawls at [CombatParams.minEngineSpeedFactor] (never
 * stranded). Engine hits **stack on top of** fuel limiting — the screen applies fuel scaling, then this.
 */
object CombatLimitedMovement {
    /**
     * [base] with [ShipMovementParams.maxSpeed] and [ShipMovementParams.maxReverseSpeed] scaled by the
     * engine-damage factor `minFactor + (1 - minFactor) * engineHpFraction`. Returns [base] itself when
     * the engine is pristine (factor `1.0f`) or has no modelled HP ([maxEngineHp] ≤ 0 ⇒ no scaling).
     */
    fun effectiveParams(
        base: ShipMovementParams,
        sectionDamage: SectionDamage,
        maxEngineHp: Int,
        params: CombatParams,
    ): ShipMovementParams {
        if (maxEngineHp <= 0) return base
        val currentHp = SectionDamages.currentHp(sectionDamage, ShipSection.ENGINE, maxEngineHp)
        val hpFraction = currentHp.toFloat() / maxEngineHp
        val factor = params.minEngineSpeedFactor + (1f - params.minEngineSpeedFactor) * hpFraction
        if (factor == 1.0f) return base
        return base.copy(
            maxSpeed = base.maxSpeed * factor,
            maxReverseSpeed = base.maxReverseSpeed * factor,
        )
    }
}
