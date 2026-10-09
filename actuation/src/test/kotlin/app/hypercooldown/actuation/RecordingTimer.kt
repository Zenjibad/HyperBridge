package app.hypercooldown.actuation

/**
 * A [RelaxationTimer] that just remembers what was asked for.
 *
 * The real timer is an `AlarmManager` alarm whose callback arrives as a broadcast, so it cannot run
 * a closure in-process — the coordinator is reached through
 * [CooldownCoordinator.onRelaxationDue] instead, which is what tests call once the clock has passed
 * a scheduled time.
 */
class RecordingTimer : RelaxationTimer {

    /** key -> the deadline currently scheduled for it. */
    val scheduled = LinkedHashMap<String, Long>()

    override fun schedule(key: String, atMs: Long) {
        scheduled[key] = atMs
    }

    override fun cancel(key: String) {
        scheduled.remove(key)
    }
}
