package app.hypercooldown.engine

/**
 * Mirrors AOSP's `PolitenessStrategy.POLITE_STATE_*` constants:
 * `POLITE_STATE_DEFAULT = 0`, `POLITE_STATE_POLITE = 1`, `POLITE_STATE_MUTED = 2`.
 */
enum class CooldownState {
    /** Full sound and vibration. */
    DEFAULT,

    /** Sound at [EngineConfig.politeVolume], vibration scaled by the same factor. */
    POLITE,

    /** No sound, no vibration. Importance is untouched, so the notification stays visible. */
    MUTED,
}
