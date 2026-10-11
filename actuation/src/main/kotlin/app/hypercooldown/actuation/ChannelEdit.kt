package app.hypercooldown.actuation

/**
 * A way in which Plan 2 knowingly falls short of AOSP. Surfaced rather than hidden: spec §5.6's
 * whole point is that a fidelity gap must be visible, because a cooldown that silently does less
 * than it claims is indistinguishable from a broken one.
 */
enum class FidelityGap {
    /**
     * POLITE had no 30 %-amplitude copy of the channel's sound to install, so the sound half of the
     * damping did not happen: the channel keeps the sound it already had, and the vibration is still
     * scaled. Raised when the copy is not built yet (`AttenuatedSoundFactory` builds it on a worker)
     * or cannot be built at all (unreadable, undecodable, or a source that is itself a copy this app
     * published).
     *
     * Deliberately *not* raised for `SoundOption.SILENT`: a POLITE volume of zero is the user asking
     * for a silent second alert, so that silence is the intent rather than a degradation, and nothing
     * is decoded to be thrown away. The two are told apart by this gap, by the written `soundUri`
     * (null versus the channel's own), and by the `AttenuatedSoundFactory` note that names the source
     * it could not attenuate.
     *
     * It is never paid for with the channel's sound. Writing `soundUri = null` here is what left
     * `com.instagram.android/ig_direct` silent and stranded on 2026-10-11 — the POLITE state's safety
     * would have rested on the write-ahead record, and a reinstall destroys the record
     * (`docs/superpowers/decisions/2026-10-09-stranded-muted-channel-root-cause.md` §3.5, §4).
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

    /**
     * The heads-up option asked for a demotion and the transport cannot release the lock that
     * demotion sets, so the importance was left alone. `unlockNotificationChannel` — transaction 43 —
     * is the only call on this ROM that clears `USER_LOCKED_IMPORTANCE`, and a transport that cannot
     * send it must not be the one to set the bit: the residue would be permanent, with no owner.
     * The demotion is refused rather than attempted, and said out loud rather than silently skipped.
     */
    HEADS_UP_SUPPRESS_UNRELEASABLE,
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
