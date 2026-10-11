package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState

/**
 * The device, as far as the cooldown can tell: a [ChannelTransport] that keeps the content of each
 * channel and answers the two questions that actually matter — what state is the channel carrying,
 * and would the platform play an alert on it.
 *
 * This exists so the differential test has an oracle that is *not* the coordinator's own model. A
 * wrong installed state changes what the device would do, which changes the audibility the engine is
 * fed, which shows up as a divergence from AOSP's own decision.
 *
 * Both answers are read off the content, the way the platform reads them:
 *  - `NotificationAttentionHelper.buzzBeepBlinkLocked` beeps when there is a sound and buzzes when
 *    the channel vibrates;
 *  - `NotificationRecord.getVibrationForChannel` falls through effect -> pattern ->
 *    `createDefaultVibration()`, so a channel with vibration enabled but no explicit effect or
 *    pattern still buzzes.
 *
 * [stateFor] identifies a state by what it would play: MUTED is nothing at all, and POLITE is the
 * channel's sound beside a *damped* vibration. Since 2026-10-11 that vibration is the only thing that
 * tells POLITE from DEFAULT, because a POLITE write with no attenuated copy to install now leaves the
 * channel's own sound in place rather than clearing it — clearing it is what stranded
 * `com.instagram.android/ig_direct` (root-cause §3.5, §4). So a channel carrying a sound and the
 * fixture's own full-amplitude pattern is DEFAULT, and one carrying a sound beside any other pattern,
 * or none, is POLITE.
 *
 * [deriveVibrationEffect] turns this device into the one this app was actually measured against; see
 * [DerivingChannelDevice].
 */
open class FakeChannelDevice(
    private val channelId: String = "messages",
    /**
     * Whether the platform derives a `VibrationEffect` from a written pattern, the way this ROM's
     * `NotificationChannel.setVibrationPattern` does when
     * `Flags.notificationChannelVibrationEffectApi()` is on
     * (`research/aosp/NotificationChannel.java:728-731`). The fixture channel is pattern-configured,
     * so on that ROM it arrives with a derived effect and every pattern write re-derives one.
     */
    private val deriveVibrationEffect: Boolean = false,
) : ChannelTransport {

    private val channels = HashMap<Pair<String, String>, ChannelFields>()

    /** Set to make every following write fail, the way a dying binder would. */
    var nextResult: WriteResult = WriteResult.Ok

    override val name: String = "fake-device"

    override fun write(target: ChannelTarget, snapshot: ChannelSnapshot, edit: ChannelEdit): WriteResult {
        // A failed write leaves the channel exactly as it was, as the real one does.
        if (nextResult is WriteResult.Failed) return nextResult
        channels[target.packageName to edit.fields.channelId] = content(edit.fields)
        return WriteResult.Ok
    }

    override fun appliedContent(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
    ): ChannelFields = content(edit.fields)

    /** The content the platform holds for [fields]: what its own setters made of the write. */
    private fun content(fields: ChannelFields): ChannelFields {
        if (!deriveVibrationEffect) return fields
        return fields.copy(
            vibrationEffectBytes = if (fields.vibrationEnabled) {
                fields.vibrationPattern?.takeIf { it.isNotEmpty() }?.let(::derivedEffectBytes)
            } else {
                null
            },
        )
    }

    private fun heldFields(packageName: String): ChannelFields =
        content(channels[packageName to channelId] ?: channelFields(channelId = channelId))

    /** The content the platform would hand back in `Ranking.getChannel()`. */
    fun snapshotFor(packageName: String): ChannelSnapshot =
        ChannelSnapshot(fields = heldFields(packageName), channelBytes = "AQ==")

    fun stateFor(packageName: String): CooldownState {
        val fields = channels[packageName to channelId] ?: return CooldownState.DEFAULT
        val pristine = channelFields(channelId = channelId)
        return when {
            // Nothing to play at all: MUTED is the only state that installs neither a sound nor a
            // vibration (`EditFactory`), so this is not ambiguous with anything else.
            fields.soundUri == null && !fields.vibrationEnabled -> CooldownState.MUTED
            // A sound beside the channel's own untouched vibration. `politePattern` is what our
            // POLITE write leaves here, so comparing the two is what tells POLITE's 30 % vibration
            // from DEFAULT's full one.
            fields.soundUri == pristine.soundUri && fields.vibrationPattern == pristine.vibrationPattern -> CooldownState.DEFAULT
            else -> CooldownState.POLITE
        }
    }

    /** AOSP's `buzz || beep`. */
    fun wouldAlert(packageName: String): Boolean {
        val fields = channels[packageName to channelId] ?: return true
        return fields.soundUri != null || fields.vibrationEnabled
    }
}

/**
 * The device this app was measured against on 2026-10-09: whatever pattern a write carries, the
 * channel hands back a `VibrationEffect` derived from that pattern, because
 * `NotificationChannel.setVibrationPattern` assigns
 * `mVibrationEffect = VibrationEffect.createWaveform(pattern, -1)` when
 * `Flags.notificationChannelVibrationEffectApi()` is on (`research/aosp/NotificationChannel.java:728-731`)
 * and `writeToParcel` carries it into the stored channel (`:443-449`, read back at `:382-388`).
 *
 * Nothing else about the device changes: the effect is not what `buzzBeepBlinkLocked` or
 * `getVibrationForChannel` reads first here, it is what makes an observation differ from the edit
 * that produced it.
 */
class DerivingChannelDevice : FakeChannelDevice(deriveVibrationEffect = true)

/**
 * What `VibrationEffect.createWaveform(pattern, -1)` marshals to, as far as anything above reads it:
 * a pure function of the pattern, so equal patterns give equal bytes and a rescaled pattern does not.
 */
private fun derivedEffectBytes(pattern: List<Long>): String = "waveform:" + pattern.joinToString(",")
