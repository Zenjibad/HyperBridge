package app.hypercooldown.actuation

import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CooldownOptionsTest {

    @Test
    fun `app beats global beats default`() {
        val global = CooldownOptions(enabled = true, timeoutPoliteMs = 30_000L, soundMuted = SoundOption.SILENT)
        val app = CooldownOptions(timeoutPoliteMs = 45_000L)
        val merged = app.mergeWith(global)
        assertEquals(true, merged.enabled)
        assertEquals(45_000L, merged.timeoutPoliteMs)
        assertEquals(SoundOption.SILENT, merged.soundMuted)
    }

    @Test
    fun `a field nobody set stays null, which is what inherit means`() {
        val merged = CooldownOptions().mergeWith(CooldownOptions())
        assertEquals(null, merged.soundPolite)
        assertEquals(null, merged.islandMuted)
        assertEquals(null, merged.attenuationFactor)
    }

    @Test
    fun `the overridden sentinel is the enable field alone`() {
        // The host's own idiom: IslandSettingsControl decides "is this app overridden at all" from
        // `config.isFloat != null` alone (ui/components/IslandSettingsControl.kt:43). Cooldown's
        // sentinel is `enabled`, and touching any other field must NOT mark the app overridden.
        assertFalse(CooldownOptions().isOverridden)
        assertFalse(CooldownOptions(soundPolite = SoundOption.SILENT).isOverridden)
        assertTrue(CooldownOptions(enabled = false).isOverridden)
    }

    @Test
    fun `the defaults are AOSP's production values`() {
        val config = CooldownOptions().effectiveConfig(EngineConfig())
        assertEquals(60_000L, config.timeoutPoliteMs)
        assertEquals(10_000L, config.timeoutMutedMs)
        assertEquals(10, config.counterReset)
        assertEquals(0.30f, config.politeVolume)
    }

    @Test
    fun `a set with T2 greater than T1 is a longer POLITE phase, not an error`() {
        val config = CooldownOptions(timeoutPoliteMs = 20_000L, timeoutMutedMs = 60_000L)
            .effectiveConfig(EngineConfig())
        assertEquals(20_000L, config.timeoutPoliteMs)
        assertEquals(60_000L, config.timeoutMutedMs)
    }

    @Test
    fun `an attenuation factor below the UI floor is not clamped by the engine`() {
        // Spec 4.4: the floor is a soft UI warning, not a clamp, because :engine's politeVolume is a
        // plain value and clamping it there would be a change to a frozen module for a UI concern.
        val config = CooldownOptions(attenuationFactor = 0.05f).effectiveConfig(EngineConfig())
        assertEquals(0.05f, config.politeVolume)
    }

    @Test
    fun `the mute volume is zero for MUTED and the factor is only the POLITE volume`() {
        val config = CooldownOptions(attenuationFactor = 0.4f).effectiveConfig(EngineConfig())
        assertEquals(0.4f, config.politeVolume)
        assertEquals(0.0f, config.mutedVolume)
    }
}
