package com.d4viddf.hyperbridge.cooldown.act.audio

import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.PoliteSoundSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * The real `PoliteSoundSource`: POLITE plays a 30 %-amplitude copy of the channel's own sound
 * (spec §5.1, §5.5), which is what AOSP's `politeness` does with `getSoundVolume(record)`.
 *
 * The pipeline is `read → decode if needed → scale → publish`, and each stage is allowed to fail by
 * returning null:
 *
 *  1. [SoundReader] hands back the bytes behind `snapshot.fields.soundUri`. A channel with **no** sound
 *     returns null here by design — an app that plays nothing must not gain a sound because of our
 *     cooldown. A *settings* URI is resolved by the reader rather than refused (see [SoundUriResolver]),
 *     which is what stops the common case degrading.
 *  2. A source that already is 16-bit PCM WAV goes straight to [WavAttenuator.attenuate]. Anything
 *     else — the measured default on this device is an Ogg ringtone — is decoded to PCM by
 *     [AudioDecoder], wrapped by [PcmWav], and *then* handed to the same [WavAttenuator.attenuate].
 *     That ordering is the point: the attenuation arithmetic exists once, so the compressed path cannot
 *     drift from the PCM path the device run measured (26213 → 7864, exactly 0.300).
 *  3. [SoundCopyPublisher] puts the copy where a privileged, non-owning process can open it — a
 *     MediaStore notification row, the recipe Q1 verified on this device.
 *
 * **Nothing here ever publishes an un-attenuated sound.** A copy that cannot be scaled is a null, which
 * `EditFactory` turns into `FidelityGap.POLITE_SOUND_UNAVAILABLE` when the channel had a sound: for
 * that alert the channel keeps its own sound un-damped rather than being made quieter by an untouched
 * one — and it is *not* silenced either, because a POLITE write that cleared the sound is exactly what
 * stranded `com.instagram.android/ig_direct` on 2026-10-11 (root-cause §3.5, §4). There is no path that
 * skips [WavAttenuator].
 *
 * **The work runs on [worker], never on the caller's thread.** `politeSoundFor` is called from
 * `EditFactory` on the notification listener's callback thread — the main thread — and decoding an Ogg
 * with `MediaCodec` there would block the alert path. A cold read therefore answers null (honest: there
 * is no copy *yet*, and the gap is recorded, with that reason named) and hands the work over; [prewarm],
 * called from the wiring when an alert arrives, is what makes the copy ready — and, because a copy
 * produced asynchronously cannot be in the write that needed it, its `onReady` callback is what gets
 * the copy *into* that write. Measured on this device: the worker's read, decode and publish complete
 * 60-80 ms after the post's own POLITE write, so without that callback the first episode of a process
 * installs POLITE with `mSound=null` and plays it silently. A failed
 * source is retried on the next alert rather than remembered, at most once at a time, because the sound
 * behind one URI can become readable and the process would otherwise never notice.
 *
 * [SoundCopyCache] keeps the published URI for `(sourceUri, factor)`, so a burst on one channel pays for
 * the read, the decode, the scale and the publish once rather than once per alert (spec §6, "bounded
 * resources"). The key is the *pristine* sound — the one recorded before this app damped the channel,
 * which is what `prewarm`'s caller must pass — not the URI the reader resolved it to, so two channels
 * aliasing one settings URI share a copy, and not the live channel's sound, which carries our own copy
 * once POLITE is installed and would be scaled a second time (0.3² = 0.09, measured). If the bytes
 * behind an unchanged URI change, the cached copy is stale: no cheaper identity for a sound exists
 * here, and re-reading per alert is exactly what §6's bound exists to avoid.
 *
 * **The factor is a parameter, never a field.** It is the alert's own `EngineConfig.politeVolume`,
 * which the user can change between two alerts, and a value captured when this class was constructed
 * would keep publishing the attenuation the process started with — the reported "a change needs a
 * reboot". The cache key and the in-flight key both carry it, so a changed setting builds a second copy
 * rather than serving the first one, and a build in flight at one factor cannot be handed to a waiter
 * that asked for another.
 *
 * [note] receives a plain-English reason for every refusal. It is a callback rather than a log call so
 * this class stays free of `android.*` and every reason stays assertable in a JVM test; the wiring points
 * it at `Log.w`, where the reason is visible rather than mysterious (spec §6, "fail loud, not silent").
 *
 * Pure Kotlin on purpose — no `android.*` — so [AttenuatedSoundFactoryTest] drives every path on the JVM.
 */
class AttenuatedSoundFactory(
    private val reader: SoundReader,
    private val decoder: AudioDecoder,
    private val publisher: SoundCopyPublisher,
    private val cache: SoundCopyCache,
    private val worker: Executor,
    private val budget: SoundBudget = SoundBudget(),
    private val note: (String) -> Unit = {},
    /**
     * Where the *name* of a sound's `MediaStore` row comes from — the durable half of
     * [isOurPublishedCopy], and the only provenance that survives the reinstall which destroys the
     * write-ahead record (see [PublishedCopyName]).
     *
     * Defaults to a lookup that never finds a name, which degrades to the in-memory half alone: the
     * behaviour before this parameter existed. The wiring in `CooldownRuntime` is the one place that
     * supplies the real one, so the default matters only to tests and to a construction mistake.
     */
    private val names: SoundNameLookup = SoundNameLookup { null },
) : PoliteSoundSource {

    /**
     * The reads/decodes/publishes queued or running, keyed by `(source, factor)` so one sound is never
     * built twice for the same attenuation.
     *
     * The factor is part of the key rather than a field of this class: it is this alert's
     * `EngineConfig.politeVolume`, which the user can change between two alerts. A build in flight at
     * 30 % must not answer for a request at 50 %, and it must not hand the 30 % copy to a waiter that
     * asked for 50 % — that is the stale-value defect this key exists to prevent.
     */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Who to tell when the copy of a source is ready, per `(source, factor)`.
     *
     * A list rather than a slot because two channels can alias one settings URI (see the class note):
     * one build serves both, and both have to hear about it or the second one's POLITE stays silent
     * until its next alert. Guarded by [waitersLock]: registered on the caller's thread, taken by the
     * worker.
     */
    private val waitersLock = Any()
    private val waiters: MutableMap<String, MutableList<() -> Unit>> = HashMap()

    override fun politeSoundFor(snapshot: ChannelSnapshot, factor: Float): String? {
        val source = sourceOf(snapshot) ?: return null
        cache.get(source, factor)?.let { return it }
        submit(source, factor)
        // The gap this answer makes `EditFactory` record must not be unexplained -- an unexplained
        // `POLITE_SOUND_UNAVAILABLE` is what made the cold-start defect hard to diagnose. This is the
        // one refusal that is not a failure: on a cold cache the copy does not exist *yet*, and the
        // worker that is building it finishes after this write (measured: 60-80 ms later).
        note("no attenuated copy of $source yet: it is being built on the worker")
        return null
    }

    /**
     * Starts building the copy for [snapshot]'s sound, off the caller's thread, and calls [onReady] —
     * once, on the worker — when that copy is there to install.
     *
     * Called from the wiring as each alert arrives, so a burst pays for the sound once, before the alert
     * that installs POLITE. A sound already cached costs nothing and does not use the callback at all,
     * because that same post's `politeSoundFor` finds the copy; a sound already in flight costs nothing
     * but the callback, which joins the listeners already waiting on it.
     *
     * [onReady] is what closes the cold-start race: the copy cannot be in the POLITE write that needed
     * it, so the write has to be re-made when it lands. The callback carries no state and this class
     * never learns what a key is: the wiring decides what to do with the news.
     *
     * [snapshot] must be the *pristine* snapshot — the record of what the channel looked like before
     * this app damped it — never the channel as it arrived, which carries our own published copy once
     * POLITE is installed. A copy handed in as a source is refused by [sourceOf] rather than scaled
     * twice, but the caller's snapshot is still where the right answer comes from.
     *
     * [factor] must be the same `EngineConfig.politeVolume` the write for this alert will use, read at
     * the same moment: the two are separate calls and a mismatch would build a copy nothing looks up.
     */
    fun prewarm(snapshot: ChannelSnapshot, factor: Float, onReady: (() -> Unit)? = null) {
        val source = sourceOf(snapshot) ?: return
        if (cache.get(source, factor) != null) return
        if (onReady != null) {
            synchronized(waitersLock) {
                waiters.getOrPut(keyOf(source, factor)) { mutableListOf() } += onReady
            }
        }
        submit(source, factor)
    }

    /** The `(source, factor)` identity of one build: one copy per sound per attenuation. */
    private fun keyOf(source: String, factor: Float): String = "$factor|$source"

    /**
     * The URI whose bytes are the sound to attenuate, or null with a reason.
     *
     * A copy this app published is refused here rather than attenuated: it is already 30 % of the
     * user's sound, so scaling it again is 9 %, and the device measured exactly that (peak 6497 ->
     * 1949) once POLITE began carrying the copy. The refusal is the last line of defence — the caller
     * is supposed to hand over the pristine snapshot — and it costs one cache lookup plus, on a miss, a
     * name lookup for the row.
     */
    private fun sourceOf(snapshot: ChannelSnapshot): String? {
        val candidate = snapshot.fields.soundUri?.takeIf { it.isNotBlank() } ?: return null
        val reason = ourCopyReason(candidate) ?: return candidate
        note("refusing to attenuate $candidate: $reason, not a pristine sound")
        return null
    }

    /**
     * Whether [soundUri] is one of this app's own published POLITE copies.
     *
     * Two halves, cheapest first, and the second one is the reason this is not a registry:
     *
     *  1. **This process's cache.** [SoundCopyCache.sourceOfCopy] is an exact reverse lookup —
     *     `(source, factor) -> published` turned the other way — for every copy this process still
     *     remembers, and its bound is the cache's own.
     *  2. **The row's name.** [PublishedCopyName] matches the name this app gave the `MediaStore` row it
     *     published. That survives the process, the install, and the data clear, which is exactly what
     *     the cache does not: a reinstall is every development iteration on this ROM, and it is what
     *     destroyed the record that held `ig_direct`'s original (root-cause §3.1, §4). It also covers
     *     the standalone `app.hypercooldown`, which publishes under the same name — the cross-writer
     *     case the process-local markers in `ChannelActuator` structurally cannot see.
     *
     * Both halves answer only "is this a copy of ours". Neither can say what the sound behind it was,
     * and neither can prove a *negative*: a copy whose row was deleted or renamed is no longer
     * recognisable, and a channel carrying it is treated as pristine again.
     */
    override fun isOurPublishedCopy(soundUri: String?): Boolean {
        val uri = soundUri?.takeIf { it.isNotBlank() } ?: return false
        return ourCopyReason(uri) != null
    }

    /** Why [uri] is a copy of ours, in the words of the refusal — or null when it is not one. */
    private fun ourCopyReason(uri: String): String? {
        cache.sourceOfCopy(uri)?.let { return "it is the copy this app published for $it" }
        if (PublishedCopyName.isOurs(names.displayNameOf(uri))) {
            return "it is named like a copy this app published, and no process remembers its source"
        }
        return null
    }

    private fun submit(source: String, factor: Float) {
        val key = keyOf(source, factor)
        if (!inFlight.add(key)) return
        try {
            worker.execute { produce(source, factor) }
        } catch (t: Exception) {
            // A rejected task must not wedge the key out of every later attempt.
            inFlight.remove(key)
            note("cannot schedule the copy of $source: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun produce(source: String, factor: Float) {
        val key = keyOf(source, factor)
        try {
            val bytes = reader.read(source)
            when {
                bytes == null -> note("cannot read the sound behind $source")
                bytes.size > budget.maxSourceBytes -> note(
                    "the sound behind $source is ${bytes.size} bytes, over the " +
                        "${budget.maxSourceBytes}-byte budget",
                )
                else -> {
                    val wav = attenuatedWav(bytes, source, factor) ?: return
                    val published = publisher.publish(displayNameFor(source, factor), wav)
                    if (published == null) {
                        note("cannot publish the copy of $source")
                    } else {
                        cache.put(source, factor, published)
                        // Announced only once the cache holds the copy, so a listener that reacts by
                        // asking for it gets the URI rather than starting the build again.
                        announce(source, factor)
                    }
                }
            }
        } catch (t: Exception) {
            // The alert path must never see a throw, and neither must the worker: an escaping exception
            // here would kill the process rather than the episode.
            note("cannot build the copy of $source: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            inFlight.remove(key)
            // A build that failed leaves nobody to tell, so its listeners are dropped rather than kept
            // for a later attempt: they are re-registered by the next prewarm for the same sound, and
            // keeping them would grow one entry per alert for a sound that never becomes readable.
            synchronized(waitersLock) { waiters.remove(key) }
        }
    }

    /**
     * Hands every listener waiting on this `(source, factor)` its one call, and forgets them.
     *
     * A listener that throws is named and swallowed: by the time this runs the copy is published and
     * cached, so the news is good and a bug in one listener must not cost the sound worker its thread
     * or the other listeners their call.
     */
    private fun announce(source: String, factor: Float) {
        val pending = synchronized(waitersLock) { waiters.remove(keyOf(source, factor)) } ?: return
        for (waiter in pending) {
            try {
                waiter()
            } catch (t: Exception) {
                note("a polite-copy listener for $source threw ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** The attenuated copy of [bytes], or null with a reason. The only place [WavAttenuator] is called. */
    private fun attenuatedWav(bytes: ByteArray, source: String, factor: Float): ByteArray? {
        WavAttenuator.attenuate(bytes, factor)?.let { return it }

        val decoded = decoder.decode(bytes)
        if (decoded == null) {
            note("neither a 16-bit PCM WAV nor a stream this process can decode: $source")
            return null
        }
        if (decoded.pcm.size > budget.maxPcmBytes) {
            note(
                "decoding $source produced ${decoded.pcm.size} bytes of PCM, over the " +
                    "${budget.maxPcmBytes}-byte budget",
            )
            return null
        }
        val wrapped = PcmWav.wrap(decoded.pcm, decoded.sampleRate, decoded.channelCount)
        if (wrapped == null) {
            note(
                "decoded audio from $source has no usable format: ${decoded.sampleRate} Hz, " +
                    "${decoded.channelCount} channel(s), ${decoded.pcm.size} bytes",
            )
            return null
        }
        val copy = WavAttenuator.attenuate(wrapped, factor)
        if (copy == null) note("cannot attenuate the decoded audio from $source")
        return copy
    }

    /**
     * The name of the MediaStore row.
     *
     * Delegated to [PublishedCopyName] rather than spelled here, because the same name is what the
     * provenance check reads back off the row: a publisher and a recogniser that each held their own
     * copy of this format could drift, and the drift would be silent — the copy would simply stop being
     * recognised as ours, on the one path that exists to stop an already-attenuated sound being
     * attenuated again.
     */
    private fun displayNameFor(sourceUri: String, factor: Float): String =
        PublishedCopyName.of(sourceUri, factor)
}
