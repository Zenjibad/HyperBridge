package com.d4viddf.hyperbridge.cooldown.act

import app.hypercooldown.actuation.ChannelFields

/**
 * The one vibration representation a channel write is allowed to carry.
 *
 * AOSP's `NotificationChannel` has two payload setters *and* a separate `mVibrationEnabled` flag,
 * and neither setter leaves the other's field alone:
 *
 *  - `setVibrationPattern(long[])` sets `mVibrationEnabled = pattern != null && pattern.length > 0`
 *    and assigns `mVibrationPattern`, but never touches `mVibrationEffect`.
 *  - `setVibrationEffect(VibrationEffect)` sets `mVibrationEnabled = effect != null`, assigns
 *    `mVibrationEffect`, and — on the null path — assigns `mVibrationPattern = null`.
 *
 * (`research/aosp/NotificationChannel.java:706-727, 771-797`.) Writing a pattern and *then* a null
 * effect therefore loses the pattern while leaving the flag saying "vibrate", which is exactly the
 * defect measured on the device on 2026-10-09: POLITE ended at `mVibrationEnabled=true,
 * mVibrationPattern=null` and fell through to `VibratorHelper.createDefaultVibration()`.
 *
 * Modelling the write as one of these three cases makes that combination unrepresentable: a state
 * carries a pattern, an effect, or nothing — never a pattern that is then nulled.
 */
internal sealed interface VibrationWrite {

    /** A `VibrationEffect`, still marshalled; the mapper unmarshals it at the call. */
    data class Effect(val effectBytes: String) : VibrationWrite

    /**
     * A legacy `[timing, amplitude, ...]` pattern. For POLITE this is already the scaled list:
     * `VibrationScaler.scale(pattern, config.politeVolume)` runs in `EditFactory`, which also drops the
     * effect the platform derived from that same pattern (`EditFactory.politeVibration`), so the
     * mapper's job is to carry the pattern through intact — not to re-derive the scaling.
     */
    data class Pattern(val pattern: List<Long>) : VibrationWrite

    /**
     * No payload at all.
     *
     * MUTED lands here (`EditFactory` nulls the pattern, the effect and the flag), and so does a
     * POLITE channel that carries nothing to scale: a channel whose original had no vibration must
     * stay absent rather than acquire one. It also covers a snapshot that never vibrated, and a
     * vibrate-enabled channel with no explicit pattern, whose platform default amplitude no public
     * API exposes — `EditFactory` flags that last case `POLITE_VIBRATION_UNSCALABLE`.
     */
    data object Clear : VibrationWrite
}

/**
 * Spec §5.4's vibration half, as a pure function of the edit's fields.
 *
 * An explicit effect wins: `NotificationRecord.getVibrationForChannel` reads `mVibrationEffect`
 * before `mVibrationPattern`, so scaling the pattern beside a live effect would be cosmetic.
 * `EditFactory` decides the same way in its `politePattern`.
 */
internal fun vibrationWriteFor(fields: ChannelFields): VibrationWrite {
    val effect = fields.vibrationEffectBytes
    if (effect != null) return VibrationWrite.Effect(effect)

    val pattern = fields.vibrationPattern
    if (pattern != null && pattern.isNotEmpty()) return VibrationWrite.Pattern(pattern)

    return VibrationWrite.Clear
}

/**
 * The three `NotificationChannel` calls a [VibrationWrite] needs, behind a seam.
 *
 * The seam exists so the *order* of those calls can be tested on the JVM. `NotificationChannel` is
 * an Android class and its methods are `Stub!` thunks in a plain JVM unit test, so a fake sink that
 * reproduces the AOSP setter semantics above is the only way a unit test can observe what the old
 * setter order did to a pattern.
 */
internal interface VibrationSink {
    /** A marshalled `VibrationEffect`, or null to clear. */
    fun setEffect(effectBytes: String?)

    fun setPattern(pattern: LongArray?)

    fun setEnabled(enabled: Boolean)
}

/**
 * Applies exactly one payload and then the flag. Never writes a payload and nulls it afterwards.
 *
 * The payload call comes first because both AOSP payload setters assign `mVibrationEnabled` as a
 * side effect of "is there a payload"; [enabled] is the edit's own answer, so it is written last and
 * wins. What must never happen is a *second* payload call after the first: setting a pattern and
 * then calling `setEffect(null)` is the defect this function replaced.
 */
internal fun applyVibration(sink: VibrationSink, write: VibrationWrite, enabled: Boolean) {
    when (write) {
        is VibrationWrite.Effect -> sink.setEffect(write.effectBytes)
        is VibrationWrite.Pattern -> sink.setPattern(write.pattern.toLongArray())
        VibrationWrite.Clear -> {
            // MUTED must clear all three or the channel falls back to a default vibration. The
            // effect call is the load-bearing one -- `setVibrationEffect(null)` clears
            // `mVibrationEffect`, `mVibrationPattern` and `mVibrationEnabled` in AOSP
            // (NotificationChannel.java:772-795) -- and the pattern call states the second of the
            // three explicitly rather than leaning on that side effect.
            sink.setEffect(null)
            sink.setPattern(null)
        }
    }
    sink.setEnabled(enabled)
}
