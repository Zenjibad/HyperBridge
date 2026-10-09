package com.d4viddf.hyperbridge.cooldown.act

import android.content.Context
import android.service.notification.NotificationListenerService
import app.hypercooldown.actuation.ChannelTransport

/** Which transport this run will use. */
enum class TransportChoice { PRIVILEGED_LISTENER, SHIZUKU, NONE }

/**
 * Q3, decided from measurements rather than from a plan — and already decided: the record at
 * `docs/superpowers/decisions/2026-10-09-q3-actuation-transport.md` concludes, at high confidence,
 * "**A — Shizuku, via the raw-parcel transaction, not the bundled AIDL**", and recommends dropping
 * transport B entirely. On this device [choose] therefore returns [TransportChoice.SHIZUKU].
 *
 * The order below is nevertheless B first, and that is deliberate rather than a re-decision: if a
 * `TRANSPORT_B_ASSOCIATION` ever exists — a different ROM, a managed device, a future platform
 * relaxation — the transport with no hidden ABI and no transaction codes to get wrong is the right
 * one to use. Transport A sits behind an exact ABI pin because a stale transaction code does not
 * fail, it calls a different method. Otherwise: nothing, because spec §5.6 forbids pretending to
 * work.
 */
object TransportSelector {

    fun choose(report: ProbeReport): TransportChoice = when {
        report.isUsable(Capability.TRANSPORT_B_ASSOCIATION) -> TransportChoice.PRIVILEGED_LISTENER
        report.isUsable(Capability.SHIZUKU_BOUND) &&
            report.isUsable(Capability.SHIZUKU_PERMISSION) &&
            report.isUsable(Capability.ROM_ABI_PINNED) -> TransportChoice.SHIZUKU

        else -> TransportChoice.NONE
    }

    fun describe(choice: TransportChoice): String = when (choice) {
        TransportChoice.PRIVILEGED_LISTENER ->
            "privileged-listener: a CompanionDeviceManager association makes the listener privileged"

        TransportChoice.SHIZUKU ->
            "shizuku: shell write via INotificationManager.transaction " +
                "${RomAbi.UPDATE_NOTIFICATION_CHANNEL_FOR_PACKAGE}, pinned to ${RomAbi.BUILD_FINGERPRINT}"

        TransportChoice.NONE ->
            "none: no transport is usable (need a CompanionDeviceManager association, or Shizuku " +
                "bound, permitted and pinned to ${RomAbi.BUILD_FINGERPRINT})"
    }

    /** The transport for [choice], or null when there is none. */
    fun transportFor(
        choice: TransportChoice,
        mapper: AndroidChannelMapper,
        listener: () -> NotificationListenerService?,
    ): ChannelTransport? = when (choice) {
        TransportChoice.PRIVILEGED_LISTENER -> PrivilegedListenerTransport(mapper, listener)
        TransportChoice.SHIZUKU -> ShizukuTransport(mapper)
        TransportChoice.NONE -> null
    }

    fun report(context: Context): ProbeReport = TransportProbe.probe(context)
}
