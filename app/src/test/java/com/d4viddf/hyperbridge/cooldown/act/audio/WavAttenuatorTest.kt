package com.d4viddf.hyperbridge.cooldown.act.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [WavAttenuator]: what it decodes, what it refuses, and the arithmetic it applies.
 *
 * The fixture is written here rather than read from a file, because the attenuator's whole job *is*
 * the byte layout: `wav()` emits the canonical 44-byte header the spike's own tone used (44 100 Hz,
 * mono, 16-bit PCM), and `samples()` reads a WAV back exactly the way the attenuator writes one.
 *
 * No Android class is touched at runtime — that is what makes this test runnable in `:app`, whose
 * JVM tests run against `android.jar`'s `Stub!` thunks.
 */
class WavAttenuatorTest {

    @Test
    fun `a 16 bit pcm wav round-trips at 30 percent with its length rate and channels intact`() {
        val source = wav(intArrayOf(0, 10000, -10000, 32767, -32768))

        val copy = requireNotNull(WavAttenuator.attenuate(source, 0.30f)) {
            "the attenuator refused a well-formed 16-bit PCM WAV"
        }

        assertEquals(source.size, copy.size)
        assertArrayEquals("RIFF".toByteArray(Charsets.US_ASCII), copy.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(Charsets.US_ASCII), copy.copyOfRange(8, 12))
        assertEquals(copy.size - 8, u32(copy, 4))       // the RIFF size is recomputed, not copied
        assertEquals(44100, u32(copy, 24))              // sample rate
        assertEquals(1, u16(copy, 22))                  // channels
        assertEquals(16, u16(copy, 34))                 // bits per sample
        assertEquals(copy.size - 44, u32(copy, 40))     // data chunk size
        assertArrayEquals(intArrayOf(0, 3000, -3000, 9830, -9830), samples(copy))
    }

    @Test
    fun `the copy's peak is 30 percent of the source peak`() {
        // 32767 * 0.30 = 9830.1 -> 9830, and 9830 / 32768 = 0.29999.
        val source = wav(IntArray(128) { if (it % 2 == 0) 32767 else -32768 })

        val copy = requireNotNull(WavAttenuator.attenuate(source, 0.30f)) { "no copy" }

        val peak = samples(copy).maxOf { Math.abs(it) }
        assertEquals(0.30, peak / 32768.0, 0.001)
    }

    @Test
    fun `bytes that are not a 16 bit pcm wav are refused`() {
        assertNull(WavAttenuator.attenuate(ByteArray(0), 0.30f))
        assertNull(WavAttenuator.attenuate(ByteArray(64) { 0x7F }, 0.30f))
        // A RIFF/WAVE header with no `data` chunk at all.
        assertNull(WavAttenuator.attenuate(wav(intArrayOf(1, 2, 3)).copyOfRange(0, 36), 0.30f))
        // A `data` chunk whose declared size runs past the end of the array.
        assertNull(WavAttenuator.attenuate(wav(intArrayOf(1, 2, 3)).also { it[40] = 0xFF.toByte() }, 0.30f))
    }

    @Test
    fun `8 bit pcm is refused`() {
        val eightBit = wav(samples = intArrayOf(), bitsPerSample = 8, data = ByteArray(16) { 0x40 })

        assertNull(WavAttenuator.attenuate(eightBit, 0.30f))
    }

    @Test
    fun `float pcm is refused`() {
        val float = wav(samples = intArrayOf(), audioFormat = 3, bitsPerSample = 32, data = ByteArray(64))

        assertNull(WavAttenuator.attenuate(float, 0.30f))
    }

    @Test
    fun `scaling clamps at both 16 bit extremes instead of wrapping`() {
        // A factor above 1 is not something POLITE uses: it is what makes the clamp observable. The
        // point is that no sample can wrap to the opposite sign and click, which is why the result is
        // clamped rather than truncated or allowed to overflow.
        val source = wav(intArrayOf(32767, -32768, 20000, -20000))

        val copy = requireNotNull(WavAttenuator.attenuate(source, 2.0f)) { "no copy" }

        assertArrayEquals(intArrayOf(32767, -32768, 32767, -32768), samples(copy))
    }

    @Test
    fun `a factor that is not a positive finite number is refused`() {
        val source = wav(intArrayOf(1000))

        assertNull(WavAttenuator.attenuate(source, 0f))
        assertNull(WavAttenuator.attenuate(source, -0.30f))
        assertNull(WavAttenuator.attenuate(source, Float.NaN))
        assertNull(WavAttenuator.attenuate(source, Float.POSITIVE_INFINITY))
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** The canonical 44-byte RIFF/WAVE header, then a 16-bit little-endian `data` chunk. */
    private fun wav(
        samples: IntArray,
        audioFormat: Int = 1,
        channels: Int = 1,
        sampleRate: Int = 44100,
        bitsPerSample: Int = 16,
        data: ByteArray? = null,
    ): ByteArray {
        val payload = data ?: ByteArray(samples.size * 2).also { out ->
            var i = 0
            for (sample in samples) {
                out[i++] = sample.toByte()
                out[i++] = (sample shr 8).toByte()
            }
        }
        val blockAlign = channels * bitsPerSample / 8
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
        i16(audioFormat)
        i16(channels)
        i32(sampleRate)
        i32(sampleRate * blockAlign)
        i16(blockAlign)
        i16(bitsPerSample)
        ascii("data")
        i32(payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    private fun samples(wav: ByteArray): IntArray =
        IntArray((wav.size - 44) / 2) { i -> u16(wav, 44 + i * 2).toShort().toInt() }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
