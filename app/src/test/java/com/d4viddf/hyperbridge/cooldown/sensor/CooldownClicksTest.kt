package com.d4viddf.hyperbridge.cooldown.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownClicksTest {

    /** `StatusBarNotification.getKey()` of a chat notification belonging to another app. */
    private val originSbnKey = "0|com.example.chat|1|null|10123|null"

    /** The engine key that tap belongs to. */
    private val originCooldownKey = "0:com.example.chat"

    /** The island HyperBridge posts for that origin: same user, our package, our own id. */
    private val islandSbnKey = "0|com.d4viddf.hyperbridge|4242|null|10123|null"

    @Test
    fun `only a click is a user interaction`() {
        assertTrue(CooldownClicks.isUserInteraction(CooldownClicks.REASON_CLICK))
        assertFalse(CooldownClicks.isUserInteraction(CooldownClicks.REASON_CANCEL))
        assertFalse(CooldownClicks.isUserInteraction(CooldownClicks.REASON_CANCEL_ALL))
        assertFalse(CooldownClicks.isUserInteraction(CooldownClicks.REASON_APP_CANCEL))
        assertFalse(CooldownClicks.isUserInteraction(CooldownClicks.REASON_APP_CANCEL_ALL))
    }

    @Test
    fun `the reason constants are the platform's, so a comparison is never off by a value`() {
        assertEquals(1, CooldownClicks.REASON_CLICK)
        assertEquals(2, CooldownClicks.REASON_CANCEL)
        assertEquals(3, CooldownClicks.REASON_CANCEL_ALL)
        // Read back from android.jar: `javap -constants android.service.notification
        // .NotificationListenerService`. The plan this file came from had 4 and 5 here; those are
        // REASON_ERROR and REASON_PACKAGE_CHANGED.
        assertEquals("4 is REASON_ERROR", 4, CooldownClicks.REASON_ERROR)
        assertEquals("5 is REASON_PACKAGE_CHANGED", 5, CooldownClicks.REASON_PACKAGE_CHANGED)
        assertEquals(8, CooldownClicks.REASON_APP_CANCEL)
        assertEquals(9, CooldownClicks.REASON_APP_CANCEL_ALL)
        assertEquals(10, CooldownClicks.REASON_LISTENER_CANCEL)
        assertEquals(11, CooldownClicks.REASON_LISTENER_CANCEL_ALL)
    }

    @Test
    fun `a click on the origin notification names the origin key`() {
        assertEquals("0:com.example.chat", CooldownClicks.keyForClick("0|com.example.chat|1|null|10123|null"))
    }

    @Test
    fun `a click on the island names the origin key, because the island carries the origin's key`() {
        // `islandClickReceiver` gets `sbn_key` from the broadcast built at NotificationReaderService
        // :1111-1121, and that value is `sbn.key` of the ORIGIN notification, not the island's.
        assertEquals("0:com.example.chat", CooldownClicks.keyForClick("0|com.example.chat|1|null|10123|null"))
    }

    @Test
    fun `an unparseable key yields nothing, so a signal is never attributed to the wrong app`() {
        assertNull(CooldownClicks.keyForClick("garbage"))
        assertNull(CooldownClicks.keyForClick(""))
    }

    @Test
    fun `the host's own key is refused even when it parses`() {
        assertNull(CooldownClicks.keyForClick("0|com.d4viddf.hyperbridge|1|null|10123|null", ownPackage = "com.d4viddf.hyperbridge"))
        assertEquals("0:com.example.chat", CooldownClicks.keyForClick("0|com.example.chat|1|null|10123|null", ownPackage = "com.d4viddf.hyperbridge"))
    }

    // -----------------------------------------------------------------------------------------
    // One tap is one interaction: the two surfaces must not both count the same tap.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a tap on the island counts once, though the platform reports it on both surfaces`() {
        // Tapping a MESSAGE island produces all of these, in the host's own order:
        //  - `islandClickReceiver` answers the broadcast, whose `sbn_key` is the ORIGIN's key (:1113)
        //  - the platform removes the island notification itself, which is OUR key, on a CLICK
        //  - `islandClickReceiver` then cancels the ORIGIN on our behalf (:178)
        val seen = listOf(
            CooldownClicks.keyForClick(originSbnKey),
            CooldownClicks.keyForRemoval(islandSbnKey, CooldownClicks.REASON_CLICK),
            CooldownClicks.keyForRemoval(originSbnKey, CooldownClicks.REASON_CANCEL),
            CooldownClicks.keyForRemoval(originSbnKey, CooldownClicks.REASON_LISTENER_CANCEL),
        ).filterNotNull()

        assertEquals(listOf(originCooldownKey), seen)
    }

    @Test
    fun `a tap on the notification counts once, and the island it dismisses is not a second tap`() {
        // `dismissWithOriginal` cancels our island programmatically once the origin goes (:490-497):
        // our key, and never the platform's CLICK reason.
        val seen = listOf(
            CooldownClicks.keyForRemoval(originSbnKey, CooldownClicks.REASON_CLICK),
            CooldownClicks.keyForRemoval(islandSbnKey, CooldownClicks.REASON_CANCEL),
            CooldownClicks.keyForRemoval(islandSbnKey, CooldownClicks.REASON_APP_CANCEL),
        ).filterNotNull()

        assertEquals(listOf(originCooldownKey), seen)
    }

    @Test
    fun `no reason other than a click makes a removal a user interaction`() {
        for (reason in 0..CooldownClicks.REASON_LOCKDOWN) {
            if (reason == CooldownClicks.REASON_CLICK) continue
            assertFalse("reason $reason", CooldownClicks.isUserInteraction(reason))
            assertNull("reason $reason", CooldownClicks.keyForRemoval(originSbnKey, reason))
        }
        assertTrue(CooldownClicks.isUserInteraction(CooldownClicks.REASON_CLICK))
    }

    @Test
    fun `the island's own notification never names a key, whatever the platform says about it`() {
        for (reason in 0..CooldownClicks.REASON_LOCKDOWN) {
            assertNull("reason $reason", CooldownClicks.keyForRemoval(islandSbnKey, reason))
        }
    }

    @Test
    fun `a removal with no notification names nothing`() {
        assertNull(CooldownClicks.keyForRemoval(null, CooldownClicks.REASON_CLICK))
    }
}
