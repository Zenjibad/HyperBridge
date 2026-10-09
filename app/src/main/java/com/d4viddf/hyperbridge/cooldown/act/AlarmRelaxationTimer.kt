package com.d4viddf.hyperbridge.cooldown.act

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.hypercooldown.actuation.RelaxationTimer

/**
 * Relaxation wake-ups as `ELAPSED_REALTIME_WAKEUP` alarms.
 *
 * An in-process `Handler` would be simpler and is what Plan 1's coordinator test doubles up to — but
 * it would be wrong. A relaxation that is missed while the device sleeps leaves the channel damped,
 * and by the time our listener callback runs the platform has *already* decided whether to play the
 * alert, so a stale damped channel silences an alert AOSP would have played. `setAndAllowWhileIdle`
 * fires in doze, and `ELAPSED_REALTIME` is the same clock [AndroidClock] reads, so the deadline needs
 * no conversion.
 *
 * `setAndAllowWhileIdle` is inexact. That is acceptable because the relaxation is idempotent and
 * re-reads the clock: a late wake-up relaxes to the state that is actually due, so only the wall-clock
 * moment can drift (a recorded known limitation).
 */
class AlarmRelaxationTimer(context: Context) : RelaxationTimer {

    private val appContext: Context = context.applicationContext
    private val alarms: AlarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun schedule(key: String, atMs: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atMs, pendingIntent(key))
    }

    override fun cancel(key: String) {
        alarms.cancel(pendingIntent(key))
    }

    /**
     * One alarm per key. The request code is the key's hash, so two keys colliding would share an
     * alarm; with `FLAG_UPDATE_CURRENT` the later schedule wins, and the surviving alarm still
     * carries its own key in the extra, so a collision costs one deferred relaxation rather than a
     * wrong one.
     */
    private fun pendingIntent(key: String): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        key.hashCode(),
        Intent(appContext, RelaxationReceiver::class.java)
            .setAction(RelaxationReceiver.ACTION_RELAX)
            .putExtra(RelaxationReceiver.EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
