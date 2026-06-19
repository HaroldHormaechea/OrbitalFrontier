package com.orbitalfrontier.render

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MotionPreference] (UC39 AC#3) — the global reduced-motion toggle.
 *
 * When [reduced] is true, the multi-layer parallax starfield is drawn as a STATIC field (a full stop, the
 * locked UC39 decision) and any future non-essential animation must consult this flag; gameplay motion is
 * untouched. These pin the default (motion ON / `false` — the prior full-parallax behaviour), the set/reset
 * surface, and the stable single-source read.
 *
 * **Test discipline:** [reduced] is MUTABLE GLOBAL STATE shared by the suite (the UiScaleTest precedent),
 * so every test restores the default via [MotionPreference.reset] in [tearDown] (and re-asserts the
 * baseline in [setUp]); otherwise the StarfieldRenderer-reading suites would become order-dependent.
 */
class MotionPreferenceTest {
    @Before
    fun setUp() {
        MotionPreference.reset()
    }

    @After
    fun tearDown() {
        // MANDATORY: restore the global so other suites see the motion-on default they assume.
        MotionPreference.reset()
    }

    @Test
    fun `the default is motion on`() {
        assertFalse("reduced-motion defaults to OFF (full parallax — the prior behaviour)", MotionPreference.DEFAULT_REDUCED)
        assertFalse("the global starts at the default", MotionPreference.reduced)
    }

    @Test
    fun `set turns reduced-motion on and off`() {
        MotionPreference.set(true)
        assertTrue("the global reflects an enable", MotionPreference.reduced)

        MotionPreference.set(false)
        assertFalse("the global reflects a disable", MotionPreference.reduced)
    }

    @Test
    fun `the latest write wins and reads are stable`() {
        MotionPreference.set(true)
        MotionPreference.set(true)
        assertTrue("repeated enables are idempotent", MotionPreference.reduced)
        // A single source of truth — repeated reads with no intervening write return the same value.
        assertTrue(MotionPreference.reduced == MotionPreference.reduced)
    }

    @Test
    fun `reset restores the motion-on default after a write`() {
        MotionPreference.set(true)
        assertTrue(MotionPreference.reduced)
        MotionPreference.reset()
        assertFalse("reset returns to motion-on", MotionPreference.reduced)
    }
}
