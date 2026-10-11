package com.d4viddf.hyperbridge.cooldown.act

import android.os.Parcel
import android.os.RemoteException
import app.hypercooldown.actuation.ChannelEdit
import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.ChannelTarget
import app.hypercooldown.actuation.ChannelTransport
import app.hypercooldown.actuation.WriteResult
import rikka.shizuku.ShizukuBinderWrapper

/**
 * Transport A (spec §8 Q3): a shell write through the hidden
 * `INotificationManager.updateNotificationChannelForPackage`.
 *
 * The call is made by hand on a `Parcel` rather than through a bundled AIDL, for two independent
 * reasons. First, Q3 measured that a bundled `android.app.INotificationManager` AIDL throws
 * `NoSuchMethodError` at `targetSdk 36` — hidden-API *linking* enforcement (`hiddenapi … using
 * linking: denied`) — so it does not merely risk the wrong code, it cannot run at all. Second, a
 * bundled AIDL numbers the methods *it* declares from `FIRST_CALL_TRANSACTION`, and this method is
 * **42** in this ROM's interface: an AIDL that declared only the methods this app uses would send `1`.
 * The code therefore comes from [RomAbi], which Task 9 reads out of the ROM's own bytecode, and the
 * interface descriptor is checked before every write.
 *
 * `ShizukuBinderWrapper` forwards `transact` to the Shizuku service, which performs it as shell
 * (uid 2000) — exactly the identity `NotificationManagerService$Stub.updateNotificationChannelForPackage`
 * admits via `assertCallerIsSystemOrSystemUiOrShell()` (ROM dump `svc_classes2` line 1784825).
 */
class ShizukuTransport(private val mapper: AndroidChannelMapper) : ChannelTransport {

    override val name: String = "shizuku"

    /**
     * Shell can send transaction 43, and this is the only transport that can: `NotificationListenerService`
     * exposes no unlock, and the second half of "a lowered importance is recoverable" depends on this
     * being true wherever importance is written.
     */
    override val canUnlockImportance: Boolean = true

    /**
     * `unlockNotificationChannel(String pkg, int uid, String channelId)` — transaction 43, the only
     * method on this ROM that clears `USER_LOCKED_IMPORTANCE` (4) and nothing else (Q2: the ROM routes
     * it to `PreferencesHelper.unlockNotificationChannelImportance`).
     *
     * Sent by hand on a `Parcel` for exactly the reasons [write] is: the code is pinned to the
     * disassembly extract, `interfaceDescriptor` is checked first, and the guard refuses on an
     * unrecognised fingerprint. The argument order is the extract's own signature,
     * `(Ljava/lang/String;ILjava/lang/String;)V`, pinned by `RomAbiTest`.
     *
     * Called from `ChannelActuator.restore` **after** the restore write, and only for a record whose
     * last write moved the importance: `lockFieldsForUpdateLocked` locks a field whose value changed,
     * so the write is what sets the bit.
     */
    override fun unlockImportance(target: ChannelTarget, channelId: String): WriteResult {
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
            data.writeString(target.packageName)
            data.writeInt(target.uid)
            data.writeString(channelId)
            ShizukuBinderWrapper(service)
                .transact(RomAbi.UNLOCK_NOTIFICATION_CHANNEL, data, reply, 0)
            // The reply carries only an exception; reading it turns "the unlock was rejected" into a
            // returned result rather than a silent no-op that leaves the bit set forever.
            reply.readException()
            WriteResult.Ok
        } catch (e: RemoteException) {
            WriteResult.Failed("unlockNotificationChannel failed: ${e.message}")
        } catch (e: SecurityException) {
            WriteResult.Failed("unlockNotificationChannel denied: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override fun write(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
    ): WriteResult {
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
            data.writeString(target.packageName)
            data.writeInt(target.uid)
            data.writeTypedObject(mapper.channelFor(snapshot, edit), 0)
            ShizukuBinderWrapper(service)
                .transact(RomAbi.UPDATE_NOTIFICATION_CHANNEL_FOR_PACKAGE, data, reply, 0)
            // The reply carries only an exception, which is how every one-way-ish AIDL method reports
            // failure; reading it is what turns "the write was rejected" into a returned result
            // instead of a silent no-op.
            reply.readException()
            WriteResult.Ok
        } catch (e: RemoteException) {
            WriteResult.Failed("updateNotificationChannelForPackage failed: ${e.message}")
        } catch (e: SecurityException) {
            WriteResult.Failed("updateNotificationChannelForPackage denied: ${e.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * The content this write leaves on the channel, read back off the very object [write] submits.
     *
     * The whole channel is stored wholesale, so the submitted object *is* what the channel holds —
     * including whatever the platform's setters derived while it was being built. Building it twice
     * is deliberate: this is a read of the platform object, not a prediction of the edit.
     */
    override fun appliedContent(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
    ): ChannelFields = mapper.fieldsOf(mapper.channelFor(snapshot, edit))
}
