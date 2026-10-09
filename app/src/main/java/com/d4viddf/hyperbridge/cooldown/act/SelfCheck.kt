package com.d4viddf.hyperbridge.cooldown.act

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Process
import android.provider.Settings
import android.util.Log
import app.hypercooldown.actuation.ChannelEdit
import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.ChannelTarget
import app.hypercooldown.actuation.ChannelTransport
import app.hypercooldown.actuation.EditFactory
import app.hypercooldown.actuation.NoPoliteSound
import app.hypercooldown.actuation.WriteResult
import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig

/**
 * The device harness: it checks the mapper and a transport against the real platform, and it sets up
 * the end-to-end burst's target channel.
 *
 * Every check logs one `CooldownCheck: PASS|FAIL <name> -- <detail>` line, so
 * `adb logcat -s CooldownCheck:V` is the whole test report. It is driven over adb by
 * [SelfCheckReceiver], which is guarded by `android.permission.DUMP`; Plan 3 replaces all of this
 * with the status UI.
 *
 * The round-trip runs on the app's **own** channel, because that is the only channel the public API
 * can read back (`getNotificationChannelsForPackage` is `enforceSystemOrSystemUI`, spec §4). It
 * deletes and re-creates that channel at the start of every run, which is also what clears the
 * user-locked bits a previous run left behind (deleting drops the lock bits — Q2's whole point).
 */
class SelfCheck(context: Context, private val mapper: AndroidChannelMapper) {

    private val appContext: Context = context.applicationContext
    private val manager: NotificationManager = appContext.getSystemService(NotificationManager::class.java)

    /** Spec §5.7's four conditions, one line each, so a failure is diagnosable rather than mysterious. */
    fun probe(): List<String> {
        val report = TransportProbe.probe(appContext)
        return Capability.entries.map { check(it.name, report.isUsable(it)) }
    }

    /** The mapper and [transport], end to end, on the one channel we may read back. */
    fun roundTrip(transport: ChannelTransport): List<String> {
        val lines = mutableListOf<String>()
        val target = ChannelTarget.perApp(
            userId = PlatformUser.id(),
            packageName = appContext.packageName,
            uid = Process.myUid(),
        )

        manager.deleteNotificationChannel(CHANNEL_ID)
        manager.createNotificationChannel(pristineChannel())
        val pristine = mapper.snapshotOf(requireNotNull(manager.getNotificationChannel(CHANNEL_ID)))
        lines += check(
            "snapshot captures the channel",
            pristine.fields.soundUri == DEFAULT_SOUND &&
                pristine.fields.vibrationPattern == VIBRATION_PATTERN &&
                pristine.fields.importance == NotificationManager.IMPORTANCE_HIGH &&
                pristine.fields.vibrationEnabled &&
                pristine.channelBytes != null,
            { pristine.fields.toString() },
        )

        for (state in listOf(CooldownState.POLITE, CooldownState.MUTED)) {
            val edit = EditFactory.editFor(pristine, state, CONFIG, NoPoliteSound)
            val written = transport.write(target, pristine, edit)
            lines += check("$state write accepted by ${transport.name}", written == WriteResult.Ok) {
                written.toString()
            }
            val readBack = readBackFields()
            // Compared against what the *platform* makes of the write, not against the edit's own
            // fields. On a ROM with `Flags.notificationChannelVibrationEffectApi()` a pattern write
            // also derives `mVibrationEffect` (`research/aosp/NotificationChannel.java:728-731`), so
            // POLITE can never read back as the edit that produced it; comparing the two was a check
            // that could only fail on this ROM. `appliedContent` is read through the same accessors
            // `readBackFields` uses, so this still proves the stored channel is the one we built —
            // and now it is the only comparison that can tell us if the platform normalises anything
            // our own object does not.
            val expected = transport.appliedContent(target, pristine, edit)
            lines += check("$state landed on the channel", readBack == expected) {
                "wrote ${edit.fields}, transport reports $expected, read $readBack"
            }
        }

        val restore = EditFactory.editFor(pristine, CooldownState.DEFAULT, CONFIG, NoPoliteSound)
        val restored = transport.write(target, pristine, restore)
        lines += check("restore write accepted", restored == WriteResult.Ok) { restored.toString() }
        val afterRestore = readBackFields()
        lines += check("the channel is back to its snapshot, field for field", afterRestore == pristine.fields) {
            "snapshot ${pristine.fields}, read $afterRestore"
        }
        lines += check(
            "importance was never touched",
            afterRestore?.importance == NotificationManager.IMPORTANCE_HIGH,
        ) { "importance ${afterRestore?.importance}" }
        // The channel is deliberately left in place: the next verification step reads it back out of
        // `dumpsys notification --noredact`, where `mUserLockedFields` is visible and proves both the
        // lock footprint our write leaves and that the values themselves came back.
        return lines
    }

    /**
     * Puts an explicit vibration pattern on a foreign channel, because the end-to-end burst needs a
     * signal that separates POLITE from MUTED in the `notification_alert` log: `beep` tracks the sound
     * and `buzz` the vibration, so POLITE is `buzz=1 beep=0` and MUTED is `buzz=0 beep=0`.
     *
     * This is the plan's **only snapshot-less write**. A foreign channel cannot be read back (spec §4),
     * so the write is built from [ChannelFields] alone, which means every field with no public setter
     * takes its platform default. It is therefore restricted to a channel whose unsettable fields are
     * *measured* to be at those defaults — `com.android.shell`'s `shell_cmd`, whose dump reads
     * `mGroup='null' mParent=null mConversationId=null mDemoted=false mImportantConvo=false
     * mOriginalImp=3 mAllowBubbles=-1 mBlockableSystem=false mUserVisibleTaskShown=false`. The values
     * written for the settable fields are exactly what that channel already carries.
     *
     * The write also leaves `USER_LOCKED_VIBRATION` on the channel, and it stays after the cooldown
     * restores its values — that residue is a known limitation of Plan 2 and is visible in the
     * `dumpsys` assertion of Task 15.
     */
    fun seedVibration(transport: ChannelTransport, packageName: String, channelId: String): List<String> {
        val uid = appContext.packageManager.getApplicationInfo(packageName, 0).uid
        val target = ChannelTarget.perApp(PlatformUser.id(), packageName, uid)
        val fields = ChannelFields(
            channelId = channelId,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            soundUri = Settings.System.DEFAULT_NOTIFICATION_URI.toString(),
            audioUsage = AudioAttributes.USAGE_NOTIFICATION,
            audioContentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
            audioFlags = FLAG_MUTE_HAPTIC,
            vibrationPattern = VIBRATION_PATTERN,
            vibrationEffectBytes = null,
            vibrationEnabled = true,
        )
        Log.w(TAG, "snapshot-less write to $packageName/$channelId: no pristine record exists for it")
        val result = transport.write(
            target,
            ChannelSnapshot(fields = fields, channelBytes = null),
            ChannelEdit(fields = fields),
        )
        return listOf(check("seed vibration on $packageName/$channelId", result == WriteResult.Ok) {
            result.toString()
        })
    }

    /** Attempts the CompanionDeviceManager association transport B depends on. */
    fun attemptAssociation(): List<String> {
        var outcome = "no callback"
        CdmAssociation(appContext).associate { outcome = it }
        return listOf(check("self-managed CDM association", outcome.startsWith("association created")) { outcome })
    }

    /**
     * Attempts a real privileged-listener write. This can only run from inside the listener process,
     * which is why it is driven by [PrivilegedListenerHolder] being set by `NotificationSensor`.
     */
    fun privilegedListenerWrite(): List<String> {
        if (PrivilegedListenerHolder.service == null) {
            return listOf("SKIP privileged-listener write: the notification listener is not connected")
        }
        return roundTrip(PrivilegedListenerTransport(mapper, { PrivilegedListenerHolder.service }))
    }

    private fun readBackFields(): ChannelFields? =
        manager.getNotificationChannel(CHANNEL_ID)?.let(mapper::fieldsOf)

    private fun pristineChannel(): NotificationChannel =
        NotificationChannel(CHANNEL_ID, "Cooldown self-check", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "round-trip probe"
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN.toLongArray()
        }

    private fun check(name: String, ok: Boolean, detail: () -> String = { "" }): String {
        val extra = detail()
        val line = "${if (ok) "PASS" else "FAIL"} $name" + if (extra.isEmpty()) "" else " -- $extra"
        Log.i(TAG, line)
        return line
    }

    companion object {
        const val TAG = "CooldownCheck"
        const val CHANNEL_ID = "hypercooldown_self_check"
        const val DEFAULT_SOUND = "content://settings/system/notification_sound"

        /**
         * `AudioAttributes.FLAG_MUTE_HAPTIC`, which is `0x800` in the platform but is `@hide`, so the
         * public `android.jar` of `compileSdk 36` does not carry it and naming it does not compile.
         * The value is the one this ROM's own `dumpsys` prints for the shell channel
         * (`flags=0x800(FLAG_MUTE_HAPTIC)`), and it is written so the foreign channel the seed targets
         * is reproduced field for field.
         */
        private const val FLAG_MUTE_HAPTIC = 0x800

        val VIBRATION_PATTERN = listOf(0L, 255L, 200L, 255L)
        private val CONFIG = EngineConfig()
    }
}
