package app.hypercooldown.engine

/**
 * A faithful, *lazy* port of AOSP's `NotificationAttentionHelper.PolitenessStrategy` and its
 * shipping `StrategyPerApp` subclass.
 *
 * "Lazy" means state only changes when a notification is posted, exactly as in AOSP: the
 * strategy recomputes on each post and never on a timer. The timer-driven anticipation lives
 * in [ChannelStatePlanner].
 */
class CooldownEngine(private val config: EngineConfig = EngineConfig()) {

    private class KeyState {
        var state: CooldownState = CooldownState.DEFAULT
        var lastAudibleAtMs: Long? = null
        var postedCount: Int = 0
    }

    private val keys = HashMap<String, KeyState>()

    /** The state AOSP would currently report for [key]. DEFAULT for an unknown key. */
    fun stateOf(key: String): CooldownState = keys[key]?.state ?: CooldownState.DEFAULT

    /** The time of the last alert that actually played for [key], or null if none has. */
    fun lastAudibleAtMs(key: String): Long? = keys[key]?.lastAudibleAtMs

    /**
     * How many posts have accumulated for [key] since the last counter reset; 0 for an unknown key.
     *
     * This is the only input to AOSP's per-post decision that is not a function of
     * `(state, elapsed)`, so [ChannelStatePlanner] needs it to anticipate the reset.
     */
    fun postedCount(key: String): Int = keys[key]?.postedCount ?: 0

    /**
     * Ports `StrategyPerApp.onNotificationPosted`: decides the state for the notification being
     * posted, then applies the counter reset.
     */
    fun onNotificationPosted(key: String, nowMs: Long): CooldownState {
        val k = keys.getOrPut(key) { KeyState() }
        val current = k.state
        val elapsed = k.lastAudibleAtMs?.let { nowMs - it } ?: Long.MAX_VALUE

        val next = when (current) {
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

        var resolved = next

        // Ports StrategyPerApp.onNotificationPosted's counter reset. Note the quirks, both
        // faithful to AOSP: (1) postedCount counts EVERY post, not only posts while muted;
        // (2) the reset requires the state *before* this post to have been MUTED.
        k.postedCount += 1
        if (current == CooldownState.MUTED && k.postedCount >= config.counterReset) {
            resolved = CooldownState.DEFAULT
            k.postedCount = 0
        }

        k.state = resolved
        return resolved
    }

    /**
     * Records that an alert played. Ports AOSP's `if (buzz || beep)` guard: only an alert that
     * actually made sound or vibration advances the clock. A silenced alert must not extend its
     * own cooldown.
     */
    fun onAlertPlayed(key: String, wasAudible: Boolean, nowMs: Long) {
        if (!wasAudible) return
        keys.getOrPut(key) { KeyState() }.lastAudibleAtMs = nowMs
    }

    /**
     * Ports `PolitenessStrategy.onUserInteraction`: drops the key entirely, which resets state to
     * DEFAULT, clears the counter and forgets the last alert. AOSP does this by setting the
     * timestamp to 0, which has the same effect because the resulting elapsed time is enormous.
     */
    fun onUserInteraction(key: String) {
        keys.remove(key)
    }

    /** Forgets a key without it meaning a user interaction (channel deleted, app uninstalled). */
    fun forget(key: String) {
        keys.remove(key)
    }
}
