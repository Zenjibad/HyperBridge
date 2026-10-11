package com.d4viddf.hyperbridge.cooldown.act.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * The name every attenuated copy this app publishes carries — and the only provenance that outlives
 * the install that published it.
 *
 * The write-ahead record is the copy's *purpose*, and it is also the thing a reinstall destroys: on
 * 2026-10-11 `com.d4viddf.hyperbridge` was reinstalled while `ig_direct` was damped, and the record
 * that could have restored the channel died with the app's own storage (root-cause §3.1, §4). A
 * process-local registry of "copies I published" dies the same death, which is why
 * `SoundCopyCache.sourceOfCopy` cannot be the whole answer to "is this sound our own damping?".
 *
 * The published row itself can. It lives in `MediaStore`, outside the package, this app never deletes
 * it, and the name is fixed by [of] — so a fresh install, a second process, and the standalone
 * `app.hypercooldown` (which publishes under the same name) can all recognise a copy as ours by looking
 * at the row it is holding.
 *
 * What this is **not**: proof. A name is a convention, and an unrelated app could publish a row called
 * `hypercooldown-polite-…wav`; the mistake then costs a refusal to damp one channel, never a channel
 * made worse. The converse is the real limit and it is stated rather than hidden: a copy whose row was
 * deleted or renamed is no longer recognisable, and a channel carrying it will be treated as pristine
 * again. Nothing here can recover the user's original sound for a channel that is already carrying one
 * of our copies — it only stops the damage from spreading.
 */
object PublishedCopyName {

    /** The prefix every copy this app publishes carries. */
    const val PREFIX = "hypercooldown-polite-"

    private const val SUFFIX = ".wav"

    /**
     * The `MediaStore` display name for the [factor] attenuation of [sourceUri].
     *
     * The source is identified by a hash so a row can be traced back to the sound it attenuates, and
     * the factor is in the name so two attenuation settings never look like the same copy. The platform
     * appends a ` (n)` disambiguator when a name repeats, which [isOurs] tolerates by construction.
     */
    fun of(sourceUri: String, factor: Float): String =
        "$PREFIX${Integer.toHexString(sourceUri.hashCode())}-${Math.round(factor * 100)}$SUFFIX"

    /** Whether [displayName] is a name this app gives its own copies. */
    fun isOurs(displayName: String?): Boolean =
        displayName != null && displayName.startsWith(PREFIX) && displayName.endsWith(SUFFIX)
}

/**
 * The name the platform holds for a sound URI, or null when it cannot be read.
 *
 * A seam rather than a call, for the same reason [SoundReader] is one: `AttenuatedSoundFactory` has no
 * `android.*` in it beyond what it is handed, so its provenance rule is assertable in a JVM test.
 */
fun interface SoundNameLookup {
    fun displayNameOf(uri: String): String?
}

/**
 * [SoundNameLookup] against the content resolver, which is the only place [PublishedCopyName]'s ruling
 * evidence lives.
 *
 * Every failure is a null — a URI the provider does not serve, a row that has gone, a resolver that
 * throws — and null is read as "not a name of ours", i.e. the sound is left to be treated as the
 * pristine original. That is the status quo before this class existed, so a provider hiccup can only
 * cost the extra guard, never silence a channel; nothing on this path writes anything.
 */
class MediaStoreSoundNameLookup(context: Context) : SoundNameLookup {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun displayNameOf(uri: String): String? = try {
        resolver
            .query(Uri.parse(uri), arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (t: Exception) {
        Log.w(TAG, "cannot read the name of $uri: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private companion object {
        /** See `ContentResolverSoundReader.TAG`. */
        const val TAG = "Cooldown"
    }
}
