package com.orbitalfrontier.debugnav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure (libGDX-free, JVM-only) coverage of the UC25 debug point-and-go arm/disarm gate,
 * [PointAndGoState]. This is the deterministic half of AC#1 — the on-screen debug toggle arms/disarms
 * point-and-go and **defaults to off** so normal taps are never hijacked until the tester arms it. The
 * actual on-screen button + tap routing is GL-bound and pinned by the source-anchored
 * [com.orbitalfrontier.screen.Uc25PointAndGoGuardTest] + a live emulator pass.
 *
 * This value is what [com.orbitalfrontier.screen.PlayScreen] swaps on each arm-button tap (`toggled()`)
 * and reads (`armed`) before treating a world-view tap as a teleport.
 */
class PointAndGoStateTest {
    @Test
    fun `the default state is disarmed`() {
        // AC#1: point-and-go defaults OFF so it never hijacks normal taps until explicitly armed.
        assertFalse("AC#1: a fresh point-and-go state is disarmed", PointAndGoState().armed)
    }

    @Test
    fun `toggled arms from the default disarmed state`() {
        // AC#1: the first tap on the debug arm toggle arms point-and-go.
        assertTrue("AC#1: toggling a disarmed state arms it", PointAndGoState().toggled().armed)
    }

    @Test
    fun `toggled disarms again from the armed state`() {
        val armed = PointAndGoState(armed = true)
        assertFalse("AC#1: toggling an armed state disarms it", armed.toggled().armed)
    }

    @Test
    fun `toggled round-trips disarmed - armed - disarmed`() {
        val disarmed = PointAndGoState()
        assertTrue(disarmed.toggled().armed)
        assertFalse(disarmed.toggled().toggled().armed)
    }
}
