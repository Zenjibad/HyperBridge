package app.hypercooldown.actuation

import app.hypercooldown.engine.CooldownState

/**
 * Whether each damped state lowers the channel's importance, resolved once from the option set.
 *
 * There is no "show heads-up" channel attribute. The gate is `importance >= IMPORTANCE_HIGH` in
 * `NotificationAttentionHelper`/`CommonVisualInterruptionSuppressors.PeekNotImportantSuppressor`, and
 * interruptiveness is never plumbed to SystemUI at all, so `setImportance` is the **only** lever
 * (`2026-10-09-q4-heads-up-under-muted.md`). Suppression is therefore a demotion to
 * `IMPORTANCE_DEFAULT`: the highest importance *below* the gate, which removes the banner and keeps
 * the sound. That is what lets POLITE stay a genuine 30 % audible alert with no heads-up instead of
 * trading the sound away, and it is why this is a demotion and not a `LOW`/`MIN` write.
 *
 * Per state, like every other effect in the option set: POLITE and MUTED are independent rows and are
 * never one switch with two names. The island is deliberately **not** part of this type — island
 * suppression writes nothing and locks nothing (`IslandOption`), and entangling the two would take
 * that asymmetry away.
 */
data class HeadsUpRealisation(
    val polite: HeadsUpOption = HeadsUpOption.INHERIT,
    val muted: HeadsUpOption = HeadsUpOption.INHERIT,
) {

    /** The option that applies to [state]. `DEFAULT` is AOSP's own treatment, so it is never lowered. */
    fun forState(state: CooldownState): HeadsUpOption = when (state) {
        CooldownState.DEFAULT -> HeadsUpOption.INHERIT
        CooldownState.POLITE -> polite
        CooldownState.MUTED -> muted
    }

    /** Whether installing [state] lowers the importance of the channel it is installed on. */
    fun lowers(state: CooldownState): Boolean = forState(state) == HeadsUpOption.SUPPRESS

    companion object {
        /** AOSP's own treatment: the importance is copied through and never written differently. */
        val NONE = HeadsUpRealisation()
    }
}
