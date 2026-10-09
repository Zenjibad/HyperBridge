package app.hypercooldown.actuation

/**
 * A way in which Plan 2 knowingly falls short of AOSP. Surfaced rather than hidden: spec §5.6's
 * whole point is that a fidelity gap must be visible, because a cooldown that silently does less
 * than it claims is indistinguishable from a broken one.
 */
enum class FidelityGap {
    /**
     * POLITE had to go without a sound, because producing the 30 %-amplitude copy is
     * `AttenuatedSoundFactory` and that is Plan 3's. The channel is played vibration-only, which is
     * spec §5.6's stated behaviour for a sound that cannot be read or decoded.
     */
    POLITE_SOUND_UNAVAILABLE,

    /**
     * POLITE could not scale the vibration: the channel carries an opaque `VibrationEffect` (or
     * enables vibration with no explicit pattern, so the platform synthesises its own), and the
     * public `VibrationEffect` API exposes neither timings nor amplitudes.
     *
     * An effect the platform *derived from the channel's own pattern* is deliberately not this case:
     * that pattern carries the timings and full-scale amplitudes, so it is scaled and the derived
     * effect dropped (see `EditFactory.politeVibration`). Only an effect with no pattern beside it is
     * opaque.
     */
    POLITE_VIBRATION_UNSCALABLE,
}

/**
 * The channel content to write, plus the deviations accepted while computing it.
 *
 * Every field is written on every call, because `updateNotificationChannelForPackage` replaces the
 * whole channel object (spec §5.4). `gaps` is diagnostics and is excluded from the equality the
 * actuator uses for idempotence — comparing whole `ChannelEdit`s would make the same state look
 * like a change whenever a gap flag appeared or disappeared.
 */
data class ChannelEdit(
    val fields: ChannelFields,
    val gaps: Set<FidelityGap> = emptySet(),
)
