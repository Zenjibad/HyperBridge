package app.hypercooldown.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Replays the exact scenario measured on the target device: an app posting on one channel at
 * varying intervals. The device was in vibrate mode, so `buzz` was set and `beep` was not — both
 * count as audible.
 */
class CooldownEngineGoldenTest {

    private val key = "0:com.example.spammer"

    /**
     * Replays [arrivals] through one engine.
     *
     * Audibility is derived from the state the engine returned for that post: on a real device only
     * an alert whose assigned state is MUTED is silent (sound and vibration are both null), so only
     * `state != MUTED` reaches [CooldownEngine.onAlertPlayed] as audible. Marking every post audible
     * would model a device whose silenced alerts still play, and would hide a regression in the
     * `if (buzz || beep)` guard.
     */
    private fun replay(vararg arrivals: Long): List<CooldownState> {
        val engine = CooldownEngine()
        return arrivals.map { atMs ->
            val state = engine.onNotificationPosted(key, atMs)
            engine.onAlertPlayed(key, wasAudible = state != CooldownState.MUTED, nowMs = atMs)
            state
        }
    }

    @Test
    fun `a three second burst goes DEFAULT then POLITE then MUTED`() {
        val states = replay(0L, 3_000L, 6_000L, 9_000L)

        assertEquals(
            listOf(
                CooldownState.DEFAULT,
                CooldownState.POLITE,
                CooldownState.MUTED,   // t = 6_000: elapsed 3_000 < T2, and silenced from here on
                CooldownState.MUTED,   // t = 9_000: elapsed 6_000 since the last audible alert
            ),
            states,
        )
    }

    @Test
    fun `the 16 second spacing that proved the native gate is off yields POLITE here`() {
        // On the device, two alerts 16.6 s apart both reported politenessState = 0. AOSP's table
        // says the second must be POLITE, because 16_600 < T1 (60_000). Our engine therefore
        // returns POLITE, and that mismatch is precisely the evidence that the ROM's native
        // feature was disabled -- it is not a disagreement about the algorithm.
        val states = replay(0L, 16_600L)

        assertEquals(listOf(CooldownState.DEFAULT, CooldownState.POLITE), states)
    }

    @Test
    fun `a long quiet gap returns to DEFAULT and then escalates again`() {
        val states = replay(0L, 3_000L, 200_000L, 203_000L)

        assertEquals(
            listOf(
                CooldownState.DEFAULT,   // t = 0
                CooldownState.POLITE,    // t = 3_000,   elapsed 3_000 < T1
                CooldownState.DEFAULT,   // t = 200_000, elapsed 197_000 > T1
                CooldownState.POLITE,    // t = 203_000, elapsed 3_000 < T1
            ),
            states,
        )
    }

    @Test
    fun `a POLITE alert is audible, so it does extend the window`() {
        // The counterpart of the silenced-alert test below: POLITE is a 30 % alert, so it plays and
        // does reset the 60 s window. 59_000 ms after the POLITE alert at 2 s the key is still
        // inside T1.
        val states = replay(0L, 2_000L, 61_000L)

        assertEquals(
            listOf(
                CooldownState.DEFAULT,   // t = 0
                CooldownState.POLITE,    // t = 2_000,   elapsed 2_000 < T1
                CooldownState.POLITE,    // t = 61_000,  elapsed 59_000 < T1 since the alert at 2_000
            ),
            states,
        )
    }

    @Test
    fun `a silenced alert does not extend the window`() {
        // Alert 1 is audible at t = 0 and alert 2 at t = 3_000 is audible at 30 %. Alert 3 at
        // t = 4_000 is assigned MUTED, so it does not play and must NOT become the new reference
        // point. At t = 13_500 the elapsed time since the last audible alert is 10_500 > T2, so the
        // key is out of the MUTED window and back to POLITE. Had the silenced alert counted, the
        // elapsed time would have been 9_500 (<= T2) and the state would still be MUTED.
        val states = replay(0L, 3_000L, 4_000L, 13_500L)

        assertEquals(
            listOf(
                CooldownState.DEFAULT,   // t = 0
                CooldownState.POLITE,    // t = 3_000
                CooldownState.MUTED,     // t = 4_000, elapsed 1_000 < T2 (silenced)
                CooldownState.POLITE,    // t = 13_500, elapsed 10_500 > T2 since the alert at 3_000
            ),
            states,
        )
    }
}
