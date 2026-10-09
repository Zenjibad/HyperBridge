package com.d4viddf.hyperbridge.cooldown.act

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import app.hypercooldown.actuation.WriteResult

/**
 * The half of the rebind fix that touches the platform: it holds this process's [ListenerBindTracker]
 * and heals the bind through the platform's allow-list when the tracker says the bind is gone.
 *
 * **Why a check is needed at all.** A listener cannot heal itself: after a force-stop or a MIUI kill
 * the `NotificationListenerService` is not bound, so none of its code runs and nothing observes that
 * the app has stopped working. The check therefore has to come from a component that *is* running —
 * the Activity on resume, and the relaxation alarm's receiver, both of which exist already. No new
 * background machinery was added: there is no boot receiver here and none was added, because the
 * platform's own `ManagedServices` re-binds enabled listeners at boot, so a boot receiver would be
 * new machinery for a case the Activity already covers (see the plan's Task 3 correction).
 *
 * **The call is the allow-list, not `requestRebind`.** `NotificationListenerService.requestRebind`
 * is the API the javadoc names for a listener whose bind is gone, and on this ROM it does nothing:
 * it reaches `NotificationManagerService.requestBindListener` →
 * `ManagedServices.setComponentState(component, uid, true)`, which returns immediately for a
 * component that is not snoozed — which a torn-but-enabled component is not. The device disproved it
 * and then proved the alternative: `cmd notification allow_listener
 * app.hypercooldown/.sensor.NotificationSensor` moved the bound-service count from `0` to `9` while
 * the app was in the broken state, and so did `disallow_listener` + `allow_listener`. That command is
 * `setNotificationListenerAccessGrantedForUser(component, userId, true, true)`, and [ListenerAllowList]
 * sends exactly that transaction through the Shizuku transport this app already uses. **The device is
 * the arbiter of that sentence** — see Task 3's execution correction 3, which records the disassembly
 * of both paths and the measurement.
 *
 * `requestRebind` is still tried when the pinned transaction cannot be sent at all (a fingerprint
 * mismatch, no Shizuku, a refusal). It is a documented no-op here, but it is the correct call on
 * AOSP, it costs one line, and a fallback that is honest about being a fallback beats leaving the
 * user with nothing.
 *
 * Its state is a process-wide singleton for the same reason [PrivilegedListenerHolder] is: the
 * callbacks arrive in the service, and the callers are the Activity, a receiver and the probe, all
 * of which live in this process and nowhere else.
 */
object ListenerWatchdog {

    private val tracker = ListenerBindTracker(SystemClock::elapsedRealtime)

    /** `onListenerConnected`. Call this *before* anything that probes, so the probe can see it. */
    fun onConnected() = tracker.connected()

    /** `onListenerDisconnected` or `onListenerDestroyed`: the callback is the only proof of a bind. */
    fun onDisconnected() = tracker.disconnected()

    /**
     * Whether this process has been handed the listener and has not been told it is gone — the
     * probe's `NOTIFICATION_CONNECTED`, and not the same fact as notification access.
     */
    fun isConnected(): Boolean = tracker.isConnected()

    /** How many rebind requests this process has made. Quoted in the log line, and by the wizard. */
    fun rebindAttempts(): Int = tracker.rebindAttempts()

    /**
     * Judges the bind and heals it when the verdict is [RebindVerdict.REBIND].
     *
     * Returns the verdict rather than a boolean so a caller can tell "asleep because the user has
     * not granted access" from "asked already" from "connected".
     *
     * Every branch that touches anything logs; the guard is [RebindPolicy.healStep]'s, and this
     * function cannot reach the allow-list except through its one [HealStep.RE_GRANT_ALLOW_LIST]
     * answer, which access that is off can never produce.
     */
    fun ensureBound(context: Context, reason: String): RebindVerdict {
        val allowed = TransportProbe.notificationAccessAllowed(context)
        val verdict = tracker.judge(allowed)
        when (RebindPolicy.healStep(allowed, verdict)) {
            HealStep.RE_GRANT_ALLOW_LIST -> {
                val component = TransportProbe.listenerComponent(context)
                // Logged before the request, so an attempt that throws still leaves the evidence that
                // it was made -- and the tracker has already counted it.
                Log.i(
                    CooldownRuntime.TAG,
                    "listener is not bound though notification access is granted " +
                        "(unbound ${tracker.unboundForMs()} ms, request ${tracker.rebindAttempts()}): " +
                        "requesting self-heal of ${component.flattenToShortString()} ($reason)",
                )
                when (val healed = attemptReGrant(component)) {
                    is WriteResult.Ok -> Log.i(
                        CooldownRuntime.TAG,
                        "self-heal: allow-list re-grant of ${component.flattenToShortString()} sent " +
                            "on transaction ${RomAbi.SET_NOTIFICATION_LISTENER_ACCESS_GRANTED_FOR_USER} " +
                            "($reason)",
                    )

                    is WriteResult.Failed -> {
                        Log.e(
                            CooldownRuntime.TAG,
                            "self-heal could not send the allow-list re-grant " +
                                "(${healed.reason}); falling back to requestRebind, which is a " +
                                "measured no-op on this ROM ($reason)",
                        )
                        runCatching { NotificationListenerService.requestRebind(component) }
                            .onFailure {
                                Log.e(CooldownRuntime.TAG, "rebind request failed ($reason)", it)
                            }
                    }
                }
            }

            HealStep.SUPPRESSED_BY_FLOOR -> Log.i(
                CooldownRuntime.TAG,
                "listener is still not bound and the last self-heal was " +
                    "${tracker.msSinceLastRebind()} ms ago, inside the " +
                    "${RebindPolicy.RETRY_INTERVAL_MS} ms floor: not asking again ($reason)",
            )

            // Nothing to say: access is not granted (the wizard's row is the remedy, and re-granting
            // a revoked permission would be a serious bug), the bind is live, or it is too early to
            // tell a bind on its way from one that was torn down.
            HealStep.NOTHING -> Unit
        }
        return verdict
    }

    /**
     * [ListenerAllowList.reGrant], with the screen protected from it.
     *
     * The transport reports a refused or rejected transaction as a [WriteResult.Failed], but a
     * binder call to a security-relevant interface can come back as an *unexpected* runtime
     * exception too — the ROM's stub rethrows whatever it caught, and this is called from
     * `onResume`. Nothing about a best-effort heal may take the Activity down with it, so anything
     * that escapes becomes a failure with the exception named, which is what the caller logs.
     */
    private fun attemptReGrant(component: ComponentName): WriteResult = try {
        ListenerAllowList.reGrant(component, PlatformUser.id())
    } catch (e: Exception) {
        WriteResult.Failed("${e.javaClass.simpleName}: ${e.message}")
    }
}
