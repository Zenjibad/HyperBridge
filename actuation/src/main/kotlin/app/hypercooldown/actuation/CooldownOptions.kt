package app.hypercooldown.actuation

import app.hypercooldown.engine.EngineConfig

/** What POLITE/MUTED do to the sound. `INHERIT` is AOSP's own treatment. */
enum class SoundOption { INHERIT, SILENT }

/** What POLITE/MUTED do to the vibration. */
enum class VibrationOption { INHERIT, OFF }

/**
 * What POLITE/MUTED do to the heads-up.
 *
 * `SUPPRESS` is **not free**: there is no "show heads-up" channel attribute, so the only lever is
 * `setImportance(...)`, which reaches `PreferencesHelper.updateNotificationChannel(..., fromUser = true)`
 * and therefore *user-locks* the importance bit permanently (transaction 43 clears only that bit, and
 * `unlockAllNotificationChannels` needs `checkCallerIsSystem`). It also demotes the notification
 * everywhere, not just during the window. AOSP deliberately does not touch importance (Q4).
 */
enum class HeadsUpOption { INHERIT, SUPPRESS }

/**
 * What POLITE/MUTED do to the island.
 *
 * `SUPPRESS` is free: the island is HyperBridge's own notification, whose extras carry
 * `miui.focus.param` for HyperOS SystemUI to render (`NotificationReaderService.kt:1081`). Not posting
 * it, or not attaching the extras, touches no channel of the origin app and writes nothing to the
 * system — the host already posts with `setShowNotification(false)` for its permanent island.
 */
enum class IslandOption { INHERIT, SUPPRESS }

/** `HIDDEN` is the one option that would need `setLockscreenVisibility`, an equally locked write. */
enum class VisibilityOption { INHERIT, SHOWN, HIDDEN }

/** The watch-relay re-post (spec 4.8). Carried in the shape; no UI writes it in v1. */
enum class RelayOption { INHERIT, SUPPRESS }

/**
 * One app's cooldown options, with the host's `null`-means-inherit idiom.
 *
 * This mirrors `models/IslandConfig.kt`: every field nullable, [mergeWith] resolving
 * `this.x ?: global.x ?: <default>`. The one designated **sentinel** for "this app is overridden at
 * all" is [enabled] — the host's own equivalent is `isFloat`
 * (`ui/components/IslandSettingsControl.kt:43`), and the host already lives with the asymmetry that
 * its read-site defaults may differ from `mergeWith`'s (`AppPreferences.kt:224-232` vs
 * `models/IslandConfig.kt:15`). Cooldown picks `enabled` and says so once.
 */
data class CooldownOptions(
    /** Master enable when this is the global row; the per-app enable when it is an app's row. */
    val enabled: Boolean? = null,

    val soundPolite: SoundOption? = null,
    val soundMuted: SoundOption? = null,
    val vibrationPolite: VibrationOption? = null,
    val vibrationMuted: VibrationOption? = null,
    val headsUpPolite: HeadsUpOption? = null,
    val headsUpMuted: HeadsUpOption? = null,
    val islandPolite: IslandOption? = null,
    val islandMuted: IslandOption? = null,
    val mutedVisibility: VisibilityOption? = null,
    val relayDuringCooldown: RelayOption? = null,

    val timeoutPoliteMs: Long? = null,
    val timeoutMutedMs: Long? = null,
    val counterReset: Int? = null,
    val attenuationFactor: Float? = null,

    /** Set only by the acknowledgement row beside the heads-up warning (spec 4.1). */
    val headsUpSuppressionAcknowledged: Boolean? = null,

    /**
     * The per-app ownership list of spec 1.1, decision 1. Empty means "every app", which is the state
     * in which the fork concedes to the standalone writer (Task 5). It is a set, not a boolean list,
     * because it is compared per key on the write path.
     */
    val ownedPackages: Set<String>? = null,
) {

    /** True when this row overrides the global one at all. The sentinel is [enabled] alone. */
    val isOverridden: Boolean get() = enabled != null

    fun mergeWith(global: CooldownOptions): CooldownOptions = CooldownOptions(
        enabled = this.enabled ?: global.enabled,
        soundPolite = this.soundPolite ?: global.soundPolite,
        soundMuted = this.soundMuted ?: global.soundMuted,
        vibrationPolite = this.vibrationPolite ?: global.vibrationPolite,
        vibrationMuted = this.vibrationMuted ?: global.vibrationMuted,
        headsUpPolite = this.headsUpPolite ?: global.headsUpPolite,
        headsUpMuted = this.headsUpMuted ?: global.headsUpMuted,
        islandPolite = this.islandPolite ?: global.islandPolite,
        islandMuted = this.islandMuted ?: global.islandMuted,
        mutedVisibility = this.mutedVisibility ?: global.mutedVisibility,
        relayDuringCooldown = this.relayDuringCooldown ?: global.relayDuringCooldown,
        timeoutPoliteMs = this.timeoutPoliteMs ?: global.timeoutPoliteMs,
        timeoutMutedMs = this.timeoutMutedMs ?: global.timeoutMutedMs,
        counterReset = this.counterReset ?: global.counterReset,
        attenuationFactor = this.attenuationFactor ?: global.attenuationFactor,
        headsUpSuppressionAcknowledged =
            this.headsUpSuppressionAcknowledged ?: global.headsUpSuppressionAcknowledged,
        ownedPackages = this.ownedPackages ?: global.ownedPackages,
    )

    /**
     * Realises the options that are expressible as engine numbers. The engine is frozen and is not
     * given a new field for a UI concern; a "silent POLITE" is a zero POLITE volume, which is the
     * value AOSP's own MUTED uses.
     */
    fun effectiveConfig(base: EngineConfig): EngineConfig = EngineConfig(
        timeoutPoliteMs = timeoutPoliteMs ?: base.timeoutPoliteMs,
        timeoutMutedMs = timeoutMutedMs ?: base.timeoutMutedMs,
        politeVolume = if (soundPolite == SoundOption.SILENT) 0.0f else attenuationFactor ?: base.politeVolume,
        mutedVolume = base.mutedVolume,
        counterReset = counterReset ?: base.counterReset,
    )
}
