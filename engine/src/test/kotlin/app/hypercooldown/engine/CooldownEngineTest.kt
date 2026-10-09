package app.hypercooldown.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CooldownEngineTest {

    private val key = "0:com.example.chat"

    @Test
    fun `a first alert is DEFAULT and an unknown key is DEFAULT`() {
        val engine = CooldownEngine()

        assertEquals(CooldownState.DEFAULT, engine.stateOf(key))
        assertEquals(CooldownState.DEFAULT, engine.onNotificationPosted(key, nowMs = 0))
        assertEquals(CooldownState.DEFAULT, engine.stateOf(key))
    }

    @Test
    fun `a second alert within T1 becomes POLITE`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, wasAudible = true, nowMs = 0)

        assertEquals(CooldownState.POLITE, engine.onNotificationPosted(key, 3_000))
    }

    @Test
    fun `a second alert at exactly T1 stays DEFAULT`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, wasAudible = true, nowMs = 0)

        assertEquals(CooldownState.DEFAULT, engine.onNotificationPosted(key, 60_000))
    }

    @Test
    fun `an alert within T2 of the last audible one becomes MUTED`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)      // DEFAULT -> POLITE
        engine.onAlertPlayed(key, true, 3_000)

        assertEquals(CooldownState.MUTED, engine.onNotificationPosted(key, 4_000))
    }

    @Test
    fun `an alert at exactly T2 stays POLITE because the POLITE test is strict`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)      // DEFAULT -> POLITE
        engine.onAlertPlayed(key, true, 3_000)

        // elapsed == 10_000: `elapsed < T2` is false and `elapsed > T1` is false -> POLITE
        assertEquals(CooldownState.POLITE, engine.onNotificationPosted(key, 13_000))
    }

    @Test
    fun `POLITE returns to DEFAULT once more than T1 has passed`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)      // POLITE
        engine.onAlertPlayed(key, true, 3_000)

        // elapsed == 60_001 > T1 -> DEFAULT
        assertEquals(CooldownState.DEFAULT, engine.onNotificationPosted(key, 63_001))
    }

    @Test
    fun `MUTED returns to POLITE, never straight to DEFAULT, once more than T2 has passed`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)      // POLITE
        engine.onAlertPlayed(key, true, 3_000)
        engine.onNotificationPosted(key, 4_000)      // MUTED
        engine.onAlertPlayed(key, true, 4_000)

        // elapsed == 10_001: greater than T2 but less than T1, so POLITE
        assertEquals(CooldownState.POLITE, engine.onNotificationPosted(key, 14_001))

        // And with a gap far beyond T1, MUTED still resolves to POLITE.
        val engine2 = CooldownEngine()
        engine2.onNotificationPosted(key, 0)
        engine2.onAlertPlayed(key, true, 0)
        engine2.onNotificationPosted(key, 3_000)
        engine2.onAlertPlayed(key, true, 3_000)
        engine2.onNotificationPosted(key, 4_000)
        engine2.onAlertPlayed(key, true, 4_000)
        assertEquals(CooldownState.POLITE, engine2.onNotificationPosted(key, 400_000))
    }

    @Test
    fun `a MUTED alert at exactly T2 stays MUTED because the MUTED test is strict`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)
        engine.onAlertPlayed(key, true, 3_000)
        engine.onNotificationPosted(key, 4_000)
        engine.onAlertPlayed(key, true, 4_000)

        // elapsed == 10_000: `elapsed > T2` is false -> MUTED
        assertEquals(CooldownState.MUTED, engine.onNotificationPosted(key, 14_000))
    }

    @Test
    fun `a silenced alert does not advance the clock`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, wasAudible = true, nowMs = 0)

        // An alert that was suppressed must not count as the "last audible" alert.
        engine.onNotificationPosted(key, 1_000)
        engine.onAlertPlayed(key, wasAudible = false, nowMs = 1_000)

        assertEquals(0L, engine.lastAudibleAtMs(key))

        // 60_001 ms after the only audible alert, the key is out of the POLITE window.
        assertEquals(CooldownState.DEFAULT, engine.onNotificationPosted(key, 60_001))
    }

    @Test
    fun `an alert played before any post still records its time`() {
        val engine = CooldownEngine()
        engine.onAlertPlayed(key, wasAudible = true, nowMs = 5_000)

        assertEquals(5_000L, engine.lastAudibleAtMs(key))
    }

    @Test
    fun `the reset fires once ten posts have accumulated and the key is MUTED`() {
        val engine = CooldownEngine()
        val times = listOf(0L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 9_000L, 10_000L, 11_000L)
        times.forEach { t ->
            engine.onNotificationPosted(key, t)
            engine.onAlertPlayed(key, wasAudible = true, nowMs = t)
        }

        assertEquals(CooldownState.DEFAULT, engine.stateOf(key))
    }

    @Test
    fun `the counter accumulates outside MUTED, matching AOSP`() {
        val engine = CooldownEngine()

        // Eight isolated alerts, each more than T1 apart, so the state stays DEFAULT throughout.
        // postedCount reaches 8.
        var last = 0L
        repeat(8) { i ->
            last = i * 61_000L
            engine.onNotificationPosted(key, last)
            engine.onAlertPlayed(key, true, last)
        }
        assertEquals(CooldownState.DEFAULT, engine.stateOf(key))

        // Now a burst. Because the counter already stands at 8, the reset trips on the FIRST post
        // after MUTED is reached. A naive "count only posts while muted" implementation would need
        // ten more posts instead -- that difference is the quirk being pinned here.
        val burst = last + 3_000L
        assertEquals(CooldownState.POLITE, engine.onNotificationPosted(key, burst))          // count 9
        engine.onAlertPlayed(key, true, burst)
        assertEquals(CooldownState.MUTED, engine.onNotificationPosted(key, burst + 1_000))   // count 10
        engine.onAlertPlayed(key, true, burst + 1_000)
        assertEquals(CooldownState.DEFAULT, engine.onNotificationPosted(key, burst + 2_000)) // count 11 -> reset
    }

    @Test
    fun `user interaction returns the key to DEFAULT immediately`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)      // POLITE
        assertEquals(CooldownState.POLITE, engine.stateOf(key))

        engine.onUserInteraction(key)

        assertEquals(CooldownState.DEFAULT, engine.stateOf(key))
        // ...and the immediately following alert is DEFAULT again, not POLITE.
        assertEquals(CooldownState.DEFAULT, engine.onNotificationPosted(key, 3_100))
    }

    @Test
    fun `user interaction also clears the accumulated counter`() {
        val engine = CooldownEngine()
        val times = listOf(0L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 9_000L, 10_000L)
        times.forEach { t ->
            engine.onNotificationPosted(key, t)
            engine.onAlertPlayed(key, true, t)
        }

        engine.onUserInteraction(key)
        engine.onNotificationPosted(key, 20_000)     // fresh key: postedCount -> 1
        engine.onAlertPlayed(key, true, 20_000)
        engine.onNotificationPosted(key, 23_000)     // POLITE
        engine.onAlertPlayed(key, true, 23_000)

        assertEquals(CooldownState.MUTED, engine.onNotificationPosted(key, 24_000))
        engine.onAlertPlayed(key, true, 24_000)

        // This assertion is what actually discriminates. The pre-post state here is MUTED, so if
        // the counter had survived onUserInteraction (standing at 9, then 10/11/12 across the three
        // posts above, i.e. >= counterReset) the reset would fire and return DEFAULT. Because
        // onUserInteraction cleared it, the count is only 4 and the key stays MUTED.
        assertEquals(CooldownState.MUTED, engine.onNotificationPosted(key, 25_000))
    }

    @Test
    fun `forget drops the key`() {
        val engine = CooldownEngine()
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)
        assertEquals(CooldownState.POLITE, engine.stateOf(key))

        engine.forget(key)

        assertEquals(CooldownState.DEFAULT, engine.stateOf(key))
        assertEquals(null, engine.lastAudibleAtMs(key))
    }

    @Test
    fun `two packages do not share cooldown state`() {
        val engine = CooldownEngine()
        val other = "0:com.other.app"

        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)          // key -> POLITE

        assertEquals(CooldownState.POLITE, engine.stateOf(key))
        assertEquals(CooldownState.DEFAULT, engine.stateOf(other))
        assertEquals(CooldownState.DEFAULT, engine.onNotificationPosted(other, 3_100))
    }

    @Test
    fun `a user id is part of the key`() {
        val engine = CooldownEngine()
        val work = "10:com.example.chat"

        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        engine.onNotificationPosted(key, 3_000)

        assertEquals(CooldownState.POLITE, engine.stateOf(key))
        assertEquals(CooldownState.DEFAULT, engine.stateOf(work))
    }

    @Test
    fun `postedCount exposes the counter the planner has to anticipate, and clears on the reset`() {
        val engine = CooldownEngine()

        assertEquals(0, engine.postedCount(key), "an unknown key has no posts")

        // Two posts, no reset: the plain accumulation the planner reads.
        engine.onNotificationPosted(key, 0)
        engine.onAlertPlayed(key, true, 0)
        assertEquals(1, engine.postedCount(key))

        engine.onNotificationPosted(key, 3_000)      // POLITE
        engine.onAlertPlayed(key, true, 3_000)
        assertEquals(2, engine.postedCount(key))

        // Eight more posts reach the reset, which clears the counter and returns the key to DEFAULT.
        listOf(4_000L, 5_000L, 6_000L, 7_000L, 8_000L, 9_000L, 10_000L, 11_000L).forEach { t ->
            engine.onNotificationPosted(key, t)
            engine.onAlertPlayed(key, true, t)
        }

        assertEquals(0, engine.postedCount(key))
        assertEquals(CooldownState.DEFAULT, engine.stateOf(key))
    }
}
