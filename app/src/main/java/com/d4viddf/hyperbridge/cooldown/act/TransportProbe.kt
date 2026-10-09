package com.d4viddf.hyperbridge.cooldown.act

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import app.hypercooldown.actuation.WriteResult
import com.d4viddf.hyperbridge.service.NotificationReaderService
import rikka.shizuku.Shizuku

/**
 * Spec §5.7's capability probe.
 *
 * Each condition is reported **distinctly**, because the failure modes are otherwise
 * indistinguishable: "nothing happened" is the symptom of at least four different problems on this
 * ROM, and a direct lesson from how opaque this notification stack turned out to be is that a silent
 * failure must be split into names.
 */
enum class Capability {
    /**
     * The user granted notification access: our component is on the platform's allow-list, which is
     * the authority `ManagedServices` binds from.
     *
     * This is **not** the same fact as [NOTIFICATION_CONNECTED] and must never be reported as if it
     * were. The allow-list survives a force-stop, so this reads `true` while the listener is not
     * bound and nothing at all is being damped — the state that made this failure invisible. It says
     * what the user granted, and nothing about what is running.
     */
    NOTIFICATION_ACCESS,

    /**
     * Our listener is actually bound: this process has seen `onListenerConnected` and has not been
     * told since that the bind is gone.
     *
     * The only evidence of a bind is that callback, so a process that has never received it reports
     * `false` however healthy the allow-list looks. The runtime is built in `onListenerConnected`,
     * so this is the condition that decides whether the app works at all, and the one the wizard
     * has to show — with the rebind remedy — instead of a green "access granted" row.
     */
    NOTIFICATION_CONNECTED,

    /** Shizuku's binder is alive: the user started Shizuku (Wireless debugging or adb). */
    SHIZUKU_BOUND,

    /** This app holds Shizuku's permission: the user tapped Allow in Shizuku's dialog. */
    SHIZUKU_PERMISSION,

    /** The ROM fingerprint matches the one whose `INotificationManager` codes [RomAbi] records. */
    ROM_ABI_PINNED,

    /**
     * Transport B is reachable: this app has a CompanionDeviceManager association, which is what
     * `hasCompanionDevice` needs to accept our listener as privileged. Measured `false` on this ROM.
     */
    TRANSPORT_B_ASSOCIATION,
}

data class ProbeReport(val usable: Map<Capability, Boolean>) {
    fun isUsable(capability: Capability): Boolean = usable[capability] == true
    fun describe(): String = Capability.entries.joinToString(" ") { "${it.name}=${isUsable(it)}" }
}

object TransportProbe {

    /**
     * This app's own listener component. `isNotificationListenerAccessGranted` is keyed to
     * `checkCallerIsSystemOrSameApp`, so this app can ask about **its own** listener and about nobody
     * else's — which is why the second-writer probe reads the platform's mirror string instead of
     * asking the platform about another package.
     */
    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, NotificationReaderService::class.java)

    fun probe(context: Context): ProbeReport {
        val cdm = CdmAssociation(context)
        return ProbeReport(
            usable = mapOf(
                Capability.NOTIFICATION_ACCESS to notificationAccessAllowed(context),
                // Read from the process's own bind tracker, never from the allow-list: the two
                // disagree exactly when the app is broken, which is when the row has to be right.
                Capability.NOTIFICATION_CONNECTED to ListenerWatchdog.isConnected(),
                Capability.SHIZUKU_BOUND to runCatching { Shizuku.pingBinder() }.getOrDefault(false),
                Capability.SHIZUKU_PERMISSION to runCatching {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false),
                Capability.ROM_ABI_PINNED to (RomAbiGuard.check() == WriteResult.Ok),
                Capability.TRANSPORT_B_ASSOCIATION to cdm.associations().isNotEmpty(),
            ),
        )
    }

    /**
     * Whether notification access is granted — **the allow-list, and only that**. It is deliberately
     * not "and the listener is connected": that conflation is what reported `NOTIFICATION_ACCESS=true`
     * while the listener was unbound, so the app looked healthy while nothing was being damped.
     * [Capability.NOTIFICATION_CONNECTED] is the other half, and [ListenerWatchdog] asks this
     * function for the allow-list before it decides to rebind.
     *
     * Three sources, strongest first, because on this device they disagree for a few seconds around a
     * grant and the disagreement is what the connect line used to print:
     *
     *  1. **The policy itself.** `NotificationManager.isNotificationListenerAccessGranted` answers from
     *     `ManagedServices`' own allow-list — the authority the user's Settings tap writes and the one
     *     the listener bind is driven from — and
     *     `NotificationManagerService.checkCallerIsSystemOrSameApp` lets this app ask about its own
     *     listener (`research/aosp/NotificationManagerService.java:6554-6558`). `runCatching` because a
     *     ROM that tightens that check must fall back rather than crash the probe.
     *  2. **A live binding.** The platform delivered us the listener through `onListenerConnected`,
     *     which it does only for an enabled listener. This is direct evidence rather than a mirror of
     *     one, and it is what makes a probe taken *from inside* `onListenerConnected` honest.
     *  3. **`Settings.Secure.enabled_notification_listeners`,** the mirror the UI and tooling read.
     *     Measured on this device, that mirror lags the bind: with a listener freshly granted, the
     *     connect line was logged at `20:30:05.192`, `settings get secure
     *     enabled_notification_listeners` still lacked our component at `20:30:05.207`, and it carried
     *     it at `20:30:08.263`. Reading only that string is what printed
     *     `NOTIFICATION_ACCESS=false` beside `listener connected`, and it stays the answer when neither
     *     of the first two sources is available — a cold process whose listener has not bound yet.
     */
    fun notificationAccessAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val grantedByPolicy = runCatching {
            manager.isNotificationListenerAccessGranted(listenerComponent(context))
        }.getOrNull()
        if (grantedByPolicy == true) return true

        if (PrivilegedListenerHolder.service != null) return true

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val flattened = listenerComponent(context).flattenToString()
        return enabled.split(":").any { it.equals(flattened, ignoreCase = true) }
    }
}
