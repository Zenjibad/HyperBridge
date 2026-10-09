package com.d4viddf.hyperbridge.cooldown.act

import android.content.ComponentName
import android.os.Parcel
import android.os.RemoteException
import app.hypercooldown.actuation.WriteResult
import rikka.shizuku.ShizukuBinderWrapper

/**
 * The self-heal: `INotificationManager.setNotificationListenerAccessGrantedForUser(component,
 * userId, true, true)` — the transaction behind `cmd notification allow_listener <component>` — sent
 * through the Shizuku transport the app already uses for its channel writes.
 *
 * **Why this and not `requestRebind`.** The device decided it. `NotificationListenerService
 * .requestRebind` reaches `ManagedServices.setComponentState(component, userId, true)`
 * (`research/ManagedServices.java:1157`), which returns immediately when the component is not
 * snoozed — and a torn-but-still-enabled component is not snoozed, so the call is a no-op here. The
 * allow-list path ends in `rebindServices(false, userId)` (`:892`) instead. Measured on this device
 * with the app in exactly that broken state: `cmd notification disallow_listener` + `allow_listener`,
 * and `allow_listener` **alone**, took the bound-service count from `0` to `9` and made the app log
 * `listener connected`.
 *
 * **There is no way to revoke from here.** The function takes no `granted` argument and writes
 * `true` into the parcel twice: re-granting access the user already granted is idempotent, while
 * re-granting access the user revoked would be a serious bug. The decision that this may be called at
 * all is [RebindPolicy.healStep]'s, which answers nothing at all when access is off — and this
 * function is reached only from [ListenerWatchdog] on that one step.
 *
 * The wire format is the ROM's own `$Stub$Proxy` body, not a guess and not a bundled AIDL: interface
 * token, `writeTypedObject(ComponentName, 0)`, `writeInt(userId)`, `writeBoolean(granted)`,
 * `writeBoolean(userSet)`, `transact(117, …)`, `readException()`. The descriptor is checked before
 * every transaction and the code comes from [RomAbi], which pins it to the disassembly extract;
 * `RomAbiTest` fails if the two ever drift. `userSet = true` is load-bearing, not decoration:
 * `NotificationManagerService` returns early for `userSet = false` once the user has set the access
 * themselves (`research/aosp/NotificationManagerService.java:6615`), and this app's component is
 * user-set by definition — the user granted it.
 *
 * This file is device-only code: the binder call cannot be exercised by a JVM test, and nothing here
 * is claimed to have run until a device log says so.
 */
object ListenerAllowList {

    /**
     * Re-grant notification access to [component], which is already granted it, to make the platform
     * rebuild the bind. [userId] is the current user's id, from [PlatformUser] like every other
     * derivation in this app.
     */
    fun reGrant(component: ComponentName, userId: Int): WriteResult {
        val pinned = RomAbiGuard.check()
        if (pinned is WriteResult.Failed) return pinned

        val service = ShizukuNotificationBinder.get()
            ?: return WriteResult.Failed("ServiceManager.getService(\"notification\") returned null")

        val descriptor = try {
            service.interfaceDescriptor
        } catch (e: RemoteException) {
            return WriteResult.Failed("could not read the notification binder's descriptor: ${e.message}")
        }
        if (descriptor != RomAbi.DESCRIPTOR) {
            return WriteResult.Failed("notification binder is '$descriptor', not '${RomAbi.DESCRIPTOR}'")
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RomAbi.DESCRIPTOR)
            data.writeTypedObject(component, 0)
            data.writeInt(userId)
            // Both booleans are hard-coded `true` and there is no parameter that could make them
            // anything else: this function re-grants, and only ever re-grants.
            data.writeBoolean(true)
            data.writeBoolean(true)
            ShizukuBinderWrapper(service).transact(
                RomAbi.SET_NOTIFICATION_LISTENER_ACCESS_GRANTED_FOR_USER,
                data,
                reply,
                0,
            )
            // The reply carries only an exception; reading it is what turns "the re-grant was
            // rejected" into a returned result instead of a silent no-op.
            reply.readException()
            WriteResult.Ok
        } catch (e: RemoteException) {
            WriteResult.Failed("setNotificationListenerAccessGrantedForUser failed: ${e.message}")
        } catch (e: SecurityException) {
            WriteResult.Failed("setNotificationListenerAccessGrantedForUser denied: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
