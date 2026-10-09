package com.d4viddf.hyperbridge.cooldown.act.audio

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Resolves a `content://settings/system/<key>` sound by reading the setting it aliases.
 *
 * Measured on this device, before this class existed:
 *
 * ```
 * W Cooldown: cannot read the channel sound content://settings/system/notification_sound:
 *             FileNotFoundException: Direct file access no longer supported; ringtone playback is
 *             available through android.media.Ringtone
 * W Cooldown: fidelity gaps for 0:com.android.shell: [POLITE_SOUND_UNAVAILABLE]
 * ```
 *
 * `Research`'s copy of the provider says why, and what to do instead: `SettingsProvider.openFile`
 * serves a file only for the three `*_cache` URIs, and throws that message for everything else
 * (`research/stray/SettingsProvider.java:806-830`). The setting's value is the real sound —
 * `file:///system/media/audio/ui/WaterDrop_preview.ogg` on this unit, a world-readable Ogg — and reading
 * it by name is also what `RingtoneManager.getActualDefaultRingtoneUri` does internally, so this is the
 * same answer as going through `RingtoneManager` without needing a `Ringtone` that can play a sound but
 * cannot hand anyone its bytes.
 *
 * The whole decision of *which* URIs to try is [SettingsSoundUris], which is pure and unit-tested; this
 * class is the one Android call it needs, plus the logging for a settings read that fails.
 */
class AndroidSoundUriResolver(context: Context) : SoundUriResolver {

    private val appContext: Context = context.applicationContext

    override fun candidatesFor(uri: String): List<String> {
        val key = SettingsSoundUris.settingKeyOf(uri) ?: return emptyList()
        val value = try {
            Settings.System.getString(appContext.contentResolver, key)
        } catch (t: Exception) {
            Log.w(TAG, "cannot read Settings.System.$key: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        return SettingsSoundUris.candidatesFor(uri, value)
    }

    private companion object {
        const val TAG = "Cooldown"
    }
}
