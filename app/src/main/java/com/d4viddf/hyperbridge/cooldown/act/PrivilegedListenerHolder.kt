package com.d4viddf.hyperbridge.cooldown.act

import android.service.notification.NotificationListenerService

/**
 * Where [NotificationSensor] publishes itself so the harness can attempt a privileged-listener write.
 *
 * A `NotificationListenerService` instance only exists inside the service's own process, so there is
 * no other way to reach one. The sensor sets this in `onListenerConnected` and clears it in
 * `onListenerDestroyed`; Plan 3 removes it along with the rest of the harness.
 */
object PrivilegedListenerHolder {
    @Volatile
    var service: NotificationListenerService? = null
}
