package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CooldownPolicyTest {

    private val own = "com.d4viddf.hyperbridge"

    private fun presentation(
        state: CooldownState,
        options: CooldownOptions = CooldownOptions(),
        packageName: String = "com.example.chat",
    ) = CooldownPolicy.presentation(state, packageName, own, options)

    @Test
    fun `inherit resolves to AOSP's values in POLITE`() {
        val p = presentation(CooldownState.POLITE)!!
        assertEquals(SoundOption.INHERIT, p.sound)
        assertEquals(VibrationOption.INHERIT, p.vibration)
        assertEquals(HeadsUpOption.INHERIT, p.headsUp)
        assertEquals(IslandOption.INHERIT, p.island)
        assertEquals(VisibilityOption.INHERIT, p.visibility)
        assertTrue(p.notes.isEmpty())
    }

    @Test
    fun `inherit resolves to AOSP's values in MUTED`() {
        val p = presentation(CooldownState.MUTED)!!
        assertEquals(SoundOption.INHERIT, p.sound)
        assertEquals(HeadsUpOption.INHERIT, p.headsUp)
        assertEquals(IslandOption.INHERIT, p.island)
    }

    @Test
    fun `heads-up suppression is unreachable without its acknowledgement`() {
        val unacknowledged = presentation(
            CooldownState.MUTED,
            CooldownOptions(headsUpMuted = HeadsUpOption.SUPPRESS),
        )!!
        assertEquals(HeadsUpOption.INHERIT, unacknowledged.headsUp)
        assertTrue(unacknowledged.notes.contains(PolicyNote.HEADS_UP_SUPPRESS_UNACKNOWLEDGED))
    }

    @Test
    fun `heads-up suppression is reachable once acknowledged`() {
        val acknowledged = presentation(
            CooldownState.MUTED,
            CooldownOptions(headsUpMuted = HeadsUpOption.SUPPRESS, headsUpSuppressionAcknowledged = true),
        )!!
        assertEquals(HeadsUpOption.SUPPRESS, acknowledged.headsUp)
        assertTrue(acknowledged.notes.isEmpty())
    }

    @Test
    fun `island suppression needs no acknowledgement, because the island is the host's own notification`() {
        val p = presentation(CooldownState.MUTED, CooldownOptions(islandMuted = IslandOption.SUPPRESS))!!
        assertEquals(IslandOption.SUPPRESS, p.island)
        assertTrue(p.notes.isEmpty())
    }

    @Test
    fun `the island option is independent of the heads-up option`() {
        val p = presentation(
            CooldownState.POLITE,
            CooldownOptions(islandPolite = IslandOption.SUPPRESS, headsUpPolite = HeadsUpOption.SUPPRESS),
        )!!
        // The heads-up half is still refused for want of an acknowledgement, while the island half is
        // honoured. This is the pin that keeps the two from collapsing into one switch with two names.
        assertEquals(HeadsUpOption.INHERIT, p.headsUp)
        assertEquals(IslandOption.SUPPRESS, p.island)
    }

    @Test
    fun `the host's own package is refused unconditionally`() {
        assertNull(CooldownPolicy.presentation(CooldownState.MUTED, own, own, CooldownOptions(enabled = true)))
        assertNull(CooldownPolicy.presentation(CooldownState.POLITE, own, own, CooldownOptions()))
    }

    @Test
    fun `DEFAULT carries no damping decisions at all`() {
        val p = presentation(CooldownState.DEFAULT, CooldownOptions(soundMuted = SoundOption.SILENT))!!
        assertEquals(SoundOption.INHERIT, p.sound)
        assertEquals(IslandOption.INHERIT, p.island)
    }

    @Test
    fun `the relay field exists and defaults to inherit`() {
        // Spec 3.4 carries `relay` in the Presentation shape so that 4.8 is wiring later, not a
        // redesign. It has no option yet, and it must not accidentally default to suppression.
        assertEquals(RelayOption.INHERIT, presentation(CooldownState.MUTED)!!.relay)
    }

    @Test
    fun `the visibility choice defaults to shown and this is AOSP's behaviour`() {
        assertEquals(VisibilityOption.INHERIT, presentation(CooldownState.MUTED)!!.visibility)
        assertEquals(
            VisibilityOption.HIDDEN,
            presentation(CooldownState.MUTED, CooldownOptions(mutedVisibility = VisibilityOption.HIDDEN))!!.visibility,
        )
    }

    @Test
    fun `the state chooses which option applies, so POLITE and MUTED do not share one switch`() {
        val options = CooldownOptions(soundPolite = SoundOption.SILENT, soundMuted = SoundOption.INHERIT)
        assertEquals(SoundOption.SILENT, presentation(CooldownState.POLITE, options)!!.sound)
        assertEquals(SoundOption.INHERIT, presentation(CooldownState.MUTED, options)!!.sound)
    }
}
