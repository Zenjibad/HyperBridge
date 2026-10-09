package com.d4viddf.hyperbridge.cooldown.act.audio

/**
 * How much sound one POLITE copy may be built from. Spec §6's "bounded resources", made explicit.
 *
 * The listener process lives indefinitely and the sound path runs on the alert path, so neither the
 * compressed source nor the decoded PCM may be allowed to grow with whatever the user picked: a
 * 20-minute podcast set as a notification sound would otherwise be read, decoded, scaled, written to
 * MediaStore and held in memory at once, per distinct sound.
 *
 * [maxSourceBytes] bounds the read; [maxPcmBytes] bounds the decode, and the decoder stops early rather
 * than trimming afterwards. Three mebibytes of PCM is about 15 seconds of 48 kHz stereo, which is
 * longer than any notification sound and still small enough to scale and publish in place.
 *
 * The values are defaults rather than platform facts, and they are values rather than constants so a
 * test can drive the bound at a size it can build.
 */
data class SoundBudget(
    val maxSourceBytes: Int = DEFAULT_MAX_SOURCE_BYTES,
    val maxPcmBytes: Int = DEFAULT_MAX_PCM_BYTES,
) {
    companion object {
        /** 8 MiB: an order of magnitude over the 18 454-byte Ogg this device's default sound is. */
        const val DEFAULT_MAX_SOURCE_BYTES = 8 * 1024 * 1024

        /** 3 MiB: ~15 s of 48 kHz stereo 16-bit. */
        const val DEFAULT_MAX_PCM_BYTES = 3 * 1024 * 1024
    }
}
