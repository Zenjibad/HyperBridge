package com.d4viddf.hyperbridge.cooldown.act

import android.os.Process
import android.service.notification.NotificationListenerService
import app.hypercooldown.actuation.ChannelEdit
import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.ChannelTarget
import app.hypercooldown.actuation.ChannelTransport
import app.hypercooldown.actuation.WriteResult

/**
 * Transport B (spec §8 Q3): the **public** `NotificationListenerService.updateNotificationChannel`,
 * with no hidden ABI, no transaction codes and no Shizuku.
 *
 * The server side is
 * `NotificationManagerService$Stub.updateNotificationChannelFromPrivilegedListener`, which calls
 * `verifyPrivilegedListener(listener, user, /* assistantAllowed = */ true)` and then
 * `verifyPrivilegedListenerUriPermission`. The latter is a *grant*, not a check: it compares the
 * update channel's sound with the stored one and calls
 * `PermissionHelper.grantUriPermission(mUgmInternal, soundUri, sourceUid)` when they differ, so a
 * foreign sound URI (Plan 3's attenuated copy) is not rejected by this path.
 *
 * `Process.myUserHandle()` rather than `UserHandle.of(UserHandle.myUserId())` or
 * `UserHandle.getUserHandleForUid(uid)`: both of the latter name `@hide` members
 * (`UserHandle.of`, `UserHandle.myUserId`) and do not compile at `targetSdk 36`. Plan 2 handles the
 * current user only, which is the same restriction the sensor applies.
 *
 * The listener is injected as a supplier rather than a field, because the only way to obtain one is
 * from inside the listener service itself, and this transport must be constructible (and must report
 * a clear failure) before that happens.
 */
class PrivilegedListenerTransport(
    private val mapper: AndroidChannelMapper,
    private val listener: () -> NotificationListenerService?,
) : ChannelTransport {

    override val name: String = "privileged-listener"

    override fun write(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
    ): WriteResult {
        val service = listener() ?: return WriteResult.Failed("the notification listener is not connected")
        val channel = mapper.channelFor(snapshot, edit)
        return try {
            service.updateNotificationChannel(
                target.packageName,
                Process.myUserHandle(),
                channel,
            )
            WriteResult.Ok
        } catch (e: SecurityException) {
            // The expected outcome on this ROM: no companion-device association and not an approved
            // assistant, so the listener is not privileged.
            WriteResult.Failed("not a privileged listener for ${target.packageName}: ${e.message}")
        }
    }

    /**
     * The content the update leaves on the channel, read back off the object handed to the listener.
     *
     * The listener stores the submitted channel wholesale, so this is the channel's content — the
     * platform accessors' answer, including anything the setters derived, not the edit's intent.
     */
    override fun appliedContent(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
    ): ChannelFields = mapper.fieldsOf(mapper.channelFor(snapshot, edit))
}
