package com.d4viddf.hyperbridge.cooldown.ui

import app.hypercooldown.actuation.CooldownOptions
import app.hypercooldown.actuation.HeadsUpOption
import app.hypercooldown.actuation.SoundOption
import app.hypercooldown.actuation.VibrationOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's switches against an option set nobody has touched.
 *
 * This is the pin for the reported defect. "Make a repeating alert silent" and "stop the vibration"
 * say "On by default" in their own copy, and the realisation does it (`EditFactory` writes no sound
 * and clears the vibration flag for MUTED), but both were *drawn* off — the switch compared the raw
 * nullable field (`options.soundMuted == SILENT`) and an unset field is `null`, not `SILENT`.
 */
class CooldownSwitchesTest {

    private val untouched = CooldownOptions()

    @Test
    fun `the master row is on before anybody has chosen anything`() {
        assertTrue(CooldownSwitches.enabled(untouched))
        assertFalse(CooldownSwitches.enabled(untouched.copy(enabled = false)))
    }

    @Test
    fun `the rows whose copy says "On by default" are on`() {
        assertTrue(CooldownSwitches.mutedSilentSound(untouched))
        assertTrue(CooldownSwitches.mutedVibrationOff(untouched))
        assertTrue(CooldownSwitches.mutedHeadsUpSuppressed(untouched))
    }

    @Test
    fun `the rows that keep AOSP's treatment do not claim the deviation they write`() {
        assertFalse(CooldownSwitches.politeSilentSound(untouched))
        assertFalse(CooldownSwitches.politeVibrationOff(untouched))
        assertFalse(CooldownSwitches.politeHeadsUpSuppressed(untouched))
        assertFalse(CooldownSwitches.politeIslandSuppressed(untouched))
        assertFalse(CooldownSwitches.mutedIslandSuppressed(untouched))
    }

    @Test
    fun `an explicit choice still wins over the default in either direction`() {
        val off = untouched.copy(
            soundMuted = SoundOption.INHERIT,
            vibrationMuted = VibrationOption.INHERIT,
            headsUpMuted = HeadsUpOption.INHERIT,
        )
        assertFalse(CooldownSwitches.mutedSilentSound(off))
        assertFalse(CooldownSwitches.mutedVibrationOff(off))
        assertFalse(CooldownSwitches.mutedHeadsUpSuppressed(off))
        assertTrue(CooldownSwitches.politeSilentSound(untouched.copy(soundPolite = SoundOption.SILENT)))
    }
}
