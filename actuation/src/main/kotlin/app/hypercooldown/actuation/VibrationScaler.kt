package app.hypercooldown.actuation

/**
 * Scales the amplitude half of a `NotificationChannel` vibration pattern.
 *
 * AOSP scales POLITE's vibration by the same factor as its sound:
 * `mVibratorHelper.scale(effect, getSoundVolume(record))`. On the channel side the equivalent is the
 * legacy pattern, `[timing0, amplitude0, timing1, amplitude1, ...]` — the shape
 * `VibrationEffect.createWaveform(long[] pattern, int repeat)` documents, and the shape
 * `VibratorHelper.createWaveformVibration` splits into timings and amplitudes.
 *
 * Two details are load-bearing rather than cosmetic:
 *  - amplitude `0` means "the device default", which resolves to **full** amplitude, so it is
 *    normalised to [maxAmplitude] before scaling. `0 * 0.3 == 0` would leave that component at full
 *    strength and POLITE would not damp it at all.
 *  - the result is clamped to `1..maxAmplitude`, for the same reason in reverse: a rounded-down `0`
 *    would silently become the default amplitude again, amplifying a very quiet 1 into a full one.
 */
object VibrationScaler {

    /** The platform's maximum vibration amplitude (`VibrationEffect.MAX_AMPLITUDE`). */
    const val MAX_AMPLITUDE = 255

    /** The pattern's "use the device default" marker, which resolves to full amplitude. */
    const val DEFAULT_AMPLITUDE = 0L

    fun isScalable(pattern: List<Long>?): Boolean =
        pattern != null && pattern.isNotEmpty() && pattern.size % 2 == 0

    fun scale(pattern: List<Long>, factor: Float, maxAmplitude: Int = MAX_AMPLITUDE): List<Long> {
        require(isScalable(pattern)) {
            "a vibration pattern is a non-empty list of [timing, amplitude] pairs"
        }
        require(factor > 0f && factor <= 1f) { "factor must be in (0, 1], got $factor" }
        return pattern.mapIndexed { index, value ->
            if (index % 2 == 0) value else scaleAmplitude(value, factor, maxAmplitude)
        }
    }

    private fun scaleAmplitude(value: Long, factor: Float, maxAmplitude: Int): Long {
        val resolved = if (value <= DEFAULT_AMPLITUDE) maxAmplitude.toLong() else value
        // `Math.round(Float)` yields an `Int`, and the stdlib has no `Int.coerceIn(Long, Long)`
        // overload, so widen before clamping.
        return Math.round(resolved * factor).toLong().coerceIn(1L, maxAmplitude.toLong())
    }
}
