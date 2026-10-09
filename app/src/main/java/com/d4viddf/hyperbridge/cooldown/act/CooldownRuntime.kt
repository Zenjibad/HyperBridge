package com.d4viddf.hyperbridge.cooldown.act

import android.app.NotificationChannel
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.d4viddf.hyperbridge.cooldown.act.audio.AndroidSoundUriResolver
import com.d4viddf.hyperbridge.cooldown.act.audio.AttenuatedSoundFactory
import com.d4viddf.hyperbridge.cooldown.act.audio.ContentResolverSoundReader
import com.d4viddf.hyperbridge.cooldown.act.audio.MediaCodecAudioDecoder
import com.d4viddf.hyperbridge.cooldown.act.audio.MediaStoreSoundPublisher
import com.d4viddf.hyperbridge.cooldown.act.audio.SoundBudget
import com.d4viddf.hyperbridge.cooldown.act.audio.SoundCopyCache
import app.hypercooldown.actuation.AlertEvent
import app.hypercooldown.actuation.ChannelActuator
import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.CooldownCoordinator
import app.hypercooldown.actuation.PropertiesSnapshotStore
import app.hypercooldown.actuation.SnapshotStore
import app.hypercooldown.actuation.WriteResult
import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File

/**
 * The wiring: sensor -> engine -> planner -> actuator, with relaxations on alarms.
 *
 * One instance per process, created lazily by [NotificationSensor]. It owns the two safety paths
 * Plan 2 had to have, and Plan 3 Task 2's third piece beside them:
 *
 *  - **reconcile on connect** (spec §6's invariant: a channel is never left damped with no live
 *    owner), which is what recovers a channel after a process kill, a reboot or a Shizuku restart —
 *    and the reason the write-ahead store exists at all;
 *  - **restore everything now**, reachable from adb without the UI;
 *  - **[guard]** and [restoreVerdict]: the `USER_LOCKED_*` residue every channel write leaves behind,
 *    **derived** from the write (the platform never reports it to a plain listener on this ROM) and
 *    stated in one sentence, because a restore puts the values back but cannot clear the lock mask.
 *
 * Nothing here decides anything: every decision is in `:actuation`, which is where the tests are.
 */
class CooldownRuntime private constructor(private val context: Context) {

    private val mapper = AndroidChannelMapper()
    private val store = PropertiesSnapshotStore(File(context.filesDir, SNAPSHOT_FILE).toPath())
    private val tuningStore = TuningStore(context)

    /**
     * The tuning the coordinator and the actuator are built from. [applyTuning] replaces it, which is
     * why it is a `var`: the numbers the user chose have to reach the engine without a restart.
     */
    private var tuningValues: TuningValues = tuningStore.load()
    private var config: EngineConfig = tuningValues.toConfig()
    private val timer = AlarmRelaxationTimer(context)

    /**
     * **This replaces Plan 2's `NoPoliteSound` placeholder.** POLITE now plays a 30 %-amplitude copy of
     * the channel's own sound (spec §5.1, §5.5), published where SystemUI's `RingtonePlayer` can open it
     * (Q1: Plan 1 Task 15, commit `39162d9`). Every stage of that pipeline returns null instead of
     * throwing, so the worst case is exactly Plan 2's behaviour — no copy,
     * `FidelityGap.POLITE_SOUND_UNAVAILABLE`, vibration-only POLITE (spec §5.6) — and never an exception
     * raised inside the listener's callback.
     *
     * The cache belongs to the process, not to a coordinator: `refreshTransport` runs again on every
     * listener reconnect, and a cache rebuilt there would re-read and re-publish the same copy each time.
     * Its key is `(sourceUri, factor)`, so a burst pays for the read, the decode, the scale and the
     * publish once rather than once per alert (spec §6, "bounded resources").
     *
     * The sound a real channel carries is usually not PCM: the shell channel's own is
     * `content://settings/system/notification_sound`, whose *value* on this device is an Ogg. Both are
     * handled here — [AndroidSoundUriResolver] resolves the alias and [MediaCodecAudioDecoder] decodes
     * the stream — and both are handled on [soundWorker] rather than on the callback thread.
     */
    private val soundCopyCache = SoundCopyCache()
    private val soundBudget = SoundBudget()

    /**
     * Where the read, the decode, the scale and the publish happen. A single daemon thread, because the
     * alternative is doing `MediaCodec` work on the notification listener's callback thread — the main
     * thread — on the alert path. One thread is enough: a burst on one channel is one sound, the work is
     * deduplicated per source URI, and a serial worker cannot fan a wobbling app into a decode storm.
     */
    private val soundWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hypercooldown-sound").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * Where a message from [soundWorker] is turned back into a channel write.
     *
     * The coordinator is only ever driven from the listener's callback thread — the main thread — and
     * that is not a stylistic preference: every write, every plan and every record mutation happens in
     * one thread's order, which is what makes "the copy arrived" safe to act on (see
     * `CooldownCoordinator.onPoliteSoundReady`). A `Handler` post is enough to keep it that way: a
     * message posted while the main thread is inside `onAlert` is dispatched only after `onAlert`
     * returns, so the re-apply can never interleave with the post whose copy it carries.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val politeSound = AttenuatedSoundFactory(
        // The settings alias is resolved, not refused: `content://settings/system/notification_sound`
        // is what the shell channel actually carries, and it cannot be opened as given. The reader tries
        // the channel's URI first and the resolved candidates behind it.
        reader = ContentResolverSoundReader(context, AndroidSoundUriResolver(context), soundBudget),
        // A compressed ringtone is decoded to PCM and then attenuated by the *same* `WavAttenuator` the
        // PCM path uses, so the two paths cannot drift.
        decoder = MediaCodecAudioDecoder(soundBudget),
        publisher = MediaStoreSoundPublisher(context),
        cache = soundCopyCache,
        factor = config.politeVolume,
        worker = soundWorker,
        budget = soundBudget,
        // Every refusal is named in the same `adb logcat -s Cooldown:V` stream as the post it belongs to.
        note = { reason -> Log.w(TAG, "polite sound unavailable: $reason") },
    )

    /**
     * The lock residue a restore cannot clear. Fed only by this app's own restores, with the mask
     * **derived** from the write — because the platform never hands the mask back to a plain
     * notification listener on this ROM, which the device run proved (see [LockResidue]). A lock on a
     * channel this app never restored is therefore never reported as ours.
     */
    private val guard = RestoreGuard()

    private var choice: TransportChoice = TransportChoice.NONE
    private var selectionNote: String = "not probed yet"
    private var coordinator: CooldownCoordinator? = null

    /** The channel mapper, for the sensor: the only part of `:app` the sensor needs from here. */
    fun snapshotOf(channel: NotificationChannel): ChannelSnapshot = mapper.snapshotOf(channel)

    /**
     * The lock residue a restore cannot clear, in one sentence, for the status UI.
     *
     * The wording matters, and it is all in [RestoreGuard.verdict]: the mask is *derived* from the
     * write rather than observed, it is a lower bound because the platform inherits whatever lock bits
     * the channel already carried, and nothing this app can call clears it.
     */
    @get:Synchronized
    val restoreVerdict: String
        get() = guard.verdict()

    /**
     * The residue the restore of one record must have left, derived from the record itself.
     *
     * It has to be computed *before* the restore write, because a successful restore drops the record —
     * and the record is the only copy of both sides of the comparison `LockResidue.forWrite` makes: the
     * pristine content as this app captured it, and the content its last write put there. [written] is
     * null when the process died between the transport accepting a write and the marker reaching disk,
     * which is reported as an unknown residue rather than as no residue.
     */
    private fun derivedResidueOf(record: SnapshotStore.Record): RestoredWrite =
        RestoredWrite(
            cooldownKey = record.target.key,
            channelId = record.snapshot.fields.channelId,
            derivedMask = record.written?.let { LockResidue.forWrite(record.snapshot.fields, it) },
        )

    @Synchronized
    fun onListenerConnected(service: NotificationListenerService) {
        PrivilegedListenerHolder.service = service
        refreshTransport()
        val restored = restoreAll("listener connected")
        Log.i(TAG, "listener connected: transport=$selectionNote, reconciled $restored channel(s)")
    }

    @Synchronized
    fun onListenerDisconnected() {
        val restored = restoreAll("listener disconnected")
        PrivilegedListenerHolder.service = null
        Log.i(TAG, "listener disconnected: restored $restored channel(s)")
    }

    @Synchronized
    fun onAlert(event: AlertEvent) {
        val active = coordinator
        if (active == null) {
            // Spec §5.6: with no channel writes available, never pretend to work.
            Log.w(TAG, "no transport, ignoring the post for ${event.target.key} ($selectionNote)")
            return
        }
        // Before the post, and off this thread: building a copy takes a read, a `MediaCodec` decode and a
        // MediaStore write, which must not happen inside the listener callback. What lands 60-80 ms later
        // is re-applied through the callback below, because the copy cannot be in the write that needed
        // it -- that is the measured cold-start defect: without the callback the first episode after a
        // process start installs POLITE with `mSound=null` and plays that alert silently.
        //
        // The snapshot is the *pristine* one, never the channel as it arrived: once POLITE carries the
        // published copy, `event.snapshot` carries that copy, and attenuating it is 9 % instead of 30 %
        // -- measured on the device as peak 6497 -> 1949. The alert's own snapshot is the right answer
        // only while no record exists, which is the one case it is pristine by definition.
        val pristine = active.pristineSnapshotFor(event.target.key) ?: event.snapshot
        politeSound.prewarm(pristine) {
            mainHandler.post {
                val current = coordinator
                val result = current?.onPoliteSoundReady(event.target.key)
                if (result is WriteResult.Failed) {
                    // Spec §6, "fail loud, not silent": the copy exists and the episode still wanted it,
                    // so a write that could not install it is an error rather than a quiet no-op.
                    Log.e(TAG, "polite copy for ${event.target.key} was not installed: ${result.reason}")
                } else {
                    Log.i(
                        TAG,
                        "polite copy ready for ${event.target.key}: " +
                            (result ?: "no live plan for it, nothing written"),
                    )
                }
            }
        }
        val outcome = active.onPost(event)
        if (outcome.gaps.isNotEmpty()) {
            Log.w(TAG, "fidelity gaps for ${event.target.key}: ${outcome.gaps}")
        }
        // Diagnostic only. The engine's verdict -- audibility as a *result* of the installed state,
        // spec §5.1 -- is what drove the decision; the platform's own record of the last audible
        // alert is logged beside it so that a divergence (the silent-POLITE case Task 7 pins) is
        // visible rather than mysterious. Its per-key semantics are not pinned, so it feeds nothing.
        Log.i(
            TAG,
            "post key=${event.target.key} installed=${outcome.installedState} next=${outcome.nextState} " +
                "applied=${outcome.applied} platformLastAudibleMs=${event.rankingLastAudiblyAlertedMs}",
        )
    }

    @Synchronized
    fun onUserInteraction(key: String) {
        coordinator?.onUserInteraction(key)
    }

    @Synchronized
    fun onChannelGone(key: String) {
        coordinator?.onChannelGone(key)
    }

    @Synchronized
    fun onRelaxationDue(key: String) {
        coordinator?.onRelaxationDue(key)
    }

    // ---- The status screen. Everything below reports; nothing below decides. ----

    /**
     * The capability probe, taken now.
     *
     * This is `TransportProbe.probe` by way of `TransportSelector.report` — the same call
     * [SelfCheck.probe] makes for `adb logcat -s CooldownCheck:V` — so the screen and the harness
     * cannot disagree about a capability, and the UI re-derives nothing (spec §5.7).
     */
    fun capabilityReport(): ProbeReport = TransportSelector.report(context)

    /**
     * What the *running* engine selected, as opposed to what a probe would choose now.
     *
     * Deliberately a read of the last probe and never a fresh one: `refreshTransport` replaces the
     * coordinator, and a coordinator that is replaced mid-episode takes its scheduled relaxations
     * with it, which is how a channel ends up damped with nobody to relax it (spec §6). A screen
     * that wants "what would run now" asks `TransportSelector.choose` itself, which has no state.
     */
    fun transportNote(): String =
        if (coordinator == null) "$selectionNote (no live coordinator)" else selectionNote

    /** The tuning the running engine was built with. */
    @Synchronized
    fun tuning(): TuningValues = tuningValues

    /**
     * Stores [values] and rebuilds the engine's configuration from them.
     *
     * The coordinator is dropped rather than mutated, because a plan it already holds was computed
     * from the old numbers and would keep using them. Everything damped is restored *first*, under
     * the numbers the live coordinator was built with — a restore writes snapshots back and reads no
     * tuning at all — so the reconfigure can never leave a channel damped with nobody to relax it.
     */
    @Synchronized
    fun applyTuning(values: TuningValues) {
        val safe = TuningValues.sanitize(values)
        tuningStore.save(safe)
        restoreAll("tuning changed")
        tuningValues = safe
        config = safe.toConfig()
        coordinator = null
        Log.i(TAG, "tuning changed: $safe")
    }

    /**
     * Every key a snapshot record still covers, with the state the running engine models for it.
     *
     * [CooldownStatusRow.installed] is null when no coordinator is live. The record on disk proves
     * the channel *was* damped; nothing in a process without a coordinator can say what it carries
     * now, and rendering that as `DEFAULT` would be the one thing this screen exists to stop — a
     * confident wrong answer (spec §5.6, never silently pretend).
     */
    @Synchronized
    fun statusRows(): List<CooldownStatusRow> {
        val active = coordinator
        return store.outstanding().map { record ->
            CooldownStatusRow(
                key = record.target.key,
                packageName = record.target.packageName,
                installed = active?.installedFor(record.target.key),
                written = record.written != null,
            )
        }
    }

    /**
     * Restores every channel still recorded as damped, and starts the engine cold — AOSP's state is
     * memory-only, so a restart is a clean slate rather than a resumed plan whose timestamps may not
     * even be comparable (`elapsedRealtime` resets on reboot).
     *
     * Returns how many channels were put back. A failure is logged as an error and never swallowed:
     * spec §6 says fail loud, not silent, and the residue sentence in [restoreVerdict] is what the UI
     * shows for the channels that come back with their lock bits still set.
     */
    @Synchronized
    fun restoreAll(reason: String): Int {
        // A panic path that only works while the listener happens to be connected is not a panic path:
        // the moment it is needed is the moment the process may have just been started by this very
        // broadcast, with no transport selected yet — `refreshTransport` is otherwise only called from
        // `onListenerConnected`, which never runs without notification access. Probing here (only when
        // there is no coordinator, so a live one is never discarded) is what makes `RESTORE_ALL`
        // restore something rather than log that it cannot.
        val active = coordinator ?: run {
            refreshTransport()
            coordinator
        }
        val outstanding = store.outstanding()
        if (active == null) {
            if (outstanding.isNotEmpty()) {
                Log.e(
                    TAG,
                    "cannot restore ${outstanding.size} damped channel(s): no transport ($reason, $selectionNote)",
                )
            }
            return 0
        }
        // Derived *before* the write, because a successful restore drops the record that holds both
        // sides of the derivation: the content this app captured, and the content its last write put
        // there. A record with no write marker yields a null mask — "unknown", never "clean".
        val residues = outstanding.associate { it.target.key to derivedResidueOf(it) }
        val results = active.reconcile()
        val failed = results.filter { it.second is WriteResult.Failed }
        failed.forEach { Log.e(TAG, "restore failed for ${it.first}: ${it.second}") }
        // The records this restore actually wrote back. They go to the guard so that only a channel this
        // app restored is reported as carrying its residue; a lock on a channel we never touched is the
        // user's own, and reporting it as ours would be a false alarm about our own safety net.
        val restored = results.filter { it.second is WriteResult.Ok }.map { it.first }
        guard.onRestored(restored.mapNotNull { residues[it] })
        // Two counts, two units, both named out loud: this pass's record keys — one channel each — and
        // the channels this process has restored in total. The second is the guard's own number, and
        // printing it here as if it were the pass's is the inconsistency this line used to have.
        if (failed.isEmpty()) {
            Log.i(
                TAG,
                "restore-all ($reason): ${restored.size} record key(s) restored " +
                    "(${restored.size} channel(s)); ${guard.restoredChannels()} channel(s) restored in " +
                    "this process",
            )
        } else {
            Log.e(
                TAG,
                "restore-all ($reason): ${restored.size} record key(s) restored " +
                    "(${restored.size} channel(s)), ${failed.size} FAILED",
            )
        }
        // The residue is derived, not observed, so nothing has "arrived" and nothing has to wait: the
        // sentence is printed with the restore that produced it, and it says so in its own words.
        Log.i(TAG, guard.verdict())
        return restored.size
    }

    @Synchronized
    private fun refreshTransport() {
        val report = TransportSelector.report(context)
        choice = TransportSelector.choose(report)
        selectionNote = "${TransportSelector.describe(choice)} (${report.describe()})"
        val transport = TransportSelector.transportFor(choice, mapper, { PrivilegedListenerHolder.service })
        coordinator = if (transport == null) {
            null
        } else {
            CooldownCoordinator(
                config = config,
                actuator = ChannelActuator(transport, store, politeSound, config),
                clock = AndroidClock,
                timer = timer,
            )
        }
    }

    companion object {

        /**
         * The panic action, and the string `AndroidManifest.xml` filters on for
         * [RestoreEverythingReceiver]. It is repeated here because a manifest cannot reference a
         * Kotlin constant; it is the action the UI's panic button broadcasts, and the one adb sends.
         */
        const val ACTION_RESTORE_ALL = "com.d4viddf.hyperbridge.action.RESTORE_ALL"

        const val TAG = "Cooldown"
        private const val SNAPSHOT_FILE = "channel-snapshots.properties"

        @Volatile
        private var instance: CooldownRuntime? = null

        fun get(context: Context): CooldownRuntime =
            instance ?: synchronized(this) {
                instance ?: CooldownRuntime(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * One row of the status screen's cooldown table, and exactly what that row needs: which key, which
 * package it belongs to, what the running engine believes the channel is carrying, and whether a
 * write of ours was recorded for it.
 *
 * [installed] is nullable on purpose (see [CooldownRuntime.statusRows]).
 */
data class CooldownStatusRow(
    val key: String,
    val packageName: String,
    val installed: CooldownState?,
    val written: Boolean,
)
