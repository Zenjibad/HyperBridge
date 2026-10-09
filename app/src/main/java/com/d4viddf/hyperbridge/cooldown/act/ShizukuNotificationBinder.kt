package com.d4viddf.hyperbridge.cooldown.act

import android.os.IBinder
import rikka.shizuku.SystemServiceHelper

/**
 * The `notification` system service as Shizuku hands it to this app.
 *
 * One lookup, shared by the two things that transact on `INotificationManager` — the channel write
 * ([ShizukuTransport]) and the listener re-grant ([ListenerAllowList]) — because both need the same
 * three answers about the same binder, and a second copy of the reasoning is a second place for it to
 * go stale.
 *
 * `SystemServiceHelper.getSystemService` resolves `android.os.ServiceManager` reflectively and
 * returns null on failure, so the null here is "Shizuku is not running, or has not granted this app":
 * a state problem, not a write that was refused, and the two are reported differently at every call
 * site. Only the caller decides what to say about it.
 */
internal object ShizukuNotificationBinder {

    /** The notification service binder, or null when Shizuku is not running or not permitted. */
    fun get(): IBinder? = try {
        SystemServiceHelper.getSystemService("notification")
    } catch (e: IllegalStateException) {
        // Shizuku throws this from its API surface when its own binder has not been received, i.e.
        // when the user has not started Shizuku or has not granted this app permission.
        null
    } catch (e: RuntimeException) {
        // SystemServiceHelper resolves `android.os.ServiceManager` reflectively and logs and returns
        // null on failure; anything else it throws is a Shizuku state problem with the same answer.
        null
    }
}
