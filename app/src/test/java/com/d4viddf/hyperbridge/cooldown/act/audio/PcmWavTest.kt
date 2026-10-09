package com.d4viddf.hyperbridge.cooldown.act.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the container the decoded path reuses so that the *arithmetic* stays the one the device run
 * measured: the seed tone's 26213 peak becoming 7864 at the factory's 0.30.
 *
 * Nothing here decodes anything — `MediaCodec` cannot run on the JVM. What it proves is that a
 * compressed source, once decoded, is handed to **the same** [WavAttenuator.attenuate] as a PCM source
 * and through a container the attenuator accepts as its own subset (16-bit PCM, `wFormatTag == 1`,
 * `data` last), so "identical arithmetic" is a property of the code path rather than a promise.
 *
 * No Android class is touched at runtime, which is what makes it runnable in `:app`.
 */
class PcmWavTest {

    @Test
    fun `a canonical 44 byte header is written for 16 bit pcm`() {
        val pcm = pcm16(1, -1, 2, -2)

        val wav = requireNotNull(PcmWav.wrap(pcm, sampleRate = 44_100, channelCount = 2)) {
            "the wrapper refused plain 16-bit PCM"
        }

        assertEquals(PcmWav.HEADER_BYTES + pcm.size, wav.size)
        assertArrayEquals("RIFF".toByteArray(Charsets.US_ASCII), wav.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(Charsets.US_ASCII), wav.copyOfRange(8, 12))
        assertArrayEquals("fmt ".toByteArray(Charsets.US_ASCII), wav.copyOfRange(12, 16))
        assertArrayEquals("data".toByteArray(Charsets.US_ASCII), wav.copyOfRange(36, 40))
        assertEquals("the RIFF size", 36 + pcm.size, i32(wav, 4))
        assertEquals("subchunk1Size", 16, i32(wav, 16))
        // `wFormatTag` must be PCM: it is the field the attenuator's subset test reads.
        assertEquals("wFormatTag", 1, i16(wav, 20))
        assertEquals("numChannels", 2, i16(wav, 22))
        assertEquals("sampleRate", 44_100, i32(wav, 24))
        assertEquals("byteRate", 44_100 * 2 * 2, i32(wav, 28))
        assertEquals("blockAlign", 4, i16(wav, 32))
        assertEquals("wBitsPerSample", 16, i16(wav, 34))
        assertEquals("dataSize", pcm.size, i32(wav, 40))
        assertArrayEquals(pcm, wav.copyOfRange(PcmWav.HEADER_BYTES, wav.size))
    }

    @Test
    fun `the wrapped pcm reaches the attenuator unchanged, so the measured arithmetic holds`() {
        // The seed tone's peak, as Plan 3's device steps measure it, and the copy the device read back.
        val pcm = pcm16(26_213, 0, -26_213)

        val wav = requireNotNull(PcmWav.wrap(pcm, sampleRate = 44_100, channelCount = 1))
        val copy = requireNotNull(WavAttenuator.attenuate(wav, 0.30f)) {
            "the attenuator refused the wrapped PCM"
        }

        assertEquals("the wrap must not touch a sample", 26_213, i16(wav, PcmWav.HEADER_BYTES))
        assertEquals(7_864, i16(copy, PcmWav.HEADER_BYTES))
        assertEquals(0, i16(copy, PcmWav.HEADER_BYTES + 2))
        assertEquals(-7_864, i16(copy, PcmWav.HEADER_BYTES + 4))
        assertEquals(0.300, 7_864.0 / 26_213.0, 0.001)
    }

    @Test
    fun `something that is not a whole number of frames is refused`() {
        assertNull(PcmWav.wrap(ByteArray(3), 44_100, 1))
        assertNull(PcmWav.wrap(ByteArray(3), 44_100, 2))
        // Five bytes is a whole number of mono samples and half a stereo frame.
        assertNull(PcmWav.wrap(ByteArray(5), 44_100, 2))
    }

    @Test
    fun `an empty payload or an impossible format is refused`() {
        assertNull(PcmWav.wrap(ByteArray(0), 44_100, 1))
        assertNull(PcmWav.wrap(ByteArray(4), 0, 1))
        assertNull(PcmWav.wrap(ByteArray(4), -1, 1))
        assertNull(PcmWav.wrap(ByteArray(4), 44_100, 0))
        assertNull(PcmWav.wrap(ByteArray(4), 44_100, -2))
    }

    private fun pcm16(vararg samples: Short): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = sample.toByte()
            out[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return out
    }

    private fun i32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun i16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
}
