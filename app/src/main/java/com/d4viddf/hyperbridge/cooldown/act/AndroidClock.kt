package com.d4viddf.hyperbridge.cooldown.act

import android.os.SystemClock
import app.hypercooldown.actuation.Clock

/**
 * `SystemClock.elapsedRealtime()`, not wall-clock time.
 *
 * Every timestamp in the engine and in the plans is used in a *duration* comparison (T1, T2), so the
 * clock must be monotonic and must not jump when the network updates the wall clock. It does reset on
 * reboot, which is one more reason [CooldownCoordinator.reconcile] restores rather than resumes a plan
 * that was persisted before the reboot.
 */
object AndroidClock : Clock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
