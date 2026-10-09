package app.hypercooldown.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChannelStatePlannerTest {

    private val config = EngineConfig()
    private val l = 1_000_000L

    @Test
    fun `no audible alert means install DEFAULT with no transitions`() {
        val plan = ChannelStatePlanner.plan(
            CooldownState.DEFAULT,
            lastAudibleAtMs = null,
            postedCount = 0,
            config = config,
        )

        assertEquals(CooldownState.DEFAULT, plan.stateAt(l))
        assertEquals(null, plan.nextChangeAfter(l))
    }

    @Test
    fun `a DEFAULT alert installs POLITE and relaxes to DEFAULT at T1`() {
        val plan = ChannelStatePlanner.plan(CooldownState.DEFAULT, l, postedCount = 0, config = config)

        assertEquals(CooldownState.POLITE, plan.stateAt(l))
        assertEquals(CooldownState.POLITE, plan.stateAt(l + config.timeoutPoliteMs - 1))
        assertEquals(CooldownState.DEFAULT, plan.stateAt(l + config.timeoutPoliteMs))
        assertEquals(l + config.timeoutPoliteMs, plan.nextChangeAfter(l))
    }

    @Test
    fun `a POLITE alert installs MUTED, relaxes to POLITE at T2 and to DEFAULT just past T1`() {
        val plan = ChannelStatePlanner.plan(CooldownState.POLITE, l, postedCount = 0, config = config)

        assertEquals(CooldownState.MUTED, plan.stateAt(l))
        assertEquals(CooldownState.MUTED, plan.stateAt(l + config.timeoutMutedMs - 1))
        assertEquals(CooldownState.POLITE, plan.stateAt(l + config.timeoutMutedMs))
        assertEquals(CooldownState.POLITE, plan.stateAt(l + config.timeoutPoliteMs))
        assertEquals(CooldownState.DEFAULT, plan.stateAt(l + config.timeoutPoliteMs + 1))
        assertEquals(l + config.timeoutMutedMs, plan.nextChangeAfter(l))
    }

    @Test
    fun `a MUTED alert installs MUTED and relaxes to POLITE just past T2, never to DEFAULT`() {
        val plan = ChannelStatePlanner.plan(CooldownState.MUTED, l, postedCount = 0, config = config)

        assertEquals(CooldownState.MUTED, plan.stateAt(l))
        assertEquals(CooldownState.MUTED, plan.stateAt(l + config.timeoutMutedMs))
        assertEquals(CooldownState.POLITE, plan.stateAt(l + config.timeoutMutedMs + 1))
        // Far beyond T1 it is still POLITE: AOSP never sends MUTED straight to DEFAULT.
        assertEquals(CooldownState.POLITE, plan.stateAt(l + 10 * config.timeoutPoliteMs))
        assertEquals(l + config.timeoutMutedMs + 1, plan.nextChangeAfter(l))
    }

    @Test
    fun `each schedule has the exact segment shape AOSP requires`() {
        val noHistory = ChannelStatePlanner.plan(
            CooldownState.DEFAULT,
            lastAudibleAtMs = null,
            postedCount = 0,
            config = config,
        )
        assertEquals(listOf(ChannelPlan.Segment(Long.MIN_VALUE, CooldownState.DEFAULT)), noHistory.segments())

        assertEquals(
            listOf(
                ChannelPlan.Segment(l, CooldownState.POLITE),
                ChannelPlan.Segment(l + config.timeoutPoliteMs, CooldownState.DEFAULT),
            ),
            ChannelStatePlanner.plan(CooldownState.DEFAULT, l, postedCount = 0, config = config).segments(),
        )

        assertEquals(
            listOf(
                ChannelPlan.Segment(l, CooldownState.MUTED),
                ChannelPlan.Segment(l + config.timeoutMutedMs, CooldownState.POLITE),
                ChannelPlan.Segment(l + config.timeoutPoliteMs + 1, CooldownState.DEFAULT),
            ),
            ChannelStatePlanner.plan(CooldownState.POLITE, l, postedCount = 0, config = config).segments(),
        )

        // Exactly two segments, and never DEFAULT: AOSP sends a muted key back to POLITE, never
        // straight to DEFAULT. Point-sampling stateAt would not catch a redundant extra segment.
        val muted = ChannelStatePlanner.plan(CooldownState.MUTED, l, postedCount = 0, config = config)
        assertEquals(
            listOf(
                ChannelPlan.Segment(l, CooldownState.MUTED),
                ChannelPlan.Segment(l + config.timeoutMutedMs + 1, CooldownState.POLITE),
            ),
            muted.segments(),
        )
        assertEquals(2, muted.segments().size)
    }

    @Test
    fun `a MUTED key one post away from the reset is planned as DEFAULT immediately`() {
        for (count in listOf(config.counterReset - 1, config.counterReset, config.counterReset + 5)) {
            val plan = ChannelStatePlanner.plan(CooldownState.MUTED, l, count, config)

            // AOSP's reset is not a function of arrival time: once the count is one short of the
            // threshold, the next post is DEFAULT whenever it arrives. Hence one segment, and the
            // state must hold arbitrarily far past T2 (it must NOT relax back to POLITE).
            assertEquals(
                listOf(ChannelPlan.Segment(l, CooldownState.DEFAULT)),
                plan.segments(),
                "postedCount=$count",
            )
            assertEquals(CooldownState.DEFAULT, plan.stateAt(l + 10 * config.timeoutPoliteMs))
            assertEquals(null, plan.nextChangeAfter(l))
        }
    }

    @Test
    fun `a MUTED key two posts short of the reset keeps the MUTED schedule`() {
        val plan = ChannelStatePlanner.plan(CooldownState.MUTED, l, config.counterReset - 2, config)

        assertEquals(
            listOf(
                ChannelPlan.Segment(l, CooldownState.MUTED),
                ChannelPlan.Segment(l + config.timeoutMutedMs + 1, CooldownState.POLITE),
            ),
            plan.segments(),
        )
    }

    @Test
    fun `the reset projection keys on the pre-post state, so it never applies to POLITE`() {
        // AOSP tests `currState == MUTED` before resetting, so a large counter cannot force a
        // POLITE (or DEFAULT) key out of its normal schedule.
        val polite = ChannelStatePlanner.plan(CooldownState.POLITE, l, config.counterReset + 5, config)

        assertEquals(
            listOf(
                ChannelPlan.Segment(l, CooldownState.MUTED),
                ChannelPlan.Segment(l + config.timeoutMutedMs, CooldownState.POLITE),
                ChannelPlan.Segment(l + config.timeoutPoliteMs + 1, CooldownState.DEFAULT),
            ),
            polite.segments(),
        )
    }
}
