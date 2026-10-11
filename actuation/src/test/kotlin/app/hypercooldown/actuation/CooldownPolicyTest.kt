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
        // Re-derived from the realisation, not weakened. An unset MUTED sound used to be reported as
        // `INHERIT`, which *means* "AOSP's treatment" — and AOSP's treatment of a muted alert is no
        // sound at all, which is exactly what `EditFactory` writes (`MUTED -> soundUri = null`). The
        // value was pointing at the behaviour; now it names it. Nothing realises `Presentation.sound`
        // either way, so no write changes: what changes is that the screen's silent row can read a
        // value that is true of the feature instead of comparing `null` against `SILENT`.
        assertEquals(SoundOption.SILENT, p.sound)
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

    @Test
    fun `an unset option resolves to the effect its own state realises, in one place`() {
        // The reported defect: the screen drew its switches from the nullable field, so every option
        // nobody had chosen showed *off* — while the copy beside it said "On by default" and the
        // realisation did the thing. `effects` is the answer both of them read now.
        val untouched = CooldownOptions()

        val muted = CooldownPolicy.effects(untouched, CooldownState.MUTED)
        assertEquals(SoundOption.SILENT, muted.sound)
        // A silent alert that still buzzed would not be silent. `EditFactory` already clears the
        // vibration flag for MUTED and AOSP's `NotificationRecord.getVibrationForChannel` returns null
        // when `!channel.shouldVibrate()` (`research/aosp/NotificationRecord.java:320`), so `OFF` is
        // what an unset silent-phase vibration option has always meant.
        assertEquals(VibrationOption.OFF, muted.vibration)
        assertEquals(HeadsUpOption.SUPPRESS, muted.headsUp)

        val polite = CooldownPolicy.effects(untouched, CooldownState.POLITE)
        assertEquals(SoundOption.INHERIT, polite.sound)
        assertEquals(VibrationOption.INHERIT, polite.vibration)
        assertEquals(HeadsUpOption.INHERIT, polite.headsUp)
    }

    @Test
    fun `an explicit choice still wins over the default in either direction`() {
        val chosen = CooldownOptions(
            soundMuted = SoundOption.INHERIT,
            vibrationMuted = VibrationOption.INHERIT,
            headsUpMuted = HeadsUpOption.INHERIT,
        )
        val muted = CooldownPolicy.effects(chosen, CooldownState.MUTED)
        assertEquals(SoundOption.INHERIT, muted.sound)
        assertEquals(VibrationOption.INHERIT, muted.vibration)
        assertEquals(HeadsUpOption.INHERIT, muted.headsUp)
    }

    @Test
    fun `the write path is handed the same resolution the screen shows`() {
        // One place, two readers: the heads-up realisation the actuator writes cannot disagree with
        // the switch the user is looking at.
        val untouched = CooldownOptions()
        assertEquals(
            CooldownPolicy.effects(untouched, CooldownState.MUTED).headsUp,
            CooldownPolicy.headsUp(untouched).muted,
        )
        assertEquals(
            CooldownPolicy.effects(untouched, CooldownState.POLITE).headsUp,
            CooldownPolicy.headsUp(untouched).polite,
        )
    }
}
