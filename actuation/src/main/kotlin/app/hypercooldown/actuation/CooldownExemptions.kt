package app.hypercooldown.actuation

/** Why a post was not fed to the engine. `NONE` means it was. */
enum class Exemption { NONE, CALL, ALARM, REMINDER, EVENT, CONVERSATION }

/**
 * Exemptions, evaluated **before** the engine is consulted (spec 4.5).
 *
 * An exempt post is not merely ignored — it is never fed to the engine, because feeding it and then
 * discarding the answer would still advance the post counter and the last-audible timestamp, which
 * is a silent fidelity loss in exactly the states this feature exists to get right.
 *
 * AOSP's own avalanche strategy exempts calls and alarms by category, and its exemption list names
 * important conversations too (spec 4.5). The category arm is high confidence and the constants are
 * checked against the platform's own source: `Notification.CATEGORY_CALL = "call"` (:1041),
 * `CATEGORY_EVENT = "event"` (:1061), `CATEGORY_ALARM = "alarm"` (:1071) and
 * `CATEGORY_REMINDER = "reminder"` (:1118) in `research/aosp/Notification.java`. The host already
 * reads `CATEGORY_ALARM` for its own timer detection (`NotificationReaderService.kt:1000-1004`,
 * `:1210-1211`). The conversation arm is **unverified on this ROM**: it depends on
 * `Ranking.isConversation()` for a MIUI-rewritten notification, and nothing in either project has
 * measured that (spec §4.5, §10).
 *
 * The categories are spelled as the platform's own constant *values* so this file needs no Android
 * import and stays a JVM test — the same split that keeps `:engine` and this module free of Android.
 */
object CooldownExemptions {

    /** `Notification.CATEGORY_CALL`. */
    const val CATEGORY_CALL = "call"

    /** `Notification.CATEGORY_ALARM`. */
    const val CATEGORY_ALARM = "alarm"

    /** `Notification.CATEGORY_REMINDER`. */
    const val CATEGORY_REMINDER = "reminder"

    /** `Notification.CATEGORY_EVENT`. */
    const val CATEGORY_EVENT = "event"

    fun verdict(category: String?, conversation: Boolean): Exemption = when (category?.lowercase()) {
        CATEGORY_CALL -> Exemption.CALL
        CATEGORY_ALARM -> Exemption.ALARM
        CATEGORY_REMINDER -> Exemption.REMINDER
        CATEGORY_EVENT -> Exemption.EVENT
        else -> if (conversation) Exemption.CONVERSATION else Exemption.NONE
    }

    fun shouldFeed(verdict: Exemption): Boolean = verdict == Exemption.NONE
}
