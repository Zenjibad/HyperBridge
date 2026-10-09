package com.d4viddf.hyperbridge.cooldown.act.audio

/**
 * Scales the samples of a 16-bit PCM WAV by [factor] and returns a complete new WAV.
 *
 * **The subset it supports, exactly.** A RIFF/WAVE file whose `fmt ` chunk declares
 * `wFormatTag == 1` (PCM), `wBitsPerSample == 16`, `nChannels >= 1`, `nSamplesPerSec > 0` and
 * `nBlockAlign == nChannels * 2`; exactly one `data` chunk, whose declared size fits inside the array
 * and is a whole number of frames. Everything else is null: `WAVE_FORMAT_EXTENSIBLE` (`0xFFFE`), 8-bit
 * and float PCM, a `data` chunk that claims more bytes than the file holds, a second `data` chunk, a
 * file with no `data` chunk, and anything that is not RIFF/WAVE at all.
 *
 * That refusal is deliberate and it is the floor spec §5.6 describes rather than a hidden failure: a
 * channel whose sound is a Vorbis or MP3 ringtone is not attenuated, the caller gets a null, and
 * `EditFactory` records `FidelityGap.POLITE_SOUND_UNAVAILABLE` so POLITE becomes vibration-only for
 * that channel *visibly*. Guessing at a compressed stream we cannot decode would be the one thing
 * that is not allowed — pretending to work.
 *
 * The copy keeps the sample rate, the channel count and the chunk layout: every chunk other than
 * `data` is copied byte for byte, in its original order, and the `RIFF` size is recomputed from the
 * result. Bytes after the last chunk are dropped, because nothing declares them.
 *
 * The factor is **not** restricted to the unit interval. A factor above 1 is what makes the 16-bit
 * clamp observable (`Math.round` then clamp to `-32768..32767`, so no sample can wrap to the opposite
 * sign and click); the production caller passes `EngineConfig.politeVolume`, which is `0.30`. A factor
 * that is zero, negative, `NaN` or infinite is refused with null rather than throwing.
 *
 * Pure Kotlin on purpose: no `android.*`, so `WavAttenuatorTest` exercises it on the JVM.
 */
object WavAttenuator {

    /** The only `wFormatTag` this decodes: `WAVE_FORMAT_PCM`. */
    private const val PCM = 1

    private const val BITS_PER_SAMPLE = 16
    private const val CHUNK_HEADER = 8
    private const val RIFF_HEADER = 12
    private const val MIN_SAMPLE = -32768
    private const val MAX_SAMPLE = 32767

    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    private const val FMT = "fmt "
    private const val DATA = "data"

    fun attenuate(wav: ByteArray, factor: Float): ByteArray? {
        if (factor.isNaN() || factor.isInfinite() || factor <= 0f) return null
        if (wav.size < RIFF_HEADER) return null
        if (ascii(wav, 0) != "RIFF" || ascii(wav, 8) != "WAVE") return null

        var format: Format? = null
        var dataOffset = -1
        var dataSize = -1
        var offset = RIFF_HEADER
        while (offset + CHUNK_HEADER <= wav.size) {
            val id = ascii(wav, offset)
            val size = u32(wav, offset + 4)
            if (size < 0 || offset + CHUNK_HEADER + size > wav.size) return null
            when (id) {
                FMT -> {
                    if (size < 16) return null
                    format = Format(
                        audioFormat = u16(wav, offset + 8),
                        channels = u16(wav, offset + 10),
                        sampleRate = u32(wav, offset + 12),
                        blockAlign = u16(wav, offset + 20),
                        bitsPerSample = u16(wav, offset + 22),
                    )
                }
                DATA -> {
                    // Two data chunks is a file this function has no single answer for.
                    if (dataOffset >= 0) return null
                    dataOffset = offset + CHUNK_HEADER
                    dataSize = size
                }
            }
            offset += CHUNK_HEADER + size + (size and 1)
        }

        val fmt = format ?: return null
        if (dataOffset < 0) return null
        val frame = fmt.channels * 2
        if (fmt.audioFormat != PCM || fmt.bitsPerSample != BITS_PER_SAMPLE) return null
        if (fmt.channels <= 0 || fmt.sampleRate <= 0) return null
        if (fmt.blockAlign != frame || dataSize % frame != 0) return null

        val out = java.io.ByteArrayOutputStream(wav.size)
        out.write(RIFF)
        out.write(byteArrayOf(0, 0, 0, 0))   // the RIFF size, patched once the whole file is written
        out.write(WAVE)
        offset = RIFF_HEADER
        while (offset + CHUNK_HEADER <= wav.size) {
            val id = ascii(wav, offset)
            val size = u32(wav, offset + 4)
            out.write(wav, offset, CHUNK_HEADER)
            if (id == DATA) {
                // `dataSize` is a whole number of even-sized frames, so this chunk needs no pad byte.
                out.write(scaled(wav, dataOffset, dataSize, factor))
            } else {
                out.write(wav, offset + CHUNK_HEADER, size)
                if (size and 1 == 1) out.write(0)   // a RIFF chunk is word-aligned
            }
            offset += CHUNK_HEADER + size + (size and 1)
        }

        val bytes = out.toByteArray()
        val riffSize = bytes.size - CHUNK_HEADER
        bytes[4] = riffSize.toByte()
        bytes[5] = (riffSize ushr 8).toByte()
        bytes[6] = (riffSize ushr 16).toByte()
        bytes[7] = (riffSize ushr 24).toByte()
        return bytes
    }

    private fun scaled(wav: ByteArray, offset: Int, size: Int, factor: Float): ByteArray {
        val out = ByteArray(size)
        var i = 0
        while (i < size) {
            val low = wav[offset + i].toInt() and 0xFF
            val high = wav[offset + i + 1].toInt() shl 8
            val sample = (low or high).toShort().toInt()
            val value = Math.round(sample * factor).coerceIn(MIN_SAMPLE, MAX_SAMPLE)
            out[i] = value.toByte()
            out[i + 1] = (value ushr 8).toByte()
            i += 2
        }
        return out
    }

    private fun ascii(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private class Format(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
    )
}
