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

    // ---------------------------------------------------------------- the importance lock, part A

    /** An actuator whose heads-up option lowers importance in both damped states. */
    private fun suppressingActuator(): ChannelActuator = ChannelActuator(
        transport,
        store,
        NoPoliteSound,
        config,
        headsUpFor = { HeadsUpRealisation(HeadsUpOption.SUPPRESS, HeadsUpOption.SUPPRESS) },
    )

    @Test
    fun `a suppressed heads-up lowers the importance the channel ends up carrying`() {
        suppressingActuator().apply(target, snapshot(), CooldownState.MUTED)

        assertEquals(3, transport.lastEdit()!!.fields.importance)
        assertEquals(3, transport.observed().fields.importance)
        // The write is what locks the field: `lockFieldsForUpdateLocked` locks only fields whose value
        // changed, so the bit appears here and nowhere else.
        assertTrue(transport.userLockedFields and RecordingTransport.IMPORTANCE_LOCK != 0)
    }

    @Test
    fun `a restore releases the importance lock`() {
        // The pin for Part A's second half, and the one the mutation removes: comment out the
        // `unlockImportance` call in `ChannelActuator.restore` and this fails with
        // `userLockedFields` still carrying 0x04 (and every other assertion below unchanged).
        val suppressing = suppressingActuator()
        suppressing.apply(target, snapshot(), CooldownState.MUTED)
        assertTrue(transport.userLockedFields and RecordingTransport.IMPORTANCE_LOCK != 0)

        assertEquals(WriteResult.Ok, suppressing.restore(target.key))

        assertEquals(
            0,
            transport.userLockedFields and RecordingTransport.IMPORTANCE_LOCK,
            "the restored channel still carries USER_LOCKED_IMPORTANCE",
        )
        // The value went back as well: releasing the bit is the *second* half, not a substitute.
        assertEquals(4, transport.lastEdit()!!.fields.importance)
        assertEquals(listOf(target), transport.unlocks)
        // The transaction addresses one channel of one app, so the id has to be the one the write
        // touched rather than a name reconstructed from the key.
        assertEquals(listOf("messages"), transport.unlockedChannels)
    }

    @Test
    fun `the unlock is sent after the restore write, never before it`() {
        val suppressing = suppressingActuator()
        suppressing.apply(target, snapshot(), CooldownState.MUTED)
        operations.clear()

        suppressing.restore(target.key)

        // `lockFieldsForUpdateLocked` locks a field whose value the write changed, so the restore write
        // is what sets the bit and the unlock has to come after it. Unlocking first would be undone by
        // the write that follows; unlocking after a *failed* write would release a lock never taken.
        assertEquals(listOf("write:${target.key}", "unlock:${target.key}", "clear:${target.key}"), operations)
    }

    @Test
    fun `a restore that never lowered importance sends no unlock`() {
        actuator.apply(target, snapshot(), CooldownState.MUTED)
        operations.clear()

        assertEquals(WriteResult.Ok, actuator.restore(target.key))

        assertTrue(transport.unlocks.isEmpty())
        assertFalse(operations.any { it.startsWith("unlock:") })
    }

    @Test
    fun `a restore whose unlock fails keeps the record and reports it`() {
        val suppressing = suppressingActuator()
        suppressing.apply(target, snapshot(), CooldownState.MUTED)
        transport.nextUnlockResult = WriteResult.Failed("binder died")

        val result = suppressing.restore(target.key)

        // Invariant 2 in the other direction: the record is only dropped once the channel is fully back,
        // and "fully back" includes the lock bit this app set. Keeping it is what makes the retry
        // possible; the content is already restored, so the retry rewrites nothing that changed.
        assertTrue(result is WriteResult.Failed)
        assertNotNull(store.snapshotFor(target.key))
        // The retry is idempotent: the content is already back, so only the unlock is actually awaited.
        transport.nextUnlockResult = WriteResult.Ok
        suppressing.restore(target.key)
        assertEquals(0, transport.userLockedFields and RecordingTransport.IMPORTANCE_LOCK)
        assertNull(store.snapshotFor(target.key))
    }

    @Test
    fun `the panic path and reconciliation release the lock too, because they are the same restore`() {
        val suppressing = suppressingActuator()
        suppressing.apply(target, snapshot(), CooldownState.MUTED)

        val results = suppressing.restoreAll()

        assertEquals(listOf(target.key to WriteResult.Ok), results)
        assertEquals(0, transport.userLockedFields and RecordingTransport.IMPORTANCE_LOCK)
        assertEquals(listOf(target), transport.unlocks)
    }

    @Test
    fun `a stand-down releases the lock as well, since it restores rather than drops the record`() {
        val suppressing = suppressingActuator()
        suppressing.apply(target, snapshot(), CooldownState.MUTED)

        // A channel that plays nothing is the state a cooldown leaves behind, so the stand-down is a
        // restore — and a restore that skipped the unlock would leave the bit with no owner.
        assertEquals(WriteResult.Ok, suppressing.standDown(target.key, transport.observed()))

        assertEquals(0, transport.userLockedFields and RecordingTransport.IMPORTANCE_LOCK)
        assertNull(store.snapshotFor(target.key))
    }

    @Test
    fun `a transport that cannot release the lock is never allowed to set it`() {
        transport.canUnlockImportance = false
        val suppressing = suppressingActuator()

        suppressing.apply(target, snapshot(), CooldownState.MUTED)

        // Fail closed, and said out loud rather than silently skipped: `USER_LOCKED_IMPORTANCE` is
        // clearable only by transaction 43, so a transport that cannot send it must not be the one to
        // set the bit. The demotion is refused, not attempted.
        assertEquals(4, transport.lastEdit()!!.fields.importance)
        assertEquals(0, transport.userLockedFields and RecordingTransport.IMPORTANCE_LOCK)
        assertTrue(FidelityGap.HEADS_UP_SUPPRESS_UNRELEASABLE in transport.lastEdit()!!.gaps)
    }

    @Test
    fun `the refusal is per state, so a transport without transaction 43 still mutes`() {
        transport.canUnlockImportance = false
        val suppressing = suppressingActuator()

        suppressing.apply(target, snapshot(), CooldownState.POLITE)

        // POLITE's demotion is refused but nothing else about the state moves: the head-up option is
        // not allowed to take the sound, the vibration or the island with it.
        val fields = transport.lastEdit()!!.fields
        assertEquals(4, fields.importance)
        assertNull(fields.soundUri)
        assertEquals(listOf(0L, 77L, 200L, 77L), fields.vibrationPattern)
        assertTrue(FidelityGap.HEADS_UP_SUPPRESS_UNRELEASABLE in transport.lastEdit()!!.gaps)
    }
}
