package com.d4viddf.hyperbridge.cooldown.act.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * The real [AudioDecoder]: `MediaExtractor` to find the track, `MediaCodec` to decode it to PCM.
 *
 * This is the piece that makes POLITE audible for the apps a user actually cares about. Measured on this
 * device, the default notification sound is Vorbis (`/system/media/audio/ui/WaterDrop_preview.ogg`,
 * opening `OggS`), so [WavAttenuator] alone refuses it and the episode logs
 * `POLITE_SOUND_UNAVAILABLE` — AOSP's headline effect, the *second* alert at 30 % volume, never happens.
 *
 * **Device-only, and named as such.** `MediaCodec` and `MediaExtractor` cannot run in `:app`'s JVM tests
 * — every Android class there is a `Stub!` thunk that throws — so nothing about this class is proved by
 * the suite. What is proved on the JVM is everything around it: that a decoded stream is wrapped by
 * [PcmWav] and attenuated by [WavAttenuator], and that a decode failure is a null with a reason. The
 * device step in Plan 3 Task 1 (seed an Ogg on `com.android.shell`, run a burst, read the log) is what
 * covers this file.
 *
 * Every failure is a null and a log line, never a throw, and two hard bounds keep a long ringtone from
 * becoming a long alert path:
 *
 *  - [SoundBudget.maxPcmBytes] stops the decode early, trimming to a whole frame, rather than decoding
 *    everything and refusing afterwards — the buffer never grows past the budget;
 *  - a wall-clock deadline ([DECODE_DEADLINE_MS]) ends a stream the codec never finishes, because a
 *    wedged decoder must not hold the worker thread forever.
 *
 * The source is fed from memory through [ByteArrayMediaDataSource] rather than by URI: the reader already
 * had to bound and obtain the bytes to know whether they are PCM at all, so re-opening the URI here would
 * duplicate the whole permission and fallback story.
 */
class MediaCodecAudioDecoder(private val budget: SoundBudget = SoundBudget()) : AudioDecoder {

    override fun decode(source: ByteArray): DecodedPcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(ByteArrayMediaDataSource(source))
            val track = firstAudioTrack(extractor)
            if (track == null) {
                Log.w(TAG, "no audio track in the ${source.size} bytes offered for decoding")
                return null
            }
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                Log.w(TAG, "the audio track declares no mime type")
                return null
            }
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            drain(extractor, codec, format)
        } catch (t: Exception) {
            Log.w(TAG, "cannot decode the sound: ${t.javaClass.simpleName}: ${t.message}")
            null
        } catch (e: OutOfMemoryError) {
            // Bounded by the budget, so this is a near-impossible case rather than a routine one -- but
            // the listener process must survive it, and an Error would otherwise escape the worker.
            Log.w(TAG, "cannot decode the sound: the codec ran out of memory")
            null
        } finally {
            try {
                codec?.stop()
            } catch (t: Exception) {
                Log.w(TAG, "stopping the decoder failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            try {
                codec?.release()
            } catch (t: Exception) {
                Log.w(TAG, "releasing the decoder failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            extractor.release()
        }
    }

    private fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
    ): DecodedPcm? {
        val bufferInfo = MediaCodec.BufferInfo()
        val out = ByteArrayOutputStream()
        var sampleRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 0)
        var channels = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 0)
        val deadline = System.nanoTime() + DECODE_DEADLINE_MS * 1_000_000L
        var inputDone = false
        var outputDone = false

        while (!outputDone && System.nanoTime() < deadline) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // The stream's own format is authoritative over the track's declared one.
                    val changed = codec.outputFormat
                    sampleRate = changed.optInt(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = changed.optInt(MediaFormat.KEY_CHANNEL_COUNT, channels)
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> if (index >= 0) {
                    if (bufferInfo.size > 0) {
                        val frameBytes = maxOf(1, channels) * BYTES_PER_SAMPLE
                        val remaining = budget.maxPcmBytes - out.size()
                        if (remaining < frameBytes) {
                            outputDone = true
                        } else {
                            val pcm = codec.getOutputBuffer(index)?.let { buffer ->
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                ByteArray(bufferInfo.size).also { buffer.get(it) }
                            }
                            if (pcm != null) {
                                // Whole frames only: a half frame would be a click, not a truncation.
                                val take = minOf(pcm.size, remaining - remaining % frameBytes)
                                out.write(pcm, 0, take)
                                if (take < pcm.size) outputDone = true
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        if (!outputDone) {
            if (System.nanoTime() >= deadline) {
                Log.w(TAG, "decoding did not finish within ${DECODE_DEADLINE_MS}ms")
                return null
            }
            Log.w(TAG, "decoding ended without any audio")
            return null
        }
        val pcm = out.toByteArray()
        if (pcm.isEmpty()) {
            Log.w(TAG, "the decoder produced no PCM")
            return null
        }
        if (sampleRate <= 0 || channels <= 0) {
            Log.w(TAG, "the decoder reported no usable format: $sampleRate Hz, $channels channel(s)")
            return null
        }
        Log.i(
            TAG,
            "decoded ${pcm.size} bytes of PCM at $sampleRate Hz, $channels channel(s), " +
                "bounded at ${budget.maxPcmBytes}",
        )
        return DecodedPcm(pcm, sampleRate, channels)
    }

    private fun firstAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private companion object {
        const val TAG = "Cooldown"

        /** `MediaCodec.dequeue*Buffer` timeout in microseconds. */
        const val TIMEOUT_US = 10_000L

        const val BYTES_PER_SAMPLE = 2

        /** A wedged codec must not hold the sound worker forever. */
        const val DECODE_DEADLINE_MS = 5_000L
    }
}

/**
 * Feeds [MediaExtractor] from a `ByteArray` it already has, so the decode needs no second trip through
 * the `ContentResolver` — and no storage permission it does not already have.
 */
private class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= bytes.size) return -1
        val count = minOf(size.toLong(), bytes.size - position).toInt()
        if (count <= 0) return -1
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}
