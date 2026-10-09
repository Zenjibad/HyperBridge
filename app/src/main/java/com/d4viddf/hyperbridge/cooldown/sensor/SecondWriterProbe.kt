package com.d4viddf.hyperbridge.cooldown.sensor

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import app.hypercooldown.actuation.WriterPartition

/**
 * Is the standalone cooldown app armed right now? (Spec 1.1, decision 1, layer 1.)
 *
 * Two public-API signals and no privileged call, because the fork cannot ask the platform about
 * another package's grant: `NotificationManager.isNotificationListenerAccessGranted` answers only
 * about **this app's own** component (`checkCallerIsSystemOrSameApp`, `cooldown/act/TransportProbe.kt`
 * `:101-106`). So the authority is the mirror the platform writes for the UI and for tooling —
 * `Settings.Secure.enabled_notification_listeners` — plus `PackageManager` for "is it even installed".
 *
 * **The honest limit, measured:** that mirror lags the bind by about 3 s on this device
 * (`MEMORY.md` §3.10: listener connected at 20:30:05.192, the mirror still lacking the component at
 * 20:30:05.207, present at 20:30:08.263). A second writer armed inside that window may go unnoticed,
 * which is why the caller asks on **every write attempt** and not once at startup, and why criterion
 * A16 is written to be able to fail.
 */
object SecondWriterProbe {

    /** `null` means "nothing readable", which is not the same as "nobody armed" and is reported. */
    fun armed(context: Context): Boolean? {
        val mirror = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull() ?: return null
        val installed = runCatching {
            context.packageManager.getPackageInfo(
                WriterPartition.STANDALONE_PACKAGE,
                PackageManager.PackageInfoFlags.of(0),
            )
        }.isSuccess
        return armedFromMirror(mirror, installed)
    }

    /**
     * The pure half. The mirror is the authority: if it names the standalone component, that component
     * is enabled whether or not `PackageManager` can see the package (an uninstalled app's component
     * cannot stay in the allow-list, but preferring the mirror is the fail-closed reading).
     *
     * Both spellings of a component are accepted. `ManagedServices` writes the mirror from a
     * `Set<ComponentName>` joined with `":"`, i.e. `ComponentName.toString()` = `flattenToString()` =
     * `"package/full.class"` -- the long form, which is why the entry-equality arms below are the
     * load-bearing ones. `flattenToShortString()` (`"package/.Class"`) is what `dumpsys` and other
     * tooling print, and accepting it costs nothing while the two errors are not symmetric: a wrong
     * `true` misses a cooldown, a wrong `false` puts two writers on one channel (spec 1.1).
     */
    fun armedFromMirror(enabledListeners: String?, standaloneInstalled: Boolean): Boolean {
        val mirror = enabledListeners ?: return false
        val component = WriterPartition.STANDALONE_COMPONENT
        val shortForm = "${WriterPartition.STANDALONE_PACKAGE}/.sensor.NotificationSensor"
        val armed = mirror.split(":").any { entry ->
            entry == component || entry == shortForm
        }
        // The last clause is deliberately wider than the two equalities: an installed standalone app
        // with *any* component in the mirror is treated as armed, including a component this file has
        // never heard of. The trailing "/" is what keeps `app.hypercooldown.pro` out of it.
        return armed || (standaloneInstalled && mirror.contains("${WriterPartition.STANDALONE_PACKAGE}/"))
    }
}
