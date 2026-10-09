package com.d4viddf.hyperbridge.cooldown.act

import android.content.Context

/**
 * Where the tuning lives between runs.
 *
 * `SharedPreferences` rather than a file: the state is five primitives, and the only interesting
 * behaviour — refusing a stored value that the current build considers illegal — is
 * [TuningValues.sanitize], which is pure and JVM-tested. A preference file written by an older build
 * of this app, or edited by hand, therefore loses the one field it broke and keeps the rest, instead
 * of turning into a cooldown that behaves unlike AOSP for a reason nobody can see.
 *
 * Not unit-tested here, and it cannot be: it touches `Context`, whose `:app` unit-test form is a
 * `Stub!` thunk. The rules it applies are tested through [TuningValues.sanitize].
 */
class TuningStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): TuningValues = TuningValues.sanitize(
        TuningValues(
            timeoutPoliteMs = prefs.getLong(KEY_TIMEOUT_POLITE, TuningValues.TIMEOUT_POLITE_MS_DEFAULT),
            timeoutMutedMs = prefs.getLong(KEY_TIMEOUT_MUTED, TuningValues.TIMEOUT_MUTED_MS_DEFAULT),
            politeVolume = prefs.getFloat(KEY_POLITE_VOLUME, TuningValues.POLITE_VOLUME_DEFAULT),
            mutedVolume = prefs.getFloat(KEY_MUTED_VOLUME, TuningValues.MUTED_VOLUME_DEFAULT),
            counterReset = prefs.getInt(KEY_COUNTER_RESET, TuningValues.COUNTER_RESET_DEFAULT),
        ),
    )

    /** Stores [values]. The caller has already put them through [TuningValues.sanitize]. */
    fun save(values: TuningValues) {
        prefs.edit()
            .putLong(KEY_TIMEOUT_POLITE, values.timeoutPoliteMs)
            .putLong(KEY_TIMEOUT_MUTED, values.timeoutMutedMs)
            .putFloat(KEY_POLITE_VOLUME, values.politeVolume)
            .putFloat(KEY_MUTED_VOLUME, values.mutedVolume)
            .putInt(KEY_COUNTER_RESET, values.counterReset)
            .apply()
    }

    private companion object {
        const val FILE = "tuning"
        const val KEY_TIMEOUT_POLITE = "timeoutPoliteMs"
        const val KEY_TIMEOUT_MUTED = "timeoutMutedMs"
        const val KEY_POLITE_VOLUME = "politeVolume"
        const val KEY_MUTED_VOLUME = "mutedVolume"
        const val KEY_COUNTER_RESET = "counterReset"
    }
}
