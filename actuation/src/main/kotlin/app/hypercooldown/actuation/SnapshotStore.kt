package app.hypercooldown.actuation

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Write-ahead storage for the pre-damping channel snapshot.
 *
 * This is spec §6's core safety property in one interface: a channel's pristine state is on disk
 * **before** the first mutation, so a process that dies mid-cooldown leaves a record the next
 * process can restore from. A channel is never left damped with no live owner.
 *
 * "Recorded" and "still damped" are deliberately the same thing — there is no separate
 * `markMutated`. Recording is write-ahead, so the set of records is a *superset* of the set of
 * actually-damped channels, which is the safe direction: restoring a channel that was never touched
 * writes its own values back and changes nothing (identical fields add no user-lock bits).
 */
interface SnapshotStore {

    /**
     * The pristine channel, where it lives, and the content of the last write we made for it.
     *
     * [written] is the record's own copy of [ChannelActuator]'s "what did I last write" map. It has
     * to be on disk for the same reason [snapshot] does: a process death takes the in-memory copy
     * with it, and a record that cannot say what we wrote leaves the next process unable to tell our
     * own damping write from a change somebody else made — which is precisely how a channel gets
     * abandoned while it is still damped. Null means no write has been recorded for this key.
     */
    data class Record(
        val target: ChannelTarget,
        val snapshot: ChannelSnapshot,
        val written: ChannelFields? = null,
    )

    /**
     * A stored record whose bytes could not be read back, kept verbatim and reported.
     *
     * Skipping one would be the worst of the options: it would read as *absent*, and the next thing
     * an absent record causes is a fresh [record] of whatever the channel carries now — which, after
     * a damping write, is our own muted content. The user's original would be gone for good. [key] is
     * the record's own key when even that much survived; null when it did not, in which case only
     * [raw] can be reported.
     */
    data class Quarantined(val key: String?, val reason: String, val raw: Map<String, String>)

    /**
     * Records [snapshot] as the pristine state for [target], and never overwrites an existing
     * record: the first snapshot is the pristine one.
     */
    fun record(target: ChannelTarget, snapshot: ChannelSnapshot)

    /**
     * Records [written] as the content of the last write the transport accepted for [key].
     *
     * A no-op for a key with no record: the marker is part of a record, never a record of its own.
     */
    fun markWritten(key: String, written: ChannelFields)

    /** The record for [target]'s key, or null when it is not being damped. */
    fun snapshotFor(key: String): Record?

    /** Drops the record for [key], once its channel is back to the snapshot. */
    fun clear(key: String)

    /** Every record still outstanding, in insertion order: the reconciliation work list. */
    fun outstanding(): List<Record>

    /**
     * Every record whose stored bytes could not be read, kept verbatim and reported rather than
     * silently absorbed (spec §6, "fail loud, not silent").
     */
    fun quarantined(): List<Quarantined>
}

/**
 * A [SnapshotStore] in one `java.util.Properties` file.
 *
 * Boring on purpose: no JSON dependency, no schema, and `Properties`' own escaping makes arbitrary
 * strings — MediaStore URIs, base64 effect bytes, channel names with commas — round-trip exactly.
 *
 * Writes go to a sibling `.tmp` file which is then moved over the real one, so a crash during a
 * write cannot truncate the records that protect the user's channels.
 */
class PropertiesSnapshotStore(private val file: Path) : SnapshotStore {

    // `Record` must be qualified here. Kotlin does not bring a supertype's nested classifiers into
    // scope, so a bare `Record` in this class resolves to `java.lang.Record` and fails to compile.

    override fun record(target: ChannelTarget, snapshot: ChannelSnapshot) {
        val loaded = load()
        if (loaded.records.containsKey(target.key)) return
        // Spec §6: a record we could not read still holds the only copy of a user's channel. Writing
        // over it would replace that copy with whatever the channel carries now, so it is refused
        // until somebody resolves the quarantine.
        if (loaded.quarantined.any { it.key == target.key }) return
        loaded.records[target.key] = SnapshotStore.Record(target, snapshot)
        save(loaded)
    }

    override fun markWritten(key: String, written: ChannelFields) {
        val loaded = load()
        val record = loaded.records[key] ?: return
        loaded.records[key] = record.copy(written = written)
        save(loaded)
    }

    override fun snapshotFor(key: String): SnapshotStore.Record? = load().records[key]

    override fun clear(key: String) {
        val loaded = load()
        if (loaded.records.remove(key) == null) return
        save(loaded)
    }

    override fun outstanding(): List<SnapshotStore.Record> = load().records.values.toList()

    override fun quarantined(): List<SnapshotStore.Quarantined> = load().quarantined

    private class Loaded(
        val records: LinkedHashMap<String, SnapshotStore.Record>,
        val quarantined: List<SnapshotStore.Quarantined>,
    )

    /** Thrown while reading a record back; the caller turns it into a quarantine. */
    private class Corrupt(message: String) : Exception(message)

    private fun load(): Loaded {
        val records = LinkedHashMap<String, SnapshotStore.Record>()
        val quarantined = mutableListOf<SnapshotStore.Quarantined>()
        if (!Files.exists(file)) return Loaded(records, quarantined)

        val props = Properties()
        Files.newInputStream(file).use(props::load)

        // Prefixes come from every numbered property, not only from `*.key`: a record whose key line
        // was lost to a partial write is exactly the record that must not read as absent.
        val prefixes = props.stringPropertyNames()
            .map { it.substringBefore('.') }
            .filter { it.toIntOrNull() != null }
            .distinct()
            .sortedBy { it.toInt() }

        for (prefix in prefixes) {
            try {
                val record = readRecord(prefix, props)
                records[record.target.key] = record
            } catch (e: Corrupt) {
                quarantined += SnapshotStore.Quarantined(
                    key = props.getProperty("$prefix.key")?.takeIf { it.isNotEmpty() },
                    reason = e.message ?: "unreadable",
                    raw = props.stringPropertyNames()
                        .filter { it.startsWith("$prefix.") }
                        .associateWith { props.getProperty(it) },
                )
            }
        }

        quarantined += readQuarantinedBlocks(props)
        return Loaded(records, quarantined)
    }

    private fun readRecord(prefix: String, props: Properties): SnapshotStore.Record {
        val key = props.getProperty("$prefix.key")?.takeIf { it.isNotEmpty() }
            ?: throw Corrupt("$prefix.key is missing or empty")
        val packageName = props.getProperty("$prefix.package")?.takeIf { it.isNotEmpty() }
            ?: throw Corrupt("$prefix.package is missing or empty")
        return SnapshotStore.Record(
            target = ChannelTarget(
                key = key,
                packageName = packageName,
                uid = props.int("$prefix.uid") ?: throw Corrupt("$prefix.uid is not a number"),
                userId = props.int("$prefix.userId") ?: throw Corrupt("$prefix.userId is not a number"),
            ),
            snapshot = ChannelSnapshot(
                fields = readFields(prefix, props),
                channelBytes = props.getProperty("$prefix.channelBytes"),
            ),
            // Optional: a record written ahead of the first accepted write has no marker yet.
            written = if (props.getProperty("$prefix.written.channelId") != null) {
                readFields("$prefix.written", props)
            } else {
                null
            },
        )
    }

    private fun readFields(prefix: String, props: Properties): ChannelFields {
        val encodedPattern = props.getProperty("$prefix.vibrationPattern")
        val pattern = if (encodedPattern == null) {
            null
        } else {
            try {
                decodeLongs(encodedPattern)
            } catch (e: NumberFormatException) {
                throw Corrupt("$prefix.vibrationPattern is not a list of numbers")
            }
        }
        if (pattern != null && pattern.size % 2 != 0) {
            throw Corrupt("$prefix.vibrationPattern is not [timing, amplitude] pairs")
        }

        return ChannelFields(
            channelId = props.getProperty("$prefix.channelId")?.takeIf { it.isNotEmpty() }
                ?: throw Corrupt("$prefix.channelId is missing or empty"),
            importance = props.int("$prefix.importance")
                ?: throw Corrupt("$prefix.importance is not a number"),
            soundUri = props.getProperty("$prefix.soundUri"),
            audioUsage = props.int("$prefix.audioUsage")
                ?: throw Corrupt("$prefix.audioUsage is not a number"),
            audioContentType = props.int("$prefix.audioContentType")
                ?: throw Corrupt("$prefix.audioContentType is not a number"),
            audioFlags = props.int("$prefix.audioFlags")
                ?: throw Corrupt("$prefix.audioFlags is not a number"),
            vibrationPattern = pattern,
            vibrationEffectBytes = props.getProperty("$prefix.vibrationEffectBytes"),
            vibrationEnabled = props.getProperty("$prefix.vibrationEnabled")?.toBooleanStrictOrNull()
                ?: throw Corrupt("$prefix.vibrationEnabled is not true or false"),
        )
    }

    /**
     * Reads back the blocks an earlier [save] preserved under the reserved namespace. They are still
     * not snapshots — only the bytes survived — so they stay quarantined, with their original
     * property names intact.
     */
    private fun readQuarantinedBlocks(props: Properties): List<SnapshotStore.Quarantined> {
        val blocks = sortedMapOf<Int, LinkedHashMap<String, String>>()
        val reasons = mutableMapOf<Int, String>()
        for (name in props.stringPropertyNames()) {
            if (!name.startsWith("$QUARANTINE.")) continue
            val rest = name.removePrefix("$QUARANTINE.")
            val index = rest.substringBefore('.').toIntOrNull() ?: continue
            val original = rest.substringAfter('.', "")
            if (original.isEmpty()) continue
            if (original == REASON) {
                props.getProperty(name)?.let { reasons[index] = it }
            } else {
                blocks.getOrPut(index) { LinkedHashMap() }[original] = props.getProperty(name)
            }
        }
        return blocks.map { (index, raw) ->
            SnapshotStore.Quarantined(
                key = raw.entries.firstOrNull { it.key.endsWith(".key") }
                    ?.value?.takeIf { it.isNotEmpty() },
                reason = reasons[index] ?: "quarantined by an earlier run",
                raw = raw,
            )
        }
    }

    private fun save(loaded: Loaded) {
        val props = Properties()
        loaded.records.values.forEachIndexed { index, record ->
            props.setProperty("$index.key", record.target.key)
            props.setProperty("$index.package", record.target.packageName)
            props.setProperty("$index.uid", record.target.uid.toString())
            props.setProperty("$index.userId", record.target.userId.toString())
            putFields(props, "$index", record.snapshot.fields)
            record.snapshot.channelBytes?.let { props.setProperty("$index.channelBytes", it) }
            record.written?.let { putFields(props, "$index.written", it) }
        }
        // Spec §6, "fail loud, not silent": the bytes we could not parse are all that is left of a
        // user's channel, so a save provoked by some *other* key writes them back rather than dropping
        // them. Nothing here reads them as a snapshot; they wait for a human to resolve them.
        loaded.quarantined.forEachIndexed { block, entry ->
            props.setProperty("$QUARANTINE.$block.$REASON", entry.reason)
            entry.raw.forEach { (name, value) -> props.setProperty("$QUARANTINE.$block.$name", value) }
        }

        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.newOutputStream(tmp).use { props.store(it, "hypercooldown write-ahead channel snapshots") }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun putFields(props: Properties, prefix: String, fields: ChannelFields) {
        props.setProperty("$prefix.channelId", fields.channelId)
        props.setProperty("$prefix.importance", fields.importance.toString())
        fields.soundUri?.let { props.setProperty("$prefix.soundUri", it) }
        props.setProperty("$prefix.audioUsage", fields.audioUsage.toString())
        props.setProperty("$prefix.audioContentType", fields.audioContentType.toString())
        props.setProperty("$prefix.audioFlags", fields.audioFlags.toString())
        fields.vibrationPattern?.let { props.setProperty("$prefix.vibrationPattern", encodeLongs(it)) }
        fields.vibrationEffectBytes?.let { props.setProperty("$prefix.vibrationEffectBytes", it) }
        props.setProperty("$prefix.vibrationEnabled", fields.vibrationEnabled.toString())
    }

    private fun Properties.int(name: String): Int? = getProperty(name)?.toIntOrNull()

    private fun encodeLongs(values: List<Long>): String = values.joinToString(",")

    private fun decodeLongs(value: String): List<Long> = value.split(",").map { it.toLong() }

    private companion object {
        /** The reserved namespace quarantined bytes are written back under. */
        const val QUARANTINE = "quarantine"

        /** `!` cannot start a field name, so this cannot collide with a preserved property. */
        const val REASON = "!reason"
    }
}
