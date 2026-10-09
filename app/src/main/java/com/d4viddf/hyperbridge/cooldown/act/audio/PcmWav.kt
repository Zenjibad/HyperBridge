package com.d4viddf.hyperbridge.cooldown.act.audio

/**
 * Wraps raw 16-bit little-endian PCM in the canonical 44-byte RIFF/WAVE container that
 * [WavAttenuator] decodes.
 *
 * This is the whole of the "re-encode to WAV" half of the compressed path, and it is deliberately the
 * *only* new format code: the decoded PCM is wrapped and then handed to the very same
 * [WavAttenuator.attenuate] a PCM channel's sound goes through, so the attenuation arithmetic is shared
 * rather than reimplemented. The device run measured a seed peak of 26213 becoming 7864 — exactly
 * 0.300 — and that number cannot drift between the two paths because there is only one path.
 *
 * The header is the canonical layout, and every field is stated rather than defaulted:
 * `RIFF`/size | `WAVE` | `fmt `/16 | 1 (PCM)/channels | sample rate | byte rate | block align | 16 bits
 * | `data`/size. That is precisely the subset [WavAttenuator] accepts (`wFormatTag == 1`,
 * `wBitsPerSample == 16`, one `data` chunk last), which is what makes the hand-off work.
 *
 * A payload that is not a whole number of frames, an empty payload, or an impossible rate or channel
 * count is null rather than an exception: the caller is on the alert path and has a documented
 * degradation for a null.
 *
 * Pure Kotlin — no `android.*` — so this is a JVM test.
 */
object PcmWav {

    /** The canonical header: 44 bytes, the shape `WavAttenuator` writes and reads. */
    const val HEADER_BYTES = 44

    /** `WAVE_FORMAT_PCM`. */
    private const val WAVE_FORMAT_PCM = 1

    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    fun wrap(pcm: ByteArray, sampleRate: Int, channelCount: Int): ByteArray? {
        if (pcm.isEmpty() || sampleRate <= 0 || channelCount <= 0) return null
        val blockAlign = channelCount * BYTES_PER_SAMPLE
        if (pcm.size % blockAlign != 0) return null
        val byteRate = sampleRate.toLong() * blockAlign
        if (byteRate > Int.MAX_VALUE) return null

        val out = ByteArray(HEADER_BYTES + pcm.size)
        fun ascii(at: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(out, at)
        }
        fun i32(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
            out[at + 2] = ((value ushr 16) and 0xFF).toByte()
            out[at + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        fun i16(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        ascii(0, "RIFF")
        i32(4, 36 + pcm.size)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        i32(16, 16)
        i16(20, WAVE_FORMAT_PCM)
        i16(22, channelCount)
        i32(24, sampleRate)
        i32(28, byteRate.toInt())
        i16(32, blockAlign)
        i16(34, BITS_PER_SAMPLE)
        ascii(36, "data")
        i32(40, pcm.size)
        pcm.copyInto(out, HEADER_BYTES)
        return out
    }
}
