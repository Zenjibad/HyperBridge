package com.d4viddf.hyperbridge.cooldown.act

/**
 * The `INotificationManager` ABI of the ROM this app was built for.
 *
 * The codes are **established from this ROM's own `framework.jar` by disassembly, never guessed** —
 * and never derived from a bundled `.aidl` file either. A bundled AIDL numbers the methods *it*
 * declares from `FIRST_CALL_TRANSACTION` upward, so an AIDL that declares only the two methods this
 * app uses would send `1` where the ROM expects `42`; a wrong code invokes a different method of the
 * same interface and shell passes the caller guard for all of them. `RomAbiTest` re-reads the
 * disassembly extract the numbers came from and fails if they drift.
 *
 * [BUILD_FINGERPRINT] is the anchor: an OTA can renumber the whole interface, so [RomAbiGuard]
 * refuses to write when the fingerprint changes, and the capability probe reports it. Standing down
 * is the only safe response to "the codes might have moved" (spec §5.6: never silently pretend to
 * work), and re-deriving them is Task 9's first step again.
 */
object RomAbi {

    /** The token every method of this interface writes; verified in the extract. */
    const val DESCRIPTOR = "android.app.INotificationManager"

    /** `updateNotificationChannelForPackage(String pkg, int uid, in NotificationChannel channel)`. */
    const val UPDATE_NOTIFICATION_CHANNEL_FOR_PACKAGE = 42

    /**
     * `unlockNotificationChannel(String pkg, int uid, String channelId)` — sent by
     * [ShizukuTransport.unlockImportance], after a restore write and only for a record whose last
     * write moved the importance.
     *
     * Pinned, because Task 9 pins the ABI it was read from. The reasoning below is **kept, and its
     * conclusion reversed in exactly one place**, which is the honest way to record it: every bullet
     * still holds for the residue the sound and the vibration leave, and that is the residue which is
     * still unreleasable. What changed is that importance *is* now written — the heads-up option lowers
     * `HIGH` to `DEFAULT` (`EditFactory.demotedImportance`) — so the one bit this transaction clears is
     * now a bit this app sets, and not calling it would be the "all downside, no upside" the old
     * conclusion warned about, in reverse:
     *
     *  - **It cannot clear the sound or the vibration bit.** The ROM routes it to
     *    `PreferencesHelper.unlockNotificationChannelImportance`, which clears `USER_LOCKED_IMPORTANCE`
     *    (4) and nothing else. The residue a cooldown write leaves on this device is
     *    `mUserLockedFields=30` — **hexadecimal**, per the ROM's own `toString`, which formats this
     *    field with `Integer.toHexString` — i.e. `0x30` = `USER_LOCKED_VIBRATION|USER_LOCKED_SOUND`
     *    (`0x10|0x20` = 48). Neither bit is 4, so a restore still leaves the mask with those bits set
     *    and the channel still observably not the user's own. That is what [LockResidue] and
     *    [RestoreGuard.verdict] say, and it is unchanged.
     *  - **A mask inherits.** `PreferencesHelper` starts from
     *    `updatedChannel.lockFields(existing.getUserLockedFields())`, so a bit 4 in a mask may be the
     *    user's own importance lock set in Settings, and 43 would drop that preference too. That risk is
     *    now bounded rather than hypothetical: the call is made **only** for a record whose own last
     *    write moved the importance (`ChannelActuator.restore`), i.e. only where this app is the one that
     *    locked it. A channel nobody damped is never unlocked.
     *  - **Nothing else can clear the other bits.** `unlockAllNotificationChannels`, the only call that
     *    would clear `0x10|0x20`, requires `checkCallerIsSystem` and is unreachable from shell and from
     *    a Shizuku-bound app.
     *
     * The sound/vibration residue therefore stands until the owning app deletes and re-creates its
     * channel, and this app says so instead of sending a transaction that cannot help. The read-back
     * that once accompanied this constant is still gone: the platform never delivers the
     * channel-modified callback to a plain listener on this ROM, so the residue is derived from the
     * write instead of read back (see [LockResidue]).
     */
    const val UNLOCK_NOTIFICATION_CHANNEL = 43

    /**
     * `updateNotificationChannelFromPrivilegedListener(INotificationListener, String, UserHandle,
     * NotificationChannel)`. Only transport B would call it, and only if it ever became reachable.
     */
    const val UPDATE_NOTIFICATION_CHANNEL_FROM_PRIVILEGED_LISTENER = 97

    /**
     * `setNotificationListenerAccessGrantedForUser(ComponentName listener, int userId, boolean
     * granted, boolean userSet)`. **The one code this app sends to heal a torn listener bind**, and
     * the only reason it can heal one at all.
     *
     * `cmd notification allow_listener <component>` is this method with `granted = true,
     * userSet = true` (`research/NotificationShellCmd.java:226`; note `userSet` — with `false`,
     * `NotificationManagerService` returns without doing anything when the user has already set the
     * access themselves, `research/aosp/NotificationManagerService.java:6615`). That path was
     * **measured** on this device: with the app torn down and the component enabled, it took the
     * bound-service count from `0` to `9` and the app logged `listener connected`.
     *
     * `NotificationListenerService.requestRebind` — the API the javadoc names for this — cannot do
     * it on this ROM: it lands on `ManagedServices.setComponentState`
     * (`research/ManagedServices.java:1157`), whose `if (previous == enabled) return` is exactly the
     * state a torn-but-enabled component is in. The allow-list path instead ends in
     * `rebindServices(false, userId)` (`:892`), which binds approved components that have no live
     * connection. The device is the arbiter of that difference, and it decided: see Task 3's
     * execution correction 3.
     *
     * Established from this ROM's own `framework.jar` → `classes.dex` by `dexdump`, exactly as
     * 42/43/97 were, and corroborated twice inside that one dump: `$Stub$Proxy` hard-codes
     * `const/16 v4, #int 117` and calls `transact(117, …)` after writing the interface token,
     * `writeTypedObject(ComponentName, 0)`, `writeInt(userId)`, `writeBoolean(granted)` and
     * `writeBoolean(userSet)`; and `$Stub.TRANSACTION_…`'s static final value is `117`, whose
     * `onTransact` case reads those same four arguments and calls the method. `RomAbiTest` re-reads
     * both extracts and fails if either drifts.
     *
     * **This code may only ever be sent with `granted = true`.** [ListenerAllowList] has no parameter
     * that could write `false`: re-granting access the user has already granted is idempotent, while
     * silently re-granting access the user revoked would be a serious bug, so the payload is built
     * in exactly one place and that place writes `true` twice.
     */
    const val SET_NOTIFICATION_LISTENER_ACCESS_GRANTED_FOR_USER = 117

    /** `adb shell getprop ro.build.fingerprint` on the device the codes were read from. */
    const val BUILD_FINGERPRINT =
        "Xiaomi/nezha_eea/nezha:17/CP2A.260605.016/OS3.0.336.0.XPAEUXM:user/release-keys"
}
