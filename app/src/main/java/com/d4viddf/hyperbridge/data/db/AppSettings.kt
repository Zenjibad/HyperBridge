package com.d4viddf.hyperbridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)

object SettingsKeys {
    // Migration Flag
    const val MIGRATION_COMPLETE = "migration_to_room_complete"

    // Core Keys
    const val SETUP_COMPLETE = "setup_complete"
    const val LAST_VERSION = "last_version_code"
    const val PRIORITY_EDU = "priority_edu_shown"
    const val ALLOWED_PACKAGES = "allowed_packages"
    const val PRIORITY_ORDER = "priority_app_order"
    const val FEATURED_PERMISSION_WARNING = "featured_permission_warning"

    // Global Configs
    const val GLOBAL_FLOAT = "global_float"
    const val GLOBAL_SHADE = "global_shade"
    const val GLOBAL_TIMEOUT = "global_timeout"
    const val GLOBAL_FLOAT_TIMEOUT = "global_float_timeout"
    const val GLOBAL_REMOVE_NOTIF = "global_remove_original_notif"
    const val GLOBAL_DISMISS_WITH_ORIGINAL = "global_dismiss_with_original"
    const val GLOBAL_ENABLE_INLINE_REPLY = "global_enable_inline_reply"
    const val GLOBAL_BLOCKED_TERMS = "global_blocked_terms"

    // Cooldown
    const val COOLDOWN_GLOBAL_ENABLED = "cooldown_global_enabled"
    const val COOLDOWN_GLOBAL_SOUND_POLITE = "cooldown_global_sound_polite"
    const val COOLDOWN_GLOBAL_SOUND_MUTED = "cooldown_global_sound_muted"
    const val COOLDOWN_GLOBAL_VIBRATION_POLITE = "cooldown_global_vibration_polite"
    const val COOLDOWN_GLOBAL_VIBRATION_MUTED = "cooldown_global_vibration_muted"
    const val COOLDOWN_GLOBAL_HEADS_UP_POLITE = "cooldown_global_heads_up_polite"
    const val COOLDOWN_GLOBAL_HEADS_UP_MUTED = "cooldown_global_heads_up_muted"
    const val COOLDOWN_GLOBAL_ISLAND_POLITE = "cooldown_global_island_polite"
    const val COOLDOWN_GLOBAL_ISLAND_MUTED = "cooldown_global_island_muted"
    const val COOLDOWN_GLOBAL_MUTED_VISIBILITY = "cooldown_global_muted_visibility"
    const val COOLDOWN_GLOBAL_RELAY_DURING_COOLDOWN = "cooldown_global_relay_during_cooldown"
    const val COOLDOWN_GLOBAL_TIMEOUT_POLITE_MS = "cooldown_global_timeout_polite_ms"
    const val COOLDOWN_GLOBAL_TIMEOUT_MUTED_MS = "cooldown_global_timeout_muted_ms"
    const val COOLDOWN_GLOBAL_COUNTER_RESET = "cooldown_global_counter_reset"
    const val COOLDOWN_GLOBAL_ATTENUATION_FACTOR = "cooldown_global_attenuation_factor"
    const val COOLDOWN_GLOBAL_HEADS_UP_ACK = "cooldown_global_heads_up_suppression_acknowledged"
    const val COOLDOWN_GLOBAL_OWNED_PACKAGES = "cooldown_global_owned_packages"

    // Nav
    const val NAV_LEFT = "nav_left_content"
    const val NAV_RIGHT = "nav_right_content"
}