package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import app.hypercooldown.engine.EngineConfig

/**
 * Spec §5.4 as a pure function: a snapshot plus a cooldown state gives the channel content to write.
 *
 * This function decides *what the channel contains*, never *whether an alert was audible*. Spec §5.1
 * makes audibility a result of the state installed here, read back by the coordinator — not an input
 * to the decision.
 */
object EditFactory {

    fun editFor(
        snapshot: ChannelSnapshot,
        state: CooldownState,
        config: EngineConfig,
        politeSound: PoliteSoundSource,
    ): ChannelEdit {
        val gaps = mutableSetOf<FidelityGap>()
        val source = snapshot.fields

        val soundUri = when (state) {
            CooldownState.DEFAULT -> source.soundUri
            CooldownState.MUTED -> null
            CooldownState.POLITE -> {
                val copy = politeSound.politeSoundFor(snapshot)
                if (copy == null && source.soundUri != null) {
                    gaps += FidelityGap.POLITE_SOUND_UNAVAILABLE
                }
                copy
            }
        }

        // Clearing the flag, not just the two payload fields, is what makes MUTED silent:
        // `NotificationRecord.getVibrationForChannel` returns null only when `!shouldVibrate()`, and
        // otherwise falls through effect -> pattern -> `VibratorHelper.createDefaultVibration()`.
        val vibrationEnabled = when (state) {
            CooldownState.DEFAULT -> source.vibrationEnabled
            CooldownState.MUTED -> false
            CooldownState.POLITE -> source.vibrationEnabled
        }

        // POLITE's two payloads are decided together: the platform's effect for a pattern-configured
        // channel is *derived from the pattern we hold* (see `politeVibration`), so carrying it as well
        // as a scaled pattern would either be ignored or -- worse -- played at full strength.
        val polite = if (state == CooldownState.POLITE) politeVibration(source, config, gaps) else null

        val vibrationPattern = when (state) {
            CooldownState.DEFAULT -> source.vibrationPattern
            CooldownState.MUTED -> null
            CooldownState.POLITE -> polite?.pattern
        }

        val vibrationEffectBytes = when (state) {
            CooldownState.DEFAULT -> source.vibrationEffectBytes
            CooldownState.MUTED -> null
            CooldownState.POLITE -> polite?.effect
        }

        return ChannelEdit(
            fields = ChannelFields(
                channelId = source.channelId,
                // Copied, never written back differently: see ChannelFields' note on AOSP parity.
                importance = source.importance,
                soundUri = soundUri,
                // The attributes describe the sound, so a state that ends up with no sound carries
                // none of them. `setSound(null, null)` drops them on the platform side, so keeping
                // the snapshot's values here would make the actuator's "the channel already carries
                // what I want" comparison fail on every single post. When Plan 3's polite copy
                // supplies a URI, the app's own stream usage comes back with it.
                audioUsage = if (soundUri == null) 0 else source.audioUsage,
                audioContentType = if (soundUri == null) 0 else source.audioContentType,
                audioFlags = if (soundUri == null) 0 else source.audioFlags,
                vibrationPattern = vibrationPattern,
                vibrationEffectBytes = vibrationEffectBytes,
                vibrationEnabled = vibrationEnabled,
            ),
            gaps = gaps,
        )
    }

    /**
     * POLITE's vibration, decided once: the pattern to carry and the effect to carry.
     *
     * AOSP plays POLITE's vibration scaled by the same factor as its sound —
     * `mVibratorHelper.scale(effect, getSoundVolume(record))`, i.e. [EngineConfig.politeVolume] — and the
     * pattern is the only representation whose amplitude this app can read, so **the pattern wins
     * whenever there is one to scale.** That is not a preference, it is the fix for a measured defect: on
     * a ROM with `Flags.notificationChannelVibrationEffectApi()`, `setVibrationPattern` also sets
     * `mVibrationEffect = VibrationEffect.createWaveform(pattern, -1)`
     * (`research/aosp/NotificationChannel.java:728-731`), so every pattern-configured channel arrives
     * with an effect that is merely the platform's copy of that pattern. Replaying that copy was POLITE
     * buzzing at full strength: measured on 2026-10-09 the channel showed
     * `mVibrationPattern=[0,255,200,255]` and an effect byte-identical to DEFAULT, where the scaled
     * pattern of that same channel is `0,77,200,77`.
     *
     * An effect is carried only when there is no pattern to build a scaled representation from, which is
     * the genuinely opaque case. AOSP's own setters keep the two shapes distinguishable: a non-waveform
     * effect has no equivalent pattern, and `setVibrationEffect` then assigns
     * `mVibrationPattern = effect.computeCreateWaveformOffOnTimingsOrNull()` — null for a one-shot,
     * prebaked or composed effect (`NotificationChannel.java:793-795`). So an effect *beside* a pattern
     * is derived from it; an effect *without* one is opaque, and that is what
     * [FidelityGap.POLITE_VIBRATION_UNSCALABLE] is recorded for rather than pretending it was scaled.
     */
    private fun politeVibration(
        source: ChannelFields,
        config: EngineConfig,
        gaps: MutableSet<FidelityGap>,
    ): PoliteVibration {
        val pattern = source.vibrationPattern
        if (pattern == null || !VibrationScaler.isScalable(pattern)) {
            // Nothing to scale. A vibrate-enabled channel with no pattern plays the platform's own
            // default vibration, whose amplitude no public API exposes, and an empty pattern is "no
            // pattern" to AOSP: `setVibrationPattern` enables vibration only when the length is > 0.
            if (pattern != null || source.vibrationEnabled) {
                gaps += FidelityGap.POLITE_VIBRATION_UNSCALABLE
            }
            return PoliteVibration(pattern = pattern, effect = source.vibrationEffectBytes)
        }
        return PoliteVibration(
            pattern = VibrationScaler.scale(pattern, config.politeVolume),
            effect = null,
        )
    }

    /** POLITE's single vibration representation: the scaled pattern, or the opaque effect. */
    private class PoliteVibration(val pattern: List<Long>?, val effect: String?)
}
