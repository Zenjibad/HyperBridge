package com.d4viddf.hyperbridge.cooldown.act

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * "Restore everything now" — spec §5.2 unit 6's panic path, and the reason it is reachable over adb
 * is that the moment you need it is exactly the moment the app's UI may not be usable.
 *
 * Exported behind `android.permission.DUMP`, which `adb shell` holds and no ordinary app does:
 *
 *   adb shell am broadcast -a app.hypercooldown.action.RESTORE_ALL \
 *     -n app.hypercooldown/.act.RestoreEverythingReceiver
 */
class RestoreEverythingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val restored = CooldownRuntime.get(context).restoreAll("restore-all requested")
        Log.i(CooldownRuntime.TAG, "restore-all requested: $restored channel(s) restored")
    }
}
