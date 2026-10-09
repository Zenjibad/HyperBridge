package com.d4viddf.hyperbridge.cooldown.sensor

import app.hypercooldown.actuation.IslandOption
import app.hypercooldown.actuation.Presentation

/**
 * What this host does with its **own** island for one origin, given the presentation the cooldown's
 * policy realises for it.
 *
 * Criterion A10 says a suppressed island is **absent**, and this is that word made executable. The
 * island is not a channel field: it is this app's own notification, rendered as an island by HyperOS
 * SystemUI from the `miui.focus.param` extra (`NotificationReaderService.postStandardNotification`).
 * Suppressing it therefore writes nothing to the origin's channel and takes no lock bit — but it is
 * **not** cost-free in the only sense the user is looking at, and the previous realisation proved it:
 * the host kept posting the notification and merely left the extra off, so SystemUI rendered the
 * notification itself, body and all, and the body this host gives every island post is
 * `R.string.notification_went_wrong` — *"If you see this, something went wrong! Click here to see how
 * to fix it."* Measured on 2026-10-10: 22 of 72 bridged posts carried no focus template, and the user
 * read that as a crash report (decision record §10, including the controlled same-id pair).
 *
 * Hence exactly two arms, and the middle one — *post it while withholding the island extras* — is
 * deliberately **not representable**. It is indistinguishable from a real failure by construction,
 * which is the worst property a fallback can have here, and it is the arm that shipped.
 *
 * What ABSENT does and does not touch, so the choice is not oversold:
 *  - it stops the post **before** any of the host's island bookkeeping (`activeIslands`,
 *    `activeTranslations`, `reverseTranslations`, the TTL job) and before
 *    `handlePostNotificationSideEffects`, so the island behaves exactly as if it had never been raised
 *    — which is what risk 11 and A10 ask of `nativeIslands` too;
 *  - the origin app's own notification is left where it is: this app never cancels it here, so the
 *    alert stays visible in the shade and keeps its own actions and click. That outranks honouring
 *    `removeOriginalNotification` for this one post: with no island to replace it, cancelling the
 *    original would hide the alert from the user entirely, and spec §6 promises the notification
 *    always stays visible;
 *  - the user-interaction reset still works — a tap on the origin's own notification arrives as
 *    `REASON_CLICK` and `CooldownSensorAdapter.onRemoved` is called above the host's own removal
 *    filters (`NotificationReaderService.onNotificationRemoved`), so the island's own click is not
 *    the only way to clear a cooldown (A7's first half).
 *
 * The verdict is a pure function of the presentation, so it is pinned on the JVM rather than by
 * reading the service; the *call site* is still measured on the device, as A10 does it.
 */
object IslandRealisation {

    /** The two realisations of the island option. */
    enum class Verdict {
        /**
         * Post the island exactly as upstream does — the focus template is attached unconditionally
         * (`ext/HyperBridge/…/NotificationReaderService.kt:1151-1152`), and the placeholder body is
         * never visible because SystemUI renders the island from the extra instead.
         */
        RAISE,

        /**
         * Raise nothing at all for this post: no island, and no notification of this app's either.
         * Not "post it without the extra" — see the class note.
         */
        ABSENT,
    }

    /**
     * The verdict for an already-realised presentation — the one function the host depends on.
     *
     * `null` — the policy refusing an origin (the host's own package, unconditionally) — is RAISE: the
     * island renders as it always did. Every option other than the island is ignored here on purpose;
     * the rows are separate (spec §4.1) and only this one is realised by not posting.
     */
    fun verdict(presentation: Presentation?): Verdict =
        if (presentation?.island == IslandOption.SUPPRESS) Verdict.ABSENT else Verdict.RAISE
}
