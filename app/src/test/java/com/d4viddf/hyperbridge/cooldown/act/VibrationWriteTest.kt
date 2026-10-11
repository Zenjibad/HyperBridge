package com.d4viddf.hyperbridge.cooldown.act

import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.EditFactory
import app.hypercooldown.actuation.FidelityGap
import app.hypercooldown.actuation.PoliteSoundSource
import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two halves of the vibration write: which single representation a channel carries
 * ([vibrationWriteFor]) and the order the platform calls are made in ([applyVibration]).
 *
 * **The limit of this test, stated honestly.** `NotificationChannel` is an Android class and
 * `android.jar`'s method bodies are `Stub!` thunks in a plain JVM unit test, so no assertion here
 * can read a real channel's `mVibrationPattern`. [AospVibrationChannel] is a transcription of the
 * three AOSP setters this app calls (`research/aosp/NotificationChannel.java:706-727, 771-797`),
 * and its fidelity is not assumed: the negative control
 * `the old order writes the pattern and then loses it` replays the shipped setter order against it
 * and asserts the exact signature the device measured on 2026-10-09
 * (`mVibrationEnabled=true, mVibrationPattern=null, mVibrationEffect=null`). Because that
 * transcription is discriminating, `POLITE's scaled pattern survives the whole write` fails if
 * anyone reintroduces the defect class. That is not an assertion — it was checked:
 * `applyVibration` was temporarily rewritten to the shipped order, the suite was run, and exactly
 * that one test failed (`9 tests completed, 1 failed`); it then passed again once the order was
 * restored. The test still cannot prove what the platform does; the verification for that is the
 * on-device dump in Plan 2's Task 15 recipe, re-run after installing this build.
 */
class VibrationWriteTest {

    // --- Which single representation a state carries -------------------------------------------

    @Test
    fun `a channel with no payload clears rather than inventing one`() {
        assertEquals(VibrationWrite.Clear, vibrationWriteFor(fields()))
    }

    @Test
    fun `an effect wins when the channel carries a pattern beside it`() {
        val result = vibrationWriteFor(fields(pattern = listOf(0L, 90L), effectBytes = EFFECT))

        assertEquals(VibrationWrite.Effect(EFFECT), result)
    }

    @Test
    fun `POLITE's scaled pattern is the representation, not a cleared effect`() {
        val scaled = listOf(0L, 90L, 250L, 90L)

        val result = vibrationWriteFor(fields(pattern = scaled, enabled = true))

        assertEquals(VibrationWrite.Pattern(scaled), result)
    }

    @Test
    fun `an empty pattern is not a payload`() {
        assertEquals(VibrationWrite.Clear, vibrationWriteFor(fields(pattern = emptyList())))
    }

    // --- The order the platform calls are made in ----------------------------------------------

    @Test
    fun `POLITE's scaled pattern survives the whole write`() {
        val scaled = listOf(0L, 90L, 250L, 90L)
        val politeness = fields(pattern = scaled, effectBytes = null, enabled = true)
        val channel = AospVibrationChannel()

        applyVibration(channel, vibrationWriteFor(politeness), politeness.vibrationEnabled)

        assertEquals(scaled, channel.pattern?.toList())
        assertNull(channel.effectBytes)
        assertTrue(channel.enabled)
    }

    @Test
    fun `MUTED clears the effect, the pattern and the flag`() {
        val muted = fields(pattern = null, effectBytes = null, enabled = false)
        val channel = AospVibrationChannel().apply {
            setPattern(longArrayOf(0L, 90L, 250L, 90L))
            setEnabled(true)
        }

        applyVibration(channel, vibrationWriteFor(muted), muted.vibrationEnabled)

        assertNull(channel.effectBytes)
        assertNull(channel.pattern)
        assertFalse(channel.enabled)
    }

    @Test
    fun `a restored effect is written once and kept`() {
        val restored = fields(effectBytes = EFFECT, enabled = true)
        val channel = AospVibrationChannel()

        applyVibration(channel, vibrationWriteFor(restored), restored.vibrationEnabled)

        assertEquals(EFFECT, channel.effectBytes)
        assertTrue(channel.enabled)
    }

    @Test
    fun `a vibrate-enabled channel with no payload keeps the flag and no payload`() {
        val defaultVibration = fields(pattern = null, effectBytes = null, enabled = true)
        val channel = AospVibrationChannel()

        applyVibration(channel, vibrationWriteFor(defaultVibration), defaultVibration.vibrationEnabled)

        assertNull(channel.effectBytes)
        assertNull(channel.pattern)
        assertTrue(channel.enabled)
    }

    /**
     * The negative control: the order `AndroidChannelMapper` shipped in v0.2, replayed against the
     * same fake. If this ever stops reproducing the measured signature, the fake has drifted from
     * AOSP and every other assertion in this file is worth less.
     */
    @Test
    fun `the old order writes the pattern and then loses it`() {
        val channel = AospVibrationChannel()

        channel.setPattern(longArrayOf(0L, 90L, 250L, 90L)) // setVibrationPattern(fields.vibrationPattern)
        channel.setEffect(null) // setVibrationEffect(fields.vibrationEffectBytes)
        channel.setEnabled(true) // enableVibration(fields.vibrationEnabled)

        assertNull(channel.pattern)
        assertNull(channel.effectBytes)
        assertTrue(channel.enabled)
    }

    /**
     * The end-to-end shape of the defect measured on-device 2026-10-09, from the ROM's own snapshot to
     * the payload the channel ends up carrying.
     *
     * A ROM with `Flags.notificationChannelVibrationEffectApi()` gives every pattern-configured channel
     * an effect derived from its pattern (`research/aosp/NotificationChannel.java:728-731`), so the
     * snapshot the listener captures carries both. The old `EditFactory` could not tell that effect from
     * an opaque one, flagged `POLITE_VIBRATION_UNSCALABLE` and carried it through unscaled, and
     * [vibrationWriteFor] then let it win: the channel showed `mVibrationPattern=[0,255,200,255]` with an
     * effect byte-identical to DEFAULT. With the fix, POLITE's edit holds the scaled pattern and no
     * effect, so the write installs `0,77,200,77` through `setVibrationPattern`.
     */
    @Test
    fun `POLITE's write scales the pattern instead of replaying the ROM's derived effect`() {
        val rom = ChannelSnapshot(
            fields = fields(pattern = listOf(0L, 255L, 200L, 255L), effectBytes = EFFECT, enabled = true),
            channelBytes = "AQ==",
        )

        val edit = EditFactory.editFor(
            rom,
            CooldownState.POLITE,
            EngineConfig(),
            PoliteSoundSource { _, _ -> null },
        )

        assertEquals(listOf(0L, 77L, 200L, 77L), edit.fields.vibrationPattern)
        assertNull(edit.fields.vibrationEffectBytes)
        assertFalse(FidelityGap.POLITE_VIBRATION_UNSCALABLE in edit.gaps)

        val channel = AospVibrationChannel()
        applyVibration(channel, vibrationWriteFor(edit.fields), edit.fields.vibrationEnabled)

        assertEquals(listOf(0L, 77L, 200L, 77L), channel.pattern?.toList())
        assertNull(channel.effectBytes)
        assertTrue(channel.enabled)
    }
}

private const val EFFECT = "dmlmcmF0aW9uLWVmZmVjdA=="

private fun fields(
    pattern: List<Long>? = null,
    effectBytes: String? = null,
    enabled: Boolean = false,
) = ChannelFields(
    channelId = "shell_cmd",
    importance = 3,
    soundUri = "content://settings/system/notification_sound",
    audioUsage = 5,
    audioContentType = 4,
    audioFlags = 0,
    vibrationPattern = pattern,
    vibrationEffectBytes = effectBytes,
    vibrationEnabled = enabled,
)

/**
 * `NotificationChannel`'s vibration fields, with the three setters this app calls transcribed from
 * AOSP (`research/aosp/NotificationChannel.java`):
 *
 * ```java
 * public void enableVibration(boolean vibration) { this.mVibrationEnabled = vibration; }   // 706
 *
 * public void setVibrationPattern(long[] vibrationPattern) {                               // 720
 *     this.mVibrationEnabled = vibrationPattern != null && vibrationPattern.length > 0;
 *     this.mVibrationPattern = vibrationPattern;
 * }
 *
 * public void setVibrationEffect(@Nullable VibrationEffect effect) {                       // 771
 *     this.mVibrationEnabled = effect != null;
 *     this.mVibrationEffect = effect;
 *     if (effect != null) { ... this.mVibrationPattern = effect.computeCreateWaveformOffOnTimingsOrNull(); }
 *     else { this.mVibrationPattern = null; }                                              // 792-795
 * }
 * ```
 *
 * One deliberate simplification: the real class derives `mVibrationPattern` from a non-null
 * waveform effect, and a JVM fake cannot compute that. It records `null` instead. No assertion in
 * this file reads the pattern after a non-null effect was written, so the simplification does not
 * weaken any of them.
 */
private class AospVibrationChannel : VibrationSink {

    var effectBytes: String? = null
        private set

    var pattern: LongArray? = null
        private set

    var enabled: Boolean = false
        private set

    override fun setEffect(effectBytes: String?) {
        this.effectBytes = effectBytes
        enabled = effectBytes != null
        pattern = null
    }

    override fun setPattern(pattern: LongArray?) {
        this.pattern = pattern
        enabled = pattern != null && pattern.isNotEmpty()
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}
