package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState

/** Something the policy had to refuse or downgrade, said out loud rather than silently applied. */
enum class PolicyNote {
    /** The heads-up suppress option is set but its warning was never acknowledged (spec 4.1). */
    HEADS_UP_SUPPRESS_UNACKNOWLEDGED,
}

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
    val notes: Set<PolicyNote> = emptySet(),
)

/**
 * The answers AOSP never had to give, because AOSP was the platform.
 *
 * A pure function of `(state, package, ownPackage, options)`. It returns `null` when there is nothing
 * for this app to do — and the host's own package is refused **unconditionally**, not by default: a
 * cooldown write to a host-owned channel would defeat the host's own `createChannels()`, because
 * `PreferencesHelper.createNotificationChannel` respects `getUserLockedFields()` and a locked channel
 * can no longer be changed by re-creating it (spec 7, risks 1 and 2).
 */
object CooldownPolicy {

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
        val headsUp = if (polite) options.headsUpPolite else options.headsUpMuted
        val acknowledged = options.headsUpSuppressionAcknowledged == true
        val headsUpEffective = if (headsUp == HeadsUpOption.SUPPRESS && !acknowledged) {
            HeadsUpOption.INHERIT
        } else {
            headsUp ?: HeadsUpOption.INHERIT
        }

        return Presentation(
            sound = (if (polite) options.soundPolite else options.soundMuted) ?: SoundOption.INHERIT,
            vibration = (if (polite) options.vibrationPolite else options.vibrationMuted)
                ?: VibrationOption.INHERIT,
            headsUp = headsUpEffective,
            // Deliberately not gated on `acknowledged`: the island is the host's own notification, so
            // not posting it writes nothing to the system and touches no channel (spec 4.1, 4.2).
            island = (if (polite) options.islandPolite else options.islandMuted) ?: IslandOption.INHERIT,
            visibility = options.mutedVisibility ?: VisibilityOption.INHERIT,
            relay = options.relayDuringCooldown ?: RelayOption.INHERIT,
            notes = if (headsUp == HeadsUpOption.SUPPRESS && !acknowledged) {
                setOf(PolicyNote.HEADS_UP_SUPPRESS_UNACKNOWLEDGED)
            } else {
                emptySet()
            },
        )
    }
}
