package com.d4viddf.hyperbridge.cooldown.act.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [SoundCopyCache]'s bound and its key: spec §6's "attenuated-sound cache is LRU-capped", and
 * the reason the key carries the factor at all.
 *
 * The bound is the property that matters for a listener process that never exits: without it the map
 * grows one entry per channel per episode forever.
 */
class SoundCopyCacheTest {

    @Test
    fun `a miss returns null and a hit returns the published uri`() {
        val cache = SoundCopyCache()

        assertNull(cache.get(SOURCE, 0.30f))

        cache.put(SOURCE, 0.30f, THIRTY)

        assertEquals(THIRTY, cache.get(SOURCE, 0.30f))
        assertEquals(1, cache.size)
    }

    @Test
    fun `the key includes the factor so a different attenuation is a different copy`() {
        val cache = SoundCopyCache()

        cache.put(SOURCE, 0.30f, THIRTY)
        assertNull(cache.get(SOURCE, 0.50f))   // a different factor is not a hit

        cache.put(SOURCE, 0.50f, FIFTY)

        assertEquals(2, cache.size)
        assertEquals(THIRTY, cache.get(SOURCE, 0.30f))
        assertEquals(FIFTY, cache.get(SOURCE, 0.50f))
    }

    @Test
    fun `the cache never holds more than its bound`() {
        val cache = SoundCopyCache(maxEntries = 2)

        cache.put(A, 0.30f, "a")
        cache.put(B, 0.30f, "b")
        cache.put(C, 0.30f, "c")

        assertEquals(2, cache.size)
        assertNull(cache.get(A, 0.30f))
        assertEquals("b", cache.get(B, 0.30f))
        assertEquals("c", cache.get(C, 0.30f))
    }

    @Test
    fun `a read is a use, so the least recently read entry is the one evicted`() {
        val cache = SoundCopyCache(maxEntries = 3)
        cache.put(A, 0.30f, "a")
        cache.put(B, 0.30f, "b")
        cache.put(C, 0.30f, "c")

        assertEquals("a", cache.get(A, 0.30f))   // A is now the most recently used

        cache.put(D, 0.30f, "d")                 // evicts B, not A

        assertEquals(3, cache.size)
        assertNull(cache.get(B, 0.30f))
        assertEquals("a", cache.get(A, 0.30f))
        assertEquals("c", cache.get(C, 0.30f))
        assertEquals("d", cache.get(D, 0.30f))
    }

    private companion object {
        const val A = "content://media/external/audio/media/1"
        const val B = "content://media/external/audio/media/2"
        const val C = "content://media/external/audio/media/3"
        const val D = "content://media/external/audio/media/4"
        const val SOURCE = "content://media/external/audio/media/9"
        const val THIRTY = "content://media/external/audio/media/30"
        const val FIFTY = "content://media/external/audio/media/50"
    }
}
