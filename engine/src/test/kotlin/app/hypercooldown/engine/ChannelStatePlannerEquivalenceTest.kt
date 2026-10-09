package app.hypercooldown.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChannelStatePlannerEquivalenceTest {

    private val config = EngineConfig()
    private val l = 1_000_000L

    @Test
    fun `the installed schedule equals AOSP's projection for every arrival time`() {
        for (assigned in CooldownState.entries) {
            // The counter is held below AOSP's reset threshold: that branch is not a function of
            // arrival time at all (it fires on the next post, whenever it arrives), so it is pinned
            // by CooldownCompositionTest instead of by this per-arrival-time equivalence.
            val plan = ChannelStatePlanner.plan(assigned, l, postedCount = 0, config = config)

            // Every millisecond across both windows, plus both boundaries and a far-future point.
            val samples = buildList {
                for (e in 0L..(config.timeoutPoliteMs + 2)) add(l + e)
                add(l + 10 * config.timeoutPoliteMs)
            }

            for (at in samples) {
                assertEquals(
                    ChannelStatePlanner.projectedState(assigned, l, at, config),
                    plan.stateAt(at),
                    "assigned=$assigned at=+${at - l}ms",
                )
            }
        }
    }

    @Test
    fun `the projection itself matches AOSP's getNextState for the documented boundary cases`() {
        fun p(assigned: CooldownState, elapsed: Long) =
            ChannelStatePlanner.projectedState(assigned, l, l + elapsed, config)

        // At elapsed 0 the DEFAULT branch tests `elapsed < T1`, which is true -> POLITE.
        assertEquals(CooldownState.POLITE, p(CooldownState.DEFAULT, 0))
        assertEquals(CooldownState.POLITE, p(CooldownState.DEFAULT, config.timeoutPoliteMs - 1))
        assertEquals(CooldownState.DEFAULT, p(CooldownState.DEFAULT, config.timeoutPoliteMs))

        assertEquals(CooldownState.MUTED, p(CooldownState.POLITE, config.timeoutMutedMs - 1))
        assertEquals(CooldownState.POLITE, p(CooldownState.POLITE, config.timeoutMutedMs))
        assertEquals(CooldownState.POLITE, p(CooldownState.POLITE, config.timeoutPoliteMs))
        assertEquals(CooldownState.DEFAULT, p(CooldownState.POLITE, config.timeoutPoliteMs + 1))

        assertEquals(CooldownState.MUTED, p(CooldownState.MUTED, config.timeoutMutedMs))
        assertEquals(CooldownState.POLITE, p(CooldownState.MUTED, config.timeoutMutedMs + 1))
        assertEquals(CooldownState.POLITE, p(CooldownState.MUTED, 10 * config.timeoutPoliteMs))
    }
}
