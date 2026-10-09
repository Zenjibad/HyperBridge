package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The regression a device run measured on 2026-10-09, reproduced on the JVM.
 *
 * On a ROM with `Flags.notificationChannelVibrationEffectApi()` — this one —
 * `NotificationChannel.setVibrationPattern` also assigns
 * `mVibrationEffect = VibrationEffect.createWaveform(pattern, -1)`
 * (`research/aosp/NotificationChannel.java:728-731`), and `writeToParcel` carries that effect into
 * the stored channel (`:443-449`, read back at `:382-388`). A write-ahead record built from the
 * *intended* edit therefore never contains what the channel hands back on the next post, so
 * `ChannelActuator.classify` reads the app's own damping as somebody else's change:
 *
 *  - `CooldownCoordinator` stands the episode down, so MUTED is never reached and nothing is
 *    silenced — measured as `installed=POLITE next=DEFAULT` on every post (`CooldownCoordinator.kt:113,122`);
 *  - `ChannelActuator.standDown` drops the record of a channel that plays something, so
 *    `restore-all` reports `0 restored` and the damping can no longer be undone;
 *  - the next post is FRESH, re-snapshots a channel that is already carrying the scaled pattern, and
 *    scales it again — measured as `255 -> 77 -> 23 -> 7 -> 2` across a session, which destroys the
 *    user's own value with nothing left to restore it.
 *
 * [DerivingChannelDevice] is this ROM in miniature, so every test here runs against the platform
 * behaviour that caused the regression rather than against the record of an intention.
 */
class DerivedVibrationEffectTest {

    private val config = EngineConfig()
    private val device = DerivingChannelDevice()
    private val store = InMemorySnapshotStore()
    private val target = target()
    private val actuator = ChannelActuator(device, store, NoPoliteSound, config)
    private val clock = MutableClock()
    private val timer = RecordingTimer()
    private val coordinator = CooldownCoordinator(config, actuator, clock, timer)

    private val packageName: String get() = target.packageName

    private fun post(atMs: Long): CooldownCoordinator.PostOutcome {
        clock.nowMs = atMs
        return coordinator.onPost(AlertEvent(target, device.snapshotFor(packageName), -1))
    }

    private fun observed() = device.snapshotFor(packageName)

    /** The pattern `EditFactory` produces for POLITE from the user's own value, scaled exactly once. */
    private fun onceScaled(pattern: List<Long>): List<Long> =
        VibrationScaler.scale(pattern, config.politeVolume)

    @Test
    fun `a platform-derived effect on our write is still our own write`() {
        val pristine = observed()
        assertNotNull(
            pristine.fields.vibrationEffectBytes,
            "the fixture channel arrives with the effect this platform derives from its pattern",
        )

        val polite = EditFactory.editFor(pristine, CooldownState.POLITE, config, NoPoliteSound)
        assertEquals(WriteResult.Ok, actuator.apply(target, pristine, CooldownState.POLITE))

        val after = observed()
        assertNotEquals(
            polite.fields,
            after.fields,
            "the platform derived an effect the edit did not carry — the premise of this test",
        )
        assertNotNull(after.fields.vibrationEffectBytes)

        assertEquals(
            ChannelActuator.Observed.OURS,
            actuator.classify(target.key, after),
            "our own damping write was read back as somebody else's change",
        )
        assertEquals(
            after.fields,
            store.snapshotFor(target.key)!!.written,
            "the write-ahead record holds what the channel contains, not what we intended to write",
        )
    }

    @Test
    fun `a stand-down never drops the record of a channel still carrying our own write`() {
        val pristine = observed()
        assertEquals(WriteResult.Ok, actuator.apply(target, pristine, CooldownState.POLITE))
        val damped = observed()

        assertEquals(WriteResult.Ok, actuator.standDown(target.key, damped))

        assertEquals(
            pristine.fields,
            observed().fields,
            "a channel carrying our own write is not somebody else's change: it is put back",
        )
        assertNull(store.snapshotFor(target.key), "and the restore write is what dropped the record")
    }

    @Test
    fun `repeated damp and undamp episodes never rescale the original twice`() {
        val original = observed()
        val pattern = requireNotNull(original.fields.vibrationPattern)

        repeat(EPISODES) { episode ->
            val base = episode * EPISODE_GAP_MS
            post(base)
            assertEquals(
                onceScaled(pattern),
                observed().fields.vibrationPattern,
                "episode $episode scaled a pattern that had already been scaled",
            )
            post(base + 4_000L)

            val restored = coordinator.reconcile()
            assertEquals(
                listOf(target.key),
                restored.map { it.first },
                "episode $episode left nothing to restore, so the next episode would take our " +
                    "damping for the user's original",
            )
            assertTrue(restored.all { it.second == WriteResult.Ok })
            assertEquals(
                original.fields,
                observed().fields,
                "episode $episode did not put the user's original back",
            )
            assertTrue(store.outstanding().isEmpty())
        }
    }

    @Test
    fun `a burst reaches MUTED and stays there instead of standing down on every post`() {
        val arrivals = (0 until 4).map { step -> post(step * 4_000L) }

        assertEquals(
            listOf(
                CooldownState.DEFAULT,
                CooldownState.POLITE,
                CooldownState.MUTED,
                CooldownState.MUTED,
            ),
            arrivals.map { it.installedState },
            "the channel the alert arrived at, post by post",
        )
        assertFalse(device.wouldAlert(packageName), "alerts 3 and 4 arrive at a channel that plays nothing")
        assertNotNull(store.snapshotFor(target.key), "the record survived the burst, so damping can be undone")
    }

    @Test
    fun `a lost record is never filled in with our own damping`() {
        val pristine = observed()
        assertEquals(WriteResult.Ok, actuator.apply(target, pristine, CooldownState.POLITE))
        val damped = observed()
        // The record is gone — an older build's stand-down dropped it, and a wiped file would do the
        // same — while the channel still carries the scaled pattern we wrote.
        store.clear(target.key)

        val result = actuator.apply(target, damped, CooldownState.MUTED)

        assertTrue(
            result is WriteResult.Failed,
            "re-snapshotting our own damping must fail loud rather than compound: $result",
        )
        assertNull(store.snapshotFor(target.key), "our damping was not recorded as the user's original")
        assertEquals(damped.fields, observed().fields, "and nothing was written over it")
    }

    /**
     * The vibration half of `AttenuatedSoundFactoryTest > a prewarm handed a copy this factory published
     * is refused at the origin`, on the path this platform actually takes.
     *
     * The sound's source is guarded per *field*: `SoundCopyCache` remembers every copy this process
     * published and `sourceOf` refuses one as a source, whatever snapshot it arrives in. The vibration's
     * source had only the whole-`ChannelFields` comparison against the in-process "what I last wrote"
     * map — and a stand-down *empties* that map (`ChannelActuator.standDown` drops the record and the
     * marker for a channel somebody else made audible). So an origin app that changes any *other* field
     * of its own channel — its sound, which on this device is republished as a new MediaStore row by
     * every process — leaves the scaled pattern this app wrote on the channel with nothing left that
     * remembers writing it, and the next alert re-records it as "the pristine original" and scales it
     * again: 0.30 x 0.30 = 9 %. Measured on the device as the record's `written.vibrationPattern` and
     * the channel's `mVibrationPattern` disagreeing (decision record §8).
     */
    @Test
    fun `a pattern this app wrote is never taken as the pristine source`() {
        val pristine = observed()
        val pattern = requireNotNull(pristine.fields.vibrationPattern)
        assertEquals(WriteResult.Ok, actuator.apply(target, pristine, CooldownState.POLITE))
        val damped = observed()
        assertEquals(
            onceScaled(pattern),
            damped.fields.vibrationPattern,
            "premise: our own scaled pattern is what the channel carries",
        )

        // The origin app re-creates its own channel with a different sound. That is its change, not
        // ours, so the episode stands down — which drops the record *and* this process's marker, while
        // the scaled pattern we wrote stays on the channel.
        device.write(target, damped, ChannelEdit(fields = damped.fields.copy(soundUri = OTHER_SOUND)))
        val foreign = observed()
        assertEquals(WriteResult.Ok, actuator.standDown(target.key, foreign))
        assertNull(store.snapshotFor(target.key), "premise: the stand-down dropped the record")

        val result = actuator.apply(target, foreign, CooldownState.POLITE)

        assertTrue(
            result is WriteResult.Failed,
            "a pattern this app wrote was taken as the pristine source and scaled again: $result",
        )
        assertNull(
            store.snapshotFor(target.key),
            "our own 30 % pattern was recorded as the user's original",
        )
        assertEquals(foreign.fields, observed().fields, "and nothing was written over it")
    }

    /**
     * The same defect end to end, through the coordinator: two consecutive episodes must leave the
     * amplitude where it was, never at 9 %.
     *
     * The sound half of this is pinned by `AttenuatedSoundFactoryTest > two consecutive prewarms for one
     * key leave the attenuated peak where it was` (peak 6497 -> 1949 before the fix). This is the
     * vibration's: episode 1 installs POLITE's 0.30, the origin moves its own sound, and episode 2 —
     * arriving on a channel with no record, carrying the pattern this app wrote — must not scale it a
     * second time.
     */
    @Test
    fun `two consecutive episodes leave the vibration amplitude where it was, never 9 percent`() {
        val original = observed()
        val pattern = requireNotNull(original.fields.vibrationPattern)

        post(0L)
        assertEquals(
            onceScaled(pattern),
            observed().fields.vibrationPattern,
            "episode 1 did not install POLITE's 0.30",
        )

        val damped = observed()
        device.write(target, damped, ChannelEdit(fields = damped.fields.copy(soundUri = OTHER_SOUND)))
        // The stand-down episode: the channel carries a change that is not ours.
        post(10_000L)
        assertNull(store.snapshotFor(target.key), "premise: the stand-down dropped the record")

        post(20_000L)

        assertEquals(
            onceScaled(pattern),
            observed().fields.vibrationPattern,
            "0.30 of the user's pattern, twice at most and never 0.30 then 0.09",
        )
        assertEquals(
            OTHER_SOUND,
            observed().fields.soundUri,
            "and the app's own sound change is not written over",
        )
    }

    /**
     * Spec §6's safety property, re-verified against this platform rather than assumed: a record is
     * cleared only by a restore write that came back `Ok`, and a restore that failed leaves the record
     * exactly where it is.
     */
    @Test
    fun `on this path a record is still only cleared by a successful restore`() {
        val pristine = observed()
        assertEquals(WriteResult.Ok, actuator.apply(target, pristine, CooldownState.MUTED))
        val muted = observed()
        assertEquals(CooldownState.MUTED, device.stateFor(packageName), "the channel plays nothing")

        device.nextResult = WriteResult.Failed("binder died")
        assertEquals(WriteResult.Failed("binder died"), actuator.standDown(target.key, muted))
        assertNotNull(store.snapshotFor(target.key), "the record survives a restore that could not be written")

        device.nextResult = WriteResult.Ok
        assertEquals(WriteResult.Ok, actuator.standDown(target.key, muted))
        assertEquals(pristine.fields, observed().fields, "the successful restore put the user's value back")
        assertNull(store.snapshotFor(target.key))
    }

    private companion object {
        const val EPISODES = 3

        /** Far enough apart that one episode cannot see the previous one's alarm deadlines. */
        const val EPISODE_GAP_MS = 1_000_000L

        /**
         * A second published copy of the same sound, as this device produces: `mVibrationPattern` stays
         * ours while `mSound` moves to the next `content://media/external/audio/media/…` row.
         */
        const val OTHER_SOUND = "content://media/external/audio/media/8562"
    }
}
