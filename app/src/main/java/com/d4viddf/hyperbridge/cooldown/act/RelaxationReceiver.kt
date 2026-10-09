package com.d4viddf.hyperbridge.cooldown.act

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Where a relaxation alarm lands. Not exported: the only sender is our own
 * [AlarmRelaxationTimer]'s `PendingIntent`, which the platform delivers to us even so.
 *
 * It is also a second place where the listener's bind can be checked, and it costs nothing to use:
 * an alarm that is already scheduled for a damped channel is the one piece of this app's existing
 * machinery that can start the process while no listener is bound — which is exactly the state the
 * rebind check exists for. Nothing new is scheduled here; a wake-up that was already due is simply
 * not wasted on a no-op.
 */
class RelaxationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ListenerWatchdog.ensureBound(context, "relaxation alarm")
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        Log.i(CooldownRuntime.TAG, "relaxation due: $key")
        CooldownRuntime.get(context).onRelaxationDue(key)
    }

    companion object {
        const val ACTION_RELAX = "com.d4viddf.hyperbridge.action.RELAX"
        const val EXTRA_KEY = "key"
    }
}
