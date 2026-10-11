package com.d4viddf.hyperbridge.cooldown.ui

import app.hypercooldown.actuation.CooldownOptionBounds
import app.hypercooldown.engine.EngineConfig

/**
 * The one place a number the user typed becomes an option, and an option becomes text.
 *
 * Nothing here clamps. An entry that is not a number, or is outside [CooldownOptionBounds], answers
 * `null`; the screen shows an error and writes nothing. Clamping silently would store a value the
 * user did not choose and then display it back as if they had — which is the failure mode this screen
 * exists to remove.
 *
 * The timing fields are in **seconds**, which is the unit a person reads a cooldown in; the option
 * set stores milliseconds, and AOSP's own values (60 s, 10 s) are shown as described presets beside a
 * field that can hold anything in range.
 */
object CooldownNumberText {

    /** `EngineConfig`'s own defaults, so an unset option shows what is actually in force. */
    private val defaults = EngineConfig()

    /** `NOTIF_COOLDOWN_T1`: AOSP's `timeout_polite`. */
    val defaultTimeoutPoliteMs: Long = defaults.timeoutPoliteMs

    /** `NOTIF_COOLDOWN_T2`: AOSP's `timeout_muted`. */
    val defaultTimeoutMutedMs: Long = defaults.timeoutMutedMs

    /** `NOTIF_VOLUME1`, i.e. 30 %. */
    val defaultFactor: Float = defaults.politeVolume

    /** `NOTIF_COOLDOWN_COUNTER_RESET`. */
    val defaultCounterReset: Int = defaults.counterReset

    /** Whole seconds, which is how the field is edited. */
    fun seconds(ms: Long): String = (ms / 1_000L).toString()

    /** The milliseconds [text] means, or null when it is not a usable window. */
    fun toMillis(text: String): Long? {
        val seconds = text.trim().toLongOrNull() ?: return null
        val ms = seconds * 1_000L
        return ms.takeIf { it in CooldownOptionBounds.TIMEOUT_MS_RANGE }
    }

    /** Whole per cent, which is how the volume is edited. */
    fun percent(factor: Float): String = (factor * 100).toInt().toString()

    /** The factor [text] means, or null when it is not a usable share. */
    fun toFactor(text: String): Float? {
        val percent = text.trim().toIntOrNull() ?: return null
        val factor = percent / 100f
        return factor.takeIf { it in CooldownOptionBounds.ATTENUATION_RANGE }
    }

    fun count(value: Int): String = value.toString()

    /** The counter [text] means, or null when it is not a usable count. */
    fun toCount(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in CooldownOptionBounds.COUNTER_RESET_RANGE }
}
