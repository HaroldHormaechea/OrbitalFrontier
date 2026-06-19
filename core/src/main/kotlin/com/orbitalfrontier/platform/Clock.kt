package com.orbitalfrontier.platform

/**
 * Injected wall-clock port (DIP — coding-guidelines § "Concurrency" / "Determinism", UC38).
 *
 * The pure simulation is **time-free** (ADR 0001 / ADR 0006): every model advances by an injected
 * `dt`, never by reading the wall clock, so a record/replay run is deterministic. Save-slot metadata,
 * however, needs a real "last saved" timestamp to show the player when each slot was last written —
 * that is genuinely wall-clock data and the only place it is read.
 *
 * This port keeps that read at the **persistence boundary only**: the `android` launcher backs it with
 * `System.currentTimeMillis()`, JVM tests inject a fixed/advanceable fake, and `core` never sees a
 * platform clock. It is deliberately NOT passed into any [com.orbitalfrontier.world.WorldState] model
 * or sim system — only [com.orbitalfrontier.save.SaveSlotRepository] stamps a slot's save time with it.
 */
interface Clock {
    /** The current wall-clock time as Unix epoch milliseconds. */
    fun nowEpochMillis(): Long
}

/**
 * A [Clock] frozen at a fixed instant. Safe default for headless/non-Android contexts; tests may use
 * it (or an advanceable fake) instead of the real platform clock. Stamping a save with [FIXED_EPOCH]
 * is harmless — the slot UI renders a sentinel for an unknown time the same way it does for `0`.
 */
object FixedClock : Clock {
    /** A stable, non-zero sentinel instant for headless use; never interpreted as "real" save time. */
    const val FIXED_EPOCH: Long = 0L

    override fun nowEpochMillis(): Long = FIXED_EPOCH
}
