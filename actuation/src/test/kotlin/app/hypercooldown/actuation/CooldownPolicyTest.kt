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
    }

    @Test
    fun `inherit resolves to AOSP's values in MUTED`() {
        val p = presentation(CooldownState.MUTED)!!
        assertEquals(SoundOption.INHERIT, p.sound)
        assertEquals(HeadsUpOption.SUPPRESS, p.headsUp)
        assertEquals(IslandOption.INHERIT, p.island)
    }

    @Test
    fun `heads-up suppression is honoured without a separate acknowledgement`() {
        // Re-derived rather than weakened. The acknowledgement used to gate this row because the write
        // was a *permanent, user-locked* side effect. It is not permanent any more: importance is the
        // one lock bit transaction 43 clears, it is shell-reachable, and `ChannelActuator.restore`
        // sends it after every restore. So a second switch that blocks the row is a trap rather than a
        // safeguard, and the honest cost is stated inline by the screen instead.
        val unacknowledged = presentation(
            CooldownState.MUTED,
            CooldownOptions(headsUpMuted = HeadsUpOption.SUPPRESS),
        )!!
        assertEquals(HeadsUpOption.SUPPRESS, unacknowledged.headsUp)

        val acknowledged = presentation(
            CooldownState.MUTED,
            CooldownOptions(headsUpMuted = HeadsUpOption.SUPPRESS, headsUpSuppressionAcknowledged = true),
        )!!
        assertEquals(HeadsUpOption.SUPPRESS, acknowledged.headsUp)
    }

    @Test
    fun `POLITE keeps AOSP's heads-up and MUTED drops it, which is what an unset option means`() {
        // The default is per state, and it is the same asymmetry the option set already has: MUTED has
        // cleared the sound and the vibration by its own realisation, so the banner is the last
        // attention effect left and leaving it up is what the state's name promises not to do. POLITE
        // is a real 30 % alert and keeps AOSP's banner.
        assertEquals(HeadsUpOption.INHERIT, presentation(CooldownState.POLITE)!!.headsUp)
        assertEquals(HeadsUpOption.SUPPRESS, presentation(CooldownState.MUTED)!!.headsUp)
        // An explicit choice still wins in either direction — the default is a default, not a rule.
        assertEquals(
            HeadsUpOption.INHERIT,
            presentation(CooldownState.MUTED, CooldownOptions(headsUpMuted = HeadsUpOption.INHERIT))!!.headsUp,
        )
        assertEquals(
            HeadsUpOption.SUPPRESS,
            presentation(CooldownState.POLITE, CooldownOptions(headsUpPolite = HeadsUpOption.SUPPRESS))!!.headsUp,
        )
    }

    @Test
    fun `island suppression writes no channel, so nothing gates it and nothing else moves with it`() {
        val p = presentation(CooldownState.MUTED, CooldownOptions(islandMuted = IslandOption.SUPPRESS))!!
        assertEquals(IslandOption.SUPPRESS, p.island)
    }

    @Test
    fun `the island option is independent of the heads-up option`() {
        val p = presentation(
            CooldownState.POLITE,
            CooldownOptions(islandPolite = IslandOption.SUPPRESS, headsUpPolite = HeadsUpOption.SUPPRESS),
        )!!
        // Both halves are honoured, and each is read from its own field: the island writes nothing to
        // any channel, the heads-up writes importance, and neither decides the other. This is the pin
        // that keeps the two from collapsing into one switch with two names.
        assertEquals(HeadsUpOption.SUPPRESS, p.headsUp)
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
