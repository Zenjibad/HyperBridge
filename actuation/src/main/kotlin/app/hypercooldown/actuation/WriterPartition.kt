package app.hypercooldown.actuation

/**
 * One writer at a time, per channel key (spec 1.1, decision 1).
 *
 * The standalone `app.hypercooldown` app stays installed beside this fork, so **two** cooldown
 * writers hold notification access on one device. Two writers on one channel, with different
 * snapshots and different write-ahead records, is the double-damp failure the capability probe exists
 * to prevent, and it is worse than the native-gate case because both writers are ours.
 *
 * The four layers of the spec's answer, and where each lives:
 *
 *  1. this file — the **fail-closed precondition**: while a second writer is armed and the ownership
 *     list is empty, nothing is written at all, so the default state of the two apps together is
 *     exactly one writer;
 *  2. this file — **the partition**: while a second writer is armed, only listed packages are ours;
 *  3. the standalone app's own exclusion set, which **does not exist yet** and belongs to that
 *     project's plan; until it ships, layer 1 is the whole enforcement;
 *  4. `ChannelActuator.Observed.CHANGED_BY_OTHER`, read as "a second writer touched this channel".
 *
 * Nothing here knows how "a second writer is armed" is detected. That is
 * `com.d4viddf.hyperbridge.cooldown.sensor.SecondWriterProbe` on the Android side (Task 11), and its
 * signal is the `enabled_notification_listeners` mirror plus `PackageManager`, both of which are
 * readable by an ordinary app.
 */
object WriterPartition {

    /** The standalone cooldown app. Its listener's package is what a second writer always is. */
    const val STANDALONE_PACKAGE = "app.hypercooldown"

    /**
     * The standalone app's listener component, exactly as it appears in the platform's
     * `enabled_notification_listeners` mirror. Pinned here, in one place, because it is the string the
     * probe greps for and the string a reviewer will look for.
     */
    const val STANDALONE_COMPONENT = "app.hypercooldown/app.hypercooldown.sensor.NotificationSensor"

    enum class Refusal {
        /** A second cooldown writer is armed and we have no ownership list. We are not the writer. */
        SECOND_WRITER_ACTIVE,

        /** A second writer is armed and this package is not on our list. */
        NOT_OWNED,

        /** `app.hypercooldown` itself. Damping the other writer's own notifications is never right. */
        FOREIGN_WRITER,

        /** The host's own package, or an empty name. Refused unconditionally (spec 7, risks 1 and 2). */
        OWN_PACKAGE,
    }

    sealed interface Verdict {
        data object Write : Verdict
        data class Refuse(val reason: Refusal) : Verdict
    }

    fun decide(
        secondWriterArmed: Boolean,
        ownedPackages: Set<String>,
        packageName: String,
        ownPackage: String,
    ): Verdict = when {
        packageName.isEmpty() || packageName == ownPackage -> Verdict.Refuse(Refusal.OWN_PACKAGE)
        packageName == STANDALONE_PACKAGE -> Verdict.Refuse(Refusal.FOREIGN_WRITER)
        !secondWriterArmed -> Verdict.Write
        ownedPackages.isEmpty() -> Verdict.Refuse(Refusal.SECOND_WRITER_ACTIVE)
        packageName !in ownedPackages -> Verdict.Refuse(Refusal.NOT_OWNED)
        else -> Verdict.Write
    }
}
