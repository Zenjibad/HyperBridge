package com.d4viddf.hyperbridge.cooldown.act.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the settings-URI reading that makes the common case readable.
 *
 * Measured on the device: `com.android.shell`'s `shell_cmd` channel carries
 * `content://settings/system/notification_sound`, and reading *that* URI is refused by
 * `SettingsProvider.openFile` — `FileNotFoundException: Direct file access no longer supported;
 * ringtone playback is available through android.media.Ringtone`
 * (`research/stray/SettingsProvider.java:829`). The setting's *value* on this device is
 * `file:///system/media/audio/ui/WaterDrop_preview.ogg`, a world-readable 18 454-byte Ogg.
 *
 * Pure Kotlin, so the whole mapping is proved here rather than on the phone. `Settings.System` itself
 * is reached through the one-argument seam the resolver takes, which is why this test never touches an
 * Android class: in `:app`'s JVM tests every one of them is a `Stub!` thunk that throws.
 */
class SettingsSoundUrisTest {

    @Test
    fun `a system settings URI names the Settings System key it aliases`() {
        assertEquals(
            "notification_sound",
            SettingsSoundUris.settingKeyOf("content://settings/system/notification_sound"),
        )
        assertEquals("ringtone", SettingsSoundUris.settingKeyOf("content://settings/system/ringtone"))
        assertEquals(
            "alarm_alert",
            SettingsSoundUris.settingKeyOf("content://settings/system/alarm_alert"),
        )
    }

    @Test
    fun `a user qualified settings URI is read without the user id`() {
        assertEquals(
            "notification_sound",
            SettingsSoundUris.settingKeyOf("content://settings/0/system/notification_sound"),
        )
    }

    @Test
    fun `anything that is not a system setting has no key`() {
        assertNull(SettingsSoundUris.settingKeyOf("content://media/external/audio/media/42"))
        assertNull(
            SettingsSoundUris.settingKeyOf("file:///system/media/audio/ui/WaterDrop_preview.ogg"),
        )
        assertNull(SettingsSoundUris.settingKeyOf("content://settings/secure/foo"))
        assertNull(SettingsSoundUris.settingKeyOf("content://settings/system/"))
        assertNull(SettingsSoundUris.settingKeyOf("content://settings/system"))
        assertNull(SettingsSoundUris.settingKeyOf("content://settings/system/notification_sound/x"))
        assertNull(SettingsSoundUris.settingKeyOf(""))
    }

    @Test
    fun `the setting value comes first and the provider cache second`() {
        assertEquals(
            listOf(
                "file:///system/media/audio/ui/WaterDrop_preview.ogg",
                "content://settings/system/notification_sound_cache",
            ),
            SettingsSoundUris.candidatesFor(
                "content://settings/system/notification_sound",
                "file:///system/media/audio/ui/WaterDrop_preview.ogg",
            ),
        )
    }

    @Test
    fun `a setting that was never written still offers the provider cache`() {
        assertEquals(
            listOf("content://settings/system/notification_sound_cache"),
            SettingsSoundUris.candidatesFor("content://settings/system/notification_sound", null),
        )
        assertEquals(
            listOf("content://settings/system/notification_sound_cache"),
            SettingsSoundUris.candidatesFor("content://settings/system/notification_sound", "  "),
        )
    }

    @Test
    fun `a self referential setting value is refused`() {
        // `SettingsProvider.isValidMediaUri` refuses to *store* one, but a value read back must not
        // send the reader in a circle. The provider cache is still offered behind it.
        assertEquals(
            listOf("content://settings/system/notification_sound_cache"),
            SettingsSoundUris.candidatesFor(
                "content://settings/system/notification_sound",
                "content://settings/system/notification_sound",
            ),
        )
    }

    @Test
    fun `a URI that is not a system setting offers no candidates`() {
        assertEquals(
            emptyList<String>(),
            SettingsSoundUris.candidatesFor("content://media/external/audio/media/42", "x"),
        )
    }
}
