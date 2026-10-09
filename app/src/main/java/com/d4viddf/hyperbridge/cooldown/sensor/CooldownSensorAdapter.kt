package com.d4viddf.hyperbridge.cooldown.sensor

import android.app.NotificationChannel
import android.os.Process
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import app.hypercooldown.actuation.AlertEvent
import app.hypercooldown.actuation.ChannelTarget
import app.hypercooldown.actuation.CooldownExemptions
import app.hypercooldown.actuation.Exemption
import com.d4viddf.hyperbridge.cooldown.act.AndroidChannelMapper

/**
 * The host's listener callbacks, turned into the engine's input.
 *
 * `Ranking.channel` is the only place a non-owning app can read another app's notification channel
 * (`getNotificationChannelsForPackage` is `enforceSystemOrSystemUI`), and it is reachable **only**
 * through the two-argument callback the host did not override. That is the whole reason this class
 * exists, and why the override change is part of the port rather than an optimisation.
 */
class CooldownSensorAdapter(
    private val ownPackage: String,
    private val currentUserId: Int = Process.myUserHandle().hashCode(),
    private val mapper: AndroidChannelMapper = AndroidChannelMapper(),
    private val onAlert: (AlertEvent) -> Unit,
    private val onUserInteraction: (String) -> Unit,
    /**
     * Called once per post the exemptions keep out of the engine, with the origin's package so the
     * line is evidence rather than a count. Defaulted because the exemptions are the only caller and a
     * no-op default keeps every existing construction site valid.
     */
    private val onExempt: (String, Exemption) -> Unit = { _, _ -> },
) {

    /** Called above every early return in the host's `onNotificationPosted`. */
    fun onPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        val event = alertEvent(sbn, rankingMap) ?: return
        onAlert(event)
    }

    /** The removal-side signal: a tap is a user interaction (AOSP's `onUserInteraction`). */
    fun onRemoved(sbn: StatusBarNotification?, reason: Int) {
        if (sbn == null) return
        if (!CooldownClicks.isUserInteraction(reason)) return
        CooldownClicks.keyForClick(sbn.key, ownPackage)?.let(onUserInteraction)
    }

    /** The island's own click, which never surfaces as `REASON_CLICK` (see `CooldownClicks`). */
    fun onIslandClicked(sbnKey: String?) {
        CooldownClicks.keyForClick(sbnKey ?: "", ownPackage)?.let(onUserInteraction)
    }

    /** The pure normalisation's Android half. Returns null when this post is not an alert to damp. */
    fun alertEvent(sbn: StatusBarNotification?, rankingMap: RankingMap?): AlertEvent? {
        if (sbn == null || rankingMap == null) return null
        val facts = PostFacts(
            packageName = sbn.packageName,
            uid = sbn.uid,
            userId = sbn.user.hashCode(),
            key = sbn.key,
            flags = sbn.notification?.flags ?: 0,
            category = sbn.notification?.category,
        )
        if (!CooldownPostRules.shouldObserve(ownPackage, currentUserId, facts)) return null

        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return null
        // Spec 4.5: the exemptions are evaluated **before** the engine is consulted, and an exempt
        // post is not fed to it at all -- feeding it and discarding the answer would still advance the
        // post counter and the last-audible timestamp, which is a silent fidelity loss. The check sits
        // above the channel read so that an exempt post with no readable channel is still reported as
        // exempt rather than vanishing.
        //
        // `runCatching` around `Ranking.isConversation`: the accessor is public API, but this ROM
        // rewrites notifications and a null-returning or throwing getter must degrade to "not a
        // conversation" rather than take the listener's callback thread down with it. That arm is
        // unverified on this ROM either way (`CooldownExemptions`' KDoc says so).
        val verdict = CooldownExemptions.verdict(
            category = facts.category,
            conversation = runCatching { ranking.isConversation }.getOrDefault(false),
        )
        if (!CooldownExemptions.shouldFeed(verdict)) {
            onExempt(facts.packageName, verdict)
            return null
        }
        val channel = ranking.channel ?: return null
        Log.d(TAG, "snapshot pkg=${facts.packageName} channel=${channel.id} sound=${channel.sound}")

        return AlertEvent(
            target = ChannelTarget.perApp(facts.userId, facts.packageName, facts.uid),
            snapshot = mapper.snapshotOf(channel),
            rankingLastAudiblyAlertedMs = ranking.lastAudiblyAlertedMillis,
        )
    }

    companion object {
        const val TAG = "CooldownSensor"
    }
}
