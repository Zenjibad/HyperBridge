package com.d4viddf.hyperbridge.cooldown.act

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rebind trigger, driven by a fake clock.
 *
 * The defect these pin: a force-stop or a kill tears the listener's binding down, the allow-list
 * still carries our component, and nothing re-establishes the bind — so the runtime, which is built
 * in `onListenerConnected`, never exists and damping stops silently. What has to be decided without
 * the device is *when* to ask for a rebind, and that it can never become a spin.
 */
class ListenerBindTest {

    private var now = 1_000L

    private val tracker = ListenerBindTracker { now }

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun `a process that has not seen a connection says so`() {
        assertEquals(false, tracker.isConnected())
        assertEquals(0, tracker.rebindAttempts())
    }

    @Test
    fun `access that was never granted is never rebindable, however long it has been`() {
        advance(RebindPolicy.GRACE_MS * 100)
        assertEquals(RebindVerdict.NOT_ALLOWED, tracker.judge(allowed = false))
        assertEquals(0, tracker.rebindAttempts())
    }

    @Test
    fun `a connected listener is left alone`() {
        tracker.connected()
        assertEquals(true, tracker.isConnected())
        assertEquals(RebindVerdict.CONNECTED, tracker.judge(allowed = true))
        assertEquals(0, tracker.rebindAttempts())
    }

    @Test
    fun `a cold process waits out the grace period, then asks`() {
        advance(RebindPolicy.GRACE_MS - 1)
        assertEquals(RebindVerdict.WITHIN_GRACE, tracker.judge(allowed = true))
        assertEquals(0, tracker.rebindAttempts())

        advance(1)
        assertEquals(RebindVerdict.REBIND, tracker.judge(allowed = true))
        assertEquals(1, tracker.rebindAttempts())
    }

    @Test
    fun `an observed disconnect is a tear, so it asks without waiting the grace period`() {
        tracker.connected()
        tracker.disconnected()

        assertEquals(false, tracker.isConnected())
        assertEquals(RebindVerdict.REBIND, tracker.judge(allowed = true))
        assertEquals(1, tracker.rebindAttempts())
    }

    @Test
    fun `a disconnect that follows a revoked access is not a tear to heal`() {
        tracker.connected()
        tracker.disconnected()

        assertEquals(RebindVerdict.NOT_ALLOWED, tracker.judge(allowed = false))
        assertEquals(0, tracker.rebindAttempts())
    }

    @Test
    fun `a hundred checks inside the retry interval ask exactly once`() {
        var asks = 0
        repeat(100) {
            advance(100)
            if (tracker.judge(allowed = true) == RebindVerdict.REBIND) asks++
        }
        // The hundred checks span 10 s: past the 3 s grace, all inside the 30 s floor.
        assertEquals(1, asks)
        assertEquals(1, tracker.rebindAttempts())
    }

    @Test
    fun `it asks again once the retry interval has passed rather than giving up`() {
        advance(RebindPolicy.GRACE_MS)
        assertEquals(RebindVerdict.REBIND, tracker.judge(allowed = true))
        assertEquals(RebindVerdict.RATE_LIMITED, tracker.judge(allowed = true))

        advance(RebindPolicy.RETRY_INTERVAL_MS)
        assertEquals(RebindVerdict.REBIND, tracker.judge(allowed = true))
        assertEquals(2, tracker.rebindAttempts())
    }

    @Test
    fun `the floor is absolute, so a bind that flaps is not hammered`() {
        tracker.connected()
        tracker.disconnected()
        assertEquals(RebindVerdict.REBIND, tracker.judge(allowed = true))

        // The rebind did arrive, and then the bind went away again. The tear is new, but the floor
        // is about how often this app may call the platform at all, so it still holds.
        tracker.connected()
        tracker.disconnected()
        assertEquals(RebindVerdict.RATE_LIMITED, tracker.judge(allowed = true))
        assertEquals(1, tracker.rebindAttempts())
    }

    @Test
    fun `a bind that arrives is reported connected, and a later tear arms the trigger again`() {
        advance(RebindPolicy.GRACE_MS)
        assertEquals(RebindVerdict.REBIND, tracker.judge(allowed = true))

        tracker.connected()
        assertEquals(RebindVerdict.CONNECTED, tracker.judge(allowed = true))

        advance(RebindPolicy.RETRY_INTERVAL_MS)
        tracker.disconnected()
        assertEquals(RebindVerdict.REBIND, tracker.judge(allowed = true))
    }

    // The self-heal step. The verdict says what is wrong with the bind; `healStep` says what this app
    // may do about it, and the difference is the permission: the allow-list path sends
    // `granted = true`, and a call that re-grants a permission the user revoked would be a serious
    // bug. Every test below is written so that deleting the branch it names makes it fail.

    @Test
    fun `a torn bind with access granted is the one step that transacts`() {
        assertEquals(
            HealStep.RE_GRANT_ALLOW_LIST,
            RebindPolicy.healStep(allowed = true, verdict = RebindVerdict.REBIND),
        )
    }

    @Test
    fun `the allow-list is never touched when access is off, whatever the verdict says`() {
        // The guard, and the reason it is a *pure* function: the wire payload is `granted = true`
        // either way, so the platform cannot tell a re-grant from a re-grant-of-a-revoked-permission.
        // REBIND is passed in deliberately -- it is the verdict that does transact when access is on
        // -- so this test fails if the `!allowed` branch is removed or moved below it.
        assertEquals(HealStep.NOTHING, RebindPolicy.healStep(allowed = false, verdict = RebindVerdict.REBIND))
        assertEquals(
            HealStep.NOTHING,
            RebindPolicy.healStep(allowed = false, verdict = RebindVerdict.NOT_ALLOWED),
        )
    }

    @Test
    fun `inside the floor the step is a logged suppression, not a second transaction`() {
        assertEquals(
            HealStep.SUPPRESSED_BY_FLOOR,
            RebindPolicy.healStep(allowed = true, verdict = RebindVerdict.RATE_LIMITED),
        )
    }

    @Test
    fun `a live bind and a bind inside its grace period do nothing at all`() {
        assertEquals(HealStep.NOTHING, RebindPolicy.healStep(allowed = true, verdict = RebindVerdict.CONNECTED))
        assertEquals(HealStep.NOTHING, RebindPolicy.healStep(allowed = true, verdict = RebindVerdict.WITHIN_GRACE))
    }

    @Test
    fun `a hundred checks past the grace period still transact exactly once`() {
        // The floor as the watchdog actually experiences it: `judge` counts an attempt only when it
        // answers REBIND, and only that answer becomes RE_GRANT_ALLOW_LIST.
        advance(RebindPolicy.GRACE_MS)
        val steps = mutableListOf<HealStep>()
        repeat(100) {
            advance(100)
            val verdict = tracker.judge(allowed = true)
            steps += RebindPolicy.healStep(allowed = true, verdict = verdict)
        }

        assertEquals(1, steps.count { it == HealStep.RE_GRANT_ALLOW_LIST })
        assertEquals(1, tracker.rebindAttempts())
        // The other 99 are named, not silent: the suppression is what the log line reports.
        assertEquals(99, steps.count { it == HealStep.SUPPRESSED_BY_FLOOR })
    }

    @Test
    fun `access that is off never transacts, however long the process waits`() {
        repeat(100) {
            advance(1_000)
            val verdict = tracker.judge(allowed = false)
            assertEquals(RebindVerdict.NOT_ALLOWED, verdict)
            assertEquals(HealStep.NOTHING, RebindPolicy.healStep(allowed = false, verdict = verdict))
        }
        assertEquals(0, tracker.rebindAttempts())
    }
}
