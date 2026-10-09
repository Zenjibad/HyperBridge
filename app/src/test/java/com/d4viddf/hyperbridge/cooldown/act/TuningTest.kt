package com.d4viddf.hyperbridge.cooldown.act

import app.hypercooldown.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningTest {

    @Test
    fun `the defaults are AOSP's production values`() {
        assertEquals(60_000L, TuningValues.TIMEOUT_POLITE_MS_DEFAULT)
        assertEquals(10_000L, TuningValues.TIMEOUT_MUTED_MS_DEFAULT)
        assertEquals(0.30f, TuningValues.POLITE_VOLUME_DEFAULT, 0.0f)
        assertEquals(0.0f, TuningValues.MUTED_VOLUME_DEFAULT, 0.0f)
        assertEquals(10, TuningValues.COUNTER_RESET_DEFAULT)
        assertEquals(EngineConfig(), TuningValues().toConfig())
        assertTrue(TuningValues().isValid())
    }

    @Test
    fun `toConfig maps every field, and defaults none of them`() {
        val values = TuningValues(
            timeoutPoliteMs = 12_345L,
            timeoutMutedMs = 6_789L,
            politeVolume = 0.11f,
            mutedVolume = 0.22f,
            counterReset = 7,
        )
        assertEquals(EngineConfig(12_345L, 6_789L, 0.11f, 0.22f, 7), values.toConfig())
    }

    @Test
    fun `the time bounds accept their legal extremes and reject just outside`() {
        assertTrue(TuningValues(timeoutPoliteMs = 1_000L).isValid())
        assertTrue(TuningValues(timeoutPoliteMs = 3_600_000L).isValid())
        assertFalse(TuningValues(timeoutPoliteMs = 999L).isValid())
        assertFalse(TuningValues(timeoutPoliteMs = 3_600_001L).isValid())
        assertTrue(TuningValues(timeoutMutedMs = 1_000L).isValid())
        assertTrue(TuningValues(timeoutMutedMs = 3_600_000L).isValid())
        assertFalse(TuningValues(timeoutMutedMs = 999L).isValid())
        assertFalse(TuningValues(timeoutMutedMs = 3_600_001L).isValid())
    }

    @Test
    fun `the volume bounds accept their legal extremes and reject just outside`() {
        assertTrue(TuningValues(politeVolume = 0.0f).isValid())
        assertTrue(TuningValues(politeVolume = 1.0f).isValid())
        assertFalse(TuningValues(politeVolume = -0.01f).isValid())
        assertFalse(TuningValues(politeVolume = 1.01f).isValid())
        assertTrue(TuningValues(mutedVolume = 0.0f).isValid())
        assertTrue(TuningValues(mutedVolume = 1.0f).isValid())
        assertFalse(TuningValues(mutedVolume = -0.01f).isValid())
        assertFalse(TuningValues(mutedVolume = 1.01f).isValid())
        assertFalse(TuningValues(politeVolume = Float.NaN).isValid())
    }

    @Test
    fun `the counter-reset bounds accept their legal extremes and reject just outside`() {
        assertTrue(TuningValues(counterReset = 1).isValid())
        assertTrue(TuningValues(counterReset = 100).isValid())
        assertFalse(TuningValues(counterReset = 0).isValid())
        assertFalse(TuningValues(counterReset = 101).isValid())
        assertEquals("counterReset", TuningValues(counterReset = 0).problems().single().substringBefore(' '))
    }

    @Test
    fun `a rejected value falls back to the default, field by field`() {
        val rejected = TuningValues(
            timeoutPoliteMs = 0L,
            timeoutMutedMs = 3_600_001L,
            politeVolume = 1.5f,
            mutedVolume = -0.5f,
            counterReset = 0,
        )
        assertEquals(TuningValues(), TuningValues.sanitize(rejected))

        val legal = TuningValues(
            timeoutPoliteMs = 2_000L,
            timeoutMutedMs = 5_000L,
            politeVolume = 0.5f,
            mutedVolume = 0.1f,
            counterReset = 3,
        )
        assertEquals(legal, TuningValues.sanitize(legal))

        val oneBadField = TuningValues(
            timeoutPoliteMs = 30_000L,
            timeoutMutedMs = 0L,
            politeVolume = 0.25f,
            mutedVolume = 0.05f,
            counterReset = 4,
        )
        assertEquals(
            TuningValues(
                timeoutPoliteMs = 30_000L,
                timeoutMutedMs = TuningValues.TIMEOUT_MUTED_MS_DEFAULT,
                politeVolume = 0.25f,
                mutedVolume = 0.05f,
                counterReset = 4,
            ),
            TuningValues.sanitize(oneBadField),
        )
    }
}
