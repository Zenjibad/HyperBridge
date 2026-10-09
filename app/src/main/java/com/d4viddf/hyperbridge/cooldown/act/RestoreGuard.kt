package com.d4viddf.hyperbridge.cooldown.act

import app.hypercooldown.actuation.ChannelFields

/**
 * The `USER_LOCKED_*` bits a shell channel write leaves behind, and the arithmetic of what can never
 * be cleared — **derived from the write, because this ROM never reports them to an app.**
 *
 * A shell/Shizuku `updateNotificationChannelForPackage` write **locks every field whose value it
 * changes**: the `fromUser` that reaches `PreferencesHelper.updateNotificationChannel` is the
 * hardcoded literal `true` (Q2, `const/4 v4, #int 1`), and `lockFieldsForUpdateLocked` then locks
 * exactly the fields whose value *differed* (`research/aosp/PreferencesHelper.java:2227-2256`). So the
 * owning app re-creating its channel will never undo our values for us. Restoring the original
 * *values* is therefore an explicit write, and that write locks the same bits again — which is what
 * makes the residue a function of two contents this app already holds ([forWrite]) rather than of
 * anything the platform has to hand back.
 *
 * **Why the mask is derived and not read.** Plan 3 Task 2 first learned the residue from
 * `NotificationListenerService.onNotificationChannelModified`. Measured on this device, that callback
 * never fires for an ordinary notification listener, in any log buffer: `NotificationManagerService`
 * delivers it only when `info.isSystem || hasCompanionDevice(info) || isServiceTokenValid(info.service)`
 * (`research/aosp/NotificationManagerService.java:13504-13511`) — system, companion-device or assistant
 * listeners. The residue is therefore **unmeasured by the platform, not zero**, and Task 2's read-back
 * path (its `ChannelLockMask` reader and the `channel modified ... locked=...` line) is
 * architecturally unavailable rather than merely unlucky. Both are gone, and the misleading "no lock
 * residue reported yet ... the platform's channel-modified callback is what reports it" verdict went
 * with them; it was a sentence that could never become anything else on this ROM.
 *
 * Two consequences are load-bearing and are repeated wherever the value is shown:
 *  - **the derived mask is a lower bound.** `PreferencesHelper` begins from
 *    `updatedChannel.lockFields(existing.getUserLockedFields())`, so whatever mask the channel already
 *    carried is inherited, is not visible to this app through any public API, and is not in the
 *    derived value. "At least these bits" — never "exactly", and never "clean".
 *  - **it is derived, not observed.** Nothing on this device confirms it except a
 *    `dumpsys notification --noredact` read by a human, so the wording says "derived".
 *
 * Measured on this device (`mId='shell_cmd'`, live):
 *
 *     mUserLockedFields=30
 *
 * **That field is printed in hexadecimal.** The ROM's own `NotificationChannel.toString` reads
 * `iget v1, v3, Landroid/app/NotificationChannel;.mUserLockedFields:I` and feeds it to
 * `Ljava/lang/Integer;.toHexString:(I)Ljava/lang/String;` (`research/device/framework_dex/classes.dex`;
 * the AOSP cross-check is `research/aosp/NotificationChannel.java:1705`). So the residue is `0x30` = 48
 * = `USER_LOCKED_VIBRATION (0x10) | USER_LOCKED_SOUND (0x20)`. Reading that dump as decimal 30 gives
 * `VISIBILITY|IMPORTANCE|LIGHTS|VIBRATION`, which is wrong — bit 4 is not set, and the plan text that
 * repeated the decimal reading is corrected by Plan 3 Task 2. [forWrite] reproduces the measured `0x30`
 * for a channel that had both a sound and vibration, which is the cross-check the device run gave.
 *
 * The values come back exactly; this mask does not, and nothing this app can call clears it.
 * `unlockNotificationChannel` (transaction 43) is shell-reachable, but the ROM routes it to
 * `PreferencesHelper.unlockNotificationChannelImportance` (`research/aosp/PreferencesHelper.java:1230`),
 * which clears `USER_LOCKED_IMPORTANCE` (4) only, while the residue a damping write leaves is `0x30`;
 * `unlockAllNotificationChannels` requires `checkCallerIsSystem()` and is not reachable at all. See
 * [RestoreGuard.verdict] for the sentence the UI shows because of it.
 */
object LockResidue {

    /** `NotificationChannel.USER_LOCKED_PRIORITY`. */
    const val PRIORITY = 1

    /** `NotificationChannel.USER_LOCKED_VISIBILITY`. */
    const val VISIBILITY = 2

    /** `NotificationChannel.USER_LOCKED_IMPORTANCE` — the only bit transaction 43 clears. */
    const val IMPORTANCE = 4

    /** `NotificationChannel.USER_LOCKED_LIGHTS`. */
    const val LIGHTS = 8

    /** `NotificationChannel.USER_LOCKED_VIBRATION`. */
    const val VIBRATION = 16

    /** `NotificationChannel.USER_LOCKED_SOUND`. */
    const val SOUND = 32

    private const val ALL = PRIORITY or VISIBILITY or IMPORTANCE or LIGHTS or VIBRATION or SOUND

    private val NAMED = listOf(
        PRIORITY to "PRIORITY",
        VISIBILITY to "VISIBILITY",
        IMPORTANCE to "IMPORTANCE",
        LIGHTS to "LIGHTS",
        VIBRATION to "VIBRATION",
        SOUND to "SOUND",
    )

    /**
     * `0` reads as `none`. Bits outside the six named ones are reported rather than dropped: the
     * platform has two more (`SHOW_BADGE`, `ALLOW_BUBBLE`) and a future write could set them.
     */
    fun describe(mask: Int): String {
        if (mask == 0) return "none"
        val named = NAMED.filter { mask and it.first != 0 }.joinToString("|") { it.second }
        val unknown = mask and ALL.inv()
        return when {
            named.isEmpty() -> "unknown(0x${Integer.toHexString(mask)})"
            unknown == 0 -> named
            else -> "$named|unknown(0x${Integer.toHexString(unknown)})"
        }
    }

    /** The platform's own rendering of the field, so a log line and a `dumpsys` line compare directly. */
    fun render(mask: Int): String = "0x${Integer.toHexString(mask)}"

    /**
     * The bits the write of [written] over [original] must have locked.
     *
     * `lockFieldsForUpdateLocked` locks a field only when its value differed
     * (`research/aosp/PreferencesHelper.java:2227-2256`), so this is a function of the two contents and
     * of nothing else:
     *  - `SOUND` when the sound URI or any of the three audio attributes differ. The platform clears
     *    the attributes with the sound (`setSound(null, null)`), so they move together — and comparing
     *    them is what keeps a "sound" difference that is only an attribute difference from being
     *    missed.
     *  - `VIBRATION` when the pattern, the marshalled effect or the enabled flag differ.
     *  - `IMPORTANCE` when importance differs, which spec §5.4 makes impossible for this app
     *    (`EditFactory` copies it through): the bit is here so that the derivation is a function of the
     *    two contents rather than of an assumption about them.
     *  - `VISIBILITY`, `LIGHTS`, `PRIORITY` and the two bits beyond [ALL] cannot be derived at all:
     *    they are not in `ChannelFields`, so no write of this app's can change them, and a difference in
     *    one of them would have been inherited from the channel rather than set by us.
     */
    fun forWrite(original: ChannelFields, written: ChannelFields): Int {
        var mask = 0
        if (original.importance != written.importance) mask = mask or IMPORTANCE
        if (soundDiffers(original, written)) mask = mask or SOUND
        if (vibrationDiffers(original, written)) mask = mask or VIBRATION
        return mask
    }

    private fun soundDiffers(original: ChannelFields, written: ChannelFields): Boolean =
        original.soundUri != written.soundUri ||
            original.audioUsage != written.audioUsage ||
            original.audioContentType != written.audioContentType ||
            original.audioFlags != written.audioFlags

    private fun vibrationDiffers(original: ChannelFields, written: ChannelFields): Boolean =
        original.vibrationPattern != written.vibrationPattern ||
            original.vibrationEffectBytes != written.vibrationEffectBytes ||
            original.vibrationEnabled != written.vibrationEnabled

    /**
     * The only bit `unlockNotificationChannel` (transaction 43) clears on this ROM: it routes to
     * `PreferencesHelper.unlockNotificationChannelImportance`
     * (`research/aosp/PreferencesHelper.java:1230`). This app never sends it — see [RestoreGuard] —
     * but the fact is what makes [unreleasable] meaningful.
     */
    fun shellClearable(): Int = IMPORTANCE

    /** The part of [mask] that no API this app can call removes. */
    fun unreleasable(mask: Int): Int = mask and shellClearable().inv()
}

/**
 * One channel's residual lock mask, **derived** from the write this app made to it.
 *
 * [key] is the channel's full key: the per-app cooldown key (`"$userId:$packageName"`, AOSP's channel
 * key with the channel id blanked) followed by `/` and the channel id. A residue belongs to a
 * *channel*, and one restore record covers one channel, so the two counts the log and the UI print are
 * the same set of things said in the two units the record uses — see [RestoreGuard.verdict].
 * [lockMask] is null when there is nothing to derive from, because the record carries no copy of what
 * this app last wrote: an **unknown** residue, which is not a zero one. [firstDerivedMs] is when the
 * residue on this channel was first derived.
 */
data class ChannelResidue(val key: String, val lockMask: Int?, val firstDerivedMs: Long) {

    /**
     * True when the only bits left are bits transaction 43 could clear.
     *
     * This is a statement about the mask, not an intention to call 43: see [RestoreGuard]. The useful
     * direction is the negation — [RestoreGuard.unreleasableCount] counts the channels where this is
     * false, which is where a "restored" channel is still observably not the user's own. An underivable
     * mask is not clearable either: "unknown" must never read as "clean".
     */
    val canStillBeCleared: Boolean get() = lockMask != null && LockResidue.unreleasable(lockMask) == 0
}

/**
 * What one restore wrote to one channel: the record key it was restored under, the channel id that
 * record held, and the residue [LockResidue.forWrite] derives for it.
 *
 * [derivedMask] is null when the record carries no copy of what this app last wrote — a process that
 * died between the transport accepting a write and the marker reaching disk — which is reported as an
 * unknown residue rather than as no residue.
 */
data class RestoredWrite(val cooldownKey: String, val channelId: String, val derivedMask: Int?)

/**
 * The guard Plan 2 did not have: it states out loud what a restore cannot undo, from the residue
 * **derived** from the write this app made — see [LockResidue] for why nothing on this ROM can observe
 * it instead.
 *
 * In plain words, because this is the one thing that must not be dressed up:
 *
 *  - the field **values** are restored exactly — sound, audio attributes, vibration, importance and
 *    every other field the snapshot captured go back to what the app had;
 *  - the lock **mask** on that channel id stays non-zero, and this app cannot clear it. Only the
 *    owning app re-creating its channel, or a delete, resets it;
 *  - `unlockNotificationChannel` (transaction 43) cannot help: the ROM routes it to
 *    `PreferencesHelper.unlockNotificationChannelImportance`, which clears `USER_LOCKED_IMPORTANCE`
 *    (4) only, while the residue this app leaves is `USER_LOCKED_VIBRATION|USER_LOCKED_SOUND` (`0x30`);
 *    `unlockAllNotificationChannels` requires `checkCallerIsSystem()` and is not reachable at all;
 *  - therefore **this app must never claim a restored channel is pristine.** [verdict] is the sentence
 *    the UI shows instead.
 *
 * Pure by construction: no `android.*` import, so `RestoreGuardTest` exercises all of it on the JVM.
 * What no JVM test can cover is whether the derivation matches the platform's own mask, and that is
 * named rather than skipped: the device check is a `dumpsys notification --noredact` read of
 * `mUserLockedFields` beside the derived mask this class reports for the same channel.
 */
class RestoreGuard(private val nowMs: () -> Long = System::currentTimeMillis) {

    /** Every channel this process has restored, with the residue derived for it, oldest first. */
    private val residues = linkedMapOf<String, ChannelResidue>()

    /** The channels the most recent [onRestored] call wrote, in the order it wrote them. */
    private val lastRestore = mutableListOf<String>()

    /**
     * Hands over the writes a restore just made.
     *
     * Called *after* the write, and with the residues derived *before* it: a successful restore drops
     * the record, and the record is the only copy of both sides of the comparison [LockResidue.forWrite]
     * makes. A residue is permanent until the app or the user deletes and re-creates the channel, so
     * entries accumulate over the process rather than being replaced by the next restore; the size of
     * the pass that just happened is kept separately ([lastRestoreChannels]) so the two counts can be
     * told apart instead of being printed as if they were the same number.
     */
    @Synchronized
    fun onRestored(writes: Collection<RestoredWrite>) {
        lastRestore.clear()
        writes.forEach { write ->
            val key = channelKey(write.cooldownKey, write.channelId)
            lastRestore += key
            residues[key] = ChannelResidue(
                key = key,
                lockMask = write.derivedMask,
                firstDerivedMs = residues[key]?.firstDerivedMs ?: nowMs(),
            )
        }
    }

    /**
     * The residues derived so far, oldest first.
     *
     * Cumulative over the process on purpose: a lock bit outlives the record that explains it, so a
     * channel restored five minutes ago still carries its mask, and a report that dropped it would be
     * the "pristine" claim this class exists to prevent.
     */
    @Synchronized
    fun outstanding(): List<ChannelResidue> = residues.values.toList()

    /** How many restored channels carry bits that no API this app can call removes. */
    @Synchronized
    fun unreleasableCount(): Int = outstanding().count { it.lockMask != null && !it.canStillBeCleared }

    /** How many channels the most recent [onRestored] call put back — one record key each. */
    @Synchronized
    fun lastRestoreChannels(): Int = lastRestore.size

    /** How many distinct channels this process has restored, which is what [outstanding] covers. */
    @Synchronized
    fun restoredChannels(): Int = residues.size

    /**
     * The one honest sentence for the UI and the log.
     *
     * It says which unit each count is in — the last restore's record keys, which is one channel each,
     * and the channels this process has restored in total — because those two numbers disagree the
     * moment a second restore happens, and a guard whose arithmetic cannot be read is a guard nobody
     * can act on. "Derived" and "a lower bound" are in the sentence, and so is the fact that nothing
     * this app can call clears the bits: `unlockNotificationChannel` (transaction 43) is shell-reachable
     * and clears only the importance bit, and `unlockAllNotificationChannels` requires system.
     */
    @Synchronized
    fun verdict(): String {
        val entries = outstanding()
        val pass = lastRestore.size
        val head = "restore guard: the last restore wrote $pass record key(s) ($pass channel(s))"
        if (entries.isEmpty()) {
            return "$head; nothing has been restored in this process, so no lock residue is derived"
        }

        val derived = entries.filter { it.lockMask != null }
        val unknown = entries.size - derived.size
        val masks = if (derived.isEmpty()) {
            ""
        } else {
            " (" + derived.joinToString(", ") {
                "${it.key}=${LockResidue.render(it.lockMask!!)}(${LockResidue.describe(it.lockMask)})"
            } + ")"
        }
        val unknownNote = if (unknown == 0) {
            ""
        } else {
            "; $unknown of the ${entries.size} restored channel(s) had no recorded write, so their " +
                "residue is not derivable — unknown, not zero"
        }

        val blocked = derived.filter { !it.canStillBeCleared }
        if (blocked.isEmpty()) {
            val meaningful = derived.filter { it.lockMask != 0 }
            return if (meaningful.isEmpty()) {
                "$head; none of the ${entries.size} channel(s) restored in this process has a " +
                    "derivable lock residue$masks, which is a lower bound rather than a clean mask: " +
                    "the platform also inherits every lock bit the channel already carried$unknownNote"
            } else {
                "$head; none of the ${entries.size} channel(s) restored in this process locks more " +
                    "than importance$masks; unlockNotificationChannel (transaction 43) could clear that " +
                    "bit, but this app never sends it, and unlockAllNotificationChannels requires " +
                    "system$unknownNote"
            }
        }
        return "$head; ${blocked.size} of the ${entries.size} channel(s) restored in this process " +
            "carry a derived lock residue this app cannot clear$masks; the field values are restored " +
            "exactly, but the lock mask stays non-zero until the app or the user deletes and " +
            "re-creates the channel, and the derived value is a lower bound because the platform also " +
            "inherits every lock bit the channel already carried$unknownNote. unlockNotificationChannel " +
            "(transaction 43) is shell-reachable and clears only the importance bit; " +
            "unlockAllNotificationChannels requires system."
    }

    companion object {

        /** `"$cooldownKey/$channelId"`. A package name cannot contain `/`, so the split below is exact. */
        fun channelKey(cooldownKey: String, channelId: String): String = "$cooldownKey/$channelId"

        /** The per-app cooldown key a channel key belongs to. */
        fun packageKeyOf(channelKey: String): String = channelKey.substringBeforeLast('/')
    }
}
