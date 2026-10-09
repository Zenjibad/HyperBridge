package com.d4viddf.hyperbridge.cooldown.act.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The bytes behind a sound URI, or null when there are none to be had.
 *
 * A one-method seam, so that [AttenuatedSoundFactory] can be tested without Android and so that the
 * device-only ways a read fails stay *values* rather than exceptions: `politeSoundFor` is reached from
 * the listener's post path, and nothing there may throw.
 */
fun interface SoundReader {
    fun read(uri: String): ByteArray?
}

/**
 * The URIs that might hold the bytes behind a channel's sound, most specific first.
 *
 * The channel carries a *name*; the bytes live behind a URI that name may only alias. Measured on this
 * device: `com.android.shell`'s `shell_cmd` carries `content://settings/system/notification_sound`,
 * which `SettingsProvider` refuses to open as a file, and whose value is
 * `file:///system/media/audio/ui/WaterDrop_preview.ogg` — a readable Ogg. Resolving the alias is what
 * turns the common case from `POLITE_SOUND_UNAVAILABLE` into a real 30 % alert.
 *
 * A seam rather than a function call inside the reader for the same reason [SoundReader] is one: the
 * Android half ([AndroidSoundUriResolver]) needs a `Context`, and the shape-to-key mapping does not.
 */
fun interface SoundUriResolver {
    /** Candidate URIs for [uri], most specific first. Empty when there is nothing to resolve. */
    fun candidatesFor(uri: String): List<String>

    companion object {
        /** For a caller that only ever reads a URI it published itself, as the seed harness does. */
        val None: SoundUriResolver = SoundUriResolver { emptyList() }
    }
}

/**
 * Opens a converted URI with the app's own `ContentResolver`.
 *
 * It tries the URI the channel named first and then every candidate [resolver] offers, and reports which
 * one opened — the device run needs that line to say whether the settings alias was the route that
 * worked. A failure anywhere is a logged null, never a throw: the caller is on the alert path.
 *
 * The read is bounded by [SoundBudget.maxSourceBytes]. One byte past the cap is read on purpose, so the
 * caller can tell "too big" from "unreadable" without this process ever holding a file it will refuse.
 *
 * The published copy this app makes is its own MediaStore row and is always openable; a *foreign*
 * `content://media/...` ringtone may not be, because this app declares no `READ_MEDIA_AUDIO` — the
 * platform's own `SettingsProvider` names that case ("Typically, happens when process doesn't hold
 * READ_EXTERNAL_STORAGE", `research/stray/SettingsProvider.java:865-868`). That one stays a null, and
 * the wiring logs it.
 */
class ContentResolverSoundReader(
    private val context: Context,
    private val resolver: SoundUriResolver = SoundUriResolver.None,
    private val budget: SoundBudget = SoundBudget(),
) : SoundReader {

    override fun read(uri: String): ByteArray? {
        val candidates = (listOf(uri) + resolver.candidatesFor(uri)).distinct()
        for (candidate in candidates) {
            val bytes = readOne(candidate) ?: continue
            if (candidate != uri) {
                Log.i(
                    TAG,
                    "the channel sound $uri resolved to $candidate (${bytes.size} bytes)",
                )
            }
            return bytes
        }
        Log.w(
            TAG,
            "cannot read the channel sound $uri: none of ${candidates.size} candidate URI(s) opened",
        )
        return null
    }

    private fun readOne(uri: String): ByteArray? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { readAtMost(it) }
    } catch (t: Exception) {
        Log.w(TAG, "cannot read $uri: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun readAtMost(stream: InputStream): ByteArray? {
        val cap = budget.maxSourceBytes + 1
        val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < cap) {
            val read = stream.read(buffer, 0, minOf(buffer.size, cap - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        // An empty stream is no sound at all, which is a refusal rather than a copy of nothing.
        return if (total == 0) null else out.toByteArray()
    }

    private companion object {
        /**
         * The runtime's own tag, deliberately the same literal as `CooldownRuntime.TAG`: one
         * `adb logcat -s Cooldown:V` then shows the whole episode — the post, the resolution, the
         * fidelity gaps and the sound copy it published — as a single stream.
         */
        const val TAG = "Cooldown"
    }
}
