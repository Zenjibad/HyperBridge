package com.d4viddf.hyperbridge.cooldown.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one place a number the user typed becomes an option, and an option becomes text.
 *
 * Pure, so the rules the screen enforces are a JVM test rather than a device run — the same reason
 * `TuningValues` and `CooldownOptionBounds` are pure. Two properties matter and both are asserted
 * here: an unparsable or out-of-range entry yields **null**, which the screen turns into a visible
 * error and *no* write (a silently clamped value is a value the user did not choose), and a legal
 * entry round-trips.
 */
class CooldownNumberTextTest {

    @Test
    fun `seconds are shown as whole seconds, and read back`() {
        assertEquals("60", CooldownNumberText.seconds(60_000L))
        assertEquals("10", CooldownNumberText.seconds(10_000L))
        assertEquals("3600", CooldownNumberText.seconds(3_600_000L))
        assertEquals(60_000L, CooldownNumberText.toMillis("60"))
        assertEquals(10_000L, CooldownNumberText.toMillis("10"))
    }

    @Test
    fun `a timeout that is not a usable number is refused rather than clamped`() {
        assertNull(CooldownNumberText.toMillis(""))
        assertNull(CooldownNumberText.toMillis("soon"))
        assertNull(CooldownNumberText.toMillis("0"))
        assertNull(CooldownNumberText.toMillis("-5"))
        assertNull(CooldownNumberText.toMillis("3601"))
        assertNull(CooldownNumberText.toMillis("1.5"))
    }

    @Test
    fun `the ends of the timeout range are legal`() {
        assertEquals(1_000L, CooldownNumberText.toMillis("1"))
        assertEquals(3_600_000L, CooldownNumberText.toMillis("3600"))
    }

    @Test
    fun `a percentage is shown as a whole percent and read back as a factor`() {
        assertEquals("30", CooldownNumberText.percent(0.3f))
        assertEquals("100", CooldownNumberText.percent(1.0f))
        assertEquals("0", CooldownNumberText.percent(0.0f))
        assertEquals(0.3f, CooldownNumberText.toFactor("30")!!, 0.0001f)
        assertEquals(1.0f, CooldownNumberText.toFactor("100")!!, 0.0001f)
        assertEquals(0.0f, CooldownNumberText.toFactor("0")!!, 0.0001f)
    }

    @Test
    fun `a percentage outside zero to a hundred is refused`() {
        assertNull(CooldownNumberText.toFactor(""))
        assertNull(CooldownNumberText.toFactor("loud"))
        assertNull(CooldownNumberText.toFactor("-1"))
        assertNull(CooldownNumberText.toFactor("101"))
    }

    @Test
    fun `the counter is a whole number in its own range`() {
        assertEquals("10", CooldownNumberText.count(10))
        assertEquals(10, CooldownNumberText.toCount("10"))
        assertNull(CooldownNumberText.toCount("0"))
        assertNull(CooldownNumberText.toCount("101"))
        assertNull(CooldownNumberText.toCount("ten"))
    }

    @Test
    fun `an unset option shows the value that is actually in force`() {
        // `null` means "AOSP's own value" everywhere in this option set, and a field that showed an
        // empty box for it would be telling the user nothing is in force when something is.
        assertEquals("60", CooldownNumberText.seconds(CooldownNumberText.defaultTimeoutPoliteMs))
        assertEquals("10", CooldownNumberText.seconds(CooldownNumberText.defaultTimeoutMutedMs))
        assertEquals("10", CooldownNumberText.count(CooldownNumberText.defaultCounterReset))
        assertEquals("30", CooldownNumberText.percent(CooldownNumberText.defaultFactor))
    }
}
