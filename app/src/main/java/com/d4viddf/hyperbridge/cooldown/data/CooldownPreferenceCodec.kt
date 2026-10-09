package com.d4viddf.hyperbridge.cooldown.data

import app.hypercooldown.actuation.CooldownOptions
import app.hypercooldown.actuation.HeadsUpOption
import app.hypercooldown.actuation.IslandOption
import app.hypercooldown.actuation.RelayOption
import app.hypercooldown.actuation.SoundOption
import app.hypercooldown.actuation.VibrationOption
import app.hypercooldown.actuation.VisibilityOption

/**
 * `CooldownOptions` <-> the host's key/value rows.
 *
 * Two rules, and they are the two the host already lives by:
 *
 *  - **A `null` field is an absent key, not a stored `"null"`.** `updateAppIslandConfig` is
 *    `if (x != null) save(...) else remove(...)` (`AppPreferences.kt:276-282`), and absence is what
 *    inherit *means*. [encode] therefore omits null fields, and the caller removes the key.
 *  - **An unparsable value reads as absent, never as a wrong value.** A stored `"yes"` is not "true".
 */
object CooldownPreferenceCodec {

    fun encode(options: CooldownOptions): Map<String, String> = buildMap {
        options.enabled?.let { put("enabled", it.toString()) }
        options.soundPolite?.let { put("sound_polite", it.name) }
        options.soundMuted?.let { put("sound_muted", it.name) }
        options.vibrationPolite?.let { put("vibration_polite", it.name) }
        options.vibrationMuted?.let { put("vibration_muted", it.name) }
        options.headsUpPolite?.let { put("heads_up_polite", it.name) }
        options.headsUpMuted?.let { put("heads_up_muted", it.name) }
        options.islandPolite?.let { put("island_polite", it.name) }
        options.islandMuted?.let { put("island_muted", it.name) }
        options.mutedVisibility?.let { put("muted_visibility", it.name) }
        options.relayDuringCooldown?.let { put("relay_during_cooldown", it.name) }
        options.timeoutPoliteMs?.let { put("timeout_polite_ms", it.toString()) }
        options.timeoutMutedMs?.let { put("timeout_muted_ms", it.toString()) }
        options.counterReset?.let { put("counter_reset", it.toString()) }
        options.attenuationFactor?.let { put("attenuation_factor", it.toString()) }
        options.headsUpSuppressionAcknowledged?.let { put("heads_up_suppression_acknowledged", it.toString()) }
        options.ownedPackages?.let { put("owned_packages", it.joinToString(",")) }
    }

    fun decode(rows: Map<String, String>): CooldownOptions = CooldownOptions(
        enabled = rows["enabled"]?.toBooleanStrictOrNull(),
        soundPolite = rows["sound_polite"].asEnum<SoundOption>(),
        soundMuted = rows["sound_muted"].asEnum<SoundOption>(),
        vibrationPolite = rows["vibration_polite"].asEnum<VibrationOption>(),
        vibrationMuted = rows["vibration_muted"].asEnum<VibrationOption>(),
        headsUpPolite = rows["heads_up_polite"].asEnum<HeadsUpOption>(),
        headsUpMuted = rows["heads_up_muted"].asEnum<HeadsUpOption>(),
        islandPolite = rows["island_polite"].asEnum<IslandOption>(),
        islandMuted = rows["island_muted"].asEnum<IslandOption>(),
        mutedVisibility = rows["muted_visibility"].asEnum<VisibilityOption>(),
        relayDuringCooldown = rows["relay_during_cooldown"].asEnum<RelayOption>(),
        timeoutPoliteMs = rows["timeout_polite_ms"]?.toLongOrNull(),
        timeoutMutedMs = rows["timeout_muted_ms"]?.toLongOrNull(),
        counterReset = rows["counter_reset"]?.toIntOrNull(),
        attenuationFactor = rows["attenuation_factor"]?.toFloatOrNull(),
        headsUpSuppressionAcknowledged = rows["heads_up_suppression_acknowledged"]?.toBooleanStrictOrNull(),
        ownedPackages = rows["owned_packages"]?.split(",")?.filter { it.isNotEmpty() }?.toSet(),
    )

    /** The field names [encode] and [decode] agree on. Used by `AppPreferences` to build keys. */
    val FIELDS = listOf(
        "enabled", "sound_polite", "sound_muted", "vibration_polite", "vibration_muted",
        "heads_up_polite", "heads_up_muted", "island_polite", "island_muted", "muted_visibility",
        "relay_during_cooldown", "timeout_polite_ms", "timeout_muted_ms", "counter_reset",
        "attenuation_factor", "heads_up_suppression_acknowledged", "owned_packages",
    )

    private inline fun <reified T : Enum<T>> String?.asEnum(): T? =
        this?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } }
}
