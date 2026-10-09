package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelActuatorTest {

    private val config = EngineConfig()
    private val operations = mutableListOf<String>()
    private val store = InMemorySnapshotStore(operations)
    private val transport = RecordingTransport(operations)
    private val actuator = ChannelActuator(transport, store, NoPoliteSound, config)
    private val target = target()

    @Test
    fun `the snapshot reaches the store before the transport write`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        // Three steps, in this order, and the order is the safety property: the pristine content is
        // on disk first, then the channel is damped, and only then is the write recorded as ours.
        // The marker deliberately trails the write — see `ChannelActuator.write` — because a marker
        // for content the channel never accepted would read as somebody else's change.
        assertEquals(
            listOf("record:${target.key}", "write:${target.key}", "written:${target.key}"),
            operations,
        )
    }

    @Test
    fun `a relaxed channel that was never damped is not written at all`() {
        // Nothing to damp and nothing to restore, so the channel must not gain the user-locked bits
        // that any differing write would add. This is also what keeps the app completely passive
        // until a burst actually happens.
        val result = actuator.apply(target, snapshot(), CooldownState.DEFAULT)
        assertEquals(WriteResult.Ok, result)
        assertTrue(operations.isEmpty())
    }

    @Test
    fun `re-applying the same state does not write twice`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        val afterFirst = operations.size
        // The second observation is what the platform now hands back: our own write.
        actuator.apply(target, transport.observed(), CooldownState.MUTED)
        assertEquals(afterFirst, operations.size)
    }

    @Test
    fun `restore writes the pristine content back and clears the record`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        val result = actuator.restore(target.key)
        assertEquals(WriteResult.Ok, result)
        assertEquals(channelFields(), transport.lastEdit()!!.fields)
        assertNull(store.snapshotFor(target.key))
    }

    @Test
    fun `restoring a key with no record is a no-op`() {
        assertEquals(WriteResult.Ok, actuator.restore("0:nobody"))
        assertTrue(operations.isEmpty())
    }

    @Test
    fun `restoreAll restores every outstanding channel and reports each one`() {
        actuator.apply(target(key = "0:a", packageName = "a"), snapshot(), CooldownState.MUTED)
        actuator.apply(target(key = "0:b", packageName = "b"), snapshot(), CooldownState.POLITE)
        val results = actuator.restoreAll()
        assertEquals(listOf("0:a", "0:b"), results.map { it.first })
        assertTrue(results.all { it.second == WriteResult.Ok })
        assertTrue(store.outstanding().isEmpty())
    }

    @Test
    fun `a failed write keeps the record so the next process can still restore`() {
        transport.nextResult = WriteResult.Failed("binder died")
        val result = actuator.apply(target, snapshot(), CooldownState.MUTED)
        assertEquals(WriteResult.Failed("binder died"), result)
        assertTrue(store.outstanding().isNotEmpty())
        // And because the write failed, the last recorded content is still the pristine one.
        assertEquals(channelFields(), store.snapshotFor(target.key)!!.snapshot.fields)
    }

    @Test
    fun `applyRecorded writes for a key whose snapshot is already stored`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        val result = actuator.applyRecorded(target.key, CooldownState.POLITE)
        assertEquals(WriteResult.Ok, result)
        assertEquals(listOf(0L, 77L, 200L, 77L), transport.lastEdit()!!.fields.vibrationPattern)
    }

    @Test
    fun `applyRecorded ignores a key with no record`() {
        assertEquals(WriteResult.Ok, actuator.applyRecorded("0:nobody", CooldownState.MUTED))
        assertTrue(operations.isEmpty())
    }

    @Test
    fun `classify calls a never-seen channel fresh`() {
        assertEquals(ChannelActuator.Observed.FRESH, actuator.classify(target.key, snapshot()))
    }

    @Test
    fun `classify calls the channel ours while it carries what we wrote`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        assertEquals(ChannelActuator.Observed.OURS, actuator.classify(target.key, transport.observed()))
    }

    @Test
    fun `classify calls a channel back at its pristine content reset by the app`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        assertEquals(ChannelActuator.Observed.RESET_BY_APP, actuator.classify(target.key, snapshot()))
    }

    @Test
    fun `classify calls anything else somebody else's change`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        val userChanged = snapshot(fields = channelFields(soundUri = "content://media/external/audio/media/42"))
        assertEquals(ChannelActuator.Observed.CHANGED_BY_OTHER, actuator.classify(target.key, userChanged))
    }

    @Test
    fun `forget drops the bookkeeping without writing`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        operations.clear()
        actuator.forget(target.key)
        assertNull(store.snapshotFor(target.key))
        assertTrue(operations.none { it.startsWith("write:") })
    }

    /**
     * A channel that plays nothing and is not carrying our last write — an app that silenced itself,
     * or a damping write whose marker never reached disk. Spec §6 ranks "never leave a channel muted"
     * above "do not clobber a user change", so a stand-down on it is a restore, and the restore write
     * is what authorises dropping the record.
     */
    private fun silencedBySomebodyElse() = snapshot(
        fields = channelFields(
            soundUri = null,
            vibrationPattern = null,
            vibrationEnabled = false,
            audioFlags = 1,
        ),
    )

    @Test
    fun `a stand-down restores a channel that plays nothing, and that write clears the record`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)

        assertEquals(WriteResult.Ok, actuator.standDown(target.key, silencedBySomebodyElse()))

        assertEquals(channelFields(), transport.lastEdit()!!.fields)
        assertNull(store.snapshotFor(target.key), "the restore write is what dropped the record")
    }

    @Test
    fun `a stand-down keeps and reports the record when the restore cannot be written`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        transport.nextResult = WriteResult.Failed("binder died")

        val result = actuator.standDown(target.key, silencedBySomebodyElse())

        assertEquals(WriteResult.Failed("binder died"), result)
        // Losing the record costs the user their channel's sound for good; keeping a stale one costs
        // a redundant restore. So the record stays, and the failure is returned to be surfaced.
        assertNotNull(store.snapshotFor(target.key))
        assertEquals(channelFields(), store.snapshotFor(target.key)!!.snapshot.fields)
    }

    @Test
    fun `a stand-down drops the record without a write for a channel somebody else made audible`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        val writes = operations.count { it.startsWith("write:") }
        val userChanged = snapshot(fields = channelFields(soundUri = "content://media/external/audio/media/42"))

        assertEquals(WriteResult.Ok, actuator.standDown(target.key, userChanged))

        assertNull(store.snapshotFor(target.key))
        assertEquals(writes, operations.count { it.startsWith("write:") }, "nothing was written over the user's change")
    }

    @Test
    fun `a record the store could not read is not treated as fresh`() {
        // Spec §6, "fail loud, not silent": "no record" and "a record I cannot read" are different
        // facts and only the first one is safe to act on. FRESH leads straight to a write, and a write
        // here would make the store re-record the *damped* content as if it were pristine.
        store.unreadable += SnapshotStore.Quarantined(
            key = target.key,
            reason = "0.importance is not a number",
            raw = mapOf("0.importance" to "not-a-number"),
        )

        assertEquals(ChannelActuator.Observed.UNREADABLE_RECORD, actuator.classify(target.key, snapshot()))
        assertTrue(actuator.apply(target, snapshot(), CooldownState.MUTED) is WriteResult.Failed)
        assertTrue(actuator.restore(target.key) is WriteResult.Failed)
        assertTrue(operations.isEmpty(), "nothing was written over the unreadable original")
    }

    @Test
    fun `a POLITE gap is reported on the write`() {
        val silentSource = snapshot(fields = channelFields(soundUri = null, vibrationEnabled = false, vibrationPattern = null))
        actuator.apply(target, silentSource, CooldownState.POLITE)
        assertTrue(transport.lastEdit()!!.gaps.isEmpty())
        actuator.apply(target(key = "0:other", packageName = "other"), snapshot(), CooldownState.POLITE)
        assertTrue(FidelityGap.POLITE_SOUND_UNAVAILABLE in transport.lastEdit()!!.gaps)
        assertFalse(operations.isEmpty())
    }
}
