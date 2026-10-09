package com.d4viddf.hyperbridge.cooldown.sensor

import app.hypercooldown.actuation.CooldownKeys

/** The facts one post carries that the cooldown decision needs, before any Android type is involved. */
data class PostFacts(
    val packageName: String,
    val uid: Int,
    val userId: Int,
    /** `StatusBarNotification.getKey()`: `userId|package|id|tag|uid|groupKey`. */
    val key: String,
    val flags: Int,
    val category: String?,
)

/**
 * The host's post filter, as the cooldown needs it.
 *
 * Every one of these skips is the standalone sensor's (`NotificationSensor.alertEvent`), kept identical
 * on purpose: a group summary is not an alert, an ongoing notification cannot be dismissed and is not a
 * repeated alert in AOSP's sense either, the host's own notifications must never be damped (that would be
 * a feedback loop over the island), and only the current user is handled.
 */
object CooldownPostRules {

    /** `Notification.FLAG_GROUP_SUMMARY`. Spelled numerically so this file has no Android import. */
    const val FLAG_GROUP_SUMMARY = 0x00000200

    /** `Notification.FLAG_ONGOING_EVENT`. */
    const val FLAG_ONGOING_EVENT = 0x00000008

    fun shouldObserve(ownPackage: String, currentUserId: Int, facts: PostFacts): Boolean = when {
        facts.packageName == ownPackage -> false
        facts.flags and FLAG_GROUP_SUMMARY != 0 -> false
        facts.flags and FLAG_ONGOING_EVENT != 0 -> false
        facts.userId != currentUserId -> false
        else -> true
    }

    /** The engine's `userId:packageName` key, or `null` when the platform key is not that shape. */
    fun cooldownKeyOf(sbnKey: String): String? {
        val parts = sbnKey.split('|')
        if (parts.size < 2) return null
        val userId = parts[0].toIntOrNull() ?: return null
        val packageName = parts[1]
        if (packageName.isEmpty()) return null
        return CooldownKeys.perApp(userId, packageName)
    }
}
