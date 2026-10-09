package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Spec §6's "never leave a channel muted" across a **process boundary**.
 *
 * Only [ChannelActuator]'s in-memory bookkeeping dies with the process; the write-ahead record on
 * disk is what survives a crash, a reboot or a Shizuku restart. These tests build a *fresh* actuator
 * over the store the previous one left behind, which is the one thing the other actuator tests never
 * do, and they are where a record that is not read as evidence of our own write shows up.
 */
class ChannelActuatorRestartTest {

    @TempDir
    lateinit var dir: Path

    private val config = EngineConfig()
    private val file: Path get() = dir.resolve("channel-snapshots.properties")
    private val target = target()

    private fun store() = PropertiesSnapshotStore(file)

    @Test
    fun `a damped channel is still ours to a process that never wrote it`() {
        val device = FakeChannelDevice()
        ChannelActuator(device, store(), NoPoliteSound, config)
            .apply(target, snapshot(), CooldownState.MUTED)
        assertEquals(CooldownState.MUTED, device.stateFor(target.packageName), "the channel is damped")

        // The process dies with everything it held in memory. The record on disk outlives it, and the
        // channel is still carrying the damping write, so the next process must recognise it as ours
        // rather than as somebody else's change — if it does not, it stands down, drops the record and
        // the channel stays muted with nothing left to restore it from.
        val restarted = ChannelActuator(device, store(), NoPoliteSound, config)

        assertEquals(
            ChannelActuator.Observed.OURS,
            restarted.classify(target.key, device.snapshotFor(target.packageName)),
        )
        assertEquals(listOf(target.key to WriteResult.Ok), restarted.restoreAll())
        assertEquals(CooldownState.DEFAULT, device.stateFor(target.packageName), "the channel plays again")
        assertTrue(store().outstanding().isEmpty(), "a successful restore drops the record")
    }

    @Test
    fun `a fresh process still tells a foreign change from our own damping`() {
        val device = FakeChannelDevice()
        ChannelActuator(device, store(), NoPoliteSound, config)
            .apply(target, snapshot(), CooldownState.MUTED)

        // The record's persisted copy of our last write is the only thing that can make this call.
        // Without it the record is just "we touched this channel once", and a channel the app has
        // since given its own audible content back would be read as ours and damped over — spec §6's
        // "re-snapshot rather than clobbering a user change" is exactly this distinction.
        val userChanged = snapshot(fields = channelFields(soundUri = "content://media/external/audio/media/42"))
        val restarted = ChannelActuator(device, store(), NoPoliteSound, config)

        assertEquals(
            ChannelActuator.Observed.CHANGED_BY_OTHER,
            restarted.classify(target.key, userChanged),
        )
    }

    @Test
    fun `a coordinator that starts after a restart never stands down on its own damping`() {
        val device = FakeChannelDevice()
        val clock = MutableClock()
        val first = CooldownCoordinator(
            config = config,
            actuator = ChannelActuator(device, store(), NoPoliteSound, config),
            clock = clock,
            timer = RecordingTimer(),
        )
        clock.nowMs = 0
        first.onPost(AlertEvent(target, device.snapshotFor(target.packageName), -1))
        clock.nowMs = 4_000
        first.onPost(AlertEvent(target, device.snapshotFor(target.packageName), -1))
        assertEquals(CooldownState.MUTED, device.stateFor(target.packageName), "the channel is damped")

        // The process dies and the listener reconnects: a cold coordinator, a cold engine and an empty
        // in-memory "what did I write" map, over the record the last process left on disk. This is the
        // whole of the defect: reading the damping write as somebody else's change stands the episode
        // down, drops the record, and leaves the channel muted with nothing able to find it again.
        clock.nowMs = 5_000
        val restarted = CooldownCoordinator(
            config = config,
            actuator = ChannelActuator(device, store(), NoPoliteSound, config),
            clock = clock,
            timer = RecordingTimer(),
        )
        val outcome = restarted.onPost(AlertEvent(target, device.snapshotFor(target.packageName), -1))

        assertTrue(outcome.applied is WriteResult.Ok, "the restart did not stand down: ${outcome.applied}")
        assertEquals(listOf(target.key), store().outstanding().map { it.target.key }, "the record survived")
        assertEquals(
            channelFields(),
            store().snapshotFor(target.key)!!.snapshot.fields,
            "and it is still the user's pristine content, not our damping",
        )
    }
}
