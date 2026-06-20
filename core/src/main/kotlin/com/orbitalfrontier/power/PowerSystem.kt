package com.orbitalfrontier.power

/**
 * The ship's power-budget systems and their brownout shedding policy (UC49; docs/adr/0037,
 * docs/design/power-and-energy.md).
 *
 * When demand exceeds reactor output the [Brownout] resolver sheds load **lowest [shedPriority]
 * first**, but never a [isProtected] system — so the ship can never be bricked (no-deadlock by
 * construction, UC49 AC#4 / pitfall):
 *  - [HELM] is **protected**: it stands for the always-on hotel/base load plus thrust/helm power, so
 *    the ship always retains the power to fly even if that protected set alone exceeds output.
 *  - [SCANNER] then [WEAPONS] are sheddable, shed in that ascending-priority order (scanner first).
 *
 * No engine types — JVM-testable like the rest of `power/`.
 */
enum class PowerSystem(
    /** Ascending shed order: a lower value is shed earlier. Protected systems are never shed. */
    val shedPriority: Int,
    /** Whether this system is exempt from shedding (the no-deadlock floor). */
    val isProtected: Boolean,
) {
    /** Helm/thrust + always-on base hotel load. Never shed (the ship keeps the power to fly). */
    HELM(shedPriority = 0, isProtected = true),

    /** Offensive systems. Sheddable; shed AFTER the scanner (higher priority). */
    WEAPONS(shedPriority = 2, isProtected = false),

    /** Sensor/active-scan systems. Sheddable; shed FIRST (lowest priority). */
    SCANNER(shedPriority = 1, isProtected = false),
}
