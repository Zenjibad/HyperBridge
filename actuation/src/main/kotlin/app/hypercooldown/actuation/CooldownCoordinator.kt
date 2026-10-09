package app.hypercooldown.actuation

import app.hypercooldown.engine.ChannelPlan
import app.hypercooldown.engine.ChannelStatePlanner
import app.hypercooldown.engine.CooldownEngine
import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig

/** A monotonic millisecond clock. `SystemClock.elapsedRealtime()` on the device. */
fun interface Clock {
    fun nowMs(): Long
}

/**
 * Schedules the relaxation wake-ups.
 *
 * The device implementation is an `ELAPSED_REALTIME_WAKEUP` alarm rather than an in-process timer,
 * and that is a correctness requirement rather than a preference: a relaxation that is missed while
 * the device sleeps would leave the channel damped, and the channel has to be right *before* the
 * alert arrives, because the platform has already decided whether to play it by the time our
 * listener callback runs. The callback comes back through
 * [CooldownCoordinator.onRelaxationDue] — which re-reads the clock, so a late alarm relaxes to the
 * state that is actually due.
 */
interface RelaxationTimer {
    /** Supersedes any pending wake-up for [key]. */
    fun schedule(key: String, atMs: Long)

    /** Drops any pending wake-up for [key]. */
    fun cancel(key: String)
}

/** One observed post, normalised off the listener callback. */
data class AlertEvent(
    val target: ChannelTarget,
    val snapshot: ChannelSnapshot,
    /**
     * `Ranking.getLastAudiblyAlertedMillis()`: the platform's own record of the last alert that
     * actually played on this notification's channel.
     *
     * Carried for diagnosis only. Its per-key semantics are not pinned, and spec §5.1 makes
     * audibility a *result* of the installed state, so it never feeds the engine — the coordinator
     * logs it beside its own verdict so that a divergence is visible rather than mysterious.
     */
    val rankingLastAudiblyAlertedMs: Long,
)

/**
 * The client half of spec §5.1's behavioural contract.
 *
 * It re-plans after **every** post — not only the audible ones — installing the state AOSP would
 * assign to the *next* alert, and applies each later relaxation when the plan's deadline comes due.
 * The same client is pinned on the `:engine` side by `CooldownCompositionTest.SimulatedClient`;
 * `CooldownCoordinatorDifferentialTest` (Task 7) re-proves this implementation against the engine
 * over randomised bursts, so the two cannot drift apart unnoticed.
 *
 * The engine's awkwardness is that `onNotificationPosted` decides for the alert **being posted**,
 * while the channel has to carry the state for the alert **after** it. That lag is handled by
 * feeding [ChannelStatePlanner.plan] the engine's state *after* the post.
 */
class CooldownCoordinator(
    private val config: EngineConfig,
    private val actuator: ChannelActuator,
    private val clock: Clock,
    private val timer: RelaxationTimer,
) {

    /** What one post did. Returned for logging, for the wiring's fidelity-gap report, and for tests. */
    data class PostOutcome(
        /** The state the channel was carrying when the alert arrived: AOSP's choice for that alert. */
        val installedState: CooldownState,
        /** The state installed for the *next* alert, i.e. `plan.stateAt(now)`. */
        val nextState: CooldownState,
        val applied: WriteResult,
        val gaps: Set<FidelityGap>,
    )

    private class KeyPlan(val plan: ChannelPlan, var installed: CooldownState, var installedAtMs: Long)

    private var engine = CooldownEngine(config)
    private val plans = HashMap<String, KeyPlan>()

    /**
     * One post. The order matters and is the contract:
     *  1. judge who last changed the channel, from the content the listener observed on arrival —
     *     **before** any relaxation is applied, so our own settle write cannot be read as somebody
     *     else's change;
     *  2. settle — apply every relaxation that came due before this alert arrived, so what the
     *     channel is carrying is what the device did with this alert;
     *  3. stand down or re-damp;
     *  4. let the engine decide for this post and advance its clock only if the alert played;
     *  5. install the state the *next* alert must already carry.
     */
    fun onPost(event: AlertEvent): PostOutcome {
        val key = event.target.key
        val nowMs = clock.nowMs()

        // Judged before `settle`, and the ordering is load-bearing. `settle` applies the relaxations
        // that came due, which writes to the channel and so replaces the actuator's record of what we
        // last wrote. Comparing the content the listener observed on arrival against that *later*
        // record reads our own relaxation as somebody else's change, stands down, and abandons a
        // channel we damped. Judging first also keeps a foreign change from being overwritten by a
        // relaxation we no longer have any business applying.
        val observed = actuator.classify(key, event.snapshot)
        if (observed == ChannelActuator.Observed.UNREADABLE_RECORD) {
            // Spec §6, "fail loud, not silent": the user's original is on disk but cannot be read, so
            // there is no snapshot to damp from and no write that would not risk it. Nothing is
            // touched — not the channel, not the record — and the refusal is reported so the wiring
            // can raise the warning spec §6 asks for. Only a human can resolve a quarantine.
            val carried = plans[key]?.installed ?: CooldownState.DEFAULT
            val result = actuator.unreadable(key)
            standDown(key)
            return PostOutcome(carried, CooldownState.DEFAULT, result, emptySet())
        }
        if (observed == ChannelActuator.Observed.CHANGED_BY_OTHER) {
            // Spec §6 outranks spec §5.6 here: restoring would overwrite a change that is not ours.
            // Stand down for this episode and say so — but the record is only dropped once the
            // channel provably needs no rescue from us, which is `ChannelActuator.standDown`'s job.
            val carried = plans[key]?.installed ?: CooldownState.DEFAULT
            val result = actuator.standDown(key, event.snapshot)
            standDown(key)
            return PostOutcome(carried, CooldownState.DEFAULT, result, emptySet())
        }

        // A relaxation that could not be written is superseded rather than reported here: the plan is
        // recomputed below regardless, and `apply` installs the state for the *next* alert. If that
        // write lands, this one is moot; if it does not, *its* failure is what this outcome returns.
        // `onRelaxationDue` is where a bare relaxation failure has nowhere else to go, and it retries.
        // Read before `settle`, which is what applies it: true when a relaxation was already due when
        // the alert arrived, so the channel is still carrying one segment less than the plan says.
        val lagging = plans[key]?.let { keyPlan ->
            keyPlan.plan.nextChangeAfter(keyPlan.installedAtMs)?.let { due -> due <= nowMs } == true
        } ?: false
        settle(key, nowMs)
        val installed = plans[key]?.installed ?: CooldownState.DEFAULT

        if (observed == ChannelActuator.Observed.RESET_BY_APP) {
            // Spec §5.6: the app put its own channel back, so nothing is lost by damping again.
            // Dropping the record makes `apply` re-record the pristine content it just observed.
            actuator.forget(key)
        }

        engine.onNotificationPosted(key, nowMs)
        // Faithful to AOSP's `if (buzz || beep)`: a silenced alert must not extend its own cooldown.
        // The channel is the witness, not this process's model of it -- except for the one arrival the
        // plan provably knows better than the channel does, which `settle` has just repaired. See
        // [alertWasAudible].
        engine.onAlertPlayed(
            key,
            wasAudible = if (lagging) {
                installed != CooldownState.MUTED
            } else {
                alertWasAudible(event.snapshot)
            },
            nowMs = nowMs,
        )

        val plan = ChannelStatePlanner.plan(
            engine.stateOf(key),
            engine.lastAudibleAtMs(key),
            engine.postedCount(key),
            config,
        )
        val state = plan.stateAt(nowMs)
        val result = actuator.apply(event.target, event.snapshot, state)
        val gaps = if (result == WriteResult.Ok) lastGaps(key) else emptySet()

        // A failed write leaves the channel carrying the previous state, so the model of what the
        // device will play must not move on without it.
        if (result is WriteResult.Failed) return PostOutcome(installed, state, result, gaps)

        plans[key] = KeyPlan(plan, state, nowMs)
        scheduleNext(key)
        return PostOutcome(installed, state, result, gaps)
    }

    /** Called when a scheduled relaxation's time has come. Idempotent: it settles, then reschedules. */
    fun onRelaxationDue(key: String) {
        settle(key, clock.nowMs())
        scheduleNext(key)
    }

    /**
     * Ports `PolitenessStrategy.onUserInteraction` — the user tapped the notification. The key is
     * dropped entirely (state, counter and clock), and the channel goes back to its snapshot.
     */
    fun onUserInteraction(key: String) {
        engine.onUserInteraction(key)
        plans.remove(key)
        timer.cancel(key)
        actuator.restore(key)
    }

    /** Spec §5.6: the channel is gone, so there is nothing to restore — stop tracking it. */
    fun onChannelGone(key: String) {
        engine.forget(key)
        plans.remove(key)
        timer.cancel(key)
        actuator.forget(key)
    }

    /**
     * Spec §6's invariant, at the moment the writer comes back: a channel is never left damped with
     * no live owner. This is what recovers a channel after a reboot, a process kill or a Shizuku
     * restart, and it is why the snapshots are on disk at all.
     *
     * The engine is memory-only, exactly as AOSP's is, so a reconciled channel starts cold rather
     * than resuming a plan whose timestamps may not even be comparable (`elapsedRealtime` resets on
     * reboot).
     */
    fun reconcile(): List<Pair<String, WriteResult>> {
        // The wake-up is cancelled for every key whose plan is about to be dropped. `plans.clear()`
        // alone leaves the alarm armed, and an armed alarm outlives the record it belonged to:
        // measured on the device, `restore-all` put the channel back at 20:49:15 and the
        // `relaxation due: 0:com.android.shell` broadcast still landed at 20:50:40, 85 s later. Nothing
        // is left to relax by then, so the callback no-ops — but only because `plans` is empty, and a
        // relaxation that survives a restore is wrong on its face. The copy is taken before the clear
        // so the iterator is never held across a mutation.
        plans.keys.toList().forEach { timer.cancel(it) }
        plans.clear()
        engine = CooldownEngine(config)
        return actuator.restoreAll()
    }

    /** The state the channel is carrying, as the coordinator models it. */
    fun installedFor(key: String): CooldownState = plans[key]?.installed ?: CooldownState.DEFAULT

    /**
     * The pristine content recorded for [key], or null when this process holds no readable record.
     *
     * The wiring prewarms POLITE's copy from this and not from the alert's own snapshot: once POLITE is
     * installed, the alert's snapshot carries the copy this app published, and attenuating that copy is
     * 9 % instead of 30 % (`AttenuatedSoundFactory`, measured 6497 -> 1949).
     */
    fun pristineSnapshotFor(key: String): ChannelSnapshot? = actuator.pristineFor(key)

    /**
     * The attenuated copy for [key]'s alert arrived *after* that alert's own write, so the write is
     * re-made with the copy in it.
     *
     * Measured on this device: the read, the `MediaCodec` decode and the MediaStore publish run on the
     * sound worker and finish 60-80 ms after the callback's POLITE write, so the copy cannot be in the
     * write that needed it — and, without this, nothing re-writes POLITE when it lands, the channel is
     * left with `mSound=null` for the alert POLITE was installed for, and the *next* alert is silent.
     *
     * Safe by construction against everything that can happen in those 60-80 ms:
     *
     *  - the caller runs this on the main thread, the same thread as every other coordinator entry
     *    point, so it cannot interleave with `onPost` or with a relaxation;
     *  - a key with no live plan is refused outright, which is what a stand-down, a `reconcile()` (the
     *    restore-all path), a user tap and a vanished channel all leave behind — a restored channel is
     *    never resurrected, and after `reconcile` *no* key has a plan;
     *  - a plan that no longer says POLITE *now* (`stateAt`, the plan's own answer for what the channel
     *    must carry, so a due-but-not-yet-delivered relaxation counts) is refused: this can only ever
     *    put the copy into a POLITE the plan still wants;
     *  - it writes through `applyRecorded`, the same path a relaxation uses, from the recorded pristine
     *    snapshot, so it cannot fight a write of this coordinator's or damp from our own content;
     *  - it does not touch the plan: the state did not change, only the copy inside it, so `installed`,
     *    `installedAtMs` and the armed wake-up stay exactly as `onPost` left them.
     *
     * @return the write's result, or null when there was nothing for this coordinator to write.
     */
    fun onPoliteSoundReady(key: String): WriteResult? {
        val keyPlan = plans[key] ?: return null
        if (keyPlan.plan.stateAt(clock.nowMs()) != CooldownState.POLITE) return null
        return actuator.applyRecorded(key, CooldownState.POLITE)
    }

    /**
     * AOSP's audible-alert test, for the alert that just arrived: `if (buzz || beep)`
     * (`research/NotificationAttentionHelper.java:597`, the guard on
     * `mStrategy.setLastNotificationUpdateTimeMs`). A channel alerts when it has a sound to play or a
     * vibration to run, which is what `NotificationChannel` carries in [ChannelSnapshot.fields].
     *
     * Read off the content the channel was carrying when the alert played, not off this process's
     * model of it. The model is wrong in exactly the cases that matter: a coordinator that restarted
     * cold holds no plan for the key and falls back to `DEFAULT`, an app can have put its own channel
     * back, and a write of ours can have failed and left the channel audible. MUTED is the only state
     * we install with neither a sound nor vibration enabled ([EditFactory]), so this and the model
     * agree whenever our write landed.
     *
     * The one arrival it is not asked about is the one whose channel is already known to be stale --
     * see `lagging` in [onPost], which is the plan's own AOSP answer for an alert that landed on a
     * relaxation deadline whose alarm had not been delivered.
     *
     * What it does *not* do: it does not make [PostOutcome.installedState] truthful after a cold
     * start, and it does not restore the counter or the last-audible timestamp. The engine is
     * memory-only by design -- AOSP's is too -- and [reconcile] is what puts the channels back.
     */
    private fun alertWasAudible(arriving: ChannelSnapshot): Boolean =
        arriving.fields.soundUri != null || arriving.fields.vibrationEnabled

    private fun settle(key: String, nowMs: Long) {
        val keyPlan = plans[key] ?: return
        while (true) {
            val change = keyPlan.plan.nextChangeAfter(keyPlan.installedAtMs) ?: return
            if (change > nowMs) return
            val next = keyPlan.plan.stateAt(change)
            if (next != keyPlan.installed) {
                val result = actuator.applyRecorded(key, next)
                // The channel still carries the previous state, so the model of what the device will
                // play must not move on without it — the same refusal the post path makes for its own
                // write. Leaving `installedAtMs` where it is keeps this relaxation due, so the next
                // wake-up retries it instead of the cooldown quietly believing in a state that is not
                // there (spec §6: never leave a channel muted).
                if (result is WriteResult.Failed) return
            }
            keyPlan.installed = next
            keyPlan.installedAtMs = change
        }
    }

    private fun scheduleNext(key: String) {
        val keyPlan = plans[key] ?: return
        val next = keyPlan.plan.nextChangeAfter(keyPlan.installedAtMs)
        if (next == null) {
            timer.cancel(key)
        } else {
            // A relaxation whose write failed has not been applied, so `next` is already in the past.
            // The device implementation is an `AlarmManager` alarm and a past-due alarm fires
            // immediately, so re-arming the dead deadline would retry a failing write in a tight loop.
            // The retry is delayed instead. That is safe because the alarm is idempotent: its callback
            // re-reads the clock and settles everything actually due, so a late retry is a correct one.
            val now = clock.nowMs()
            timer.schedule(key, if (next <= now) now + RETRY_DELAY_MS else next)
        }
    }

    /** Stops damping [key] for the rest of the episode. The record's fate is the actuator's call. */
    private fun standDown(key: String) {
        engine.forget(key)
        plans.remove(key)
        timer.cancel(key)
    }

    private fun lastGaps(key: String): Set<FidelityGap> = actuator.lastGaps(key)

    private companion object {
        /**
         * How long a relaxation whose write failed waits before it is tried again. It only has to be
         * long enough that a wedged writer cannot spin the alarm queue; the retry itself is idempotent.
         */
        const val RETRY_DELAY_MS = 1_000L
    }
}
