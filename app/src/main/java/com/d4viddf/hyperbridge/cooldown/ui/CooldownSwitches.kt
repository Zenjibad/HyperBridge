package com.d4viddf.hyperbridge.cooldown.ui

import app.hypercooldown.actuation.CooldownOptions
import app.hypercooldown.actuation.CooldownPolicy
import app.hypercooldown.actuation.HeadsUpOption
import app.hypercooldown.actuation.IslandOption
import app.hypercooldown.actuation.SoundOption
import app.hypercooldown.actuation.VibrationOption
import app.hypercooldown.engine.CooldownState

/**
 * The state of every switch on the cooldown screen, read from the one resolution of an unset option.
 *
 * A switch is **not** `options.x == <the value it writes>`. Every field of [CooldownOptions] is
 * nullable, "nobody has ever chosen" is the common case, and that comparison answers *off* for it —
 * which is how "make a repeating alert silent" and "stop the vibration" came to be drawn off while
 * the copy beside them said "On by default" and the realisation did exactly that.
 * [CooldownPolicy.effects] is the resolution; this is the screen's naming of it, one function per row,
 * so a row's state is assertable without a device and cannot drift from the policy again.
 */
object CooldownSwitches {

    /** The master row. `!= false` because AOSP's own `notification_cooldown_enabled` defaults to 1. */
    fun enabled(options: CooldownOptions): Boolean = options.enabled != false

    fun politeSilentSound(options: CooldownOptions): Boolean =
        sound(options, CooldownState.POLITE) == SoundOption.SILENT

    fun mutedSilentSound(options: CooldownOptions): Boolean =
        sound(options, CooldownState.MUTED) == SoundOption.SILENT

    fun politeVibrationOff(options: CooldownOptions): Boolean =
        vibration(options, CooldownState.POLITE) == VibrationOption.OFF

    fun mutedVibrationOff(options: CooldownOptions): Boolean =
        vibration(options, CooldownState.MUTED) == VibrationOption.OFF

    fun politeIslandSuppressed(options: CooldownOptions): Boolean =
        island(options, CooldownState.POLITE) == IslandOption.SUPPRESS

    fun mutedIslandSuppressed(options: CooldownOptions): Boolean =
        island(options, CooldownState.MUTED) == IslandOption.SUPPRESS

    fun politeHeadsUpSuppressed(options: CooldownOptions): Boolean =
        headsUp(options, CooldownState.POLITE) == HeadsUpOption.SUPPRESS

    fun mutedHeadsUpSuppressed(options: CooldownOptions): Boolean =
        headsUp(options, CooldownState.MUTED) == HeadsUpOption.SUPPRESS

    /** The acknowledged note is not a defaulted option: the user either read it or did not. */
    fun headsUpNoteRead(options: CooldownOptions): Boolean = options.headsUpSuppressionAcknowledged == true

    private fun sound(options: CooldownOptions, state: CooldownState) =
        CooldownPolicy.effects(options, state).sound

    private fun vibration(options: CooldownOptions, state: CooldownState) =
        CooldownPolicy.effects(options, state).vibration

    private fun island(options: CooldownOptions, state: CooldownState) =
        CooldownPolicy.effects(options, state).island

    private fun headsUp(options: CooldownOptions, state: CooldownState) =
        CooldownPolicy.effects(options, state).headsUp
}
