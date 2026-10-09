package com.d4viddf.hyperbridge.cooldown.act.audio

/**
 * The `content://settings/system/<key>` shape a channel's sound can take, and what to read instead.
 *
 * This exists because the shape is common and *unreadable as given*. Measured on this device, on
 * `com.android.shell`'s `shell_cmd`: the channel carries
 * `content://settings/system/notification_sound`, and opening that URI is refused by
 * `SettingsProvider.openFile` — `FileNotFoundException: Direct file access no longer supported;
 * ringtone playback is available through android.media.Ringtone`
 * (`research/stray/SettingsProvider.java:829`). The provider only serves *files* for the three
 * `*_cache` URIs, which is the second candidate below.
 *
 * The setting's **value** is the sound the URI aliases. On this device
 * `settings get system notification_sound` reads `file:///system/media/audio/ui/WaterDrop_preview.ogg`
 * — a world-readable 18 454-byte Ogg. That is why resolving it matters: the common case is not
 * unreadable, it is *aliased*, and this reads the alias rather than the name.
 *
 * Reading the setting by name is what `RingtoneManager.getActualDefaultRingtoneUri` does internally
 * (`getSetting`, `RingtoneManager.java`), so it is the same answer as going through
 * `RingtoneManager`/`Ringtone` — with the difference that it also covers a channel pointing at any
 * other system setting, and that `Ringtone` can play a sound but exposes no readable stream.
 *
 * Pure Kotlin — no `android.*` — so the whole mapping is a JVM test, and the one Android call it needs
 * (the `Settings.System` read) is the resolver's, not this object's.
 */
object SettingsSoundUris {

    /** `Settings.AUTHORITY`. Spelled out so this file stays free of `android.*`. */
    const val AUTHORITY = "settings"

    /**
     * The `Settings.System` key a `content://settings/system/<key>` URI names, or null when [uri] is
     * not that shape. A user-qualified `content://settings/0/system/<key>` is accepted and the user id
     * dropped, because the key is what is wanted and the caller's own user is the one being read.
     */
    fun settingKeyOf(uri: String): String? {
        val prefix = "content://$AUTHORITY/"
        if (!uri.startsWith(prefix)) return null
        val segments = uri.removePrefix(prefix).split('/')
        val afterUser = if (segments.firstOrNull()?.toIntOrNull() != null) segments.drop(1) else segments
        if (afterUser.size != 2 || afterUser[0] != "system") return null
        return afterUser[1].takeIf { it.isNotEmpty() }
    }

    /**
     * The URIs that might hold the bytes behind a settings URI, most specific first.
     *
     * [value] is what `Settings.System.getString` returns for the key — passed in rather than read here
     * so this stays pure. A value equal to the alias itself is refused: `SettingsProvider` will not
     * *store* one (`isValidMediaUri`), but a value read back must not send the reader in a circle. The
     * provider's own cache file is offered either way: it is written whenever the setting is set, and
     * it is the one route to the bytes of a user-chosen sound that needs no storage permission.
     */
    fun candidatesFor(uri: String, value: String?): List<String> {
        val key = settingKeyOf(uri) ?: return emptyList()
        val setting = value?.trim()?.takeIf { it.isNotEmpty() && it != uri }
        return listOfNotNull(setting, "content://$AUTHORITY/system/${key}_cache")
    }
}
