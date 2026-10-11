package app.hypercooldown.actuation

/**
 * The bounds every cooldown number is held to, in one place, plus the sentences that say what a value
 * means.
 *
 * Pure, so every bound is a JVM test rather than a device run — the same reason `TuningValues` is
 * pure. Two callers go through here and neither has its own copy of the rules: the screen, which
 * refuses to store a value it cannot describe, and [CooldownOptions.effectiveConfig], which refuses
 * to hand the engine a stored value it cannot use. A hand-edited preference row is the case the
 * second one exists for: `AttenuationProblem` is about a number the *engine* would act on, not about
 * what the UI happens to offer.
 *
 * The three ranges are the same three `TuningValues` uses, and that class now reads them from here
 * rather than declaring its own, so the two cannot drift.
 */
object CooldownOptionBounds {

    /** One second to one hour. Below a second the window is shorter than the alert latency it damps. */
    val TIMEOUT_MS_RANGE = 1_000L..3_600_000L

    /**
     * A factor, not a percentage. `0.0` is [SoundOption.SILENT]'s own value for POLITE and `1.0` is
     * the identity — "quieter by nothing", i.e. the option disabled. Neither is a hazard, so neither
     * is refused; what is refused is a value outside the interval, and a *negative* one especially:
     * it scales a vibration pattern's amplitudes below zero, which this ROM's vibrator rejects with an
     * `IllegalArgumentException`.
     */
    val ATTENUATION_RANGE = 0.0f..1.0f

    /** One to a hundred. Zero silent posts would never reset the counter, i.e. never end a cooldown. */
    val COUNTER_RESET_RANGE = 1..100

    /** Why [ms] is not a usable window, or null when it is. */
    fun timeoutProblem(ms: Long): String? =
        if (ms in TIMEOUT_MS_RANGE) null else "${describeMs(ms)} is outside $TIMEOUT_MS_RANGE"

    /** Why [count] is not a usable counter reset, or null when it is. */
    fun counterProblem(count: Int): String? =
        if (count in COUNTER_RESET_RANGE) null else "$count is outside $COUNTER_RESET_RANGE"

    /** Why [factor] is not a usable attenuation, or null when it is. */
    fun attenuationProblem(factor: Float): String? =
        if (factor in ATTENUATION_RANGE) null else "$factor is outside $ATTENUATION_RANGE"

    /**
     * What [ms] means to a person, in whole seconds: "10 seconds", "1 minute 30 seconds", "1 hour".
     *
     * English, and deliberately not a translated resource: it is a *description of a number the user
     * typed*, not app copy, and the whole cooldown screen falls back to English in the locales this
     * fork does not translate (see `values/strings.xml`'s cooldown block).
     */
    fun describeMs(ms: Long): String {
        val seconds = Math.floorDiv(ms, 1_000L)
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        val rest = seconds % 60L
        val parts = buildList {
            if (hours > 0) add("$hours ${plural(hours, "hour")}")
            if (minutes > 0) add("$minutes ${plural(minutes, "minute")}")
            if (rest > 0 || isEmpty()) add("$rest ${plural(rest, "second")}")
        }
        return parts.joinToString(" ")
    }

    /** What [factor] means to a person: "30 %". */
    fun describeFactor(factor: Float): String = "${(factor * 100).toInt()} %"

    /**
     * The state machine as the user experiences it, derived from the numbers they have rather than
     * written out again — so the summary cannot claim a window the settings do not.
     */
    fun describeSequence(timeoutPoliteMs: Long, timeoutMutedMs: Long, factor: Float): String =
        "the 2nd alert within ${describeMs(timeoutPoliteMs)} plays at ${describeFactor(factor)} · " +
            "the 3rd within ${describeMs(timeoutMutedMs)} is silent"

    private fun plural(count: Long, unit: String): String = if (count == 1L) unit else "${unit}s"
}
