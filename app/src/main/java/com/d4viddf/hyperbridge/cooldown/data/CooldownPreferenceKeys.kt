package com.d4viddf.hyperbridge.cooldown.data

/**
 * The per-app key builder, in the host's own shape.
 *
 * `AppPreferences` writes every per-app key as a raw literal — `config_${packageName}_float` and
 * friends (`AppPreferences.kt:268-282`) — deliberately outside `SettingsKeys`, which holds only the
 * global and core keys (`data/db/AppSettings.kt:12-37`). Cooldown copies that split: globals are
 * constants on `SettingsKeys`, per-app keys are built here.
 */
object CooldownPreferenceKeys {

    const val PREFIX = "cooldown_"

    fun app(packageName: String, field: String): String = "$PREFIX${packageName}_$field"

    fun global(field: String): String = "${PREFIX}global_$field"
}
