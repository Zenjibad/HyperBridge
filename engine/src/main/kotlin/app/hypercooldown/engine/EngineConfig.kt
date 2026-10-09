package app.hypercooldown.engine

/**
 * Defaults are AOSP's production-build values. They are read through a `FlagResolver` that,
 * on a non-debuggable build, returns exactly these constants and ignores the
 * `persist.debug.sysui.notification.*` sysprops.
 */
data class EngineConfig(
    /** `NOTIF_COOLDOWN_T1`, ms after the last audible alert at which DEFAULT becomes POLITE. */
    val timeoutPoliteMs: Long = 60_000L,
    /** `NOTIF_COOLDOWN_T2`, ms after the last audible alert at which POLITE becomes MUTED. */
    val timeoutMutedMs: Long = 10_000L,
    /** `NOTIF_VOLUME1` (30) divided by 100. */
    val politeVolume: Float = 0.30f,
    /** `NOTIF_VOLUME2` (0) divided by 100. */
    val mutedVolume: Float = 0.0f,
    /** `NOTIF_COOLDOWN_COUNTER_RESET`. */
    val counterReset: Int = 10,
)
