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
 * One state's four per-state effects, **with nothing left unset**.
 *
 * This exists because "nobody has chosen" is an answer, and it is the common case: every option in
 * [CooldownOptions] is nullable, and a switch drawn from the nullable field compares `null` against
 * the value it would write and shows **off**. That is the reported defect — "make a repeating alert
 * silent" and "stop the vibration" are on by their own copy and on in the realisation, and were shown
 * off — so the screen and the write path read the same resolved answer from here instead.
 *
 * Non-null by construction, and separate from [Presentation] because [Presentation] carries the two
 * options that have no per-state choice and is nullable (the host's own package is refused).
 */
data class CooldownEffects(
    val sound: SoundOption,
    val vibration: VibrationOption,
    val headsUp: HeadsUpOption,
    val island: IslandOption,
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
 *
 * **[effects] is where an unset option is resolved, and it is the only place.** The screen's switches,
 * [headsUp] (what the write path is handed) and [presentation] (what the island decision reads) all
 * ask it, so a row and the behaviour behind it cannot disagree about a default again.
 */
object CooldownPolicy {

    /**
     * AOSP's own treatment of one state, named — i.e. what every unset option resolves to.
     *
     * POLITE inherits everything: it is a real, audible alert at 30 %, and taking away its banner
     * while keeping its sound would be a deviation with no intent behind it.
     *
     * MUTED's *own realisation* has already cleared the sound and the vibration, so `SILENT` and `OFF`
     * are not new behaviour there, they are the existing behaviour written down: `EditFactory` writes
     * `soundUri = null` for MUTED and clears the vibration flag (`MUTED -> vibrationEnabled = false`),
     * and AOSP itself is the same — `NotificationRecord.getVibrationForChannel` returns `null` when
     * `!channel.shouldVibrate()` (`research/aosp/NotificationRecord.java:320`). A silent alert that
     * still buzzed would not be silent.
     *
     * The banner is the one place the state and AOSP disagree: MUTED keeps the heads-up, so the last
     * attention effect would still take the screen — the one deviation the option set offers, and the
     * reason `headsUpMuted` resolves to `SUPPRESS` rather than to AOSP's `INHERIT`.
     */
    fun effects(options: CooldownOptions, state: CooldownState): CooldownEffects = when (state) {
        // DEFAULT is AOSP's own treatment: nothing is damped, so no option applies.
        CooldownState.DEFAULT -> CooldownEffects(
            sound = SoundOption.INHERIT,
            vibration = VibrationOption.INHERIT,
            headsUp = HeadsUpOption.INHERIT,
            island = IslandOption.INHERIT,
        )

        CooldownState.POLITE -> CooldownEffects(
            sound = options.soundPolite ?: SoundOption.INHERIT,
            vibration = options.vibrationPolite ?: VibrationOption.INHERIT,
            headsUp = options.headsUpPolite ?: HeadsUpOption.INHERIT,
            island = options.islandPolite ?: IslandOption.INHERIT,
        )

        CooldownState.MUTED -> CooldownEffects(
            sound = options.soundMuted ?: SoundOption.SILENT,
            vibration = options.vibrationMuted ?: VibrationOption.INHERIT,
            headsUp = options.headsUpMuted ?: HeadsUpOption.SUPPRESS,
            island = options.islandMuted ?: IslandOption.INHERIT,
        )
    }

    /** The heads-up realisation one app's options resolve to — what the write path is handed. */
    fun headsUp(options: CooldownOptions): HeadsUpRealisation = HeadsUpRealisation(
        polite = effects(options, CooldownState.POLITE).headsUp,
        muted = effects(options, CooldownState.MUTED).headsUp,
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

        val realised = effects(options, state)

        return Presentation(
            sound = realised.sound,
            vibration = realised.vibration,
            headsUp = realised.headsUp,
            // Deliberately independent of the heads-up row: the island is the host's own notification,
            // so not posting it writes nothing to the system and touches no channel (spec 4.1, 4.2).
            island = realised.island,
            visibility = options.mutedVisibility ?: VisibilityOption.INHERIT,
            relay = options.relayDuringCooldown ?: RelayOption.INHERIT,
        )
    }
}
