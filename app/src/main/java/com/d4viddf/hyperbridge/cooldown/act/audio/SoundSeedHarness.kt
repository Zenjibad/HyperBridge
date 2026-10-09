package com.d4viddf.hyperbridge.cooldown.act.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.d4viddf.hyperbridge.cooldown.act.PlatformUser
import app.hypercooldown.actuation.ChannelEdit
import app.hypercooldown.actuation.ChannelFields
import app.hypercooldown.actuation.ChannelSnapshot
import app.hypercooldown.actuation.ChannelTarget
import app.hypercooldown.actuation.ChannelTransport
import app.hypercooldown.actuation.WriteResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The device harness for the attenuated-sound path, driven over adb:
 *
 *   adb shell am broadcast -a app.hypercooldown.action.SELF_CHECK \
 *     -n app.hypercooldown/.act.SelfCheckReceiver --es action seed-sound
 *
 * It synthesises the spike's own tone (44 100 Hz, mono, 16-bit PCM, three 180 ms bursts with 8 ms
 * fades — 68 840 bytes, the exact artefact Q1 played) and then does three things, one
 * `CooldownCheck: PASS|FAIL` line each:
 *
 *  1. publishes it through the **production** [MediaStoreSoundPublisher], so the publisher is proved on
 *     the device rather than assumed;
 *  2. reads the published row back through the **production** [ContentResolverSoundReader] and checks
 *     the byte count — the one property no JVM test can cover, because it needs a real ContentResolver
 *     and a real MediaStore row;
 *  3. writes it as the target channel's sound through the transport, exactly as
 *     `SelfCheck.seedVibration` writes a pattern: a snapshot-less write, and logged as one.
 *
 * The tone's peak is 80 % of full scale (`26214`) on purpose. After the factory has scaled it, the
 * copy's peak must read 30 % of that, which the last device step measures with `adb pull` — so the
 * claim "POLITE is the channel's own sound at 30 %" is checked as *bytes*, not inferred from the log.
 *
 * Plan 3's status UI replaces this, as it replaces [app.hypercooldown.act.SelfCheck].
 */
class SoundSeedHarness(context: Context) {

    private val appContext: Context = context.applicationContext
    private val reader = ContentResolverSoundReader(appContext)
    private val publisher = MediaStoreSoundPublisher(appContext)

    fun seedSound(transport: ChannelTransport, packageName: String, channelId: String): List<String> {
        val tone = toneWav()
        val published = publisher.publish(SEED_NAME, tone)
            ?: return listOf("FAIL could not publish the seed tone to MediaStore")

        val lines = mutableListOf(
            check("publish the seed tone through MediaStoreSoundPublisher", true) {
                "${tone.size} bytes -> $published"
            },
        )

        val readBack = reader.read(published)
        lines += check("read the seed back through ContentResolverSoundReader", readBack?.size == tone.size) {
            "expected ${tone.size} bytes, read ${readBack?.size ?: "null"}"
        }

        val uid = appContext.packageManager.getApplicationInfo(packageName, 0).uid
        val target = ChannelTarget.perApp(PlatformUser.id(), packageName, uid)
        val fields = ChannelFields(
            channelId = channelId,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            soundUri = published,
            audioUsage = AudioAttributes.USAGE_NOTIFICATION,
            audioContentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
            audioFlags = FLAG_MUTE_HAPTIC,
            vibrationPattern = VIBRATION_PATTERN,
            vibrationEffectBytes = null,
            vibrationEnabled = true,
        )
        Log.w(TAG, "snapshot-less write to $packageName/$channelId: the seed is not the pristine state")
        val result = transport.write(
            target,
            ChannelSnapshot(fields = fields, channelBytes = null),
            ChannelEdit(fields = fields),
        )
        lines += check("seed sound on $packageName/$channelId", result == WriteResult.Ok) {
            result.toString()
        }
        return lines
    }

    /** The spike's tone, byte for byte: the file Q1 proved SystemUI's `RingtonePlayer` will play. */
    private fun toneWav(): ByteArray {
        val toneSamples = SAMPLE_RATE * TONE_MS / 1000
        val gapSamples = SAMPLE_RATE * GAP_MS / 1000
        val totalSamples = BURSTS * toneSamples + (BURSTS - 1) * gapSamples
        val fade = SAMPLE_RATE * FADE_MS / 1000 // an 8 ms edge at each end avoids a click

        val pcm = ShortArray(totalSamples)
        var idx = 0
        for (burst in 0 until BURSTS) {
            for (i in 0 until toneSamples) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i >= toneSamples - fade -> (toneSamples - i).toDouble() / fade
                    else -> 1.0
                }
                pcm[idx++] = (PEAK * env * sin(2.0 * PI * FREQ * i / SAMPLE_RATE)).toInt().toShort()
            }
            if (burst < BURSTS - 1) idx += gapSamples // leave silence
        }

        val dataSize = totalSamples * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                       // fmt chunk size
        buf.putShort(1.toShort())            // PCM
        buf.putShort(1.toShort())            // mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)          // byte rate = sr * channels * bytesPerSample
        buf.putShort(2.toShort())            // block align
        buf.putShort(16.toShort())           // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (sample in pcm) buf.putShort(sample)
        Log.i(TAG, "seed tone: ${44 + dataSize} bytes, peak=$PEAK, $totalSamples samples")
        return buf.array()
    }

    private fun check(name: String, ok: Boolean, detail: () -> String = { "" }): String {
        val extra = detail()
        val line = "${if (ok) "PASS" else "FAIL"} $name" + if (extra.isEmpty()) "" else " -- $extra"
        Log.i(TAG, line)
        return line
    }

    private companion object {
        /** The harness's own tag, the same literal as `SelfCheck.TAG`: one filter shows the report. */
        const val TAG = "CooldownCheck"

        const val SEED_NAME = "hypercooldown-seed-tone.wav"
        const val SAMPLE_RATE = 44100
        const val TONE_MS = 180
        const val GAP_MS = 120
        const val FADE_MS = 8
        const val BURSTS = 3

        /** 80 % of 32767, so that the 30 % copy is measurable by `adb pull` on the device. */
        const val PEAK = 26214.0

        const val FREQ = 440.0

        /**
         * `AudioAttributes.FLAG_MUTE_HAPTIC`, which is `0x800` and `@hide`, so `compileSdk 36`'s public
         * `android.jar` does not carry it and naming it does not compile. The value is the one this
         * ROM's `dumpsys` prints for the shell channel, and it is written so the seeded channel is
         * reproduced field for field.
         */
        const val FLAG_MUTE_HAPTIC = 0x800

        val VIBRATION_PATTERN = listOf(0L, 255L, 200L, 255L)
    }
}
