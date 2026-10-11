package com.d4viddf.hyperbridge.cooldown.act

import app.hypercooldown.actuation.CooldownOptionBounds
import app.hypercooldown.engine.EngineConfig

/**
 * The four numbers and the counter that decide what a cooldown does, in the units [EngineConfig]
 * uses, with AOSP's production-build values as the defaults.
 *
 * Pure: no `android.*` import, so every bound below is a JVM test rather than a device run.
 *
 * Validation is per field and there is deliberately no cross-field rule: `timeoutPoliteMs` and
 * `timeoutMutedMs` are independent times measured from the last audible alert (POLITE begins T1
 * after it, MUTED begins T2 after it, so a set with T2 greater than T1 is a longer POLITE phase, not
 * an error to reject). The ranges are wide on purpose and exist for one reason: a typo must not be
 * able to leave a channel damped with no relaxation due. A T1 of zero would do exactly that, which
 * is the abandoned-channel state spec §6 forbids.
 */
data class TuningValues(
    val timeoutPoliteMs: Long = TIMEOUT_POLITE_MS_DEFAULT,
    val timeoutMutedMs: Long = TIMEOUT_MUTED_MS_DEFAULT,
    val politeVolume: Float = POLITE_VOLUME_DEFAULT,
    val mutedVolume: Float = MUTED_VOLUME_DEFAULT,
    val counterReset: Int = COUNTER_RESET_DEFAULT,
) {

    /** The engine configuration these values describe. Every field is mapped; none is defaulted. */
    fun toConfig(): EngineConfig = EngineConfig(
        timeoutPoliteMs = timeoutPoliteMs,
        timeoutMutedMs = timeoutMutedMs,
        politeVolume = politeVolume,
        mutedVolume = mutedVolume,
        counterReset = counterReset,
    )

    /** Every field that is out of range, named, with its range and AOSP's own value. */
    fun problems(): List<String> = buildList {
        if (timeoutPoliteMs !in TIMEOUT_POLITE_MS_RANGE) {
            add("timeoutPoliteMs must be in $TIMEOUT_POLITE_MS_RANGE (AOSP uses $TIMEOUT_POLITE_MS_DEFAULT)")
        }
        if (timeoutMutedMs !in TIMEOUT_MUTED_MS_RANGE) {
            add("timeoutMutedMs must be in $TIMEOUT_MUTED_MS_RANGE (AOSP uses $TIMEOUT_MUTED_MS_DEFAULT)")
        }
        if (politeVolume !in VOLUME_RANGE) {
            add("politeVolume must be in $VOLUME_RANGE (AOSP uses $POLITE_VOLUME_DEFAULT)")
        }
        if (mutedVolume !in VOLUME_RANGE) {
            add("mutedVolume must be in $VOLUME_RANGE (AOSP uses $MUTED_VOLUME_DEFAULT)")
        }
        if (counterReset !in COUNTER_RESET_RANGE) {
            add("counterReset must be in $COUNTER_RESET_RANGE (AOSP uses $COUNTER_RESET_DEFAULT)")
        }
    }

    fun isValid(): Boolean = problems().isEmpty()

    companion object {
        /** `NOTIF_COOLDOWN_T1`: AOSP's `timeout_polite` default. */
        const val TIMEOUT_POLITE_MS_DEFAULT = 60_000L

        /** `NOTIF_COOLDOWN_T2`: AOSP's `timeout_muted` default. */
        const val TIMEOUT_MUTED_MS_DEFAULT = 10_000L

        /** `NOTIF_VOLUME1` (30) divided by 100. */
        const val POLITE_VOLUME_DEFAULT = 0.30f

        /** `NOTIF_VOLUME2` (0) divided by 100. */
        const val MUTED_VOLUME_DEFAULT = 0.0f

        /** `NOTIF_COOLDOWN_COUNTER_RESET`. */
        const val COUNTER_RESET_DEFAULT = 10

        /** One second to one hour, per timeout. */
        val TIMEOUT_POLITE_MS_RANGE = CooldownOptionBounds.TIMEOUT_MS_RANGE
        val TIMEOUT_MUTED_MS_RANGE = CooldownOptionBounds.TIMEOUT_MS_RANGE

        /** The volumes are factors, not percentages: AOSP's 30 and 0 are 0.30 and 0.0 here. */
        val VOLUME_RANGE = CooldownOptionBounds.ATTENUATION_RANGE

        val COUNTER_RESET_RANGE = CooldownOptionBounds.COUNTER_RESET_RANGE

        /**
         * [values] with every out-of-range field replaced by AOSP's value for that field, one field
         * at a time — a bad counter reset must not throw away a good timeout.
         *
         * Both the screen and [TuningStore] go through here, so a value that reaches the engine is
         * always legal, and neither path needs its own copy of the rules.
         */
        fun sanitize(values: TuningValues): TuningValues = TuningValues(
            timeoutPoliteMs = values.timeoutPoliteMs.takeIf { it in TIMEOUT_POLITE_MS_RANGE }
                ?: TIMEOUT_POLITE_MS_DEFAULT,
            timeoutMutedMs = values.timeoutMutedMs.takeIf { it in TIMEOUT_MUTED_MS_RANGE }
                ?: TIMEOUT_MUTED_MS_DEFAULT,
            politeVolume = values.politeVolume.takeIf { it in VOLUME_RANGE } ?: POLITE_VOLUME_DEFAULT,
            mutedVolume = values.mutedVolume.takeIf { it in VOLUME_RANGE } ?: MUTED_VOLUME_DEFAULT,
            counterReset = values.counterReset.takeIf { it in COUNTER_RESET_RANGE }
                ?: COUNTER_RESET_DEFAULT,
        )
    }
}
