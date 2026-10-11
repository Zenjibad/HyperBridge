package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The bounds a cooldown option is held to, and the one place they are applied.
 *
 * They exist for the reason `TuningValues`' do, and for one measured reason of their own:
 * `attenuationFactor` reached `EngineConfig.politeVolume` **unclamped**, so a stored
 * `attenuation_factor=-1` became a negative vibration amplitude. This ROM's vibrator rejects that
 * with an `IllegalArgumentException` — the crash
 * `docs/superpowers/decisions/2026-10-09-vibration-illegalargument-root-cause.md` attributes to a
 * *different* writer, which is exactly why the arithmetic that produces it matters here: this app
 * must not be able to produce it either. A negative timeout is the same class of hazard pointed at
 * the safety net instead of at the vibrator: the plan's relaxation deadline is derived from it.
 */
class CooldownOptionBoundsTest {

    private val base = EngineConfig()

    @Test
    fun `a factor outside the range never reaches the engine`() {
        assertEquals(
            base.politeVolume,
            CooldownOptions(attenuationFactor = -0.5f).effectiveConfig(base).politeVolume,
        )
        assertEquals(
            base.politeVolume,
            CooldownOptions(attenuationFactor = 1.5f).effectiveConfig(base).politeVolume,
        )
        assertEquals(
            base.politeVolume,
            CooldownOptions(attenuationFactor = Float.NaN).effectiveConfig(base).politeVolume,
        )
    }

    @Test
    fun `a usable factor and the two ends of the range are taken as given`() {
        // 0.0 is not refused: it is exactly what `SoundOption.SILENT` installs for POLITE, so a stored
        // factor of zero expresses a silence the engine already understands.
        assertEquals(0.0f, CooldownOptions(attenuationFactor = 0.0f).effectiveConfig(base).politeVolume)
        assertEquals(0.75f, CooldownOptions(attenuationFactor = 0.75f).effectiveConfig(base).politeVolume)
        assertEquals(1.0f, CooldownOptions(attenuationFactor = 1.0f).effectiveConfig(base).politeVolume)
    }

    @Test
    fun `a timeout that cannot be a window never reaches the engine`() {
        assertEquals(
            base.timeoutPoliteMs,
            CooldownOptions(timeoutPoliteMs = 0L).effectiveConfig(base).timeoutPoliteMs,
        )
        assertEquals(
            base.timeoutPoliteMs,
            CooldownOptions(timeoutPoliteMs = -1_000L).effectiveConfig(base).timeoutPoliteMs,
        )
        assertEquals(
            base.timeoutMutedMs,
            CooldownOptions(timeoutMutedMs = Long.MIN_VALUE).effectiveConfig(base).timeoutMutedMs,
        )
        assertEquals(30_000L, CooldownOptions(timeoutPoliteMs = 30_000L).effectiveConfig(base).timeoutPoliteMs)
    }

    @Test
    fun `a counter that cannot reset anything never reaches the engine`() {
        assertEquals(base.counterReset, CooldownOptions(counterReset = 0).effectiveConfig(base).counterReset)
        assertEquals(base.counterReset, CooldownOptions(counterReset = -4).effectiveConfig(base).counterReset)
        assertEquals(7, CooldownOptions(counterReset = 7).effectiveConfig(base).counterReset)
    }

    @Test
    fun `the silence option still wins over the factor, because it is not a factor`() {
        // `SoundOption.SILENT` is realised as a zero POLITE volume, and that is a decision the sound
        // row makes -- not a factor the bounds have any business refusing.
        assertEquals(
            0.0f,
            CooldownOptions(soundPolite = SoundOption.SILENT, attenuationFactor = 5.0f)
                .effectiveConfig(base).politeVolume,
        )
    }

    @Test
    fun `an out-of-range value is named, with its range, rather than silently clamped`() {
        assertNull(CooldownOptionBounds.timeoutProblem(60_000L))
        assertNotNull(CooldownOptionBounds.timeoutProblem(999L))
        assertNotNull(CooldownOptionBounds.timeoutProblem(3_600_001L))
        assertNull(CooldownOptionBounds.counterProblem(10))
        assertNotNull(CooldownOptionBounds.counterProblem(101))
        assertNull(CooldownOptionBounds.attenuationProblem(0.3f))
        assertNotNull(CooldownOptionBounds.attenuationProblem(-0.1f))
    }

    @Test
    fun `the bounds say what a value means`() {
        assertEquals("10 seconds", CooldownOptionBounds.describeMs(10_000L))
        assertEquals("1 minute", CooldownOptionBounds.describeMs(60_000L))
        assertEquals("1 minute 30 seconds", CooldownOptionBounds.describeMs(90_000L))
        assertEquals("1 hour", CooldownOptionBounds.describeMs(3_600_000L))
        assertEquals("30 %", CooldownOptionBounds.describeFactor(0.3f))
        assertEquals("100 %", CooldownOptionBounds.describeFactor(1.0f))
    }

    @Test
    fun `the sequencing summary is a description of the two windows, not a third setting`() {
        // One sentence, derived from the numbers the user actually has, so it cannot drift from them.
        assertEquals(
            "the 2nd alert within 1 minute plays at 30 % · the 3rd within 10 seconds is silent",
            CooldownOptionBounds.describeSequence(
                timeoutPoliteMs = 60_000L,
                timeoutMutedMs = 10_000L,
                factor = 0.3f,
            ),
        )
    }
}
