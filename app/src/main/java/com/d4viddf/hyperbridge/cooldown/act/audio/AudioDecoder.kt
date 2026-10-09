package com.d4viddf.hyperbridge.cooldown.act.audio

/**
 * The audio a platform decoder handed back: 16-bit little-endian interleaved PCM, plus the format it
 * was decoded at.
 *
 * The format travels with the bytes because the WAV container [PcmWav] writes has to declare it, and a
 * ringtone's rate and channel count are not knowable in advance — the measured default on this device
 * is Vorbis, and a decoded stream's own format is authoritative (a decoder may also change rate at
 * run time, which is what `INFO_OUTPUT_FORMAT_CHANGED` reports).
 */
data class DecodedPcm(
    val pcm: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
)

/**
 * Turns a compressed sound into PCM, or null.
 *
 * A one-method seam for the same reason [SoundReader] is one: `MediaCodec` cannot run in `:app`'s JVM
 * tests at all (every Android class there is a `Stub!` thunk that throws), so the *decision* — decode
 * or refuse, wrap or refuse, attenuate or refuse — is driven with a fake and stays testable. The real
 * implementation is [MediaCodecAudioDecoder], and "does `MediaCodec` actually decode this phone's Ogg
 * ringtone" is a device question, named as one.
 */
fun interface AudioDecoder {
    /** 16-bit PCM for [source], or null when this process cannot decode it. Never throws. */
    fun decode(source: ByteArray): DecodedPcm?
}
