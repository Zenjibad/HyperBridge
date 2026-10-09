package app.hypercooldown.actuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VibrationScalerTest {

    @Test
    fun `timings are untouched and amplitudes are scaled by the polite factor`() {
        // 255 * 0.30 = 76.5, which rounds to 77.
        assertEquals(
            listOf(0L, 77L, 200L, 77L),
            VibrationScaler.scale(listOf(0L, 255L, 200L, 255L), 0.30f),
        )
    }

    @Test
    fun `the default-amplitude marker is full amplitude, not zero`() {
        // Amplitude 0 means "use the device default", and the device default is full amplitude.
        // Scaling it as a literal 0 would leave that component at full strength, so POLITE would
        // not damp it at all.
        assertEquals(
            listOf(0L, 77L, 200L, 77L),
            VibrationScaler.scale(listOf(0L, 0L, 200L, 0L), 0.30f),
        )
    }

    @Test
    fun `a scaled amplitude never rounds down to the default marker`() {
        // Otherwise a 1 would become 0, i.e. full amplitude, and POLITE would amplify it.
        assertEquals(listOf(0L, 1L), VibrationScaler.scale(listOf(0L, 1L), 0.30f))
    }

    @Test
    fun `a pattern is a non-empty even-length list`() {
        assertFalse(VibrationScaler.isScalable(null))
        assertFalse(VibrationScaler.isScalable(emptyList()))
        assertFalse(VibrationScaler.isScalable(listOf(0L, 255L, 200L)))
        assertTrue(VibrationScaler.isScalable(listOf(0L, 255L)))
        assertThrows(IllegalArgumentException::class.java) {
            VibrationScaler.scale(listOf(0L, 255L, 200L), 0.30f)
        }
    }

    @Test
    fun `a factor outside the unit interval is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            VibrationScaler.scale(listOf(0L, 255L), 0.0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VibrationScaler.scale(listOf(0L, 255L), 1.5f)
        }
    }
}
