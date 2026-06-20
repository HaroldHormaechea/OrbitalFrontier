package com.orbitalfrontier.save

/**
 * Data-driven autosave tuning (UC52 AC#1; docs/design/save-and-persistence.md "Periodic autosave").
 *
 * The periodic in-flight autosave cadence lives here rather than as a hard-coded constant at the call
 * site so it is a single authored knob the design can retune (or a later UC can source from settings).
 * [AutosaveController] reads [periodicIntervalSeconds] for its periodic trigger.
 *
 * Pure value, no engine types — JVM-testable like the rest of `core` (ADR 0001).
 *
 * @property periodicIntervalSeconds seconds of flight between periodic autosaves. The default 20s sits
 *   in UC04's 15–30s range: frequent enough to bound lost progress on a crash, infrequent enough not to
 *   churn the DB during flight. [TUNE]
 */
data class AutosaveParams(
    val periodicIntervalSeconds: Float = DEFAULT_PERIODIC_INTERVAL_SECONDS,
) {
    init {
        require(periodicIntervalSeconds > 0f) {
            "periodicIntervalSeconds must be positive: $periodicIntervalSeconds"
        }
    }

    companion object {
        /** Default periodic autosave interval (seconds); within UC04's documented 15–30s range. [TUNE] */
        const val DEFAULT_PERIODIC_INTERVAL_SECONDS: Float = 20f
    }
}
