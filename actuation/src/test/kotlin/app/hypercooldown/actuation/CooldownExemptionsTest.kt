package app.hypercooldown.actuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CooldownExemptionsTest {

    @Test
    fun `calls are exempt by default`() {
        assertEquals(Exemption.CALL, CooldownExemptions.verdict(category = "call", conversation = false))
    }

    @Test
    fun `alarms are exempt by default`() {
        assertEquals(Exemption.ALARM, CooldownExemptions.verdict(category = "alarm", conversation = false))
    }

    @Test
    fun `reminders and events are exempt by default`() {
        assertEquals(Exemption.REMINDER, CooldownExemptions.verdict(category = "reminder", conversation = false))
        assertEquals(Exemption.EVENT, CooldownExemptions.verdict(category = "event", conversation = false))
    }

    @Test
    fun `an important conversation is exempt, and that arm is unverified on this ROM`() {
        assertEquals(Exemption.CONVERSATION, CooldownExemptions.verdict(category = null, conversation = true))
    }

    @Test
    fun `an ordinary post is not exempt`() {
        assertEquals(Exemption.NONE, CooldownExemptions.verdict(category = "msg", conversation = false))
        assertEquals(Exemption.NONE, CooldownExemptions.verdict(category = null, conversation = false))
    }

    @Test
    fun `the category match is case-insensitive because the platform's constants are lower case but apps may not be`() {
        assertEquals(Exemption.CALL, CooldownExemptions.verdict(category = "CALL", conversation = false))
        assertEquals(Exemption.ALARM, CooldownExemptions.verdict(category = "Alarm", conversation = false))
    }

    @Test
    fun `an exempt post is never fed to the engine, because feeding it would move the clock`() {
        // Spec 4.5: feeding an exempt post and then ignoring the answer would still advance the post
        // counter and the last-audible timestamp, which is a silent fidelity loss.
        assertTrue(CooldownExemptions.shouldFeed(Exemption.NONE))
        Exemption.entries.filter { it != Exemption.NONE }.forEach { exempt ->
            assertFalse(CooldownExemptions.shouldFeed(exempt), "$exempt must not reach the engine")
        }
    }
}
