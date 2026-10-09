package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditFactoryTest {

    private val config = EngineConfig()
    private val original = snapshot()

    private fun edit(state: CooldownState, source: PoliteSoundSource = NoPoliteSound) =
        EditFactory.editFor(original, state, config, source)

    @Test
    fun `DEFAULT replays the snapshot field for field`() {
        assertEquals(original.fields, edit(CooldownState.DEFAULT).fields)
        assertTrue(edit(CooldownState.DEFAULT).gaps.isEmpty())
    }

    @Test
    fun `POLITE silences the sound, records the gap, and scales the vibration`() {
        val result = edit(CooldownState.POLITE)
        val fields = result.fields
        assertNull(fields.soundUri)
        assertEquals(listOf(0L, 77L, 200L, 77L), fields.vibrationPattern)
        assertTrue(fields.vibrationEnabled)
        // With no sound there are no audio attributes: the platform drops them together with the
        // sound, and carrying the snapshot's values here would make the actuator's idempotence
        // comparison fail on every post.
        assertEquals(0, fields.audioUsage)
        assertEquals(setOf(FidelityGap.POLITE_SOUND_UNAVAILABLE), result.gaps)
    }

    @Test
    fun `MUTED silences the sound, the pattern, the effect, and the vibration flag`() {
        val fields = edit(CooldownState.MUTED).fields
        assertNull(fields.soundUri)
        assertNull(fields.vibrationPattern)
        assertNull(fields.vibrationEffectBytes)
        // Load-bearing: `NotificationRecord.getVibrationForChannel` returns null only when the
        // channel does not vibrate, and otherwise falls through effect -> pattern ->
        // `VibratorHelper.createDefaultVibration()`. Clearing the flag is what makes MUTED silent;
        // without it the channel keeps buzzing at full strength.
        assertFalse(fields.vibrationEnabled)
    }

    @Test
    fun `no state ever changes importance`() {
        // Spec §5.4 and the Q4 decision: AOSP's MUTED is not an importance change, and the heads-up
        // must stay as interruptive as AOSP leaves it.
        for (state in CooldownState.entries) {
            assertEquals(original.fields.importance, edit(state).fields.importance, "state $state")
        }
    }

    @Test
    fun `no state ever changes the channel id`() {
        for (state in CooldownState.entries) {
            assertEquals(original.fields.channelId, edit(state).fields.channelId, "state $state")
        }
    }

    @Test
    fun `a channel with no sound does not report a sound gap`() {
        val silent = snapshot(fields = channelFields(soundUri = null, vibrationEnabled = false, vibrationPattern = null))
        val result = EditFactory.editFor(silent, CooldownState.POLITE, config, NoPoliteSound)
        assertTrue(result.gaps.isEmpty(), "nothing was lost: the channel had no sound to attenuate")
    }

    @Test
    fun `POLITE reports the vibration gap when there is no pattern to scale`() {
        val noPattern = snapshot(fields = channelFields(vibrationPattern = null, vibrationEnabled = true))
        val result = EditFactory.editFor(noPattern, CooldownState.POLITE, config, NoPoliteSound)
        assertEquals(setOf(FidelityGap.POLITE_SOUND_UNAVAILABLE, FidelityGap.POLITE_VIBRATION_UNSCALABLE), result.gaps)
    }

    @Test
    fun `POLITE scales the pattern the ROM derived its effect from`() {
        // The shape this ROM produces for every pattern-configured channel: with
        // `Flags.notificationChannelVibrationEffectApi()` on, `setVibrationPattern` also sets
        // `mVibrationEffect = VibrationEffect.createWaveform(pattern, -1)`
        // (`research/aosp/NotificationChannel.java:728-731`), so the snapshot carries both. That effect
        // is the pattern in disguise -- its amplitudes are unreadable through public API -- so the
        // pattern is what gets scaled, and the effect is dropped rather than replayed at full strength.
        // Replaying it is the defect measured on-device 2026-10-09: POLITE ended at
        // `mVibrationPattern=[0,255,200,255]` with an effect byte-identical to DEFAULT.
        val derived = snapshot(
            fields = channelFields(
                vibrationPattern = listOf(0L, 255L, 200L, 255L),
                vibrationEffectBytes = "AwQF",
            ),
        )
        val result = EditFactory.editFor(derived, CooldownState.POLITE, config, NoPoliteSound)
        assertEquals(listOf(0L, 77L, 200L, 77L), result.fields.vibrationPattern)
        assertNull(result.fields.vibrationEffectBytes)
        assertFalse(FidelityGap.POLITE_VIBRATION_UNSCALABLE in result.gaps)
    }

    @Test
    fun `POLITE carries a genuinely opaque effect through and says so`() {
        // An effect with no pattern beside it. AOSP's `setVibrationEffect` stores no equivalent pattern
        // for a one-shot, prebaked or composed effect -- `computeCreateWaveformOffOnTimingsOrNull()` is
        // null and `mVibrationPattern` becomes null (`NotificationChannel.java:793-795`) -- so there is
        // nothing to scale and the effect travels unchanged, gap and all.
        val opaque = snapshot(
            fields = channelFields(vibrationPattern = null, vibrationEffectBytes = "AwQF"),
        )
        val result = EditFactory.editFor(opaque, CooldownState.POLITE, config, NoPoliteSound)
        assertEquals("AwQF", result.fields.vibrationEffectBytes)
        assertNull(result.fields.vibrationPattern)
        assertTrue(FidelityGap.POLITE_VIBRATION_UNSCALABLE in result.gaps)
    }

    @Test
    fun `POLITE uses a polite sound when one is available`() {
        val source = PoliteSoundSource { "content://media/external/audio/media/8161" }
        val result = EditFactory.editFor(original, CooldownState.POLITE, config, source)
        assertEquals("content://media/external/audio/media/8161", result.fields.soundUri)
        // With a sound to play, the app's own stream usage travels with it -- which is what Plan 3's
        // attenuated copy needs in order to be played on the notification stream at all.
        assertEquals(original.fields.audioUsage, result.fields.audioUsage)
        assertEquals(original.fields.audioContentType, result.fields.audioContentType)
        assertTrue(result.gaps.isEmpty())
    }
}
