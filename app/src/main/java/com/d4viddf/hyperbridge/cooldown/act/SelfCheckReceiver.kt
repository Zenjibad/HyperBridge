package com.d4viddf.hyperbridge.cooldown.act

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.d4viddf.hyperbridge.cooldown.act.audio.SoundSeedHarness

/**
 * The adb-driven harness entry point.
 *
 * Exported behind `android.permission.DUMP` rather than declared non-exported, because `adb shell`
 * (uid 2000) holds DUMP and a non-exported component cannot be started from adb at all — while no
 * ordinary app can reach a DUMP-guarded one. Plan 3 replaces this with the status UI.
 *
 *   adb shell am broadcast -a app.hypercooldown.action.SELF_CHECK \
 *     -n app.hypercooldown/.act.SelfCheckReceiver --es action round-trip
 */
class SelfCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val mapper = AndroidChannelMapper()
        val check = SelfCheck(context, mapper)
        val choice = TransportSelector.choose(TransportSelector.report(context))
        Log.i(SelfCheck.TAG, "transport choice: ${TransportSelector.describe(choice)}")
        val transport = TransportSelector.transportFor(choice, mapper, { PrivilegedListenerHolder.service })
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_PROBE
        val noTransport = listOf("FAIL no transport is usable: ${TransportSelector.describe(choice)}")
        val lines = when (action) {
            ACTION_PROBE -> check.probe()
            ACTION_ROUND_TRIP -> transport?.let(check::roundTrip) ?: noTransport
            ACTION_SEED_VIBRATION -> transport?.let {
                check.seedVibration(
                    it,
                    intent.getStringExtra(EXTRA_PACKAGE) ?: "com.android.shell",
                    intent.getStringExtra(EXTRA_CHANNEL) ?: "shell_cmd",
                )
            } ?: noTransport
            ACTION_SEED_SOUND -> transport?.let {
                SoundSeedHarness(context).seedSound(
                    it,
                    intent.getStringExtra(EXTRA_PACKAGE) ?: "com.android.shell",
                    intent.getStringExtra(EXTRA_CHANNEL) ?: "shell_cmd",
                )
            } ?: noTransport
            ACTION_CDM -> check.attemptAssociation()
            ACTION_PRIVILEGED_WRITE -> check.privilegedListenerWrite()
            else -> listOf("FAIL unknown action '$action'")
        }
        Log.i(SelfCheck.TAG, "action=$action\n" + lines.joinToString("\n"))
    }

    companion object {
        const val ACTION_PROBE = "probe"
        const val ACTION_ROUND_TRIP = "round-trip"
        const val ACTION_SEED_VIBRATION = "seed-vibration"
        const val ACTION_SEED_SOUND = "seed-sound"
        const val ACTION_CDM = "cdm"
        const val ACTION_PRIVILEGED_WRITE = "privileged-write"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_CHANNEL = "channel"
    }
}
