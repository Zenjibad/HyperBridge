package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CooldownCoordinatorTest {

    private val config = EngineConfig()
    private val clock = MutableClock()
    private val timer = RecordingTimer()
    private val store = InMemorySnapshotStore()
    private val transport = RecordingTransport()
    private val actuator = ChannelActuator(transport, store, NoPoliteSound, config)
    private val coordinator = CooldownCoordinator(config, actuator, clock, timer)
    private val target = target()

    private fun post(atMs: Long): CooldownCoordinator.PostOutcome {
        clock.nowMs = atMs
        return coordinator.onPost(event(atMs))
    }

    private fun event(rankingLastAudiblyAlertedMs: Long = -1L) = AlertEvent(
        target = target,
        snapshot = transport.observed(),
        rankingLastAudiblyAlertedMs = rankingLastAudiblyAlertedMs,
    )

    @Test
    fun `the first post installs POLITE for the next alert, not for a later one`() {
        // AOSP would play alert 2 at 30 %; leaving the channel alone would play it in full, which is
        // the "one notification behind" bug spec §5.1's pre-emptive rule exists to prevent.
        val outcome = post(0)
        assertEquals(CooldownState.DEFAULT, outcome.installedState)
        assertEquals(CooldownState.POLITE, outcome.nextState)
        assertEquals(CooldownState.POLITE, coordinator.installedFor(target.key))
    }

    @Test
    fun `the second post inside T1 is rated MUTED and the channel is pre-armed for MUTED`() {
        post(0)
        val outcome = post(4_000)
        assertEquals(CooldownState.POLITE, outcome.installedState)
        assertEquals(CooldownState.MUTED, outcome.nextState)
        // The pre-arm is MUTED, so the write clears the pattern *and* the vibration flag: with the
        // flag still set, `NotificationRecord.getVibrationForChannel` would fall through to
        // `createDefaultVibration()` and the alert would buzz.
        assertNull(transport.lastEdit()!!.fields.vibrationPattern)
        assertFalse(transport.lastEdit()!!.fields.vibrationEnabled)
        assertNull(transport.lastEdit()!!.fields.soundUri)
    }

    @Test
    fun `a third post inside T2 arrives at MUTED, so the device plays nothing`() {
        post(0)
        post(4_000)
        assertEquals(CooldownState.MUTED, post(8_000).installedState)
    }

    @Test
    fun `the relaxation is scheduled for the T2 deadline and is not applied early`() {
        post(0)
        post(4_000)
        // The alert at 4 000 played POLITE, so the engine's state *after* that post is POLITE and the
        // plan follows the POLITE branch. That branch's `elapsed < T2` test is what makes MUTED, and
        // it is false at exactly T2 -- where `elapsed > T1` is false too, so the state there is
        // POLITE. The +1 belongs to the MUTED branch, which tests `elapsed > T2`.
        assertEquals(4_000 + config.timeoutMutedMs, timer.scheduled[target.key])
        assertEquals(CooldownState.MUTED, coordinator.installedFor(target.key))
    }

    @Test
    fun `a due relaxation moves the channel to POLITE when the timer fires`() {
        post(0)
        post(4_000)
        clock.nowMs = 4_000 + config.timeoutMutedMs + 1
        coordinator.onRelaxationDue(target.key)
        assertEquals(CooldownState.POLITE, coordinator.installedFor(target.key))
        assertNull(transport.lastEdit()!!.fields.soundUri)
        assertTrue(transport.lastEdit()!!.fields.vibrationEnabled)
    }

    @Test
    fun `a silenced post does not advance the clock`() {
        // Posts at 0, 4 s and 8 s: the 8 s one is silent, so it must not extend its own cooldown, and
        // the MUTED -> POLITE relaxation must still be measured from the 4 s alert.
        post(0)
        post(4_000)
        post(8_000)
        assertEquals(4_000 + config.timeoutMutedMs + 1, timer.scheduled[target.key])
    }

    @Test
    fun `the counter reset is pre-empted, so the tenth alert arrives at DEFAULT`() {
        // Posts every 4 s. Alerts 1 and 2 are audible; from then on the last audible alert only moves
        // when the T2 relaxation to POLITE makes an alert audible again. By the ninth post the
        // counter stands at 9 while the key is MUTED, which is exactly the condition
        // `ChannelStatePlanner.plan` treats as AOSP's forced return to DEFAULT "whenever the next
        // alert arrives" -- so the plan becomes a single DEFAULT segment.
        post(0)
        post(4_000)
        var at = 8_000L
        repeat(7) {
            post(at)
            at += 4_000
        }

        val outcome = post(at)
        assertEquals(
            CooldownState.DEFAULT,
            outcome.installedState,
            "the tenth alert arrives at a channel that is already DEFAULT, because the reset is pre-empted",
        )
    }

    @Test
    fun `a user interaction restores the channel and forgets the key`() {
        post(0)
        post(4_000)
        coordinator.onUserInteraction(target.key)
        assertEquals(channelFields(), transport.lastEdit()!!.fields)
        assertEquals(CooldownState.DEFAULT, coordinator.installedFor(target.key))
        assertTrue(store.outstanding().isEmpty())
        assertNull(timer.scheduled[target.key])
    }

    @Test
    fun `a channel someone else changed is left alone and forgotten`() {
        post(0)
        post(4_000)
        val userChanged = snapshot(fields = channelFields(soundUri = "content://media/external/audio/media/42"))
        clock.nowMs = 5_000
        val outcome = coordinator.onPost(
            AlertEvent(target = target, snapshot = userChanged, rankingLastAudiblyAlertedMs = -1),
        )
        assertTrue(outcome.applied is WriteResult.Ok)
        assertEquals(
            CooldownState.MUTED,
            outcome.installedState,
            "the channel was carrying MUTED when the user's change arrived",
        )
        // Nothing was written over the user's change, and the record is gone.
        assertEquals(2, transport.calls.size)
        assertTrue(store.outstanding().isEmpty())
        assertEquals(CooldownState.DEFAULT, coordinator.installedFor(target.key))
        assertNull(timer.scheduled[target.key])
    }

    @Test
    fun `a channel the app put back is damped again on the next post`() {
        post(0)
        post(4_000)
        // The app re-creates its own channel, restoring its own values.
        clock.nowMs = 5_000
        val outcome = coordinator.onPost(
            AlertEvent(target = target, snapshot = snapshot(), rankingLastAudiblyAlertedMs = -1),
        )
        assertEquals(CooldownState.MUTED, outcome.nextState)
        assertNull(transport.lastEdit()!!.fields.soundUri)
        assertEquals(channelFields(), store.snapshotFor(target.key)!!.snapshot.fields, "the pristine record survived")
    }

    @Test
    fun `a channel that no longer exists is forgotten without a write`() {
        post(0)
        post(4_000)
        val writes = transport.calls.size
        coordinator.onChannelGone(target.key)
        assertEquals(writes, transport.calls.size)
        assertTrue(store.outstanding().isEmpty())
        assertNull(timer.scheduled[target.key])
    }

    @Test
    fun `reconcile restores every damped channel and starts the engine cold`() {
        post(0)
        post(4_000)
        val restored = coordinator.reconcile()
        assertEquals(listOf(target.key), restored.map { it.first })
        assertEquals(channelFields(), transport.lastEdit()!!.fields)
        // AOSP's state is memory only, so after a restart the first post is a fresh DEFAULT.
        assertEquals(CooldownState.DEFAULT, coordinator.installedFor(target.key))
    }

    @Test
    fun `a restore leaves no pending relaxation, so no alarm survives to fire after it`() {
        post(0)
        post(4_000)
        // The device run: `restore-all` put the channel back at 20:49:15 and the alarm armed for this
        // episode still landed at 20:50:40, logging `relaxation due: 0:com.android.shell` 85 s after
        // the restore. Nothing is left to relax, so an alarm that outlives the record is wrong on its
        // face -- and it is only `plans` being empty that stops the callback writing.
        assertNotNull(timer.scheduled[target.key], "the episode must have a relaxation to leave behind")

        coordinator.reconcile()

        assertNull(timer.scheduled[target.key], "a restored channel must not keep a relaxation armed")
    }

    @Test
    fun `a post that cannot be written does not move the model of the channel`() {
        post(0)
        transport.nextResult = WriteResult.Failed("binder died")
        clock.nowMs = 4_000
        val outcome = coordinator.onPost(event())
        assertTrue(outcome.applied is WriteResult.Failed)
        // The channel still carries the old state, so the model must not claim otherwise.
        assertEquals(CooldownState.POLITE, coordinator.installedFor(target.key))
        transport.nextResult = WriteResult.Ok
    }

    @Test
    fun `a relaxation that cannot be written stays due and is retried`() {
        post(0)
        post(4_000)
        assertEquals(CooldownState.MUTED, coordinator.installedFor(target.key))
        val deadline = timer.scheduled[target.key]!!

        transport.nextResult = WriteResult.Failed("binder died")
        clock.nowMs = deadline
        coordinator.onRelaxationDue(target.key)

        // The channel still carries MUTED, so the model must not claim the relaxation was applied.
        // This is the same refusal the post path makes at :138-140; a relaxation is not allowed to
        // believe in a state the channel does not carry just because nobody asked it to report.
        assertEquals(CooldownState.MUTED, coordinator.installedFor(target.key))
        // And the retry must not be armed for a time that has already passed: `AlarmManager` fires a
        // past-due alarm immediately, so re-arming the dead deadline would retry in a tight loop.
        assertTrue(
            timer.scheduled[target.key]!! > clock.nowMs,
            "the retry was armed for ${timer.scheduled[target.key]}, which has already passed",
        )

        transport.nextResult = WriteResult.Ok
        clock.nowMs = timer.scheduled[target.key]!!
        coordinator.onRelaxationDue(target.key)

        assertEquals(CooldownState.POLITE, coordinator.installedFor(target.key), "the retry applied it")
    }

    @Test
    fun `an arrival on a relaxation deadline still arms the channel for the next alert`() {
        post(0)
        post(4_000)
        assertEquals(CooldownState.MUTED, coordinator.installedFor(target.key))
        val deadline = timer.scheduled[target.key]!!

        // The alert arrives at the exact instant the MUTED -> POLITE relaxation is due, before its
        // alarm broadcast has been delivered — the race the listener cannot rule out. `onPost` settles
        // that relaxation and then has to arm the channel for the *next* alert, which the plan says is
        // MUTED again. Its "the channel already carries what I want" test must not be reading the
        // content from before the settle it just did, or it skips a write the device still needs and
        // the model ends up describing a channel that does not exist.
        clock.nowMs = deadline
        val outcome = coordinator.onPost(event())

        assertEquals(CooldownState.MUTED, outcome.nextState)
        assertNull(transport.lastEdit()!!.fields.soundUri, "the channel was armed for the next alert")
        assertFalse(
            transport.lastEdit()!!.fields.vibrationEnabled,
            "and with the vibration flag cleared, or `createDefaultVibration` buzzes it",
        )
    }

    @Test
    fun `a post against a record that cannot be read is refused rather than dismissed`() {
        store.unreadable += SnapshotStore.Quarantined(
            key = target.key,
            reason = "0.importance is not a number",
            raw = mapOf("0.importance" to "not-a-number"),
        )
        clock.nowMs = 0

        val outcome = coordinator.onPost(event())

        assertTrue(outcome.applied is WriteResult.Failed, "the refusal is reported, not swallowed")
        assertTrue(transport.calls.isEmpty(), "nothing was written over the unreadable original")
        // The episode is dropped rather than planned around a snapshot that cannot be read: the key is
        // not damped, not scheduled, and nothing pretends to know what state the channel should hold.
        assertEquals(CooldownState.DEFAULT, outcome.nextState)
        assertEquals(CooldownState.DEFAULT, coordinator.installedFor(target.key))
        assertNull(timer.scheduled[target.key])
    }
}
