package com.d4viddf.hyperbridge.cooldown.act

/**
 * The bind state of our own notification listener, and the rule that decides when to ask the
 * platform to rebuild it.
 *
 * Pure on purpose: no `android.*` import and the clock is a lambda, so the whole decision is a JVM
 * test. [ListenerWatchdog] is the half that touches the platform, and `ListenerBindTest` is the
 * half that decides.
 *
 * **Why this exists.** A force-stop or a kill tears the listener's binding down. The platform's
 * allow-list still carries our component, the app's package is not stopped, and nothing
 * re-establishes the bind — so `onListenerConnected` never runs again, the runtime is never built
 * (it is built there), and damping stops while every "is access granted" check still says yes. The
 * device measured exactly that: `dumpsys activity services app.hypercooldown` listed no
 * `NotificationSensor` while the process was alive and `dumpsys package` said `stopped=false`.
 *
 * **Access and connection are different facts.** Access is the platform's allow-list, which this app
 * can read but cannot write. Connection is whether *this* process has been handed the listener, and
 * the only evidence of it is a callback. The probe used to report the first as if it were the
 * second, which is what made the failure invisible.
 */

/**
 * What the watchdog decided about the listener's bind, and why.
 *
 * The verdicts are reported rather than collapsed into a boolean because "not connected" has four
 * different meanings, and three of them are not a defect: the user has not granted access, the bind
 * is on its way, or this app has already asked recently.
 */
enum class RebindVerdict {

    /** Access is not granted. There is no binding to heal, so the remedy is the user's, not ours. */
    NOT_ALLOWED,

    /** The listener connected here and has not disconnected since. Nothing to do. */
    CONNECTED,

    /**
     * Access is granted but this process has never been connected, and not for long enough yet to
     * tell a bind that is on its way from one that was torn down. Waiting is the honest answer.
     */
    WITHIN_GRACE,

    /** Access is granted, the bind is gone, and no recent request was made: ask now. */
    REBIND,

    /**
     * Access is granted and the bind is still gone, but this app asked inside the retry interval.
     * This is the verdict that stops the check from being a spin.
     */
    RATE_LIMITED,
}

/**
 * What the watchdog must actually do about a verdict — and the only place that decides whether the
 * platform's allow-list may be touched.
 *
 * It is a separate answer from the verdict because the verdict judges the *bind* while this judges
 * the *permission to act on it*, and the two are asked in different places: [RebindVerdict.REBIND] is
 * about the listener, and [RE_GRANT_ALLOW_LIST] additionally asserts that notification access is
 * still granted. Keeping the second question in its own function is what makes "never re-grant a
 * permission the user revoked" a property a JVM test can hold rather than a claim about a call site.
 */
enum class HealStep {

    /**
     * Nothing happens, and nothing is logged as an attempt.
     *
     * This is also the answer whenever access is **not** granted, whatever the verdict says, which is
     * the fail-closed case the whole design rests on: the watchdog may re-grant access the user has
     * already granted, and may never grant an access the user revoked.
     */
    NOTHING,

    /** Access is granted and the bind is gone, but this app asked inside the floor. Say so; do not ask. */
    SUPPRESSED_BY_FLOOR,

    /**
     * The one step that transacts: send `setNotificationListenerAccessGrantedForUser(component,
     * userId, true, true)` — the allow-list path `cmd notification allow_listener` walks — through
     * the Shizuku transport.
     */
    RE_GRANT_ALLOW_LIST,
}

object RebindPolicy {

    /**
     * How long access-granted-but-never-connected has to last before it is treated as a torn bind.
     *
     * Only the *unknown* case waits: a process whose listener has not connected yet cannot tell a
     * platform that is about to bind it from one that never will. An observed disconnect is not
     * unknown — `judge` treats it as a tear and does not wait — so this bounds the cold case only.
     *
     * Three seconds is a measurement, not a taste: on this device, with the listener freshly
     * granted, `Settings.Secure.enabled_notification_listeners` still lacked our component 3.0 s
     * after `onListenerConnected` was logged, so a normal bind is not instant here.
     */
    const val GRACE_MS = 3_000L

    /**
     * The floor between two rebind requests, whatever caused them.
     *
     * It is deliberately absolute rather than per-cause: a bind that is flapping would otherwise
     * earn one request per disconnect, and "at most one request per interval" is a property that
     * can be read off the code instead of argued from the cause. An attempt that throws still
     * counts, because the alternative is a process that retries a failing binder call on every
     * resume.
     */
    const val RETRY_INTERVAL_MS = 30_000L

    /**
     * The decision, with every input the tracker holds.
     *
     * [tearObserved] is the difference between "we watched the bind go away" and "we have never
     * seen one"; [sinceLastRebindMs] is null when this process has never asked.
     */
    fun judge(
        allowed: Boolean,
        connected: Boolean,
        unboundForMs: Long,
        tearObserved: Boolean,
        sinceLastRebindMs: Long?,
        graceMs: Long = GRACE_MS,
        retryIntervalMs: Long = RETRY_INTERVAL_MS,
    ): RebindVerdict = when {
        !allowed -> RebindVerdict.NOT_ALLOWED
        connected -> RebindVerdict.CONNECTED
        !tearObserved && unboundForMs < graceMs -> RebindVerdict.WITHIN_GRACE
        sinceLastRebindMs != null && sinceLastRebindMs < retryIntervalMs -> RebindVerdict.RATE_LIMITED
        else -> RebindVerdict.REBIND
    }

    /**
     * What [ListenerWatchdog] may do, given the allow-list fact and the verdict.
     *
     * **The order is the guard, and it is checked first.** `!allowed` is answered before any verdict
     * is even looked at, so no future change to [judge] — and no caller that passes a verdict
     * computed somewhere else — can route a revoked access into [HealStep.RE_GRANT_ALLOW_LIST]. The
     * platform cannot tell "re-grant what is already granted" from "grant what was revoked" by the
     * payload alone (`granted = true` either way), so the refusal has to live here, in the only
     * function whose answer leads to a transact.
     *
     * [HealStep.SUPPRESSED_BY_FLOOR] exists so the suppression is a *named* outcome: the rate-limit
     * floor is [RebindPolicy.judge]'s (the tracker counts an attempt only when it returns
     * [RebindVerdict.REBIND]), and the watchdog must log the wait rather than silently do nothing —
     * "it stopped asking and said nothing" is the failure mode this whole layer was written to avoid.
     */
    fun healStep(allowed: Boolean, verdict: RebindVerdict): HealStep = when {
        !allowed -> HealStep.NOTHING
        verdict == RebindVerdict.REBIND -> HealStep.RE_GRANT_ALLOW_LIST
        verdict == RebindVerdict.RATE_LIMITED -> HealStep.SUPPRESSED_BY_FLOOR
        else -> HealStep.NOTHING
    }
}

/**
 * The listener's bind state as *this process* has seen it.
 *
 * Only callbacks write to it: `onListenerConnected` and `onListenerDisconnected` are the only
 * evidence that a bind exists, and neither survives a process kill, so a process that has just
 * started reports "not connected" until the platform says otherwise. That is the whole point — the
 * allow-list says what the user granted, this says what is actually running.
 *
 * Every method is synchronized on the instance because the callbacks arrive on the service's main
 * thread while the watchdog is asked from the Activity and from a receiver, and a torn read of
 * "connected and its timestamp" is exactly the kind of thing that would make the verdict flap.
 */
class ListenerBindTracker(private val clock: () -> Long) {

    private val processStartMs: Long = clock()
    private var connectedAtMs: Long? = null
    private var unboundSinceMs: Long = processStartMs
    private var tearObserved: Boolean = false
    private var lastRebindAtMs: Long? = null
    private var attempts: Int = 0

    @Synchronized
    fun connected() {
        connectedAtMs = clock()
        tearObserved = false
    }

    @Synchronized
    fun disconnected() {
        connectedAtMs = null
        unboundSinceMs = clock()
        tearObserved = true
    }

    @Synchronized
    fun isConnected(): Boolean = connectedAtMs != null

    /** How long this process has been without a bind: since the last disconnect, else since start. */
    @Synchronized
    fun unboundForMs(): Long = clock() - unboundSinceMs

    /**
     * How long since this process last asked the platform to rebind, or null when it never has.
     * Reported so the suppression line can say how long the wait is, rather than "not yet".
     */
    @Synchronized
    fun msSinceLastRebind(): Long? = lastRebindAtMs?.let { clock() - it }

    /** How many rebind requests this process has made. Reported, so "it healed itself" is checkable. */
    @Synchronized
    fun rebindAttempts(): Int = attempts

    /** [RebindPolicy.judge] on this process's own state, recording a request when it says REBIND. */
    @Synchronized
    fun judge(allowed: Boolean): RebindVerdict {
        val verdict = RebindPolicy.judge(
            allowed = allowed,
            connected = isConnected(),
            // Without an observed tear this is measured from the process start — which is the state a
            // force-stop leaves behind, and also the state in which the platform may simply still be
            // about to bind us. That ambiguity is what the grace period is for.
            unboundForMs = unboundForMs(),
            tearObserved = tearObserved,
            sinceLastRebindMs = lastRebindAtMs?.let { clock() - it },
        )
        if (verdict == RebindVerdict.REBIND) {
            lastRebindAtMs = clock()
            attempts++
        }
        return verdict
    }
}
