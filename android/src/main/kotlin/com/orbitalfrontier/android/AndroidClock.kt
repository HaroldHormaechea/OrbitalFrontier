package com.orbitalfrontier.android

import com.orbitalfrontier.platform.Clock

/**
 * On-device [Clock] (UC38) — the real wall clock, backing the injected port with
 * `System.currentTimeMillis()`. This is the ONLY place a platform wall clock is read: it stamps a save
 * slot's `last_saved_epoch_millis` so the save/load list can show when each slot was last written
 * (UC38 AC#1). The pure simulation never reads it — it advances by an injected `dt` only (ADR 0006), so
 * keeping the clock at the persistence boundary preserves determinism (the record/replay harness uses a
 * fixed/fake clock instead).
 */
class AndroidClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
