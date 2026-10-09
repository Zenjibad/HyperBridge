package com.d4viddf.hyperbridge.cooldown.act.audio

import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [AttenuatedSoundFactory]'s contract: what it answers, what it publishes, when it stops, why it
 * stopped, and that every failure is a null rather than a throw.
 *
 * `SoundReader`, `AudioDecoder` and `SoundCopyPublisher` are this module's own seams precisely so the
 * whole decision — including the compressed path and every degradation — can be driven on the JVM with
 * fakes. Two things this test cannot prove, and both are named rather than assumed: that `MediaCodec`
 * really decodes a Vorbis stream (`MediaCodecAudioDecoder`, device-only), and that the platform will
 * *open* what was published (Q1's proof, re-run by this task's device steps).
 *
 * The asynchronous half is pinned with a queue-only [Executor]: the caller hands the work over and gets
 * nothing back, which is what keeps an 18 KB Ogg out of the listener's main thread.
 *
 * No Android class is touched at runtime, which is what makes it runnable in `:app`.
 */
class AttenuatedSoundFactoryTest {

    @Test
    fun `a channel with no sound is left with no sound and nothing is published`() {
        val publisher = RecordingPublisher(publishedUri)
        val factory = factory(SoundReader { null }, publisher)

        assertNull(factory.politeSoundFor(snapshot(soundUri = null)))
        factory.prewarm(snapshot(soundUri = null))

        assertEquals(0, publisher.published.size)
    }

    @Test
    fun `a cold read hands the work to the worker and answers null rather than blocking`() {
        val publisher = RecordingPublisher(publishedUri)
        var reads = 0
        val queued = ArrayDeque<Runnable>()
        val factory = factory(
            SoundReader { reads++; source },
            publisher,
            worker = Executor { queued += it },
        )

        assertNull(factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals("nothing may be read on the caller's thread", 0, reads)
        assertEquals("the work must have been handed to the worker", 1, queued.size)

        queued.removeFirst().run()

        assertEquals(publishedUri, factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(1, reads)
    }

    @Test
    fun `the copy is published once and then served from the cache`() {
        val publisher = RecordingPublisher(publishedUri)
        var reads = 0
        val factory = factory(SoundReader { reads++; source }, publisher)

        factory.prewarm(snapshot(sourceUri))

        assertEquals(publishedUri, factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(publishedUri, factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(1, reads)
        assertEquals(1, publisher.published.size)
    }

    @Test
    fun `a second prewarm for the same source is not queued twice`() {
        val queued = ArrayDeque<Runnable>()
        val factory = factory(
            SoundReader { source },
            RecordingPublisher(publishedUri),
            worker = Executor { queued += it },
        )

        factory.prewarm(snapshot(sourceUri))
        factory.prewarm(snapshot(sourceUri))
        factory.politeSoundFor(snapshot(sourceUri))

        assertEquals(1, queued.size)
    }

    @Test
    fun `a source already in the cache is not queued again`() {
        val queued = ArrayDeque<Runnable>()
        val factory = factory(
            SoundReader { source },
            RecordingPublisher(publishedUri),
            worker = Executor { queued += it },
        )

        factory.prewarm(snapshot(sourceUri))
        queued.removeFirst().run()
        factory.prewarm(snapshot(sourceUri))

        assertEquals(0, queued.size)
    }

    @Test
    fun `the published copy is the source scaled by the factory's factor`() {
        val publisher = RecordingPublisher(publishedUri)
        val factory = factory(SoundReader { source }, publisher)

        factory.prewarm(snapshot(sourceUri))

        assertEquals(publishedUri, factory.politeSoundFor(snapshot(sourceUri)))
        val copy = publisher.published.single()
        assertEquals(source.size, copy.size)
        // 20000 * 0.30 = 6000, and the second sample is its negative.
        assertEquals(6000, sampleAt(copy, 0))
        assertEquals(-6000, sampleAt(copy, 1))
        assertTrue(copy.contentEquals(WavAttenuator.attenuate(source, factor)))
    }

    @Test
    fun `a wav source never reaches the decoder`() {
        var decodes = 0
        val publisher = RecordingPublisher(publishedUri)
        val factory = factory(
            SoundReader { source },
            publisher,
            decoder = AudioDecoder { decodes++; compressedPcm },
        )

        factory.prewarm(snapshot(sourceUri))

        assertEquals("16-bit PCM is the attenuator's own path and must not be decoded", 0, decodes)
        assertTrue(publisher.published.single().contentEquals(WavAttenuator.attenuate(source, factor)))
    }

    @Test
    fun `a compressed source is decoded and attenuated by the same arithmetic as a pcm source`() {
        val publisher = RecordingPublisher(publishedUri)
        val factory = factory(
            SoundReader { ogg },
            publisher,
            decoder = AudioDecoder { bytes ->
                assertEquals("the decoder must be handed the source bytes", ogg.size, bytes.size)
                DecodedPcm(pcm16(26_213, 0, -26_213), sampleRate = 44_100, channelCount = 1)
            },
        )

        factory.prewarm(snapshot(sourceUri))

        assertEquals(publishedUri, factory.politeSoundFor(snapshot(sourceUri)))
        val copy = publisher.published.single()
        // `WavAttenuator`'s own header plus the three decoded samples, at exactly 0.300.
        assertEquals(PcmWav.HEADER_BYTES + 6, copy.size)
        assertEquals(7_864, sampleAt(copy, 0))
        assertEquals(0, sampleAt(copy, 1))
        assertEquals(-7_864, sampleAt(copy, 2))
        assertEquals("the copy keeps the decoded sample rate", 44_100, i32(copy, 24))
        assertEquals("and the decoded channel count", 1, i16(copy, 22))
        assertEquals(0.300, sampleAt(copy, 0).toDouble() / 26_213.0, 0.001)
    }

    @Test
    fun `a sound that cannot be read degrades to no copy and says which URI`() {
        val publisher = RecordingPublisher(publishedUri)
        val notes = mutableListOf<String>()
        val factory = factory(SoundReader { null }, publisher, notes = notes)

        factory.prewarm(snapshot(sourceUri))

        assertNull(factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(0, publisher.published.size)
        assertTrue("no reason was recorded", notes.isNotEmpty())
        assertTrue("the reason must name the sound: ${notes.first()}", notes.first().contains(sourceUri))
    }

    @Test
    fun `a sound that cannot be decoded degrades to no copy instead of throwing`() {
        val publisher = RecordingPublisher(publishedUri)
        val factory = factory(SoundReader { ByteArray(4096) { 0x7F } }, publisher)

        factory.prewarm(snapshot(sourceUri))

        assertNull(factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(0, publisher.published.size)
    }

    @Test
    fun `a decoder that throws is a null and a named reason, never an escaping exception`() {
        val notes = mutableListOf<String>()
        val factory = factory(
            SoundReader { ogg },
            RecordingPublisher(publishedUri),
            decoder = AudioDecoder { throw IllegalStateException("codec died") },
            notes = notes,
        )

        factory.prewarm(snapshot(sourceUri))

        assertNull(factory.politeSoundFor(snapshot(sourceUri)))
        assertTrue("no reason was recorded", notes.isNotEmpty())
        assertTrue(notes.first(), notes.first().contains("codec died"))
    }

    @Test
    fun `decoded audio over the pcm budget is refused and named, and nothing is published`() {
        val publisher = RecordingPublisher(publishedUri)
        val notes = mutableListOf<String>()
        val factory = factory(
            SoundReader { ogg },
            publisher,
            decoder = AudioDecoder { DecodedPcm(ByteArray(8), sampleRate = 44_100, channelCount = 1) },
            budget = SoundBudget(maxSourceBytes = 4096, maxPcmBytes = 4),
            notes = notes,
        )

        factory.prewarm(snapshot(sourceUri))

        assertNull(factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(0, publisher.published.size)
        assertTrue("no reason was recorded", notes.isNotEmpty())
        assertTrue(notes.first(), notes.first().contains("budget"))
    }

    @Test
    fun `a source over the byte budget is refused before anything reads or decodes it`() {
        var decodes = 0
        val publisher = RecordingPublisher(publishedUri)
        val notes = mutableListOf<String>()
        val factory = factory(
            SoundReader { ogg },
            publisher,
            decoder = AudioDecoder { decodes++; compressedPcm },
            budget = SoundBudget(maxSourceBytes = 8, maxPcmBytes = 4096),
            notes = notes,
        )

        factory.prewarm(snapshot(sourceUri))

        assertNull(factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(0, decodes)
        assertEquals(0, publisher.published.size)
        assertTrue("no reason was recorded", notes.isNotEmpty())
        assertTrue(notes.first(), notes.first().contains("budget"))
    }

    @Test
    fun `a publisher that cannot publish degrades to no copy and caches nothing`() {
        val cache = SoundCopyCache()
        val publisher = RecordingPublisher(uri = null)
        val factory = factory(SoundReader { source }, publisher, cache = cache)

        factory.prewarm(snapshot(sourceUri))

        // A copy that could not be published is not cached, so nothing points at a row that is not there.
        assertNull(cache.get(sourceUri, factor))
        assertEquals(1, publisher.published.size)

        // And it is retried rather than remembered as impossible: a MediaStore insert can be refused
        // once and succeed later, and the cache stays empty until one does.
        assertNull(factory.politeSoundFor(snapshot(sourceUri)))
        assertEquals(2, publisher.published.size)
        assertNull(cache.get(sourceUri, factor))
    }

    @Test
    fun `the read and the decode happen on the worker thread, never on the caller's`() {
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sound-worker") }
        try {
            var readOn: String? = null
            var decodedOn: String? = null
            val factory = factory(
                SoundReader { readOn = Thread.currentThread().name; ogg },
                RecordingPublisher(publishedUri),
                decoder = AudioDecoder { decodedOn = Thread.currentThread().name; compressedPcm },
                worker = worker,
            )

            factory.prewarm(snapshot(sourceUri))
            // A single worker runs its queue in order, so this no-op settling means the task is done.
            worker.submit { }.get()

            assertEquals("sound-worker", readOn)
            assertEquals("sound-worker", decodedOn)
            assertNotEquals(Thread.currentThread().name, readOn)
            assertEquals(publishedUri, factory.politeSoundFor(snapshot(sourceUri)))
        } finally {
            worker.shutdown()
        }
    }

    // --- the two defects the device measured in this pipeline ------------------------------------

    @Test
    fun `the copy is announced to the caller that prewarmed it`() {
        val publisher = RecordingPublisher(publishedUri)
        val announced = mutableListOf<String>()
        val factory = factory(SoundReader { source }, publisher)

        factory.prewarm(snapshot(sourceUri)) { announced += "ready" }

        // The device's 60-80 ms gap between the POLITE write and the copy being published is what this
        // callback exists to close: the write has to be re-made once the copy is there, or the alert
        // that write was installed for plays `mSound=null`.
        assertEquals(listOf("ready"), announced)
    }

    @Test
    fun `every prewarm waiting on one sound is told, not just the first`() {
        val publisher = RecordingPublisher(publishedUri)
        val announced = mutableListOf<String>()
        val queued = ArrayDeque<Runnable>()
        val factory = factory(SoundReader { source }, publisher, worker = Executor { queued += it })

        // Two channels aliasing one settings URI are one sound and one build, so the second waiter must
        // not be dropped: its own POLITE write is the one that needed the copy.
        factory.prewarm(snapshot(sourceUri)) { announced += "first" }
        factory.prewarm(snapshot(sourceUri)) { announced += "second" }
        assertEquals("one sound is one build", 1, queued.size)

        queued.removeFirst().run()

        assertEquals(listOf("first", "second"), announced)
    }

    @Test
    fun `a build that produces no copy announces nothing`() {
        val announced = mutableListOf<String>()
        val factory = factory(SoundReader { null }, RecordingPublisher(publishedUri))

        factory.prewarm(snapshot(sourceUri)) { announced += "ready" }

        // Nothing was published, so there is nothing to install: a caller that re-applied POLITE here
        // would write the same sound-less state again and log an upgrade that did not happen.
        assertTrue(announced.isEmpty())
    }

    @Test
    fun `a cold answer names the reason it has no copy yet`() {
        val notes = mutableListOf<String>()
        val queued = ArrayDeque<Runnable>()
        val factory = factory(
            SoundReader { source },
            RecordingPublisher(publishedUri),
            notes = notes,
            worker = Executor { queued += it },
        )

        assertNull(factory.politeSoundFor(snapshot(sourceUri)))

        // An unexplained `POLITE_SOUND_UNAVAILABLE` is what made the cold-start defect hard to
        // diagnose: the gap this answer records has to say that the copy is late, not missing.
        assertEquals("exactly one reason, and it has to be there: $notes", 1, notes.size)
        assertTrue("the reason must say the copy is being built: ${notes.single()}", notes.single().contains("is being built on the worker"))
    }

    @Test
    fun `a prewarm handed a copy this factory published is refused at the origin`() {
        val publisher = RecordingPublisher(publishedUri)
        val notes = mutableListOf<String>()
        var reads = 0
        var liveBytes: ByteArray? = null
        val factory = factory(
            SoundReader { uri -> reads++; if (uri == sourceUri) source else liveBytes },
            publisher,
            notes = notes,
        )

        factory.prewarm(snapshot(sourceUri))
        // The channel now carries our copy, so a caller that reached for the live channel's sound would
        // hand the copy straight back in.
        liveBytes = publisher.published.single()

        factory.prewarm(snapshot(publishedUri))

        assertEquals("a copy of a copy is 0.09, so it must not be built at all", 1, publisher.published.size)
        assertEquals("the copy was never read as a source", 1, reads)
        assertTrue(
            "the refusal must name the sound it is a copy of: $notes",
            notes.any { it.contains("it is the copy this app published for $sourceUri") },
        )
    }

    @Test
    fun `two consecutive prewarms for one key leave the attenuated peak where it was`() {
        val publisher = RecordingPublisher(publishedUri)
        var liveBytes: ByteArray? = null
        val factory = factory(
            SoundReader { uri -> if (uri == sourceUri) source else liveBytes },
            publisher,
        )

        // The device's first two alerts of an episode: alert 1's POLITE write happens before the copy
        // exists, and by alert 2 the channel carries the copy this factory published. Measured before
        // this fix: peak 6497 -> 1949, exactly 0.30 x 0.30.
        factory.prewarm(snapshot(sourceUri))
        liveBytes = publisher.published.single()
        factory.prewarm(snapshot(publishedUri))

        assertEquals(
            "0.30 of the user's sound, twice -- never 0.30 and then 0.09",
            listOf(6_000),
            publisher.published.map { peakOf(it) },
        )
    }

    // --- fixtures -------------------------------------------------------------------------------

    private val factor = 0.30f
    private val sourceUri = "content://settings/system/notification_sound"
    private val publishedUri = "content://media/external/audio/media/2"
    private val source = pcm16Wav(intArrayOf(20000, -20000, 0))

    /** Ogg pages begin `OggS`; nothing here parses it, because nothing on the JVM can decode it. */
    private val ogg = "OggS\u0000\u0002\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val compressedPcm = DecodedPcm(pcm16(26_213, 0, -26_213), sampleRate = 44_100, channelCount = 1)

    private fun factory(
        reader: SoundReader,
        publisher: SoundCopyPublisher,
        decoder: AudioDecoder = AudioDecoder { null },
        cache: SoundCopyCache = SoundCopyCache(),
        budget: SoundBudget = SoundBudget(),
        notes: MutableList<String> = mutableListOf(),
        worker: Executor = Executor { runnable -> runnable.run() },
    ) = AttenuatedSoundFactory(
        reader = reader,
        decoder = decoder,
        publisher = publisher,
        cache = cache,
        factor = factor,
        worker = worker,
        budget = budget,
        note = { notes += it },
    )

    private fun snapshot(soundUri: String?) = ChannelSnapshot(
        fields = ChannelFields(
            channelId = "shell_cmd",
            importance = 3,
            soundUri = soundUri,
            // The platform's own values as literals: `AudioAttributes.USAGE_NOTIFICATION` (5) and
            // `CONTENT_TYPE_SONIFICATION` (4). A test in this module must not touch an Android class.
            audioUsage = 5,
            audioContentType = 4,
            audioFlags = 0x800,
            vibrationPattern = null,
            vibrationEffectBytes = null,
            vibrationEnabled = true,
        ),
        channelBytes = null,
    )

    private class RecordingPublisher(private val uri: String?) : SoundCopyPublisher {
        val published = mutableListOf<ByteArray>()

        override fun publish(displayName: String, wav: ByteArray): String? {
            published += wav
            return uri
        }
    }

    private fun pcm16(vararg samples: Short): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = sample.toByte()
            out[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return out
    }

    private fun pcm16Wav(samples: IntArray): ByteArray {
        val payload = ByteArray(samples.size * 2).also { out ->
            var i = 0
            for (sample in samples) {
                out[i++] = sample.toByte()
                out[i++] = (sample shr 8).toByte()
            }
        }
        val out = java.io.ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun i32(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        fun i16(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }
        ascii("RIFF")
        i32(36 + payload.size)
        ascii("WAVE")
        ascii("fmt ")
        i32(16)
        i16(1)
        i16(1)
        i32(44100)
        i32(44100 * 2)
        i16(2)
        i16(16)
        ascii("data")
        i32(payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    private fun sampleAt(wav: ByteArray, index: Int): Int {
        val low = wav[44 + index * 2].toInt() and 0xFF
        val high = wav[45 + index * 2].toInt() shl 8
        return (low or high).toShort().toInt()
    }

    /** The loudest sample in a published copy: what the device measures to tell 0.30 from 0.09. */
    private fun peakOf(wav: ByteArray): Int {
        val samples = (wav.size - 44) / 2
        return (0 until samples).maxOf { abs(sampleAt(wav, it)) }
    }

    private fun i32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun i16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
}
