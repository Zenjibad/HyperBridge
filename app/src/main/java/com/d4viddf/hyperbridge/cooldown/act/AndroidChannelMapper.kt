package com.d4viddf.hyperbridge.cooldown.act

import android.app.NotificationChannel
import android.media.AudioAttributes
import android.net.Uri
import android.os.Parcel
import android.os.VibrationEffect
import android.util.Base64
import app.hypercooldown.actuation.ChannelEdit
import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot

/**
 * The only place Android's `NotificationChannel` meets the pure actuation layer.
 *
 * The write target is a **parcel copy of the platform's own channel object**, never a channel
 * assembled from [ChannelFields]. `PreferencesHelper.updateNotificationChannel` stores whatever
 * object it is handed, wholesale (`r.channels.put(updatedChannel.getId(), updatedChannel)`,
 * `research/aosp/PreferencesHelper.java:1267`), and the public API cannot express `mDemoted`,
 * `mImportantConvo`, `mOriginalImp`, `mAllowBubbles`, `mUserVisibleTaskShown`, `mBlockableSystem` or
 * `mImportanceLockedDefaultApp`. Building from a field list would silently reset all of them; copying
 * the platform's own object cannot, because those fields travel in the parcel.
 *
 * The copy is taken through `Parcel` rather than through a copy constructor, because
 * `NotificationChannel(NotificationChannel)` is `@hide`. Both `writeToParcel` and `CREATOR` are public
 * and both run against the device's own class at runtime, so this is a faithful in-process copy, not a
 * re-serialisation against our compileSdk.
 */
class AndroidChannelMapper {

    fun snapshotOf(channel: NotificationChannel): ChannelSnapshot =
        ChannelSnapshot(fields = fieldsOf(channel), channelBytes = marshalChannel(channel))

    fun fieldsOf(channel: NotificationChannel): ChannelFields = ChannelFields(
        channelId = channel.id,
        importance = channel.importance,
        soundUri = channel.sound?.toString(),
        // `setSound(null, null)` clears the attributes along with the sound, so a silent channel has
        // none. Reading them as zero -- not as "the default notification attributes" -- is what makes
        // a MUTED edit compare equal to a MUTED channel; substituting defaults here would make the
        // actuator believe every MUTED channel still needed writing, on every post.
        audioUsage = channel.audioAttributes?.usage ?: 0,
        audioContentType = channel.audioAttributes?.contentType ?: 0,
        audioFlags = channel.audioAttributes?.flags ?: 0,
        vibrationPattern = channel.vibrationPattern?.toList(),
        vibrationEffectBytes = channel.vibrationEffect?.let(::marshalEffect),
        vibrationEnabled = channel.shouldVibrate(),
    )

    /**
     * Rebuilds the write target: the snapshot's own bytes when we have them, otherwise a channel
     * built from [ChannelEdit.fields] — which is only safe for a channel known to be at platform
     * defaults (Task 10's `seed-vibration`, which logs that it is doing exactly this).
     */
    fun channelFor(snapshot: ChannelSnapshot, edit: ChannelEdit): NotificationChannel {
        val fields = edit.fields
        val channel = snapshot.channelBytes?.let(::unmarshalChannel)
            ?: NotificationChannel(fields.channelId, fields.channelId, fields.importance)

        val sound = fields.soundUri
        if (sound == null) {
            channel.setSound(null, null)
        } else {
            channel.setSound(
                Uri.parse(sound),
                AudioAttributes.Builder()
                    .setUsage(fields.audioUsage)
                    .setContentType(fields.audioContentType)
                    .setFlags(fields.audioFlags)
                    .build(),
            )
        }

        // One coherent vibration representation per state, applied by exactly one payload setter.
        //
        // The previous version called `setVibrationPattern(...)` and then `setVibrationEffect(null)`
        // for every POLITE edit that carried a scaled pattern, because
        // `fields.vibrationEffectBytes` is null there. AOSP's `setVibrationEffect` assigns
        // `mVibrationEnabled = effect != null`, `mVibrationEffect = effect` and, on the null path,
        // `mVibrationPattern = null` (`research/aosp/NotificationChannel.java:771-797`), so the
        // scaled pattern was discarded and the trailing `enableVibration(true)` restored only the
        // flag. Measured on-device 2026-10-09: POLITE ended at `mVibrationEnabled=true,
        // mVibrationPattern=null`, `NotificationRecord.getVibrationForChannel` fell through to
        // `VibratorHelper.createDefaultVibration()`, every episode logged
        // `POLITE_VIBRATION_UNSCALABLE`, and a restore could not bring a real user's pattern back.
        //
        // `vibrationWriteFor` chooses the single representation (an effect, a scaled pattern, or
        // nothing) and `applyVibration` makes the calls in an order that cannot lose it. Both are
        // unit-tested; see `VibrationWriteTest`.
        applyVibration(
            sink = ChannelVibrationSink(channel),
            write = vibrationWriteFor(fields),
            enabled = fields.vibrationEnabled,
        )
        // Importance is deliberately NOT set: AOSP's MUTED leaves it alone (spec §5.4) and the Q4
        // decision established that the heads-up is not suppressed either, so the copy keeps it.
        return channel
    }

    fun marshalChannel(channel: NotificationChannel): String = marshal { channel.writeToParcel(it, 0) }

    fun unmarshalChannel(bytes: String): NotificationChannel =
        unmarshal(bytes) { NotificationChannel.CREATOR.createFromParcel(it) }
}

/**
 * The platform side of [VibrationSink]: the three `NotificationChannel` setters, and nothing else.
 *
 * It is a class rather than a lambda set so that the one place the platform is called stays obvious.
 * It is never loaded by a JVM unit test, which is why the sink seam exists at all.
 */
private class ChannelVibrationSink(private val channel: NotificationChannel) : VibrationSink {

    override fun setEffect(effectBytes: String?) {
        channel.setVibrationEffect(effectBytes?.let(::unmarshalEffect))
    }

    override fun setPattern(pattern: LongArray?) {
        channel.setVibrationPattern(pattern)
    }

    override fun setEnabled(enabled: Boolean) {
        channel.enableVibration(enabled)
    }
}

private fun marshalEffect(effect: VibrationEffect): String = marshal { effect.writeToParcel(it, 0) }

private fun unmarshalEffect(bytes: String): VibrationEffect =
    unmarshal(bytes) { VibrationEffect.CREATOR.createFromParcel(it) }

private inline fun marshal(write: (Parcel) -> Unit): String {
    val parcel = Parcel.obtain()
    try {
        write(parcel)
        return Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
    } finally {
        parcel.recycle()
    }
}

private inline fun <T> unmarshal(bytes: String, read: (Parcel) -> T): T {
    val raw = Base64.decode(bytes, Base64.NO_WRAP)
    val parcel = Parcel.obtain()
    try {
        parcel.unmarshall(raw, 0, raw.size)
        parcel.setDataPosition(0)
        return read(parcel)
    } finally {
        parcel.recycle()
    }
}
