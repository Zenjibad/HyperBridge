package app.hypercooldown.actuation

import app.hypercooldown.engine.ChannelStatePlanner
import app.hypercooldown.engine.CooldownEngine
import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The load-bearing test of the whole client contract (spec §5.1).
 *
 * With the pre-emptive plan installed correctly, the state the channel is carrying when an alert
 * arrives must be exactly the state AOSP's `StrategyPerApp.onNotificationPosted` assigns to that
 * alert. That makes the engine itself the oracle — no transcribed transition table is needed here,
 * because `:engine` already proves its own equivalence — and it fails loudly when the cadence drifts:
 * re-planning only after audible posts, feeding the engine the pre-post state, forgetting
 * `postedCount`, or not clearing the vibration flag on MUTED all show up as a divergence.
 */
class CooldownCoordinatorDifferentialTest {

    private val config = EngineConfig()

    @Test
    fun `the installed state is AOSP's choice for every arrival, across randomised bursts`() {
        val random = Random(SEED)
        var arrivals = 0
        var divergences = 0
        var mutedArrivals = 0
        var counterResets = 0
        var silentArrivals = 0
        val samples = mutableListOf<String>()

        repeat(BURSTS) { burst ->
            val packageName = "com.example.burst$burst"
            val target = ChannelTarget.perApp(userId = 0, packageName = packageName, uid = 10_000 + burst)
            val device = FakeChannelDevice()
            val clock = MutableClock()
            val timer = RecordingTimer()
            val store = InMemorySnapshotStore()
            val coordinator = CooldownCoordinator(
                config = config,
                actuator = ChannelActuator(device, store, NoPoliteSound, config),
                clock = clock,
                timer = timer,
            )
            val oracle = CooldownEngine(config)

            var at = random.nextLong(0, 1_000)
            repeat(random.nextInt(2, MAX_ARRIVALS)) { step ->
                // Three arrivals in four land inside a cooldown window, so POLITE, MUTED and the
                // counter reset are all reached; the fourth lands far out, so the relaxations are
                // exercised too.
                val stepMs = if (random.nextInt(4) == 0) random.nextLong(0, 120_000) else random.nextLong(0, 12_000)

                // One arrival in three is aimed at the exact instant a relaxation comes due. That is
                // the interleaving this ordering exists for, and a random arrival would essentially
                // never land on the boundary. With the deadline's alarm not yet delivered, `settle`
                // inside `onPost` does real work, so classifying before settling is observably
                // different from settling first; when the alarm *has* already been delivered, `settle`
                // at the arrival is a strict no-op and the two orderings cannot be told apart at all.
                val deadline = timer.scheduled[target.key]?.takeIf { random.nextInt(3) == 0 }
                val late = deadline != null
                at = deadline ?: (at + stepMs)

                // The alarm the real client would have received, replayed before the arrival is judged.
                // `RelaxationTimer`'s device implementation is an `AlarmManager` alarm that lands on
                // `onRelaxationDue`, so at every arrival the channel already carries the state the plan
                // put there. Without this the fake channel still holds the previous post's pre-arm,
                // which is not a channel the device would ever have presented to the platform.
                //
                // A deadline arrival deliberately leaves its own alarm undelivered — the "the post
                // callback won the race" case, which the listener cannot rule out and the coordinator
                // has to survive.
                while (true) {
                    val due = timer.scheduled[target.key] ?: break
                    if (due > at) break
                    if (late && due == at) break
                    clock.nowMs = due
                    coordinator.onRelaxationDue(target.key)
                }
                clock.nowMs = at

                val installed = device.stateFor(packageName)
                // The oracle's own pre-post state: the reset is a transition out of MUTED, and it
                // reads it off AOSP's state rather than off the channel, because the plan pre-arms
                // the reset (DEFAULT is installed one post early), so the channel is no longer MUTED
                // by the time the reset actually happens.
                val beforeState = oracle.stateOf(target.key)
                val expected = oracle.onNotificationPosted(target.key, at)
                // Whether *this* alert played is a result of the state AOSP assigned it (spec §5.1),
                // and MUTED is the only state the device plays nothing for. A deadline arrival's
                // channel is still one segment behind — its alarm has not been delivered — so the
                // lagging content is not what this alert's audibility may be read from.
                val audible = if (late) expected != CooldownState.MUTED else device.wouldAlert(packageName)
                oracle.onAlertPlayed(target.key, wasAudible = audible, nowMs = at)

                val outcome = coordinator.onPost(
                    AlertEvent(
                        target = target,
                        snapshot = device.snapshotFor(packageName),
                        rankingLastAudiblyAlertedMs = -1,
                    ),
                )

                // What the channel must carry once the post has been handled: the state AOSP assigns
                // the *next* alert. Read off :engine's planner, the same one the coordinator uses.
                val nextPlan = ChannelStatePlanner.plan(
                    oracle.stateOf(target.key),
                    oracle.lastAudibleAtMs(target.key),
                    oracle.postedCount(target.key),
                    config,
                )
                val next = nextPlan.stateAt(at)
                val armed = device.stateFor(packageName) == next

                // Nothing but the coordinator ever touches these channels, so a record that went away
                // is a stand-down that had no business happening — that is the ordering bug, and it
                // is the failure that leaves a channel muted with nothing able to find it again.
                val abandoned = store.outstanding().isEmpty() && device.stateFor(packageName) != CooldownState.DEFAULT

                arrivals++
                if (!audible) silentArrivals++
                if (installed == CooldownState.MUTED) mutedArrivals++
                if (beforeState == CooldownState.MUTED && expected == CooldownState.DEFAULT) counterResets++
                // Four ways the client contract can break, and only the first is waived — a deadline
                // arrival's channel is one segment behind by construction, because its alarm was
                // deliberately not delivered. The other three hold at every arrival:
                //  - the model must be AOSP's assignment, whatever the channel still carries;
                //  - the channel must be armed for the next alert once the post has been handled;
                //  - and a channel still carrying a damping must still have its record, because that
                //    record is the only thing that can ever put it back (spec §6).
                val modelled = outcome.installedState != expected
                val behind = !late && installed != expected
                if (behind || modelled || !armed || abandoned) {
                    divergences++
                    if (samples.size < 5) {
                        samples += "burst=$burst step=$step at=$at late=$late installed=$installed " +
                            "outcome=${outcome.installedState} aosp=$expected modelled=$modelled behind=$behind " +
                            "after=${device.stateFor(packageName)} armed=$armed next=$next " +
                            "abandoned=$abandoned applied=${outcome.applied}"
                    }
                }
            }
        }

        assertEquals(
            0,
            divergences,
            "$divergences of $arrivals arrivals diverged from AOSP:\n${samples.joinToString("\n")}",
        )
        assertTrue(silentArrivals > 0, "the burst mix never produced a silent alert")
        assertTrue(mutedArrivals > 0, "the burst mix never reached MUTED")
        assertTrue(counterResets > 0, "the burst mix never exercised the counter reset")
    }

    @Test
    fun `a channel that cannot vibrate still plays its own sound at POLITE, and the device agrees`() {
        // Re-derived, and the deviation this test used to record is gone rather than tolerated. It read
        // "with no sound copy (NoPoliteSound) and no vibration the device plays nothing at POLITE, while
        // spec §5.1's rule tells the engine the alert played" — and that was not a harmless fidelity
        // gap: a POLITE write that clears the sound is what left `com.instagram.android/ig_direct`
        // silent and stranded on the device, because a silent channel never enters cooldown again
        // (root-cause §3.5, §3.6). POLITE now keeps the channel's own sound when there is no copy to
        // install, so the device plays it and its read-back is POLITE, exactly as the engine's model has
        // it. The only shape that still plays nothing at POLITE is a channel with no sound *and* no
        // vibration, and that channel can never enter a cooldown at all: `alertWasAudible` reads it as
        // silent.
        val packageName = "com.example.silent"
        val target = ChannelTarget.perApp(userId = 0, packageName = packageName, uid = 10_500)
        val device = FakeChannelDevice()
        val clock = MutableClock()
        val store = InMemorySnapshotStore()
        // A channel the seed never touches has the fixture's vibration; write a silent one first.
        device.write(
            target,
            snapshot(),
            ChannelEdit(channelFields(vibrationEnabled = false, vibrationPattern = null)),
        )
        val coordinator = CooldownCoordinator(
            config = config,
            actuator = ChannelActuator(device, store, NoPoliteSound, config),
            clock = clock,
            timer = RecordingTimer(),
        )

        clock.nowMs = 0
        coordinator.onPost(AlertEvent(target, device.snapshotFor(packageName), -1))
        clock.nowMs = 4_000
        // What the second alert arrives in, read before it is judged: `stateFor` names a state by what
        // it would play, and POLITE's mark on a channel that cannot vibrate is the sound it *kept* --
        // so the read-back, the audibility and the model all agree.
        val at = device.stateFor(packageName)
        val audible = device.wouldAlert(packageName)
        val outcome = coordinator.onPost(AlertEvent(target, device.snapshotFor(packageName), -1))

        assertEquals(CooldownState.POLITE, at, "the channel still has its own sound, so it is POLITE")
        assertTrue(audible, "and the alert does play, at the channel's own volume")
        assertEquals(CooldownState.POLITE, outcome.installedState)
    }

    private companion object {
        const val SEED = 20261009L
        const val BURSTS = 200
        const val MAX_ARRIVALS = 30
    }
}
