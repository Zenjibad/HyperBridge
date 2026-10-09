package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two defects a device run measured in POLITE's sound path, pinned at the level that decides them.
 *
 *  1. **The copy lands one write too late.** The read, the `MediaCodec` decode and the MediaStore
 *     publish run on the sound worker and finish 60-80 ms after the post's own POLITE write, so the
 *     channel is left with `mSound=null` for the alert POLITE was installed for and that alert plays
 *     silently. [CooldownCoordinator.onPoliteSoundReady] is the re-apply that closes it, and every way
 *     those 60-80 ms can go wrong (a stand-down, a restore, a relaxation that came due) has to refuse.
 *  2. **The copy must never become the next source.** `0.30` of the user's sound is a sound like any
 *     other once the channel carries it, so a second attenuation is `0.09` — measured as peak
 *     6497 -> 1949. [CooldownCoordinator.pristineSnapshotFor] is what the wiring prewarms from, and
 *     this test pins that every POLITE edit is built from the recorded original and never from the
 *     channel as it arrived.
 *
 * `LatePoliteSound` is the factory's real cold-cache behaviour: it has no copy until it is published.
 * This module must not touch Android, so the copy's arrival is this fake's `ready` flag rather than a
 * worker thread; the naming is what makes the test still about the measured defect.
 */
class CooldownCoordinatorPoliteSoundTest {

    private val config = EngineConfig()
    private val clock = MutableClock()
    private val timer = RecordingTimer()
    private val store = InMemorySnapshotStore()
    private val transport = RecordingTransport()
    private val target = target()
    private val copyUri = "content://media/external/audio/media/2"
    private val pristineSound = channelFields().soundUri!!
    private val polite = LatePoliteSound(copyUri)
    private val actuator = ChannelActuator(transport, store, polite, config)
    private val coordinator = CooldownCoordinator(config, actuator, clock, timer)

    /** The factory as it behaves on a cold cache: null until the copy has been published. */
    private class LatePoliteSound(private val copyUri: String) : PoliteSoundSource {
        var ready = false

        /** The channel sound every POLITE edit asked to attenuate, in order. */
        val asked = mutableListOf<String?>()

        override fun politeSoundFor(snapshot: ChannelSnapshot): String? {
            asked += snapshot.fields.soundUri
            return if (ready) copyUri else null
        }
    }

    private fun event() = AlertEvent(
        target = target,
        // What the platform would hand back on arrival: after the first write, our own damping.
        snapshot = transport.observed(),
        rankingLastAudiblyAlertedMs = -1L,
    )

    private fun post(atMs: Long): CooldownCoordinator.PostOutcome {
        clock.nowMs = atMs
        return coordinator.onPost(event())
    }

    @Test
    fun `the copy arriving after the post is written into the POLITE that post installed`() {
        val outcome = post(0)
        assertEquals(CooldownState.POLITE, outcome.nextState)
        // The measured cold start: the write for the next alert is installed before the copy exists.
        assertNull(transport.lastEdit()!!.fields.soundUri)
        assertTrue(FidelityGap.POLITE_SOUND_UNAVAILABLE in transport.lastEdit()!!.gaps)
        assertTrue(FidelityGap.POLITE_SOUND_UNAVAILABLE in outcome.gaps)

        // The worker finishes with the copy 60-80 ms later, and that is what closes the race.
        polite.ready = true
        assertEquals(WriteResult.Ok, coordinator.onPoliteSoundReady(target.key))

        assertEquals(2, transport.calls.size, "the post's write, and the re-apply that carries the copy")
        assertEquals(copyUri, transport.lastEdit()!!.fields.soundUri)
        assertTrue(
            transport.lastEdit()!!.gaps.isEmpty(),
            "the write that carries the copy records no sound gap: ${transport.lastEdit()!!.gaps}",
        )
        // And it is POLITE that was re-made, not some other state: one state for the next alert.
        assertEquals(CooldownState.POLITE, coordinator.installedFor(target.key))
        assertEquals(
            listOf(pristineSound, pristineSound),
            polite.asked,
            "the re-apply attenuates the user's sound again, never the copy the channel now carries",
        )
    }

    @Test
    fun `a re-apply after a restore writes nothing, so a restored channel is never resurrected`() {
        post(0)
        coordinator.reconcile()
        val afterRestore = transport.calls.size
        assertNull(timer.scheduled[target.key], "a restore leaves no episode behind")

        polite.ready = true
        assertNull(
            coordinator.onPoliteSoundReady(target.key),
            "a hand with no plan must not be able to damp a channel again",
        )
        assertEquals(afterRestore, transport.calls.size)
    }

    @Test
    fun `a re-apply after a user tap writes nothing`() {
        post(0)
        coordinator.onUserInteraction(target.key)
        val afterTap = transport.calls.size

        polite.ready = true

        assertNull(coordinator.onPoliteSoundReady(target.key))
        assertEquals(afterTap, transport.calls.size)
    }

    @Test
    fun `a re-apply after the plan has moved on to MUTED does not put POLITE back`() {
        post(0)
        post(4_000)
        assertEquals(CooldownState.MUTED, coordinator.installedFor(target.key))
        val afterSecondPost = transport.calls.size

        polite.ready = true

        // Re-installing POLITE here would turn a muted channel audible again, which is the one thing
        // the coordinator's own writes must never be fought over.
        assertNull(coordinator.onPoliteSoundReady(target.key))
        assertEquals(afterSecondPost, transport.calls.size)
    }

    @Test
    fun `a relaxation that came due but whose alarm has not landed is not overwritten by a late copy`() {
        post(0)
        val deadline = timer.scheduled[target.key]!!
        val afterFirstPost = transport.calls.size

        // The alert path cannot rule this out: the alarm broadcast may not have been delivered yet when
        // the copy lands. The plan is the authority on what the channel must carry *now*.
        clock.nowMs = deadline
        polite.ready = true

        assertNull(coordinator.onPoliteSoundReady(target.key))
        assertEquals(afterFirstPost, transport.calls.size)
    }

    @Test
    fun `the pristine sound is what POLITE attenuates, even once the channel carries our copy`() {
        // A warm cache, so POLITE's own write carries the copy: from here on the live channel's sound
        // *is* the copy this app published, and a caller that prewarmed from it would attenuate 0.30 a
        // second time -- the peak 6497 -> 1949 the device measured.
        polite.ready = true
        post(0)

        assertEquals(copyUri, transport.observed().fields.soundUri, "the live channel carries our copy")
        assertEquals(pristineSound, coordinator.pristineSnapshotFor(target.key)!!.fields.soundUri)
        assertNotEquals(
            transport.observed().fields.soundUri,
            coordinator.pristineSnapshotFor(target.key)!!.fields.soundUri,
        )
        assertEquals(
            listOf(pristineSound),
            polite.asked,
            "the copy the channel carries is never a source",
        )
    }
}
