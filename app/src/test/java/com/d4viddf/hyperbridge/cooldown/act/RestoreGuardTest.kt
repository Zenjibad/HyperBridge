package com.d4viddf.hyperbridge.cooldown.act

import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.EditFactory
import app.hypercooldown.actuation.NoPoliteSound
import app.hypercooldown.actuation.PoliteSoundSource
import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure half of Plan 3 Task 2: the lock-bit model, the derivation that replaced the platform
 * read-back the device proved unavailable, and the guard's bookkeeping.
 *
 * What this test cannot cover, and does not pretend to: whether the derivation agrees with the mask the
 * platform actually stores. That is a `dumpsys notification --noredact` read of `mUserLockedFields`
 * beside the value [RestoreGuard.verdict] prints for the same channel, taken by a human — nothing in
 * `:app`'s JVM tests, and nothing in the app's own process, can read another package's channel mask.
 */
class RestoreGuardTest {

    // ---- The bit model ----

    @Test
    fun `the lock bits are the platform's own values`() {
        assertEquals(1, LockResidue.PRIORITY)
        assertEquals(2, LockResidue.VISIBILITY)
        assertEquals(4, LockResidue.IMPORTANCE)
        assertEquals(8, LockResidue.LIGHTS)
        assertEquals(16, LockResidue.VIBRATION)
        assertEquals(32, LockResidue.SOUND)
    }

    @Test
    fun `a mask is described by the bits it sets`() {
        assertEquals("none", LockResidue.describe(0))
        assertEquals("IMPORTANCE", LockResidue.describe(4))
        // The residue measured on the device. `dumpsys` prints `mUserLockedFields=30` and the ROM
        // formats that field with Integer.toHexString, so the mask is 0x30 = 48 = VIBRATION | SOUND.
        assertEquals("VIBRATION|SOUND", LockResidue.describe(48))
        assertEquals("0x30", LockResidue.render(48))
        assertEquals(
            "PRIORITY|VISIBILITY|IMPORTANCE|LIGHTS|VIBRATION|SOUND",
            LockResidue.describe(63),
        )
    }

    @Test
    fun `only the importance bit is shell-clearable`() {
        assertEquals(LockResidue.IMPORTANCE, LockResidue.shellClearable())
        assertEquals(48 and 4.inv(), LockResidue.unreleasable(48))
        assertEquals(0, LockResidue.unreleasable(LockResidue.IMPORTANCE))
        // The measured residue is not the importance bit, so none of it is releasable.
        assertEquals(48, LockResidue.unreleasable(48))
        assertFalse(ChannelResidue("0:com.android.shell/shell_cmd", 48, 1L).canStillBeCleared)
        assertTrue(ChannelResidue("0:com.android.shell/shell_cmd", 4, 1L).canStillBeCleared)
        // An unknown mask is not a clearable one: "cannot read it" must never read as "clean".
        assertFalse(ChannelResidue("0:com.android.shell/shell_cmd", null, 1L).canStillBeCleared)
    }

    // ---- The derivation that replaced the platform read-back ----

    @Test
    fun `a damping write derives the residue the device measured`() {
        val snapshot = ChannelSnapshot(fields = fields(), channelBytes = "AA==")
        val muted = EditFactory.editFor(snapshot, CooldownState.MUTED, EngineConfig(), NoPoliteSound)

        // 0x30 = 48 = USER_LOCKED_VIBRATION | USER_LOCKED_SOUND — the mask this device's `dumpsys`
        // printed as `mUserLockedFields=30` (hex) for com.android.shell's shell_cmd after exactly the
        // write this edit makes: the sound was cleared and the vibration disabled.
        val derived = LockResidue.forWrite(snapshot.fields, muted.fields)
        assertEquals(48, derived)
        assertEquals("VIBRATION|SOUND", LockResidue.describe(derived))
        assertEquals("0x30", LockResidue.render(derived))
    }

    @Test
    fun `a polite write derives sound and vibration too, and never a bit outside its field set`() {
        val snapshot = ChannelSnapshot(fields = fields(), channelBytes = "AA==")
        val copy = PoliteSoundSource { "content://media/external/audio/media/8161" }
        val polite = EditFactory.editFor(snapshot, CooldownState.POLITE, EngineConfig(), copy)
        val derived = LockResidue.forWrite(snapshot.fields, polite.fields)

        assertEquals(48, derived)
        // Spec §5.4: importance never differs, and visibility/lights are not fields this app can write,
        // so no write of its can derive those bits.
        assertEquals(0, derived and LockResidue.IMPORTANCE)
        assertEquals(0, derived and LockResidue.VISIBILITY)
        assertEquals(0, derived and LockResidue.LIGHTS)
        assertEquals(0, derived and LockResidue.PRIORITY)
    }

    @Test
    fun `the restore write differs in the same fields as the damping, so it locks the same bits`() {
        val snapshot = ChannelSnapshot(fields = fields(), channelBytes = "AA==")
        val muted = EditFactory.editFor(snapshot, CooldownState.MUTED, EngineConfig(), NoPoliteSound)
        val restore = EditFactory.editFor(snapshot, CooldownState.DEFAULT, EngineConfig(), NoPoliteSound)

        // The restore is the value-restoring half: sound, attributes, pattern and the enabled flag come
        // back to what the snapshot recorded.
        assertEquals(snapshot.fields, restore.fields)
        // And it differs from the damped content in exactly the fields the damping changed, which is why
        // the residue is the same 0x30 before and after a restore.
        assertEquals(48, LockResidue.forWrite(muted.fields, restore.fields))
        assertEquals(
            LockResidue.forWrite(snapshot.fields, muted.fields),
            LockResidue.forWrite(muted.fields, restore.fields),
        )
    }

    @Test
    fun `a write that changes nothing derives no bits`() {
        val original = fields()
        assertEquals(0, LockResidue.forWrite(original, original))
    }

    @Test
    fun `the derivation names the fields that differ, one at a time`() {
        val original = fields()
        assertEquals(
            LockResidue.SOUND,
            LockResidue.forWrite(original, original.copy(soundUri = "content://other")),
        )
        assertEquals(LockResidue.SOUND, LockResidue.forWrite(original, original.copy(audioFlags = 0)))
        assertEquals(
            LockResidue.VIBRATION,
            LockResidue.forWrite(original, original.copy(vibrationEnabled = false)),
        )
        assertEquals(
            LockResidue.VIBRATION,
            LockResidue.forWrite(original, original.copy(vibrationPattern = listOf(0L, 77L, 200L, 77L))),
        )
        assertEquals(
            LockResidue.VIBRATION,
            LockResidue.forWrite(original, original.copy(vibrationEffectBytes = "AA==")),
        )
        assertEquals(LockResidue.IMPORTANCE, LockResidue.forWrite(original, original.copy(importance = 4)))
        assertEquals(
            LockResidue.SOUND or LockResidue.VIBRATION or LockResidue.IMPORTANCE,
            LockResidue.forWrite(
                original,
                original.copy(soundUri = null, vibrationPattern = null, importance = 4),
            ),
        )
    }

    // ---- The guard's bookkeeping ----

    @Test
    fun `a residue is derived only for a channel a restore reported`() {
        val guard = RestoreGuard { 1_000L }
        guard.onRestored(listOf(RestoredWrite("0:com.android.shell", "shell_cmd", 48)))

        val outstanding = guard.outstanding()
        assertEquals(1, outstanding.size)
        assertEquals("0:com.android.shell/shell_cmd", outstanding.single().key)
        assertEquals(48, outstanding.single().lockMask)
        assertEquals(1_000L, outstanding.single().firstDerivedMs)
        assertEquals(1, guard.unreleasableCount())
        assertEquals(1, guard.lastRestoreChannels())
        assertEquals(1, guard.restoredChannels())
    }

    @Test
    fun `the last restore's count and the process-wide count are both stated, with their units`() {
        val guard = RestoreGuard { 1_000L }
        guard.onRestored(listOf(RestoredWrite("0:com.android.shell", "shell_cmd", 48)))
        guard.onRestored(listOf(RestoredWrite("0:com.other", "other_cmd", 48)))

        // This is the inconsistency the log used to print as "1 restored" beside "2 restored key(s)":
        // one number was the pass, the other the process, and neither sentence said which.
        assertEquals(1, guard.lastRestoreChannels())
        assertEquals(2, guard.restoredChannels())
        val verdict = guard.verdict()
        assertTrue(verdict, verdict.contains("the last restore wrote 1 record key(s) (1 channel(s))"))
        assertTrue(verdict, verdict.contains("2 of the 2 channel(s) restored in this process"))
        assertTrue(verdict, verdict.contains("0:com.android.shell/shell_cmd=0x30(VIBRATION|SOUND)"))
        assertTrue(verdict, verdict.contains("0:com.other/other_cmd=0x30(VIBRATION|SOUND)"))
    }

    @Test
    fun `a residue survives a later restore of another channel`() {
        val guard = RestoreGuard { 1_000L }
        guard.onRestored(listOf(RestoredWrite("0:com.android.shell", "shell_cmd", 48)))
        guard.onRestored(listOf(RestoredWrite("0:com.other", "other_cmd", 16)))

        assertEquals(2, guard.outstanding().size)
        assertEquals(1_000L, guard.outstanding().first().firstDerivedMs)
        assertEquals(2, guard.unreleasableCount())
    }

    @Test
    fun `the verdict says the residue cannot be undone`() {
        val guard = RestoreGuard { 1_000L }
        guard.onRestored(listOf(RestoredWrite("0:com.android.shell", "shell_cmd", 48)))

        val verdict = guard.verdict()
        assertTrue(verdict, verdict.contains("cannot clear"))
        assertTrue(verdict, verdict.contains("deletes and re-creates"))
        assertTrue(verdict, verdict.contains("0x30"))
        assertTrue(verdict, verdict.contains("lower bound"))
        assertTrue(verdict, verdict.contains("unlockNotificationChannel (transaction 43)"))
        assertTrue(verdict, verdict.contains("unlockAllNotificationChannels requires system"))
    }

    @Test
    fun `an underivable residue is unknown, never zero`() {
        val guard = RestoreGuard { 1_000L }
        guard.onRestored(listOf(RestoredWrite("0:com.android.shell", "shell_cmd", null)))

        val only = guard.outstanding().single()
        assertNull(only.lockMask)
        assertFalse(only.canStillBeCleared)
        // Not counted as unreleasable, because it is not measured: the count is "channels we can prove
        // are locked", and an unknown one is reported in words instead.
        assertEquals(0, guard.unreleasableCount())
        assertTrue(guard.verdict(), guard.verdict().contains("unknown, not zero"))
    }

    @Test
    fun `a derived zero is a lower bound, not a clean mask`() {
        val guard = RestoreGuard { 1_000L }
        guard.onRestored(listOf(RestoredWrite("0:com.other", "other_cmd", 0)))

        assertEquals(0, guard.outstanding().single().lockMask)
        assertTrue(guard.outstanding().single().canStillBeCleared)
        assertEquals(0, guard.unreleasableCount())
        val verdict = guard.verdict()
        assertTrue(verdict, verdict.contains("lower bound rather than a clean mask"))
        assertTrue(verdict, verdict.contains("inherits every lock bit the channel already carried"))
    }

    @Test
    fun `the verdict of a process that has restored nothing says so`() {
        assertEquals(
            "restore guard: the last restore wrote 0 record key(s) (0 channel(s)); " +
                "nothing has been restored in this process, so no lock residue is derived",
            RestoreGuard { 1_000L }.verdict(),
        )
    }

    private fun fields(
        soundUri: String? = DEFAULT_SOUND,
        audioFlags: Int = FLAG_MUTE_HAPTIC,
        importance: Int = IMPORTANCE_DEFAULT,
        vibrationPattern: List<Long>? = PATTERN,
        vibrationEffectBytes: String? = null,
        vibrationEnabled: Boolean = true,
    ): ChannelFields = ChannelFields(
        channelId = "shell_cmd",
        importance = importance,
        soundUri = soundUri,
        audioUsage = USAGE_NOTIFICATION,
        audioContentType = CONTENT_TYPE_SONIFICATION,
        audioFlags = audioFlags,
        vibrationPattern = vibrationPattern,
        vibrationEffectBytes = vibrationEffectBytes,
        vibrationEnabled = vibrationEnabled,
    )

    private companion object {
        /** `Settings.System.DEFAULT_NOTIFICATION_URI`, as a string so no `android.*` class loads. */
        const val DEFAULT_SOUND = "content://settings/system/notification_sound"

        /** `NotificationManager.IMPORTANCE_DEFAULT`. */
        const val IMPORTANCE_DEFAULT = 3

        /** `AudioAttributes.USAGE_NOTIFICATION` / `CONTENT_TYPE_SONIFICATION`. */
        const val USAGE_NOTIFICATION = 5
        const val CONTENT_TYPE_SONIFICATION = 4

        /** `AudioAttributes.FLAG_MUTE_HAPTIC`, the flag this ROM's `dumpsys` prints for shell_cmd. */
        const val FLAG_MUTE_HAPTIC = 0x800

        /** The pattern the shell channel carried when the residue was measured. */
        val PATTERN = listOf(0L, 255L, 200L, 255L)
    }
}
