package app.hypercooldown.actuation

/**
 * A [SnapshotStore] that keeps records in a map and appends every operation to [operations], so a
 * test can assert the *ordering* of the write-ahead protocol and not just its result.
 */
class InMemorySnapshotStore(private val operations: MutableList<String> = mutableListOf()) : SnapshotStore {

    private val records = LinkedHashMap<String, SnapshotStore.Record>()

    override fun record(target: ChannelTarget, snapshot: ChannelSnapshot) {
        operations += "record:${target.key}"
        if (records.containsKey(target.key)) return
        records[target.key] = SnapshotStore.Record(target, snapshot)
    }

    override fun markWritten(key: String, written: ChannelFields) {
        operations += "written:$key"
        val record = records[key] ?: return
        records[key] = record.copy(written = written)
    }

    override fun snapshotFor(key: String): SnapshotStore.Record? = records[key]

    override fun clear(key: String) {
        operations += "clear:$key"
        records.remove(key)
    }

    override fun outstanding(): List<SnapshotStore.Record> = records.values.toList()

    /** Simulates a store holding a record it could not read back. */
    val unreadable = mutableListOf<SnapshotStore.Quarantined>()

    override fun quarantined(): List<SnapshotStore.Quarantined> = unreadable
}
