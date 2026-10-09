package app.hypercooldown.actuation

/** The outcome of one channel write. */
sealed interface WriteResult {
    data object Ok : WriteResult
    data class Failed(val reason: String) : WriteResult
}

/**
 * The narrow seam the actuation layer writes through.
 *
 * Q3's record settles which transport can actually write a channel on this ROM — A, the raw-parcel
 * Shizuku write — and both implementations live in `:app` behind this one interface, so everything
 * above it is written once and the choice is a probe result rather than a compile-time fact.
 *
 * The method takes the snapshot as well as the edit because the Android side must rebuild the write
 * target from the platform's own channel object (see `AndroidChannelMapper`): the channel is stored
 * wholesale, so a target built only from [ChannelEdit.fields] would reset every field those fields
 * do not cover.
 */
interface ChannelTransport {
    /** Short name for logs and for Q3's decision record. */
    val name: String

    /** Replaces the whole channel for [target] with the content of [edit]. */
    fun write(target: ChannelTarget, snapshot: ChannelSnapshot, edit: ChannelEdit): WriteResult

    /**
     * What the channel carries once [edit] has been written over [snapshot]: the platform's own answer,
     * read back through the accessors an observation is read with — never a copy of [edit].
     *
     * Several platform setters *normalise*, so what a channel holds is not what an edit asked for, and
     * the difference is not cosmetic. On a ROM with `Flags.notificationChannelVibrationEffectApi()`
     * (this one), `setVibrationPattern` also assigns
     * `mVibrationEffect = VibrationEffect.createWaveform(pattern, -1)`
     * (`research/aosp/NotificationChannel.java:728-731`), and `writeToParcel` carries that effect into
     * the stored channel (`:443-449`, read back at `:382-388`). A write-ahead record built from the
     * *intended* edit therefore does not contain the effect the channel hands back on the next post,
     * and `ChannelActuator.classify` reads our own damping as somebody else's change: the episode
     * stands down, the record is dropped, and the scaled pattern becomes the next episode's
     * "original".
     *
     * A foreign channel cannot be read back over the binder — spec §4: `getNotificationChannelsForPackage`
     * is `enforceSystemOrSystemUI` — so an implementation reports the platform object it submits, which
     * is the same object `PreferencesHelper.updateNotificationChannel` stores wholesale
     * (`research/aosp/PreferencesHelper.java:1267`).
     */
    fun appliedContent(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
    ): ChannelFields
}
