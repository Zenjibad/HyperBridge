package com.d4viddf.hyperbridge.cooldown.act.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/**
 * Where an attenuated copy is put, so that a privileged, non-owning process can open it at alert time.
 *
 * One method and one production implementation, because the choice of store is a device question
 * rather than a design one: spec §5.5 names MediaStore (a `FileProvider` grant "may not survive long
 * enough"), and Q1 proved the result on this phone.
 */
interface SoundCopyPublisher {
    /** Publishes [wav] under [displayName]; returns the URI to install, or null on any failure. */
    fun publish(displayName: String, wav: ByteArray): String?
}

/**
 * Q1's verified recipe, transcribed from the spike that proved it
 * (`spike/src/main/java/app/hypercooldown/spike/MainActivity.kt`, `publishToMediaStore`): insert into
 * `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with `IS_NOTIFICATION=1`, `IS_MUSIC=0`,
 * `IS_RINGTONE=0`, `IS_ALARM=0`, `RELATIVE_PATH=Environment.DIRECTORY_NOTIFICATIONS` and
 * `MIME_TYPE=audio/wav`, then write the bytes into the returned row.
 *
 * No storage permission is needed for this at `minSdk 31`, because the app writes only the rows it
 * just created — the installed `:spike` declares no storage permission either, and that is the
 * manifest whose run published the row SystemUI played.
 *
 * Every failure deletes the row it just created, so a half-written file is never left where the
 * platform could pick it up as a notification sound, and returns null: a sound we cannot publish is a
 * sound we do not install.
 */
class MediaStoreSoundPublisher(private val context: Context) : SoundCopyPublisher {

    override fun publish(displayName: String, wav: ByteArray): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_WAV)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_RINGTONE, 0)
            put(MediaStore.Audio.Media.IS_ALARM, 0)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_NOTIFICATIONS)
        }
        val uri: Uri = try {
            resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        } catch (t: Exception) {
            Log.w(TAG, "MediaStore refused a row for $displayName: ${t.javaClass.simpleName}")
            return null
        }
        return try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                Log.e(TAG, "no output stream for $uri")
                resolver.delete(uri, null, null)
                return null
            }
            stream.use { out ->
                out.write(wav)
                out.flush()
            }
            Log.i(TAG, "published $displayName (${wav.size} bytes) -> $uri")
            uri.toString()
        } catch (t: Exception) {
            Log.e(TAG, "could not write the copy to $uri: ${t.javaClass.simpleName}: ${t.message}")
            resolver.delete(uri, null, null)
            null
        }
    }

    private companion object {
        const val MIME_WAV = "audio/wav"

        /** See `ContentResolverSoundReader.TAG`. */
        const val TAG = "Cooldown"
    }
}
