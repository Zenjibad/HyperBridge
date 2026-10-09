package app.hypercooldown.engine

/**
 * Converts the engine's lazy (state, lastAudibleAt) pair into the schedule the channel must
 * follow, so that the *next* notification already carries the state AOSP would have chosen.
 *
 * The state is the one the engine holds **after the last post** — AOSP's `currState` — not the
 * state assigned to the last audible alert. Those come apart after any silenced post, and the
 * schedule is only correct for the post-state.
 *
 * AOSP's state for an alert arriving at time `t` is piecewise-constant in `t`, so a finite list of
 * segments reproduces it exactly for every possible arrival time.
 */
object ChannelStatePlanner {

    /**
     * [assignedState] is the state the engine holds **after the last post** — AOSP's `currState` —
     * not the state assigned to the last audible alert; after a silenced post the two differ.
     *
     * [postedCount] is the engine's count of **all** posts for the key, muted or not. It is
     * required rather than defaulted: AOSP's counter reset depends on it, and a default would let a
     * caller forget to pass it and silently under-alert (the reset would never be anticipated).
     */
    fun plan(
        assignedState: CooldownState,
        lastAudibleAtMs: Long?,
        postedCount: Int,
        config: EngineConfig,
    ): ChannelPlan {
        if (lastAudibleAtMs == null) {
            return ChannelPlan(listOf(ChannelPlan.Segment(Long.MIN_VALUE, CooldownState.DEFAULT)))
        }

        // AOSP's counter reset is the one branch of StrategyPerApp.onNotificationPosted that is not
        // a function of (state, elapsed): it tests the count *after* this post against counterReset
        // and, when the pre-post state was MUTED, overrides the transition unconditionally. So once
        // the counter stands at counterReset - 1 while the key is MUTED, the next alert is DEFAULT
        // *regardless of when it arrives* -- which is why one segment reproduces it exactly and
        // there is no relaxation to schedule.
        if (assignedState == CooldownState.MUTED && postedCount >= config.counterReset - 1) {
            return ChannelPlan(listOf(ChannelPlan.Segment(lastAudibleAtMs, CooldownState.DEFAULT)))
        }

        val l = lastAudibleAtMs
        val segments = when (assignedState) {
            CooldownState.DEFAULT -> listOf(
                ChannelPlan.Segment(l, CooldownState.POLITE),
                ChannelPlan.Segment(l + config.timeoutPoliteMs, CooldownState.DEFAULT),
            )
            CooldownState.POLITE -> listOf(
                ChannelPlan.Segment(l, CooldownState.MUTED),
                ChannelPlan.Segment(l + config.timeoutMutedMs, CooldownState.POLITE),
                // +1 because AOSP's POLITE branch tests `elapsed > timeoutPoliteMs`, so at exactly
                // T1 the state is still POLITE.
                ChannelPlan.Segment(l + config.timeoutPoliteMs + 1, CooldownState.DEFAULT),
            )
            CooldownState.MUTED -> listOf(
                ChannelPlan.Segment(l, CooldownState.MUTED),
                // +1 because AOSP's MUTED branch tests `elapsed > timeoutMutedMs`, so at exactly
                // T2 the state is still MUTED. There is deliberately no segment at T1: a MUTED key
                // goes to POLITE, never directly to DEFAULT.
                ChannelPlan.Segment(l + config.timeoutMutedMs + 1, CooldownState.POLITE),
            )
        }
        return ChannelPlan(segments)
    }

    /**
     * AOSP's `getNextState` restated as a function of arrival time. This is the reference the
     * equivalence test compares [plan] against; it is written from the AOSP transitions directly
     * and must not be refactored to call [plan].
     */
    fun projectedState(
        assignedState: CooldownState,
        lastAudibleAtMs: Long?,
        atMs: Long,
        config: EngineConfig,
    ): CooldownState {
        if (lastAudibleAtMs == null) return CooldownState.DEFAULT
        val elapsed = atMs - lastAudibleAtMs
        return when (assignedState) {
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
    }
}
