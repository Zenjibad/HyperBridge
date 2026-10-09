package app.hypercooldown.actuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChannelFieldsTest {

    @Test
    fun `a vibration pattern is timing and amplitude pairs, so it has even length`() {
        // `VibrationEffect.createWaveform(long[] pattern, int repeat)` documents this shape, and
        // `VibratorHelper.createWaveformVibration` splits it exactly this way: even indices are
        // timings, odd indices are amplitudes (0 = device default).
        assertThrows(IllegalArgumentException::class.java) {
            channelFields(vibrationPattern = listOf(0L, 255L, 200L))
        }
    }

    @Test
    fun `a channel id is required`() {
        assertThrows(IllegalArgumentException::class.java) { channelFields(channelId = "") }
    }

    @Test
    fun `a snapshot without bytes is allowed, for a channel known to be at platform defaults`() {
        assertEquals(null, snapshot(channelBytes = null).channelBytes)
    }
}
