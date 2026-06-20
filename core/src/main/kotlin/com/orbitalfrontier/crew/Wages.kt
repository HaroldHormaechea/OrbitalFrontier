package com.orbitalfrontier.crew

/**
 * Tunables for the periodic crew **wage / upkeep** drain (UC50 AC#2) — the economy sink ADR 0010
 * deferred. A pure, immutable value pinned per playthrough (the replay harness snapshots it, like every
 * other `*Params`) so a later re-tune can never silently invalidate an old recorded run.
 *
 * **Default rate 0 is the zero-regen lever.** With [creditsPerCrewPerPeriod] = 0 the owed wage is always
 * 0, so [Wages.resolve] is a same-value no-op (`changed = false`, credits unchanged) on every tick — and
 * because credits are part of the deterministic record/replay equality, every existing fixture stays
 * **byte-identical**. Production wires a non-zero rate; only the new UC50 wage fixture exercises a drain.
 *
 * @property creditsPerCrewPerPeriod credits drained per crew member each wage period (>= 0; 0 = off).
 * @property periodTicks how many simulation ticks make one wage period — the drain fires once per this
 *   many ticks (> 0). Keyed on the integer tick so the live device path and the replay harness drain in
 *   lockstep (UC50, challenger #2). [TUNE]
 */
data class WageParams(
    val creditsPerCrewPerPeriod: Long = 0L,
    val periodTicks: Int = DEFAULT_PERIOD_TICKS,
) {
    init {
        require(creditsPerCrewPerPeriod >= 0L) { "creditsPerCrewPerPeriod must be >= 0: $creditsPerCrewPerPeriod" }
        require(periodTicks > 0) { "periodTicks must be positive: $periodTicks" }
    }

    /**
     * True on a tick at which a wage period closes and the drain should fire. Tick 0 never pays (a fresh
     * game / the first replay tick must not drain), so the first charge lands at [periodTicks].
     */
    fun isWageTick(tick: Int): Boolean = tick > 0 && tick % periodTicks == 0

    companion object {
        /** Default wage period in ticks (≈ a steady upkeep cadence). [TUNE] */
        const val DEFAULT_PERIOD_TICKS: Int = 600
    }
}

/**
 * The outcome of a single [Wages.resolve] — the new credit balance, how much was actually [paid], how
 * much went [unpaid] (the shortfall when the wallet couldn't cover the bill), and whether anything
 * changed. A small explicit result type (coding-guidelines § error-handling): "can't fully pay" is a
 * normal, defined outcome (clamp-at-zero, no debt / no desertion in the MVP — ADR 0038), not an error.
 */
data class WageResult(
    /** Credit balance after the drain (never negative; unchanged on a no-op). */
    val credits: Long,
    /** Credits actually drained this period — `min(owed, credits)`. */
    val paid: Long,
    /** The unpaid shortfall — `owed - paid` (> 0 only when the wallet couldn't cover the bill). */
    val unpaid: Long,
    /** True when credits actually moved (a real drain); false on a no-op (rate 0 / no crew / empty wallet). */
    val changed: Boolean,
)

/**
 * Pure, deterministic crew wages (UC50 AC#2) — the crew analogue of [Hiring]. A side-effect-free
 * function of `(credits, totalCrew, params)`: identical inputs always yield an identical result, with no
 * I/O and no engine types, so it slots into the deterministic simulation/replay path and is fully
 * JVM-unit-testable. It does **not** mutate anything — the caller (the play screen on device, the
 * simulation on the JVM) applies the [WageResult] and decides the period cadence ([WageParams.isWageTick]).
 *
 * **Unpaid rule (MVP, ADR 0038):** the bill is `creditsPerCrewPerPeriod × totalCrew`; the player pays
 * `min(owed, credits)`, the balance clamps at 0 (preserving the credits ≥ 0 invariant), and any shortfall
 * is reported via [WageResult.unpaid] so the caller can surface a WARNING. There is **no desertion and no
 * accruing debt** — an unaffordable period simply drains the wallet to 0 and the shortfall is forgotten.
 */
object Wages {
    /**
     * Resolve one wage period's drain against the player's [credits] and fleet-wide [totalCrew]. Returns
     * the inputs **unchanged** ([changed] = false, [paid] = 0) when the rate is 0, the crew is 0, or the
     * wallet is already empty — so a default-rate (0) resolve is a same-value no-op and stays
     * byte-identical. Otherwise it pays `min(owed, credits)`, clamps the balance at 0, and reports any
     * shortfall in [WageResult.unpaid].
     */
    fun resolve(
        credits: Long,
        totalCrew: Int,
        params: WageParams,
    ): WageResult {
        val owed = params.creditsPerCrewPerPeriod * totalCrew.coerceAtLeast(0).toLong()
        if (owed <= 0L) return WageResult(credits, paid = 0L, unpaid = 0L, changed = false)
        val available = credits.coerceAtLeast(0L)
        val paid = minOf(owed, available)
        val unpaid = owed - paid
        val newCredits = (credits - paid).coerceAtLeast(0L)
        return WageResult(newCredits, paid = paid, unpaid = unpaid, changed = paid > 0L)
    }
}
