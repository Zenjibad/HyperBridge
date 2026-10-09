package app.hypercooldown.engine

import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the *composition* of [CooldownEngine] and [ChannelStatePlanner].
 *
 * The engine is a lazy port of AOSP's strategy; the planner is the anticipatory half that installs
 * the state AOSP would assign to the **next** alert and relaxes it on timers. Neither is worth much
 * unless the pair reproduces AOSP's per-post state for an alert that arrives at an arbitrary time.
 *
 * Every expectation here is checked against [AospReference], a from-scratch transcription of
 * `PolitenessStrategy.getNextState` plus `StrategyPerApp.onNotificationPosted`. It is deliberately
 * *not* derived from the production code: a wrong input choice, a wrong re-plan cadence, or a missed
 * branch (the counter reset) has to show up as a divergence from the oracle rather than as agreement
 * with a re-implementation of the same mistake.
 */
class CooldownCompositionTest {

    private val config = EngineConfig()
    private val key = "0:com.example.chatter"

    /** Inter-arrival gaps that sit exactly on the strict inequalities the transition table uses. */
    private val boundaryGaps = longArrayOf(
        0L,
        config.timeoutMutedMs - 1,
        config.timeoutMutedMs,
        config.timeoutMutedMs + 1,
        config.timeoutPoliteMs - 1,
        config.timeoutPoliteMs,
        config.timeoutPoliteMs + 1,
    )

    // ---------------------------------------------------------------------------------------------
    // The oracle: AOSP's per-post logic, transcribed by hand.
    // ---------------------------------------------------------------------------------------------

    /**
     * AOSP's per-post logic as a small pure state machine.
     *
     * ```
     * next  = getNextState(currState, elapsed)
     * count = count + 1
     * if (currState == MUTED && count >= counterReset) { next = DEFAULT; count = 0 }
     * ```
     *
     * Note that the reset reads the **pre-post** state and overrides `next` unconditionally.
     *
     * The device model: a post whose state is MUTED has neither sound nor vibration, so it is not
     * audible and does not advance the clock (AOSP's `if (buzz || beep)` guard).
     */
    private class AospReference(private val config: EngineConfig) {
        private var state = CooldownState.DEFAULT
        private var lastAudibleAtMs: Long? = null
        private var count = 0

        fun onPost(nowMs: Long): CooldownState {
            val curr = state
            val elapsed = lastAudibleAtMs?.let { nowMs - it } ?: Long.MAX_VALUE

            var next = when (curr) {
                CooldownState.DEFAULT ->
                    if (elapsed < config.timeoutPoliteMs) CooldownState.POLITE else CooldownState.DEFAULT

                CooldownState.POLITE -> when {
                    elapsed < config.timeoutMutedMs -> CooldownState.MUTED
                    elapsed > config.timeoutPoliteMs -> CooldownState.DEFAULT
                    else -> CooldownState.POLITE
                }

                CooldownState.MUTED ->
                    if (elapsed > config.timeoutMutedMs) CooldownState.POLITE else CooldownState.MUTED
            }

            count += 1
            if (curr == CooldownState.MUTED && count >= config.counterReset) {
                next = CooldownState.DEFAULT
                count = 0
            }

            state = next
            if (next != CooldownState.MUTED) lastAudibleAtMs = nowMs
            return next
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The simulated client, following the client contract in spec 5.1.
    // ---------------------------------------------------------------------------------------------

    /**
     * A client that re-plans after **every** post, feeding `engine.stateOf(key)` (the state after
     * the last post, not the last *audible* alert), `engine.lastAudibleAtMs(key)` and the current
     * post count; installs `plan.stateAt(now)`; and applies each subsequent relaxation by
     * re-installing `plan.stateAt(now)` when `plan.nextChangeAfter(now)` elapses.
     *
     * Audibility is taken from the state the channel is actually carrying when the alert arrives,
     * not from what the engine assigns to the post, because that is what the device would do: an
     * alert whose installed state is MUTED does not play, so it must call
     * `onAlertPlayed(wasAudible = false)`.
     */
    private class SimulatedClient(private val config: EngineConfig, private val key: String) {
        private val engine = CooldownEngine(config)
        private var plan = ChannelStatePlanner.plan(CooldownState.DEFAULT, null, postedCount = 0, config = config)
        private var installedAtMs = Long.MIN_VALUE
        private var installed = CooldownState.DEFAULT

        /** Moves the channel forward to [atMs], applying every relaxation that has come due. */
        private fun settle(atMs: Long) {
            while (true) {
                val change = plan.nextChangeAfter(installedAtMs) ?: return
                if (change > atMs) return
                installed = plan.stateAt(change)
                installedAtMs = change
            }
        }

        /** Posts an alert at [atMs] and returns the state the channel carried when it arrived. */
        fun onPost(atMs: Long): CooldownState {
            settle(atMs)
            val played = installed

            engine.onNotificationPosted(key, atMs)
            engine.onAlertPlayed(key, wasAudible = played != CooldownState.MUTED, nowMs = atMs)
            plan = ChannelStatePlanner.plan(
                engine.stateOf(key),
                engine.lastAudibleAtMs(key),
                engine.postedCount(key),
                config,
            )
            installed = plan.stateAt(atMs)
            installedAtMs = atMs
            return played
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Differential run.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the installed state matches AOSP's per-post choice across thousands of randomised bursts`() {
        val random = Random(SEED)
        val regimeCounts = IntArray(Regime.entries.size)
        var arrivals = 0
        var divergences = 0
        val samples = mutableListOf<String>()

        repeat(BURSTS) { burst ->
            val client = SimulatedClient(config, "$key:$burst")
            val reference = AospReference(config)
            var now = 0L

            repeat(1 + random.nextInt(MAX_BURST_LENGTH)) { arrival ->
                val atMs = now
                val expected = reference.onPost(atMs)
                val played = client.onPost(atMs)
                arrivals += 1

                if (played != expected) {
                    divergences += 1
                    if (samples.size < MAX_SAMPLES) {
                        samples += "burst $burst arrival #$arrival at +${atMs}ms: " +
                            "installed $played where AOSP says $expected"
                    }
                }

                now = atMs + nextGap(random) { regime -> regimeCounts[regime.ordinal] += 1 }
            }
        }

        assertEquals(
            0,
            divergences,
            "$divergences of $arrivals arrivals diverged from AOSP's per-post state" +
                " (regimes: sub-T2 ${regimeCounts[Regime.SUB_T2.ordinal]}," +
                " T2-T1 ${regimeCounts[Regime.T2_TO_T1.ordinal]}," +
                " beyond-T1 ${regimeCounts[Regime.BEYOND_T1.ordinal]}," +
                " boundary ${regimeCounts[Regime.BOUNDARY.ordinal]})" +
                if (samples.isEmpty()) "" else ":\n" + samples.joinToString("\n"),
        )

        // The burst generator must actually have exercised all three realistic regimes, otherwise a
        // green run would mean nothing.
        for (regime in Regime.entries) {
            assertTrue(regimeCounts[regime.ordinal] > 0) { "no gaps generated for $regime" }
        }
    }

    private enum class Regime { SUB_T2, T2_TO_T1, BEYOND_T1, BOUNDARY }

    private fun nextGap(random: Random, onRegime: (Regime) -> Unit): Long {
        val regime = Regime.entries[random.nextInt(Regime.entries.size)]
        onRegime(regime)
        return when (regime) {
            // 0 .. T2-1: a burst, so the key slides towards MUTED.
            Regime.SUB_T2 -> random.nextLong(config.timeoutMutedMs)
            // T2 .. T1: the wide middle of the table, where POLITE holds.
            Regime.T2_TO_T1 ->
                config.timeoutMutedMs + random.nextLong(config.timeoutPoliteMs - config.timeoutMutedMs + 1)
            // Past T1: the key must fall back out of the cooldown.
            Regime.BEYOND_T1 -> config.timeoutPoliteMs + 1 + random.nextLong(300_000)
            // The strict-inequality edges, plus 0.
            Regime.BOUNDARY -> boundaryGaps[random.nextInt(boundaryGaps.size)]
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Hand-traced regressions.
    // ---------------------------------------------------------------------------------------------

    /**
     * The reviewer's cascade. The tenth post arrives while the key is MUTED with the counter at 9,
     * so AOSP's reset overrides the MUTED transition and the post plays at **DEFAULT**: full volume,
     * right in the middle of a burst. A planner that only projects `(state, elapsed)` installs MUTED
     * there, under-alerts, and then cascades because the silenced post never advances the clock.
     */
    @Test
    fun `the reviewer's ten second cascade plays the tenth post at DEFAULT`() {
        val states = replay(
            listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 9_000L, 65_000L),
        )

        assertEquals(
            listOf(
                CooldownState.DEFAULT,   // t =     0  no history
                CooldownState.POLITE,    // t = 1_000  elapsed 1_000 < T1
                CooldownState.MUTED,     // t = 2_000  elapsed 1_000 < T2
                CooldownState.MUTED,     // t = 3_000  count 4
                CooldownState.MUTED,     // t = 4_000  count 5
                CooldownState.MUTED,     // t = 5_000  count 6
                CooldownState.MUTED,     // t = 6_000  count 7
                CooldownState.MUTED,     // t = 7_000  count 8
                CooldownState.MUTED,     // t = 8_000  count 9
                CooldownState.DEFAULT,   // t = 9_000  count 10 with pre-post MUTED -> reset
                CooldownState.POLITE,    // t = 65_000 elapsed 56_000 < T1
            ),
            states,
        )
    }

    /**
     * The same threshold from the other side: with the counter at 8 the next post is still MUTED,
     * and only the post after that resets. This is what makes the planner's `counterReset - 1`
     * anticipation exact rather than merely safe.
     */
    @Test
    fun `one post below the reset the key is still MUTED`() {
        val states = replay(
            listOf(0L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 9_000L, 10_000L, 11_000L),
        )

        assertEquals(
            listOf(
                CooldownState.DEFAULT,   // t =      0
                CooldownState.POLITE,    // t =  3_000
                CooldownState.MUTED,     // t =  4_000  elapsed 1_000 < T2
                CooldownState.MUTED,     // t =  5_000  count 4
                CooldownState.MUTED,     // t =  6_000  count 5
                CooldownState.MUTED,     // t =  7_000  count 6
                CooldownState.MUTED,     // t =  8_000  count 7
                CooldownState.MUTED,     // t =  9_000  count 8
                CooldownState.MUTED,     // t = 10_000  count 9 -> below the reset, still silent
                CooldownState.DEFAULT,   // t = 11_000  count 10 with pre-post MUTED -> reset
            ),
            states,
        )
    }

    /**
     * The spec trap: re-planning only after *audible* alerts and feeding the state of the last
     * audible alert is wrong, because AOSP's `currState` is the state after the last **post**. The
     * post at 6 s is silenced, so a spec-literal client would still be holding the 3 s plan when the
     * 76 s alert arrives and would install DEFAULT; AOSP installs POLITE.
     */
    @Test
    fun `a silenced post is fed back into the plan, so 76 seconds later the state is POLITE`() {
        val states = replay(listOf(0L, 3_000L, 6_000L, 76_000L))

        assertEquals(
            listOf(
                CooldownState.DEFAULT,   // t =     0
                CooldownState.POLITE,    // t = 3_000  elapsed 3_000 < T1
                CooldownState.MUTED,     // t = 6_000  elapsed 3_000 < T2 (silenced: clock stays at 3_000)
                CooldownState.POLITE,    // t = 76_000 elapsed 73_000 > T2, and MUTED never goes to DEFAULT
            ),
            states,
        )
    }

    /**
     * A MUTED key with a gap beyond T1 still relaxes to POLITE, never straight to DEFAULT: the
     * relaxation ladder must be installed, not merely the state for the arrival that triggered it.
     */
    @Test
    fun `a long quiet gap relaxes MUTED to POLITE, never to DEFAULT`() {
        val states = replay(listOf(0L, 3_000L, 4_000L, 400_000L))

        assertEquals(
            listOf(
                CooldownState.DEFAULT,   // t =      0
                CooldownState.POLITE,    // t =  3_000
                CooldownState.MUTED,     // t =  4_000  elapsed 1_000 < T2 (silenced)
                CooldownState.POLITE,    // t = 400_000 elapsed 397_000 > T2 -> POLITE, not DEFAULT
            ),
            states,
        )
    }

    /**
     * Runs [arrivals] through a fresh simulated client, asserting at every arrival that the state
     * the channel carried equals the state the reference model chose, and returning what played.
     */
    private fun replay(arrivals: List<Long>): List<CooldownState> {
        val client = SimulatedClient(config, key)
        val reference = AospReference(config)
        return arrivals.map { atMs ->
            val expected = reference.onPost(atMs)
            val played = client.onPost(atMs)
            assertEquals(expected, played, "installed state at +${atMs}ms")
            played
        }
    }

    private companion object {
        /** Fixed so a failure is reproducible. */
        const val SEED = 20_261_009L
        const val BURSTS = 4_000
        const val MAX_BURST_LENGTH = 40
        const val MAX_SAMPLES = 8
    }
}
