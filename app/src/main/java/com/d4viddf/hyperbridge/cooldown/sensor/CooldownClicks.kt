package com.d4viddf.hyperbridge.cooldown.sensor

/**
 * AOSP's user-interaction reset, and the two ways this host can produce one.
 *
 * `PolitenessStrategy.onUserInteraction` drops the key and returns to DEFAULT, and the standalone
 * sensor drives it from `onNotificationRemoved(..., REASON_CLICK)`. This host never sees that reason:
 * it tests only CANCEL/CANCEL_ALL (`NotificationReaderService.kt:447`) and APP_CANCEL (`:492`). And a
 * tap on HyperBridge's island is not the origin's dismissal either: `islandClickReceiver`
 * (`:155-186`) answers the tap by cancelling the origin itself (`:178`), which is indistinguishable
 * from a dismissal. So there are two signals and both must be taken deliberately:
 *
 *  1. `onNotificationRemoved` with `REASON_CLICK` — a tap on a notification the platform still owns.
 *  2. the island's own click broadcast — `com.d4viddf.hyperbridge.ISLAND_CLICKED`, whose `sbn_key`
 *     extra is the **origin** notification's key (`:1113` puts `sbn.key`, and `sbn` there is the
 *     origin being translated).
 *
 * **One tap is one interaction, and the rule that keeps it that way is reason-based, not time-based.**
 * The two signals above are not independent — one tap on the island produces both — but neither can
 * be counted twice by accident, because:
 *
 *  - a *removal* names a key only when its reason is `REASON_CLICK` ([keyForRemoval]). The island's
 *    own cancellation of the origin (`:178`) is a cancel, and AOSP reserves `REASON_LISTENER_CANCEL`
 *    for a listener's own cancel; neither is a tap.
 *  - the island is **our** notification, so [keyForClick] refuses every key of this package. That is
 *    the other half: the platform's own removal of the tapped island carries OUR key, and attributing
 *    it to the origin the island stands for would count the same tap a second time.
 *
 * Because the rule is a pure function of `(key, reason)` it needs no memory of earlier taps, so it
 * cannot be raced, cannot leak, and cannot swallow a real second tap. `CooldownClicksTest` pins both
 * halves, and pins the reason values against the platform's.
 */
object CooldownClicks {

    /**
     * `NotificationListenerService`'s removal reasons, spelled numerically so this file has no Android
     * import and stays a JVM test.
     *
     * The values are the platform's, read back from `android.jar`:
     * `javap -constants android.service.notification.NotificationListenerService`. The plan this file
     * came from listed `REASON_APP_CANCEL = 4` and `REASON_APP_CANCEL_ALL = 5`; those two values are
     * `REASON_ERROR` and `REASON_PACKAGE_CHANGED`, and the real ones are 8 and 9.
     */
    const val REASON_CLICK = 1
    const val REASON_CANCEL = 2
    const val REASON_CANCEL_ALL = 3
    const val REASON_ERROR = 4
    const val REASON_PACKAGE_CHANGED = 5
    const val REASON_APP_CANCEL = 8
    const val REASON_APP_CANCEL_ALL = 9
    const val REASON_LISTENER_CANCEL = 10
    const val REASON_LISTENER_CANCEL_ALL = 11

    /** `REASON_LOCKDOWN`: the highest reason this platform declares, so `0..it` is the whole space. */
    const val REASON_LOCKDOWN = 23

    /** This app. The island is ours, and a tap on it is never a cooldown key of our own. */
    const val OWN_PACKAGE = "com.d4viddf.hyperbridge"

    fun isUserInteraction(reason: Int): Boolean = reason == REASON_CLICK

    /**
     * The engine key a tap belongs to, or `null` when the key is not the platform's shape or belongs
     * to the host itself. Returning null is the honest answer: a mis-attributed reset would clear the
     * wrong app's cooldown.
     */
    fun keyForClick(sbnKey: String, ownPackage: String = OWN_PACKAGE): String? {
        val parts = sbnKey.split('|')
        if (parts.size < 2) return null
        val packageName = parts[1]
        if (packageName.isEmpty() || packageName == ownPackage) return null
        return CooldownPostRules.cooldownKeyOf(sbnKey)
    }

    /**
     * The one key a *removal* names, or `null`.
     *
     * This is the de-duplication rule in a single place: the reason must be a click, and the key must
     * not be ours. So the island's own cancellation of the origin names nothing, and the platform's
     * removal of the island itself names nothing either — only the island's broadcast names the
     * origin, and it names it once.
     */
    fun keyForRemoval(sbnKey: String?, reason: Int, ownPackage: String = OWN_PACKAGE): String? =
        if (!isUserInteraction(reason)) null else keyForClick(sbnKey ?: "", ownPackage)
}
