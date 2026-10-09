package com.d4viddf.hyperbridge.cooldown.sensor

import app.hypercooldown.actuation.CooldownKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownPostRulesTest {

    private fun facts(
        packageName: String = "com.example.chat",
        uid: Int = 10123,
        userId: Int = 0,
        key: String = "0|com.example.chat|1|null|10123|null",
        flags: Int = 0,
        category: String? = null,
    ) = PostFacts(packageName, uid, userId, key, flags, category)

    @Test
    fun `an ordinary post from another app is observed`() {
        assertTrue(CooldownPostRules.shouldObserve("com.d4viddf.hyperbridge", 0, facts()))
    }

    @Test
    fun `the host's own post is never observed`() {
        assertFalse(CooldownPostRules.shouldObserve("com.d4viddf.hyperbridge", 0, facts(packageName = "com.d4viddf.hyperbridge")))
    }

    @Test
    fun `a group summary is not an alert`() {
        assertFalse(CooldownPostRules.shouldObserve("com.d4viddf.hyperbridge", 0, facts(flags = CooldownPostRules.FLAG_GROUP_SUMMARY)))
    }

    @Test
    fun `an ongoing notification is not an alert`() {
        assertFalse(CooldownPostRules.shouldObserve("com.d4viddf.hyperbridge", 0, facts(flags = CooldownPostRules.FLAG_ONGOING_EVENT)))
    }

    @Test
    fun `another user's post is ignored, because only the current user is handled`() {
        assertFalse(CooldownPostRules.shouldObserve("com.d4viddf.hyperbridge", 0, facts(userId = 10)))
    }

    @Test
    fun `the cooldown key is the engine key, parsed out of the platform's notification key`() {
        assertEquals(
            CooldownKeys.perApp(0, "com.example.chat"),
            CooldownPostRules.cooldownKeyOf("0|com.example.chat|1|null|10123|null"),
        )
        assertEquals(
            CooldownKeys.perApp(10, "com.example.chat"),
            CooldownPostRules.cooldownKeyOf("10|com.example.chat|1|a|tag|10123|null"),
        )
    }

    @Test
    fun `a key that is not the platform's shape yields nothing rather than a wrong key`() {
        assertNull(CooldownPostRules.cooldownKeyOf("com.example.chat"))
        assertNull(CooldownPostRules.cooldownKeyOf("notanumber|com.example.chat|1|null|10123|null"))
        assertNull(CooldownPostRules.cooldownKeyOf("0||1|null|10123|null"))
    }
}
