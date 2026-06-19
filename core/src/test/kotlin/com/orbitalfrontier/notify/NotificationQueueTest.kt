package com.orbitalfrontier.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [NotificationQueue] — the pure, libGDX-free toast state machine the screen drives (UC35
 * AC#2/#3/#5).
 *
 * Drives the queue deterministically with a bespoke [NotificationPolicy] (short clocks, small windows) and
 * a hand-fed `dt`, so there is no real time or engine in the loop. Asserts the four player-facing rules:
 *  - **enqueue → visible**, and **auto-dismiss** after [NotificationPolicy.displaySeconds];
 *  - **coalescing (drop/refresh)** of same-key coalescable bursts into a single live, content-refreshed,
 *    clock-reset entry — and that the window has a boundary (a stale entry stops absorbing);
 *  - **non-coalescable kinds stack** as distinct entries, bounded by [NotificationPolicy.maxQueued];
 *  - **non-overlap promotion**: only the first [NotificationPolicy.maxVisible] age; waiters start their
 *    display clock only on promotion, and severity is preserved end-to-end.
 */
class NotificationQueueTest {
    private fun gain(amount: Long): GameNotification = GameNotification(NotificationKind.CREDIT_GAIN, "+$amount CR")

    private fun jump(sector: String): GameNotification = GameNotification(NotificationKind.JUMP_COMPLETED, "JUMPED TO $sector")

    // --- AC#5: enqueue → visible, then auto-dismiss after the display window ----------------------------

    @Test
    fun `an enqueued notification becomes visible`() {
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 2f))
        q.enqueue(jump("CYGNUS"))
        assertEquals(1, q.size())
        assertEquals(listOf("JUMPED TO CYGNUS"), q.visible().map { it.message })
    }

    @Test
    fun `a visible notification auto-dismisses once it reaches displaySeconds`() {
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 2f))
        q.enqueue(jump("CYGNUS"))
        q.update(1f)
        assertEquals("still visible before the window elapses", 1, q.visible().size)
        q.update(1f)
        assertTrue("dismissed once age reaches displaySeconds", q.visible().isEmpty())
        assertEquals(0, q.size())
    }

    @Test
    fun `update with no entries is a no-op`() {
        val q = NotificationQueue()
        q.update(5f)
        assertEquals(0, q.size())
    }

    // --- AC#2 pitfall: coalescing (drop/refresh) for same-key bursts ------------------------------------

    @Test
    fun `two same-key coalescable enqueues collapse into one refreshed entry`() {
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 5f, coalesceWindowSeconds = 5f))
        q.enqueue(gain(100))
        q.enqueue(gain(250))
        assertEquals("a burst of same-kind gains stays a single toast", 1, q.size())
        assertEquals("the surviving toast shows the newest content", listOf("+250 CR"), q.visible().map { it.message })
    }

    @Test
    fun `coalescing resets the surviving entry's display clock`() {
        // With a 2s window/display: age the first gain to 1.5s, then a second gain refreshes it back to 0.
        // Without the reset it would dismiss at the next 1s tick; with it, the toast lingers through the burst.
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 2f, coalesceWindowSeconds = 2f))
        q.enqueue(gain(10))
        q.update(1.5f)
        q.enqueue(gain(20))
        q.update(1f)
        assertEquals("the coalesced toast survived because its clock reset", 1, q.visible().size)
        assertEquals(listOf("+20 CR"), q.visible().map { it.message })
    }

    @Test
    fun `a same-key event arriving past the coalesce window stacks instead of coalescing`() {
        // displaySeconds deliberately longer than the window so the first entry can age past the window
        // without being dismissed; the second same-kind event then finds no in-window entry and stacks.
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 10f, coalesceWindowSeconds = 1f))
        q.enqueue(gain(10))
        q.update(2f)
        q.enqueue(gain(20))
        assertEquals("a stale entry no longer absorbs same-key repeats", 2, q.size())
    }

    @Test
    fun `non-coalescable kinds stack as distinct entries`() {
        val q = NotificationQueue(NotificationPolicy(maxVisible = 5))
        q.enqueue(jump("A"))
        q.enqueue(jump("B"))
        q.enqueue(GameNotification(NotificationKind.DOCKED, "DOCKED: PORT"))
        assertEquals("one-shot kinds never coalesce", 3, q.size())
        assertEquals(listOf("JUMPED TO A", "JUMPED TO B", "DOCKED: PORT"), q.visible().map { it.message })
    }

    @Test
    fun `coalescing is keyed so different coalescable kinds do not merge`() {
        val q = NotificationQueue(NotificationPolicy(maxVisible = 5))
        q.enqueue(GameNotification(NotificationKind.CREDIT_GAIN, "+5 CR"))
        q.enqueue(GameNotification(NotificationKind.CREDIT_LOSS, "-5 CR"))
        assertEquals("a gain and a loss are distinct toasts", 2, q.size())
    }

    // --- AC#2: the queue is bounded so a flood can't grow it without limit ------------------------------

    @Test
    fun `enqueue past maxQueued drops the overflow`() {
        val q = NotificationQueue(NotificationPolicy(maxVisible = 2, maxQueued = 3))
        repeat(6) { q.enqueue(jump("S$it")) }
        assertEquals("live entries are capped at maxQueued", 3, q.size())
        // The earliest entries are retained; the flood overflow is dropped (the on-screen toasts win).
        assertEquals(listOf("JUMPED TO S0", "JUMPED TO S1"), q.visible().map { it.message })
    }

    // --- AC#2: non-overlap — only the visible window ages; waiters start their clock on promotion --------

    @Test
    fun `entries behind the visible window do not age while they wait`() {
        // maxVisible = 2 with three entries: A,B are visible and age; C waits. The queue documents that only
        // the visible window ages — so C must survive a near-full-window step that would dismiss it if it had
        // been ageing behind the window.
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 2f, maxVisible = 2, maxQueued = 10))
        q.enqueue(jump("A"))
        q.enqueue(jump("B"))
        q.enqueue(jump("C"))
        assertEquals("only maxVisible show at once", listOf("JUMPED TO A", "JUMPED TO B"), q.visible().map { it.message })

        q.update(1.9f) // A,B age to 1.9 (still visible); C is behind the window and must not age.
        assertEquals("A,B still up just shy of the window", listOf("JUMPED TO A", "JUMPED TO B"), q.visible().map { it.message })

        q.update(0.2f) // A,B reach 2.1 ≥ 2 → dismissed; C is promoted and only now begins ageing (to 0.2).
        // If C had been ageing behind the window it would now be at 2.1 and dismissed too — it isn't.
        assertEquals("the un-aged waiter is promoted, not dismissed", listOf("JUMPED TO C"), q.visible().map { it.message })
        assertEquals(1, q.size())
    }

    @Test
    fun `a promoted waiter is not pre-aged by the time it spent waiting`() {
        // maxVisible = 1: A is visible, B waits a long time, then A dismisses and B is promoted. B must get
        // essentially its own fresh window from promotion (carrying only the promotion-tick dt), not be
        // dismissed early because it "waited" while A was up.
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 2f, maxVisible = 1, maxQueued = 10))
        q.enqueue(jump("A"))
        q.enqueue(jump("B"))

        q.update(1.9f) // A ages to 1.9; B waits (un-aged).
        assertEquals(listOf("JUMPED TO A"), q.visible().map { it.message })

        q.update(0.2f) // A reaches 2.1 → dismissed; B promoted, now at age 0.2 (NOT pre-aged by the 1.9 wait).
        assertEquals(listOf("JUMPED TO B"), q.visible().map { it.message })

        q.update(1.7f) // B at 1.9 — still under its own window, so it remains up.
        assertEquals("the promoted waiter keeps ~a full window from promotion", listOf("JUMPED TO B"), q.visible().map { it.message })

        q.update(0.2f) // B reaches 2.1 ≥ 2 → dismissed.
        assertTrue("…and dismisses after its own window elapses", q.visible().isEmpty())
    }

    // --- AC#2: severity styling survives the round-trip through the queue -------------------------------

    @Test
    fun `severity is preserved for visible entries`() {
        val q = NotificationQueue(NotificationPolicy(maxVisible = 5))
        q.enqueue(GameNotification(NotificationKind.LOW_FUEL, "LOW FUEL"))
        q.enqueue(jump("A"))
        val bySeverity = q.visible().associate { it.message to it.severity }
        assertEquals(NotificationSeverity.WARNING, bySeverity["LOW FUEL"])
        assertEquals(NotificationSeverity.INFO, bySeverity["JUMPED TO A"])
    }

    @Test
    fun `visible never exceeds maxVisible even when many entries wait`() {
        val q = NotificationQueue(NotificationPolicy(maxVisible = 3, maxQueued = 12))
        repeat(12) { q.enqueue(jump("S$it")) }
        assertEquals(3, q.visible().size)
        assertEquals(12, q.size())
    }

    // --- UC40 AC#2: visibleWithProgress() exposes a pure life fraction for the animated renderer ---------

    @Test
    fun `a freshly enqueued entry reports a zero life fraction`() {
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 2f))
        q.enqueue(gain(100))
        val progress = q.visibleWithProgress()
        assertEquals(1, progress.size)
        assertEquals("a just-shown toast is at the start of its life (0 = full fade-in pending)", 0f, progress.single().lifeFraction, 1e-6f)
    }

    @Test
    fun `the life fraction is the visible age as a fraction of displaySeconds`() {
        // Half a 4s display window elapsed → lifeFraction 0.5; the renderer maps this pure value to alpha/drift.
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 4f))
        q.enqueue(gain(100))
        q.update(1f)
        assertEquals(0.25f, q.visibleWithProgress().single().lifeFraction, 1e-6f)
        q.update(1f)
        assertEquals(0.5f, q.visibleWithProgress().single().lifeFraction, 1e-6f)
    }

    @Test
    fun `the life fraction increases monotonically and stays within zero to one`() {
        // Step a long-lived toast across its window and assert the fraction never decreases and never leaves
        // the clamped 0..1 range the renderer relies on for a well-formed fade curve.
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 5f))
        q.enqueue(gain(100))
        var previous = q.visibleWithProgress().single().lifeFraction
        repeat(9) {
            q.update(0.5f)
            val current = q.visibleWithProgress().firstOrNull()?.lifeFraction ?: return@repeat
            assertTrue("life fraction must not exceed 1 (clamped)", current <= 1f)
            assertTrue("life fraction must not drop below 0 (clamped)", current >= 0f)
            assertTrue("life fraction must be monotonic non-decreasing as the toast ages", current >= previous)
            previous = current
        }
    }

    @Test
    fun `a promoted waiter starts a fresh life fraction at zero`() {
        // maxVisible = 1: A is up and ages; B waits un-aged. When A dismisses and B is promoted, B must begin
        // its own life at 0 — so the animated fade-in plays for the waiter too, not a mid-life pop-in.
        val q = NotificationQueue(NotificationPolicy(displaySeconds = 2f, maxVisible = 1, maxQueued = 10))
        q.enqueue(gain(10))
        q.enqueue(jump("B"))
        q.update(1.9f) // A ages to 1.9; B waits.
        q.update(0.2f) // A reaches 2.1 → dismissed; B promoted at age 0.2.
        val promoted = q.visibleWithProgress().single()
        assertEquals("JUMPED TO B", promoted.notification.message)
        assertEquals("the promoted waiter begins its own life, not pre-aged by the wait", 0.1f, promoted.lifeFraction, 1e-6f)
    }

    @Test
    fun `visibleWithProgress carries the notification content and severity unchanged`() {
        val q = NotificationQueue(NotificationPolicy(maxVisible = 5))
        q.enqueue(GameNotification(NotificationKind.INSUFFICIENT_CREDITS, "INSUFFICIENT CREDITS"))
        q.enqueue(jump("A"))
        val bySeverity = q.visibleWithProgress().associate { it.notification.message to it.notification.severity }
        assertEquals(
            "the styled ERROR survives the round-trip for the DANGER tint",
            NotificationSeverity.ERROR,
            bySeverity["INSUFFICIENT CREDITS"],
        )
        assertEquals(NotificationSeverity.INFO, bySeverity["JUMPED TO A"])
    }

    @Test
    fun `visibleWithProgress respects maxVisible and top-of-stack ordering`() {
        // The animated snapshot must mirror visible(): only the first maxVisible entries, in order.
        val q = NotificationQueue(NotificationPolicy(maxVisible = 3, maxQueued = 12))
        repeat(12) { q.enqueue(jump("S$it")) }
        val progress = q.visibleWithProgress()
        assertEquals(3, progress.size)
        assertEquals(
            listOf("JUMPED TO S0", "JUMPED TO S1", "JUMPED TO S2"),
            progress.map { it.notification.message },
        )
        assertEquals("the animated snapshot agrees with visible()", q.visible(), progress.map { it.notification })
    }
}
