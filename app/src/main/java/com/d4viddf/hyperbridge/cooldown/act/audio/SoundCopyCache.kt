package com.d4viddf.hyperbridge.cooldown.act.audio

/**
 * A bounded, least-recently-used map from a channel's own sound to the copy published for it.
 *
 * The key is `(sourceUri, factor)`. Both halves are load-bearing: the URI is what the channel's sound
 * *is*, and the factor is what the copy *is* — a change of `EngineConfig.politeVolume` must never hand
 * back a file scaled by the old one. The value is the `content://` URI to install as the channel's
 * sound.
 *
 * The bound is spec §6's "bounded resources": a listener process lives indefinitely, and one entry per
 * channel per burst would grow without limit. Eviction is only of the map entry — the MediaStore row it
 * names is left alone, because deleting it could race the SystemUI `RingtonePlayer` that is opening the
 * file during the very alert being damped. That is this task's stated limitation, not an oversight.
 *
 * Recency is on both `get` and `put`: an entry that keeps being used outlives one that was inserted
 * earlier and never read, which is what a burst on one channel looks like from here.
 */
class SoundCopyCache(private val maxEntries: Int = 8) {

    init {
        require(maxEntries > 0) { "a cache needs room for at least one entry, got $maxEntries" }
    }

    private data class Key(val sourceUri: String, val factor: Float)

    /**
     * Insertion-ordered, and a read re-inserts. That is what makes the first key the least recently
     * used without a second structure to keep in step.
     */
    private val entries = LinkedHashMap<Key, String>()

    val size: Int
        @Synchronized get() = entries.size

    @Synchronized
    fun get(sourceUri: String, factor: Float): String? {
        val key = Key(sourceUri, factor)
        val published = entries.remove(key) ?: return null
        entries[key] = published
        return published
    }

    @Synchronized
    fun put(sourceUri: String, factor: Float, publishedUri: String) {
        val key = Key(sourceUri, factor)
        entries.remove(key)
        entries[key] = publishedUri
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    /**
     * The source URI the copy at [publishedUri] attenuates, or null when [publishedUri] is not a copy
     * this process published.
     *
     * This is the correlation the entry already holds — the copy's URI *is* the value and the sound it
     * came from *is* the key — turned the other way round so that "never attenuate a copy we published"
     * is a property of the factory rather than a promise from its callers. Measured on the device as
     * the difference between peak 6497 and peak 1949 (0.30²): once POLITE carries the published copy,
     * the live channel's sound *is* that copy, and a factory that trusted its caller would attenuate it
     * again.
     *
     * Exact for every copy this process still remembers, which is the bound this class already has:
     * eviction forgets the correlation along with the entry.
     */
    @Synchronized
    fun sourceOfCopy(publishedUri: String): String? =
        entries.entries.firstOrNull { it.value == publishedUri }?.key?.sourceUri
}
