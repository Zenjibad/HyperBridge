package app.hypercooldown.actuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PropertiesSnapshotStoreTest {

    @TempDir
    lateinit var dir: Path

    private fun store() = PropertiesSnapshotStore(dir.resolve("channel-snapshots.properties"))

    @Test
    fun `a record survives a new store instance`() {
        // This is the whole point: the next process must be able to restore a channel that this one
        // damped and then died on.
        store().record(target(), snapshot())
        val reloaded = store().snapshotFor("0:com.example.chat")
        assertEquals(target(), reloaded!!.target)
        assertEquals(channelFields(), reloaded.snapshot.fields)
        assertEquals("AQ==", reloaded.snapshot.channelBytes)
    }

    @Test
    fun `the first snapshot wins, because it is the pristine one`() {
        // After the first damping write the platform hands back *our* values, so a second snapshot
        // would make the "original" drift and the restore would restore our damping.
        val store = store()
        store.record(target(), snapshot())
        store.record(target(), snapshot(fields = channelFields(soundUri = null)))
        assertEquals(channelFields(), store.snapshotFor("0:com.example.chat")!!.snapshot.fields)
    }

    @Test
    fun `a null sound, a null pattern and a null effect survive the round trip`() {
        store().record(
            target(),
            snapshot(fields = channelFields(soundUri = null, vibrationPattern = null, vibrationEnabled = false)),
        )
        val fields = store().snapshotFor("0:com.example.chat")!!.snapshot.fields
        assertNull(fields.soundUri)
        assertNull(fields.vibrationPattern)
        assertFalse(fields.vibrationEnabled)
    }

    @Test
    fun `a URI with equals signs and commas survives the round trip`() {
        // `Properties` escapes `=`, `:` and `\` on store and unescapes on load; this proves the
        // escaping is not fighting a real MediaStore URI.
        val uri = "content://media/external/audio/media/8161?volume=30,40"
        val pattern = listOf(0L, 255L, 200L, 255L)
        store().record(target(), snapshot(fields = channelFields(soundUri = uri, vibrationPattern = pattern)))
        val fields = store().snapshotFor("0:com.example.chat")!!.snapshot.fields
        assertEquals(uri, fields.soundUri)
        assertEquals(pattern, fields.vibrationPattern)
    }

    @Test
    fun `clear removes only that key and leaves the others outstanding`() {
        val store = store()
        store.record(target(key = "0:a", packageName = "a"), snapshot())
        store.record(target(key = "0:b", packageName = "b"), snapshot())
        store.clear("0:a")
        assertNull(store.snapshotFor("0:a"))
        assertEquals(listOf("0:b"), store().outstanding().map { it.target.key })
    }

    @Test
    fun `outstanding lists records in insertion order`() {
        val store = store()
        store.record(target(key = "0:b", packageName = "b"), snapshot())
        store.record(target(key = "0:a", packageName = "a"), snapshot())
        assertEquals(listOf("0:b", "0:a"), store.outstanding().map { it.target.key })
    }

    @Test
    fun `the store writes through a temporary file`() {
        val file = dir.resolve("channel-snapshots.properties")
        PropertiesSnapshotStore(file).record(target(), snapshot())
        assertTrue(Files.exists(file))
        assertFalse(Files.exists(file.resolveSibling("channel-snapshots.properties.tmp")))
    }

    @Test
    fun `an empty store reports nothing outstanding`() {
        assertTrue(store().outstanding().isEmpty())
        assertNull(store().snapshotFor("0:com.example.chat"))
    }

    /**
     * A garbled or half-written field, the way a truncated file or a bad flash leaves one: the
     * record's bytes are all still there, but the record can no longer be rebuilt from them.
     */
    private fun corruptTheOnlyRecord(file: Path) {
        Files.writeString(
            file,
            Files.readString(file).replace("0.importance=4", "0.importance=not-a-number"),
        )
    }

    @Test
    fun `a corrupt record is quarantined rather than read as absent`() {
        val file = dir.resolve("channel-snapshots.properties")
        store().record(target(), snapshot())
        corruptTheOnlyRecord(file)

        val store = store()

        assertNull(store.snapshotFor("0:com.example.chat"), "an unreadable record cannot be handed out")
        assertEquals(1, store.quarantined().size, "and it is not silently dropped either")
        assertEquals("0:com.example.chat", store.quarantined().single().key)
        assertEquals("not-a-number", store.quarantined().single().raw["0.importance"])
    }

    @Test
    fun `re-recording over a quarantined record is refused so the original is not destroyed`() {
        val file = dir.resolve("channel-snapshots.properties")
        store().record(target(), snapshot())
        corruptTheOnlyRecord(file)

        // This is exactly what `ChannelActuator.apply` does next if the corrupt record reads as
        // absent: it records the content the channel carries *now*, and after a damping write that is
        // our own muted content. Doing so would overwrite the only copy of the user's original.
        val damped = snapshot(
            fields = channelFields(soundUri = null, vibrationPattern = null, vibrationEnabled = false),
        )
        store().record(target(), damped)

        assertTrue(store().outstanding().isEmpty(), "no record was written over the quarantine")
        assertEquals(1, store().quarantined().size, "and the situation is still reported")
        assertEquals("not-a-number", store().quarantined().single().raw["0.importance"])
    }

    @Test
    fun `a quarantine survives a save provoked by another key`() {
        val file = dir.resolve("channel-snapshots.properties")
        store().record(target(), snapshot())
        corruptTheOnlyRecord(file)

        store().record(target(key = "0:other", packageName = "other"), snapshot())

        assertEquals(listOf("0:other"), store().outstanding().map { it.target.key })
        assertEquals(1, store().quarantined().size, "the unreadable bytes outlived the save")
        assertEquals("not-a-number", store().quarantined().single().raw["0.importance"])
        assertTrue(Files.readString(file).contains("not-a-number"), "and they are still in the file")
    }
}
