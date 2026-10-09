package app.hypercooldown.actuation

/**
 * The channel fields the cooldown reads and writes, as primitives — deliberately **not** the whole
 * channel.
 *
 * `PreferencesHelper.updateNotificationChannel` stores the submitted object wholesale
 * (`r.channels.put(updatedChannel.getId(), updatedChannel)`, `research/aosp/PreferencesHelper.java:1267`),
 * and the public API cannot express every field. Restricting the actuation layer to this set is what
 * makes it safe: the Android side writes a copy of the platform's own channel object and touches
 * only these fields, so nothing it cannot express is reset.
 *
 * [importance] is here so a test can prove it is *never* changed; `EditFactory` copies it through.
 * AOSP's MUTED is `shouldMuteNotificationLocked()`, not an importance change, and the Q4 decision
 * established that AOSP does not suppress the heads-up either — so leaving importance alone is
 * exactly what keeps the notification visible and interruptive as AOSP leaves it (spec §5.4).
 *
 * The three audio-attribute fields describe the *sound*, so a state that carries no sound carries
 * none of them (`0`, which is `USAGE_UNKNOWN` and `CONTENT_TYPE_UNKNOWN`): the platform clears the
 * attributes with `setSound(null, null)`, and a field set the platform discards would make the
 * actuator's "does the channel already carry what I want" comparison fail forever.
 */
data class ChannelFields(
    val channelId: String,
    val importance: Int,
    val soundUri: String?,
    val audioUsage: Int,
    val audioContentType: Int,
    val audioFlags: Int,
    /**
     * Legacy pattern: `[timing, amplitude, timing, amplitude, ...]`, where amplitude `0` means the
     * device default (i.e. full amplitude). Null means the channel carries no pattern.
     */
    val vibrationPattern: List<Long>?,
    /**
     * The channel's `VibrationEffect`, marshalled and base64-wrapped. The public API exposes neither
     * its timings nor its amplitudes, so the bytes are the only faithful representation — they are
     * carried through verbatim and never rescaled.
     */
    val vibrationEffectBytes: String?,
    val vibrationEnabled: Boolean,
) {
    init {
        require(channelId.isNotEmpty()) { "a channel id must not be empty" }
        require(vibrationPattern == null || vibrationPattern.size % 2 == 0) {
            "a vibration pattern is [timing, amplitude] pairs, so its length is even"
        }
    }
}
