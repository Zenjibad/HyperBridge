package app.hypercooldown.actuation

import app.hypercooldown.engine.ChannelStatePlanner
import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The audibility verdict `CooldownCoordinator.onPost` feeds the engine when its model of the channel
 * has nothing to say about the alert that just arrived.
 *
 * `onPost` derived it from the model -- `installed != CooldownState.MUTED`, with `installed`
 * defaulted to `DEFAULT` for a key the coordinator holds no plan for. A process that restarted cold
 * reaches that fallback with the channel still carrying our MUTED content, so the alert was fed to
 * the engine as audible. The content the channel was carrying when the alert played is the witness
 * instead, which is the same thing AOSP's `if (buzz || beep)` reads.
 *
 * The verdict is not exposed to the outside: what a test can see is the *plan* the engine was driven
 * to, read back through the public `ChannelStatePlanner`, plus the state the coordinator installs and
 * the relaxation it schedules. A plan with no audible time in it is a single DEFAULT segment, so
 * "nothing is scheduled" is the observable form of "the engine recorded no audible alert".
 */
class CooldownCoordinatorColdStartTest {

    private val config = EngineConfig()
    private val clock = MutableClock()
    private val store = InMemorySnapshotStore()
    private val transport = RecordingTransport()
    private val actuator = ChannelActuator(transport, store, NoPoliteSound, config)
    private val timer = RecordingTimer()
    private val coordinator = CooldownCoordinator(config, actuator, clock, timer)
    private val target = target()

    /** One post, whose arrival is whatever the transport's channel is carrying at that moment. */
    private fun post(atMs: Long): CooldownCoordinator.PostOutcome {
        clock.nowMs = atMs
        return coordinator.onPost(AlertEvent(target, transport.observed(), -1L))
    }

    /** A new process over the same disk and the same device: nothing in memory survives. */
    private fun restart(timer: RecordingTimer): CooldownCoordinator = CooldownCoordinator(
        config,
        ChannelActuator(transport, store, NoPoliteSound, config),
        clock,
        timer,
    )

    @Test
    fun `a cold coordinator does not call a MUTED-content arrival audible`() {
        // A previous process damped the channel twice and left its record on disk, so the channel is
        // carrying MUTED and nothing in memory knows that.
        post(0)
        post(4_000)
        assertNull(transport.observed().fields.soundUri)
        assertFalse(transport.observed().fields.vibrationEnabled)

        val coldTimer = RecordingTimer()
        val restarted = restart(coldTimer)
        val arrival = transport.observed()

        clock.nowMs = 8_000
        val outcome = restarted.onPost(AlertEvent(target, arrival, -1L))

        // The alert played nothing, so it must not extend its own cooldown: no audible time, no plan
        // to relax, and a channel left at DEFAULT.
        assertNull(
            coldTimer.scheduled[target.key],
            "a MUTED-content alert played nothing, so nothing may be scheduled from it",
        )
        assertEquals(CooldownState.DEFAULT, outcome.nextState)
        assertEquals(CooldownState.DEFAULT, restarted.installedFor(target.key))

        // NEGATIVE CONTROL for the old rule, run against the public planner rather than by keeping a
        // copy of the old coordinator. The cold model holds no plan, so `installed` is DEFAULT,
        // `installed != MUTED` is true, and the engine's clock is advanced to the arrival -- a plan
        // that pre-arms POLITE and schedules a relaxation at T1 for an alert that was silent.
        assertEquals(
            CooldownState.DEFAULT,
            outcome.installedState,
            "the cold model has no plan for the key",
        )
        assertTrue(outcome.installedState != CooldownState.MUTED, "so the old rule said `audible`")
        val oldRulePlan = ChannelStatePlanner.plan(
            assignedState = CooldownState.DEFAULT,
            lastAudibleAtMs = clock.nowMs,
            postedCount = 1,
            config = config,
        )
        assertEquals(CooldownState.POLITE, oldRulePlan.stateAt(clock.nowMs))
        assertEquals(68_000L, oldRulePlan.nextChangeAfter(clock.nowMs))
        assertNull(
            coldTimer.scheduled[target.key],
            "the fix disagrees with the old rule, and that disagreement is this test",
        )
    }

    @Test
    fun `a sounding arrival is audible with no plan at all`() {
        val arrival = snapshot(fields = channelFields())
        assertTrue(arrival.fields.soundUri != null, "the fixture channel is audible")

        clock.nowMs = 0
        val outcome = coordinator.onPost(AlertEvent(target, arrival, -1L))

        assertEquals(CooldownState.POLITE, outcome.nextState, "an audible alert starts the cooldown")
        assertEquals(config.timeoutPoliteMs, timer.scheduled[target.key], "relaxed to DEFAULT at T1")
    }

    @Test
    fun `an arrival with no sound left but vibration on is still audible`() {
        // The `||` of the derivation, pinned. The sound is gone and the vibration flag survived -- a
        // write of ours that only half landed, or an app whose own channel is vibration-only. A
        // `soundUri != null` reading would call this silent and never start the cooldown.
        val arrival = snapshot(
            fields = channelFields(
                soundUri = null,
                vibrationPattern = null,
                vibrationEffectBytes = null,
                vibrationEnabled = true,
            ),
        )

        clock.nowMs = 0
        val outcome = coordinator.onPost(AlertEvent(target, arrival, -1L))

        assertEquals(CooldownState.POLITE, outcome.nextState)
        assertEquals(config.timeoutPoliteMs, timer.scheduled[target.key])

        // The negative control for this case: the sound-only misreading of the same arrival arms
        // nothing at all, because a plan with no audible time is one DEFAULT segment.
        val soundOnlyPlan = ChannelStatePlanner.plan(
            assignedState = CooldownState.DEFAULT,
            lastAudibleAtMs = null,
            postedCount = 1,
            config = config,
        )
        assertNull(soundOnlyPlan.nextChangeAfter(clock.nowMs))
        assertEquals(CooldownState.DEFAULT, soundOnlyPlan.stateAt(clock.nowMs))
    }

    @Test
    fun `the counter reset still pre-empts on a silent arrival`() {
        // Spec §5.1's last row: a MUTED key whose counter stands one short of COUNTER_RESET plays the
        // next alert at DEFAULT whenever it arrives. Everything after the second post is silent MUTED
        // content, so the plan has to pre-empt the reset without the engine's clock advancing on any
        // of those arrivals.
        post(0)
        post(4_000)
        var at = 8_000L
        repeat(6) {
            post(at)
            at += 4_000
        }

        // The ninth post: the counter stands at COUNTER_RESET - 1 while the key is MUTED, which is
        // the branch `ChannelStatePlanner.plan` answers with a single DEFAULT segment.
        val ninth = post(at)
        assertEquals(CooldownState.DEFAULT, ninth.nextState, "the reset is pre-empted one post early")
        assertNull(timer.scheduled[target.key], "and the pre-emption leaves nothing to relax")

        at += 4_000
        val tenth = post(at)
        assertEquals(
            CooldownState.DEFAULT,
            tenth.installedState,
            "the tenth alert arrives at a channel that is already DEFAULT",
        )
        assertEquals(
            at + config.timeoutPoliteMs,
            timer.scheduled[target.key],
            "the counter reset restarts the cycle from the tenth alert",
        )
    }
}
