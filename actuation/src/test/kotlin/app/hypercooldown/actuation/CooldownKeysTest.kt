package app.hypercooldown.actuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CooldownKeysTest {

    @Test
    fun `the key is per app, because AOSP blanks the channel id`() {
        // AOSP's `getChannelKey` is `userId:package:channelId`, but with `notification_cooldown_all`
        // (default 1) and a channel with no user-set sound the channel id is blanked, collapsing the
        // key to the per-app form that spec §5.1 pins. Two channels of one app therefore share one
        // cooldown.
        assertEquals("0:com.example.chat", CooldownKeys.perApp(0, "com.example.chat"))
        assertNotEquals(
            CooldownKeys.perApp(0, "com.example.chat"),
            CooldownKeys.perApp(0, "com.example.other"),
        )
    }

    @Test
    fun `a work profile is a different cooldown`() {
        assertNotEquals(
            CooldownKeys.perApp(0, "com.example.chat"),
            CooldownKeys.perApp(10, "com.example.chat"),
        )
    }

    @Test
    fun `a channel target carries everything the platform needs to address the channel`() {
        val target = ChannelTarget.perApp(userId = 0, packageName = "com.android.shell", uid = 2000)
        assertEquals("0:com.android.shell", target.key)
        assertEquals("com.android.shell", target.packageName)
        assertEquals(2000, target.uid)
        assertEquals(0, target.userId)
    }
}
