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
import app.hypercooldown.actuation.ChannelTransport
import app.hypercooldown.actuation.CooldownCoordinator
import app.hypercooldown.actuation.CooldownKeys
import app.hypercooldown.actuation.CooldownOptions
import app.hypercooldown.actuation.CooldownPolicy
import app.hypercooldown.actuation.Exemption
import app.hypercooldown.actuation.Presentation
import app.hypercooldown.actuation.PropertiesSnapshotStore
import app.hypercooldown.actuation.SnapshotStore
import app.hypercooldown.actuation.VisibilityOption
import app.hypercooldown.actuation.WriteResult
import app.hypercooldown.actuation.WriterPartition
import com.d4viddf.hyperbridge.cooldown.sensor.SecondWriterProbe
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

    /**
     * Where the effective per-app options come from. A function, not a store: the listener thread must
     * not suspend (`AppPreferences.getEffectiveCooldownOptionsSync` reads `memoryCache` for exactly
     * this reason), and keeping it a seam is what lets this class stay testable without Android and
     * lets the UI's writes take effect without a restart.
     *
     * The default answers one empty option set for every package, which is every field `null` -- i.e.
     * AOSP's own treatment, and the same answer `CooldownPolicy` gives a state it has no option for.
     */
    private var optionsFor: (String) -> CooldownOptions = { CooldownOptions() }

    fun setOptionsSource(source: (String) -> CooldownOptions) {
        optionsFor = source
    }

    /**
     * The ownership list the two-writer partition reads. The key is the empty package name, which is
     * the *global* row: spec 1.1 decision 1 makes ownership a global list, not a per-app one, because
     * a per-app list cannot express "these apps are mine and those are the other writer's" without
     * the other writer's complementary list, which is not implemented.
     */
    fun ownedPackages(): Set<String> = optionsFor("").ownedPackages ?: emptySet()

    /**
     * Whether the standalone writer is armed. **Default true**: before the real probe is wired the
     * honest answer is "assume another writer may exist", because the cost of a wrong `true` is a
     * missed cooldown and the cost of a wrong `false` is two writers on one channel (spec 1.1).
     */
    private var secondWriterArmed: () -> Boolean = { true }

    fun setSecondWriterProbe(probe: () -> Boolean) {
        secondWriterArmed = probe
    }

    /** The transport the running coordinators were built from, or null when there is none. */
    private var transport: ChannelTransport? = null

    /**
     * One coordinator per effective config: `EngineConfig` is the coordinator's constructor input and
     * the coordinator owns the engine, so a per-app timing override cannot be expressed inside one.
     * Apps that share a config share a coordinator, which keeps each engine's per-key state in one
     * place. [configByKey] is what makes "which coordinator holds this key" answerable, which is what
     * the island decision and every per-key callback ask.
     */
    private val coordinators = LinkedHashMap<EngineConfig, CooldownCoordinator>()
    private val configByKey = HashMap<String, EngineConfig>()

    /** The config one app's alerts are damped with: AOSP's values, overridden by that app's options. */
    private fun configFor(packageName: String): EngineConfig =
        optionsFor(packageName).effectiveConfig(tuningValues.toConfig())

    private fun buildCoordinator(config: EngineConfig, transport: ChannelTransport): CooldownCoordinator =
        CooldownCoordinator(
            config = config,
            actuator = ChannelActuator(
                transport,
                store,
                politeSound,
                config,
                // The heads-up option is per **app** while an actuator is per *config*, so it is handed
                // over as a function of the package and read at write time: two apps that share a config
                // must not share an answer, and a change in the options has to take effect on the next
                // post without rebuilding a coordinator (which would take its armed wake-ups with it,
                // spec §6). `CooldownPolicy.headsUp` is the one place the unset-option default is
                // resolved, so the write path and the island decision cannot disagree about it.
                headsUpFor = { packageName -> CooldownPolicy.headsUp(optionsFor(packageName)) },
            ),
            clock = AndroidClock,
            timer = timer,
        )

    /**
     * The coordinator that owns [key], building one for this app's config if it does not exist yet.
     *
     * A key that is already owned by a *different* config is the one case that needs care: the engine
     * holding its live plan would otherwise be abandoned with the plan and the armed wake-up still in
     * it, which is invariant 1's exact failure mode (a damped channel with no owner). The vendor's
     * coordinator exposes no per-key "give this back", so the safe give-back is the pass that already
     * exists for it -- [restoreAll], which cancels every coordinator's wake-ups and puts every
     * outstanding record back before this key is handed to the new engine. It happens only when a user
     * changes timing or attenuation while a channel is damped.
     */
    private fun coordinatorFor(
        key: String,
        packageName: String,
        transport: ChannelTransport,
    ): CooldownCoordinator {
        val config = configFor(packageName)
        val previous = configByKey[key]
        if (previous != null && previous != config) {
            restoreAll("cooldown options changed for $packageName")
        }
        configByKey[key] = config
        return coordinators.getOrPut(config) { buildCoordinator(config, transport) }
    }

    /** The owner of [key]'s live plan, or null when nothing here has ever planned it. */
    private fun coordinatorForKey(key: String): CooldownCoordinator? =
        configByKey[key]?.let { coordinators[it] }

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

    /**
     * The POLITE copy factory. It holds no attenuation of its own: the factor is handed to it per call
     * from the alert's own `configFor(packageName)`, so "how much quieter" is a live setting and not a
     * value this process was constructed with. The cache inside it is keyed by `(source, factor)` and
     * belongs to the process, which is why the factory is one instance shared by every coordinator.
     */
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
     *
     * **The importance bit is subtracted when this app is about to release it.** It is the one bit
     * transaction 43 clears, `ChannelActuator.restore` sends it for exactly the records whose write
     * moved the importance, and a mask that still counted it would report a residue the user does not
     * have — a false alarm about our own safety net, in the direction that makes the sentence useless.
     * When the transport cannot send 43 the bit is counted, because then nobody clears it.
     */
    private fun derivedResidueOf(record: SnapshotStore.Record): RestoredWrite =
        RestoredWrite(
            cooldownKey = record.target.key,
            channelId = record.snapshot.fields.channelId,
            derivedMask = record.written?.let { written ->
                val derived = LockResidue.forWrite(record.snapshot.fields, written)
                if (transport?.canUnlockImportance == true) LockResidue.unreleasable(derived) else derived
            },
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
        // The transport is the one capability this class resolves at connect time rather than per
        // post, and a phone that has just come up has no Shizuku yet: every post is refused, so
        // *nothing* the user changes in the settings screen can be seen and the feature looks like it
        // needs a reboot to take a setting -- measured on this device, where the log below repeated
        // for every post with `SHIZUKU_BOUND=false`. So it is re-probed here, and only while it is
        // absent. That is safe by construction rather than by care: a null transport means
        // `refreshTransport` has just cleared the coordinator map, and with no transport this app
        // cannot have written anything -- there is no live plan, no armed wake-up and no record it
        // owns to strand (spec §6, invariant 1). A live transport is never re-probed here, so no
        // coordinator is ever dropped underneath a channel it is damping.
        if (transport == null) refreshTransport()
        val selected = transport
        if (selected == null) {
            // Spec §5.6: with no channel writes available, never pretend to work.
            Log.w(TAG, "no transport, ignoring the post for ${event.target.key} ($selectionNote)")
            return
        }
        // The partition and the enable gate, before anything is written *or prewarmed* (spec 1.1,
        // 4.5). A refusal here is not an error: it is the fork declining to be a writer, and it is
        // logged as a decision with its reason. `enabled != false` because the option is nullable and
        // an option nobody ever set is AOSP's own default, which is on.
        val options = optionsFor(event.target.packageName)
        val armed = secondWriterArmed()
        when (
            val verdict = WriterPartition.decide(
                enabled = options.enabled != false,
                secondWriterArmed = armed,
                ownedPackages = ownedPackages(),
                packageName = event.target.packageName,
                ownPackage = context.packageName,
            )
        ) {
            is WriterPartition.Verdict.Refuse -> {
                Log.i(
                    TAG,
                    "not writing ${event.target.key}: ${verdict.reason} " +
                        "(second writer armed=$armed)",
                )
                return
            }

            WriterPartition.Verdict.Write -> Unit
        }
        val active = coordinatorFor(event.target.key, event.target.packageName, selected)
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
        // The attenuation for *this* alert, read at the same moment the coordinator's own config was
        // derived: `coordinatorFor` above just built `active` from `configFor(packageName)`, and asking
        // again here is the same live read of the option set, so a change made in the settings screen is
        // what the very next alert uses. The two calls must not disagree -- `EditFactory` looks the copy
        // up with the same number -- which is why neither is cached anywhere.
        politeSound.prewarm(pristine, configFor(event.target.packageName).politeVolume) {
            mainHandler.post {
                // `active`, captured, rather than a re-read of the field: the copy belongs to the
                // episode this callback was posted from, and a transport refresh between the post and
                // this message must not hand it to a coordinator that never planned the key.
                val result = active.onPoliteSoundReady(event.target.key)
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
        coordinatorForKey(key)?.onUserInteraction(key)
    }

    @Synchronized
    fun onChannelGone(key: String) {
        coordinatorForKey(key)?.onChannelGone(key)
    }

    @Synchronized
    fun onRelaxationDue(key: String) {
        // Routed by key rather than to "the" coordinator: with one engine per effective config, the
        // one holding this key's plan is the only one whose arrival deadline this is. A key nobody
        // owns is a no-op, which is the honest answer -- the alarm fires once and finds no plan.
        coordinatorForKey(key)?.onRelaxationDue(key)
    }

    /**
     * A post the exemptions kept out of the engine (spec 4.5).
     *
     * Logged rather than swallowed, because an exemption the user cannot see is indistinguishable
     * from a cooldown that is not working, and because this is the only trace of the arm that is
     * unverified on this ROM (`CooldownExemptions`' conversation case).
     */
    @Synchronized
    fun onExempt(packageName: String, verdict: Exemption) {
        Log.i(
            TAG,
            "exempt $packageName: $verdict -- not fed to the engine, so it cannot move the clock",
        )
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
        if (coordinators.isEmpty()) "$selectionNote (no live coordinator)" else selectionNote

    /** The tuning the running engine was built with. */
    @Synchronized
    fun tuning(): TuningValues = tuningValues

    /**
     * The state the cooldown has installed for a package, or null when it has never damped it.
     *
     * The **write-ahead record is the witness**, not the coordinator's own map: a coordinator answers
     * `DEFAULT` for a key it has never seen, and "never seen" and "installed DEFAULT" must not be the
     * same answer here -- the first means "render the island as usual", the second means "the cooldown
     * owns this key and is currently at DEFAULT".
     *
     * One honest limitation, stated rather than hidden: this answers from `configByKey`, so a key
     * damped before this process started and not yet re-planned answers `null`, and its island renders
     * as usual. That is the safe direction -- no island is suppressed for an app this process has no
     * live plan for -- and it is the same shape as the cold-start carve-out in `MEMORY.md` §3.9.
     */
    @Synchronized
    fun installedStateFor(packageName: String): CooldownState? {
        val key = CooldownKeys.perApp(PlatformUser.id(), packageName)
        if (store.snapshotFor(key) == null) return null
        return coordinatorForKey(key)?.installedFor(key)
    }

    /** The options one origin app's presentation is decided from, merged with the global row. */
    fun effectiveOptionsFor(packageName: String): CooldownOptions = optionsFor(packageName)

    /**
     * Spec 4.7: there is no "hide for a while" API, and the only channel-level lever is
     * `setLockscreenVisibility`, which is `USER_LOCKED_VISIBILITY = 0x2` -- a persistent, user-locked
     * write with the same class of cost as heads-up. It is realised nowhere in this build: the option
     * exists in the set and in [CooldownPolicy.Presentation], and the realisation is deferred.
     *
     * The point of returning the sentence rather than only logging it is that the status card can show
     * the same words the log carries, so a user who set the option sees why nothing happened instead
     * of a silent no-op.
     */
    fun visibilityUnsupportedNote(): String =
        "hide-entirely is deferred: it needs a locked visibility write, and cancellation is refused"

    private var visibilityNoteLogged = false

    /**
     * Says the deferred-visibility sentence once per process, at the point the option is actually
     * read, and only when the option is set. Nothing else in this class reads `presentation.visibility`
     * yet -- "hide entirely" has no lever that is not the locked write the note names.
     *
     * Never by cancelling: the alert has already fired by the time the listener sees the post, ongoing
     * notifications cannot be cancelled, and in this host cancelling the original is known to break the
     * watch relay (`NotificationReaderService.kt:520-523`).
     */
    @Synchronized
    fun noteUnrealisedVisibility(packageName: String, presentation: Presentation) {
        if (presentation.visibility != VisibilityOption.HIDDEN || visibilityNoteLogged) return
        visibilityNoteLogged = true
        Log.w(
            TAG,
            "not hiding $packageName entirely: ${visibilityUnsupportedNote()} (spec 4.7)",
        )
    }

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
        // A changed global config makes every coordinator built from the old one stale, per-app ones
        // included: `configFor` derives from `tuningValues` for every app. Dropping them is safe for
        // the same reason the alarm/record pair is -- every live plan is also on disk as a write-ahead
        // record, and the next reconcile restores from it rather than from memory.
        coordinators.clear()
        configByKey.clear()
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
        return store.outstanding().map { record ->
            CooldownStatusRow(
                key = record.target.key,
                packageName = record.target.packageName,
                installed = coordinatorForKey(record.target.key)?.installedFor(record.target.key),
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
        // there is no transport at all, so live coordinators are never discarded) is what makes
        // `RESTORE_ALL` restore something rather than log that it cannot.
        if (transport == null) refreshTransport()
        val outstanding = store.outstanding()
        if (coordinators.isEmpty()) {
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
        // One pass per coordinator, because each owns its own plans and its own armed wake-ups, and
        // `reconcile()` cancels only the wake-ups it knows about. This is *not* the double restore
        // Task 8 caught: the restore itself is store-wide and is guarded by the record it writes back
        // (`ChannelActuator.restore` drops the record on `Ok`), so a later pass finds nothing left for
        // a key an earlier pass put back and writes nothing. A record whose restore FAILED stays, and
        // the next pass retries it -- a retry, never a second write of a channel already restored.
        val results = coordinators.values.flatMap { it.reconcile() }
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
        val selected = TransportSelector.transportFor(choice, mapper, { PrivilegedListenerHolder.service })
        transport = selected
        // Layer 1 of spec 1.1: the fork concedes by default. `null` from the probe means "nothing
        // readable", and the fail-closed reading of an unreadable signal is "another writer may be
        // armed". Re-probed here rather than once at startup, because this is the pass that every
        // listener reconnect runs, and the probe is asked again on every write attempt besides.
        setSecondWriterProbe {
            when (val armed = SecondWriterProbe.armed(context)) {
                null -> {
                    Log.w(TAG, "second-writer probe unreadable; assuming another writer may be armed")
                    true
                }

                else -> armed
            }
        }
        // Every coordinator was built from the transport that was just replaced. Dropping them is the
        // same trade `applyTuning` makes and for the same reason: a live plan is also a write-ahead
        // record, so the next `reconcile()` restores from disk rather than from a coordinator that
        // would write through a dead transport. The one built here is the current global config's, so
        // the common case has a live engine without waiting for an alert; a per-app config is built on
        // that app's first alert.
        coordinators.clear()
        configByKey.clear()
        if (selected != null) {
            coordinators[config] = buildCoordinator(config, selected)
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
