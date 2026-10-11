package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig

/**
 * Applies a cooldown state to a channel, and puts it back.
 *
 * The ordering is the safety property (spec §6), and it is the same in both directions:
 *
 *  1. the pristine snapshot is on disk **before** the first mutation, so a crash can never leave a
 *     channel damped with no record of what it looked like;
 *  2. the record is cleared only **after** a restore write comes back clean.
 *
 * A crash between the two leaves a record that the next process reconciles (spec §6's invariant: a
 * channel is never left damped with no live owner).
 *
 * The snapshot is never re-taken while a record exists. After the first damping write the platform
 * hands back *our* values, so re-snapshotting — which spec §5.2's `ChannelActuator` bullet suggests —
 * would make the "original" drift until the restore restored our own damping.
 *
 * What is recorded as "what we last wrote" is what the **channel** contains once the write landed,
 * read back through the transport, not the edit that was submitted: the platform normalises, so a
 * record of the intention does not match the content the next observation reports — see
 * [ChannelTransport.appliedContent].
 */
class ChannelActuator(
    private val transport: ChannelTransport,
    private val store: SnapshotStore,
    private val politeSound: PoliteSoundSource,
    private val config: EngineConfig,
    /**
     * The heads-up realisation for one **package**, resolved from its options by the wiring.
     *
     * A function and not a value, because one actuator serves every app that shares an
     * [EngineConfig] — `CooldownRuntime` builds one coordinator per config — while the heads-up option
     * is per app. It is read at write time, so a change in the options takes effect on the next post
     * without rebuilding anything. The default is AOSP's own treatment.
     */
    private val headsUpFor: (String) -> HeadsUpRealisation = { HeadsUpRealisation.NONE },
) {

    /**
     * What an observed channel means for a key we hold a record for. This is where spec §5.6
     * ("re-apply on next post") and spec §6 ("re-snapshot rather than clobbering a user change") are
     * reconciled: a channel that is back at the snapshot lost nothing when the app reset it, and is
     * safe to damp again; anything else was changed by somebody who is not us, and is left alone.
     */
    enum class Observed { FRESH, OURS, RESET_BY_APP, CHANGED_BY_OTHER, UNREADABLE_RECORD }

    private val written = HashMap<String, ChannelEdit>()

    /**
     * What we last wrote for [key]: this process's copy if it has one, the record's persisted copy
     * otherwise. Only the persisted copy survives a process or a Shizuku death, and without it a
     * channel we damped reads as somebody else's change on the way back up.
     */
    private fun lastWrittenFor(key: String, record: SnapshotStore.Record): ChannelFields? =
        written[key]?.fields ?: record.written

    fun classify(key: String, observed: ChannelSnapshot): Observed {
        // Checked before the record lookup, because "no record" and "a record I cannot read" are
        // different facts and only the first is safe to read as FRESH: FRESH leads straight to a
        // write, and a write with an unreadable record behind it re-records the content the channel
        // carries *now* — our own damping — as if it were the pristine one.
        if (quarantineOf(key) != null) return Observed.UNREADABLE_RECORD

        val record = store.snapshotFor(key) ?: return Observed.FRESH
        if (observed.fields == record.snapshot.fields) return Observed.RESET_BY_APP

        val mine = lastWrittenFor(key, record)
        if (mine != null) {
            // The channel carries content we never wrote, so "somebody else changed it" is proved,
            // not guessed: the record says exactly what we put there.
            return if (observed.fields == mine) Observed.OURS else Observed.CHANGED_BY_OTHER
        }

        // The record exists but says nothing about a write of ours — the process died between the
        // transport accepting a write and the record's marker reaching disk, or the very first write
        // never landed. The two are indistinguishable, and spec §6 settles the tie: a record is
        // write-ahead evidence that *we* are the ones who damped this channel, and "never leave a
        // channel muted" outranks "do not clobber a user change". Erring here toward CHANGED_BY_OTHER
        // is what abandons a damped channel, so it is ours, and [restore] puts it back.
        return Observed.OURS
    }

    /**
     * The refusal to surface for [key] when its stored record could not be read back.
     *
     * Spec §6, "fail loud, not silent". The user's original is still on disk, so there is no snapshot
     * to damp from and no write that would not risk it: every path here answers with this instead.
     */
    fun unreadable(key: String): WriteResult.Failed =
        quarantineOf(key)?.let { refusal(it.key, it.reason) } ?: refusal(key, null)

    fun apply(
        target: ChannelTarget,
        observed: ChannelSnapshot,
        state: CooldownState,
    ): WriteResult {
        quarantineOf(target.key)?.let { return refusal(it.key, it.reason) }

        val record = store.snapshotFor(target.key)

        // Never touch a channel we are not damping. A relaxed channel needs no write, and an
        // un-damped one must not gain the user-locked bits that any differing write adds.
        if (record == null && state == CooldownState.DEFAULT) return WriteResult.Ok

        val original = record?.snapshot ?: observed
        val desired = desiredEdit(original, state, target.packageName)

        // `observed` is what the channel carried when the alert *arrived*, and it stops being the
        // truth the moment anything writes. `onPost` settles due relaxations between observing and
        // calling this, so an alert landing on a relaxation deadline would compare against content
        // that has already been replaced and skip a write the device still needs — leaving the
        // channel carrying one state while the coordinator models another. What the channel carries
        // is our own last write, with the arrival's observation as the fallback for the one case it
        // is still authoritative: a key we have no record of a write for.
        val current = if (record == null) observed.fields else lastWrittenFor(target.key, record) ?: observed.fields
        if (current == desired.fields) return WriteResult.Ok

        if (record == null) {
            // The pristine snapshot is first-wins and may only ever be taken from a channel this app
            // did not write. A channel still carrying our own last write is not the user's original:
            // recording it would make our damping the baseline and scale an already-scaled pattern
            // again — measured on the device as 255 -> 77 -> 23 -> 7 -> 2 across a session, which
            // destroys the user's own value, and the worst of this defect's consequences. The record
            // is missing and the value it held is gone, so this refuses rather than writes: spec §6,
            // "fail loud, not silent".
            if (observed.fields == written[target.key]?.fields) {
                return WriteResult.Failed(
                    "refusing to damp ${target.key}: the channel still carries this app's own write " +
                        "and the record of its original is gone",
                )
            }
            // The same refusal, per *field*, for the vibration half — see [dampedVibration]. The check
            // above is whole-`ChannelFields` equality against a marker that a stand-down empties, so it
            // misses the case this platform produces constantly: an origin app that republishes its own
            // sound (a new `content://media/external/media/…` row per process) leaves the pattern this
            // app wrote on the channel with nothing left that remembers writing it. Taking *that* as
            // the pristine source is 0.30 of a value that is already 0.30 — the vibration half of the
            // defect `9d92f12` fixed for the sound, and the reason `AttenuatedSoundFactory.sourceOf`
            // consults a registry rather than trusting the snapshot it is handed.
            if (isDampingPayloadWeWrote(target.key, observed.fields)) {
                return WriteResult.Failed(
                    "refusing to damp ${target.key}: the channel carries a vibration this app wrote " +
                        "and the record of its original is gone",
                )
            }
            store.record(target, observed)
        }
        return write(
            target,
            store.snapshotFor(target.key)!!.snapshot,
            desired,
            damping = state != CooldownState.DEFAULT,
        )
    }

    /** Applies [state] for a key whose snapshot is already recorded — the relaxation path. */
    fun applyRecorded(key: String, state: CooldownState): WriteResult {
        quarantineOf(key)?.let { return refusal(it.key, it.reason) }
        val record = store.snapshotFor(key) ?: return WriteResult.Ok
        val desired = desiredEdit(record.snapshot, state, record.target.packageName)
        return write(record.target, record.snapshot, desired, damping = state != CooldownState.DEFAULT)
    }

    /**
     * The pristine content recorded for [key] — the channel as it was before this app damped it — or
     * null when no readable record is held.
     *
     * This is the only snapshot anything that builds POLITE's copy may be given. The channel itself
     * stops being pristine the moment the first damping write lands: it carries our own attenuated copy
     * from then on, and an attenuator fed that copy hands back 9 % instead of 30 % (`0.30²`) — measured
     * on the device as peak 6497 -> 1949. A record that cannot be read is not a record, which is the
     * same test [applyRecorded] makes.
     */
    fun pristineFor(key: String): ChannelSnapshot? = store.snapshotFor(key)?.snapshot

    /** Puts [key]'s channel back to its pristine content and drops its record. */
    fun restore(key: String): WriteResult {
        quarantineOf(key)?.let { return refusal(it.key, it.reason) }
        val record = store.snapshotFor(key) ?: return WriteResult.Ok
        val desired = EditFactory.editFor(record.snapshot, CooldownState.DEFAULT, config, politeSound)

        // Was the importance actually moved by our last write? Read off the record, before the write
        // below drops it: `record.written` is the persisted copy of what this app last put on the
        // channel, and it is the only side of the comparison that survives a process restart.
        val importanceWritten =
            lastWrittenFor(key, record)?.importance != record.snapshot.fields.importance

        // The transport is called directly rather than through `write`: a restore does not need a
        // "last write" marker, because a successful one drops the record the marker belongs to.
        val result = transport.write(record.target, record.snapshot, desired)
        if (result != WriteResult.Ok) return result

        // The value is back; the *lock bit* this app's own write set is not, and transaction 43 is the
        // only call that clears it. The order is forced and it is write-then-unlock: the write is what
        // locks the field (`lockFieldsForUpdateLocked` locks only fields whose value changed), so
        // unlocking first would be undone by the write that follows, and a write that failed has
        // nothing locked to release. This is the second half of "a lowered importance is recoverable",
        // and it is a no-op for a record whose write never touched importance.
        val released =
            if (importanceWritten) transport.unlockImportance(record.target, desired.fields.channelId)
            else WriteResult.Ok
        if (released != WriteResult.Ok) {
            // Spec §6, "fail loud, not silent", and invariant 2 with it: the record is *kept*, so the
            // next reconcile (or panic restore) retries both halves. The content is already back, so
            // the retry rewrites nothing that changed — but the lock bit is the residue this app
            // promised to clear, so a restore that leaves it is not a successful restore.
            return WriteResult.Failed(
                "restored ${record.target.key} but could not release its importance lock: " +
                    (released as WriteResult.Failed).reason,
            )
        }

        written.remove(key)
        store.clear(key)
        return result
    }

    /**
     * The content to write for [state], with the heads-up demotion applied only when this transport
     * can take the demotion back.
     *
     * Fail closed, and say so: `USER_LOCKED_IMPORTANCE` is clearable only by transaction 43, so a
     * transport that cannot send it must not be the one to set the bit. The refusal is recorded as a
     * [FidelityGap] rather than silently skipped — spec §5.6's rule that a cooldown doing less than it
     * claims must be visible.
     */
    private fun desiredEdit(
        original: ChannelSnapshot,
        state: CooldownState,
        packageName: String,
    ): ChannelEdit {
        val headsUp = headsUpFor(packageName)
        val releasable = transport.canUnlockImportance || !headsUp.lowers(state)
        val edit = EditFactory.editFor(
            original,
            state,
            config,
            politeSound,
            if (releasable) headsUp else HeadsUpRealisation.NONE,
        )
        return if (releasable) edit else edit.copy(gaps = edit.gaps + FidelityGap.HEADS_UP_SUPPRESS_UNRELEASABLE)
    }

    /**
     * The panic path and the reconciliation pass (spec §5.2 unit 6, spec §6): restore every channel
     * still recorded as damped. Reported per key rather than as one result, so a single failing
     * channel does not hide the others.
     *
     * Records the store could not read are reported too. They cannot be restored, but they are
     * exactly the ones the wiring has to surface rather than swallow — silence here is what would
     * leave a user with a muted channel and no explanation (spec §6, "fail loud, not silent").
     */
    fun restoreAll(): List<Pair<String, WriteResult>> =
        store.outstanding().map { it.target.key to restore(it.target.key) } +
            store.quarantined().mapNotNull { entry -> entry.key?.let { it to refusal(entry.key, entry.reason) } }

    /**
     * Drops the bookkeeping for [key] without writing. Used when the channel no longer exists, and
     * when somebody else has taken the channel over and restoring would clobber their change.
     */
    fun forget(key: String) {
        written.remove(key)
        store.clear(key)
    }

    /**
     * The stand-down path: this channel is no longer ours to damp, so stop tracking it.
     *
     * Spec §6's "never leave a channel muted" outranks spec §5.6's "do not fight a foreign change",
     * so this refuses to be the place where a damped channel loses its record. A channel that plays
     * nothing is the state a cooldown leaves behind and nothing else produces, so its snapshot is
     * written back — and the write that comes back `Ok` is what authorises dropping the record. A
     * restore that failed leaves the record exactly where it is and returns the failure for the
     * wiring to surface ("fail loud, not silent"): a stale record costs one redundant restore, a
     * lost one costs the user their channel's sound for good.
     *
     * A channel still carrying **our own** last write is the mute case by another route, not a foreign
     * change: the content on it was produced from the snapshot this record holds, so putting the
     * snapshot back clobbers nothing and leaving it damped is exactly what this path exists to
     * prevent. This guard is what makes "the pristine snapshot is never re-taken from a channel we
     * have damped" structural rather than a consequence of [classify] being right: the record — the
     * only copy of the user's original — is never dropped for a channel that still carries our write.
     *
     * A channel somebody else changed to something they can hear is the case the stand-down is
     * actually for. Nothing is muted, so nothing has to be rescued, and writing the snapshot back
     * would clobber a change that is not ours; only there is the record dropped without a write.
     */
    fun standDown(key: String, observed: ChannelSnapshot): WriteResult {
        val record = store.snapshotFor(key) ?: run {
            written.remove(key)
            return WriteResult.Ok
        }
        if (!observed.fields.playsAnything()) return restore(key)
        if (observed.fields == lastWrittenFor(key, record)) return restore(key)

        written.remove(key)
        store.clear(key)
        return WriteResult.Ok
    }

    /**
     * AOSP's `buzz || beep`: whether anything in this content makes the platform play an alert. It is
     * false for `MUTED` and true for every other state `EditFactory` installs, which is what makes
     * "we hold a record and the channel plays nothing" a channel this app silenced.
     */
    private fun ChannelFields.playsAnything(): Boolean = soundUri != null || vibrationEnabled

    /**
     * The quarantine covering [key], or null when [key]'s record is readable. Its existence is the
     * difference between "nothing recorded" and "something recorded that we cannot read", and only
     * the first of those is safe to act on.
     */
    private fun quarantineOf(key: String): SnapshotStore.Quarantined? =
        store.quarantined().firstOrNull { it.key == key }

    /** The refusal to surface for a record this app cannot read, and why. */
    private fun refusal(key: String?, reason: String?): WriteResult.Failed =
        WriteResult.Failed(
            "the stored snapshot for ${key ?: "an unreadable key"} could not be read" +
                (reason?.let { " ($it)" } ?: ""),
        )

    /** The gaps recorded the last time the transport accepted a write for [key]. */
    fun lastGaps(key: String): Set<FidelityGap> = written[key]?.gaps ?: emptySet()

    private fun write(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
        damping: Boolean,
    ): WriteResult {
        val result = transport.write(target, snapshot, edit)
        if (result == WriteResult.Ok) {
            // What the channel contains, not what we asked for. The platform normalises as it stores —
            // this ROM's `setVibrationPattern` also derives `mVibrationEffect` from the pattern
            // (`research/aosp/NotificationChannel.java:728-731`) — so a record built from the intended
            // edit would not contain what the next observation reports, and `classify` would read our
            // own damping as somebody else's change. Recording the transport's own read-back makes
            // that comparison observed-vs-observed.
            val content = transport.appliedContent(target, snapshot, edit)
            written[target.key] = edit.copy(fields = content)
            // Persisted on the same principle as the snapshot: the next process has to be able to
            // tell our own damping write from somebody else's change (spec §6).
            store.markWritten(target.key, content)
            if (damping) rememberDampingPayload(target.key, snapshot.fields, content)
        }
        return result
    }

    /**
     * Every vibration **payload this app produced** for a key — a pattern or an effect the pristine
     * snapshot did not already carry — for as long as this process lives.
     *
     * This is the vibration half of `SoundCopyCache`'s registry, and it exists because the marker above
     * is not enough on its own. `written` is dropped the moment a channel stops being ours to damp
     * ([standDown] drops it for a channel somebody else made audible) and it is whole-`ChannelFields`
     * equality, so an origin app that changes any *other* field of its own channel — on this device, its
     * sound, republished as a new `content://media/external/media/…` row by every process — hides the
     * fact that the vibration on it is still ours. The next alert then records that pattern as the
     * pristine original and scales it again: 0.30 x 0.30 = 9 %, the vibration's copy of the defect
     * `9d92f12` fixed for the sound.
     *
     * Deliberately **not** cleared by [restore], [forget] or [standDown]: surviving exactly those is the
     * point, and it is why it cannot be folded into `written` (which must be emptied on a restore, or a
     * channel we put back would read as our own damping forever). Values the pristine snapshot already
     * carried are never remembered — a restore, a relaxation and an unscalable POLITE all re-write
     * content the user already had, and refusing *that* as a source would refuse to damp a pristine
     * channel. Only payloads are remembered: MUTED's "no pattern, no effect, vibration off" is a state
     * any channel can be in without this app, and there is nothing in it to rescale.
     *
     * Bounded per key at [REMEMBERED_DAMPING] values, one of which is the one a channel carries.
     */
    private val dampedVibration = HashMap<String, MutableSet<Vibration>>()

    private fun rememberDampingPayload(key: String, pristine: ChannelFields, content: ChannelFields) {
        val wrote = Vibration.of(content)
        if (!wrote.carriesPayload) return
        if (wrote == Vibration.of(pristine)) return
        val remembered = dampedVibration.getOrPut(key) { LinkedHashSet() }
        remembered += wrote
        while (remembered.size > REMEMBERED_DAMPING) remembered.remove(remembered.first())
    }

    /** Whether [observed]'s vibration is a payload this app produced and the user's original is gone. */
    private fun isDampingPayloadWeWrote(key: String, observed: ChannelFields): Boolean {
        val present = Vibration.of(observed)
        if (!present.carriesPayload) return false
        return dampedVibration[key]?.contains(present) == true
    }

    /** The vibration half of a content: the three fields a damping write can be recognised by. */
    private data class Vibration(
        val pattern: List<Long>?,
        val effectBytes: String?,
        val enabled: Boolean,
    ) {
        /** Whether there is anything here to rescale — a pattern or an effect, not merely "off". */
        val carriesPayload: Boolean get() = pattern != null || effectBytes != null

        companion object {
            fun of(fields: ChannelFields): Vibration =
                Vibration(fields.vibrationPattern, fields.vibrationEffectBytes, fields.vibrationEnabled)
        }
    }

    private companion object {
        /** How many of a key's own damping payloads are remembered; one is what the channel carries. */
        const val REMEMBERED_DAMPING = 8

    }
}
