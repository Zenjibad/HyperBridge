package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState

/**
 * One state's realised presentation for one app. Android-free by construction: `:app` is the only
 * place that knows how to realise a field, and this is what it realises.
 */
data class Presentation(
    val sound: SoundOption,
    val vibration: VibrationOption,
    val headsUp: HeadsUpOption,
    val island: IslandOption,
    val visibility: VisibilityOption,
    val relay: RelayOption,
)

/**
 * The answers AOSP never had to give, because AOSP was the platform.
 *
 * A pure function of `(state, package, ownPackage, options)`. It returns `null` when there is nothing
 * for this app to do — and the host's own package is refused **unconditionally**, not by default: a
 * cooldown write to a host-owned channel would defeat the host's own `createChannels()`, because
 * `PreferencesHelper.createNotificationChannel` respects `getUserLockedFields()` and a locked channel
 * can no longer be changed by re-creating it (spec 7, risks 1 and 2).
 *
 * **Nothing here refuses an option any more.** The heads-up row used to be gated behind a separate
 * acknowledgement, and that gate is gone: importance is recoverable (transaction 43 clears its lock
 * bit, and `ChannelActuator.restore` sends it), so a switch that blocks a documented, reversible
 * change is a trap rather than a safeguard. What the user is owed instead is the honest cost, stated
 * where the switch is — the screen's job, not the policy's.
 */
object CooldownPolicy {

    /**
     * AOSP's own treatment of the heads-up in each damped state, which is what an unset option means.
     *
     * POLITE inherits: it is a real, audible alert at 30 %, and removing its banner while keeping the
     * sound would be a deviation with no intent behind it. MUTED suppresses: its own realisation has
     * already cleared the sound and the vibration, so the banner is the last attention effect left,
     * and leaving it up is the one thing the state's name promises not to do.
     */
    val HEADS_UP_DEFAULTS = HeadsUpRealisation(
        polite = HeadsUpOption.INHERIT,
        muted = HeadsUpOption.SUPPRESS,
    )

    /** The heads-up realisation one app's options resolve to — what the write path is handed. */
    fun headsUp(options: CooldownOptions): HeadsUpRealisation = HeadsUpRealisation(
        polite = options.headsUpPolite ?: HEADS_UP_DEFAULTS.polite,
        muted = options.headsUpMuted ?: HEADS_UP_DEFAULTS.muted,
    )

    fun presentation(
        state: CooldownState,
        packageName: String,
        ownPackage: String,
        options: CooldownOptions,
    ): Presentation? {
        if (packageName == ownPackage) return null

        // DEFAULT is AOSP's own treatment: nothing is damped, so no option applies. Options are read
        // per state on purpose — one switch per effect per state, never one switch with two names.
        if (state == CooldownState.DEFAULT) return Presentation(
            sound = SoundOption.INHERIT,
            vibration = VibrationOption.INHERIT,
            headsUp = HeadsUpOption.INHERIT,
            island = IslandOption.INHERIT,
            visibility = VisibilityOption.INHERIT,
            relay = RelayOption.INHERIT,
        )

        val polite = state == CooldownState.POLITE
        val headsUp = headsUp(options)

        return Presentation(
            sound = (if (polite) options.soundPolite else options.soundMuted) ?: SoundOption.INHERIT,
            vibration = (if (polite) options.vibrationPolite else options.vibrationMuted)
                ?: VibrationOption.INHERIT,
            headsUp = headsUp.forState(state),
            // Deliberately independent of the heads-up row: the island is the host's own notification,
            // so not posting it writes nothing to the system and touches no channel (spec 4.1, 4.2).
            island = (if (polite) options.islandPolite else options.islandMuted) ?: IslandOption.INHERIT,
            visibility = options.mutedVisibility ?: VisibilityOption.INHERIT,
            relay = options.relayDuringCooldown ?: RelayOption.INHERIT,
        )
    }
}
