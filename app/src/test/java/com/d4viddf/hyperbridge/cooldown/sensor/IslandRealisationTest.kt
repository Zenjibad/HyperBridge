package com.d4viddf.hyperbridge.cooldown.sensor

import app.hypercooldown.actuation.CooldownOptions
import app.hypercooldown.actuation.CooldownPolicy
import app.hypercooldown.actuation.HeadsUpOption
import app.hypercooldown.actuation.IslandOption
import app.hypercooldown.actuation.RelayOption
import app.hypercooldown.actuation.VisibilityOption
import app.hypercooldown.engine.CooldownState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Criterion A10's realisation, pinned on the JVM.
 *
 * A10 (`…hyperbridge-cooldown-integration-design.md` §9.3) says a suppressed island is **absent**. The
 * shipped realisation did something A10 does not allow and that reads like a crash on the device: it
 * kept posting the host's own notification and merely left `miui.focus.param` off, so HyperOS rendered
 * the *body* the host puts on every island post — `R.string.notification_went_wrong`,
 * "If you see this, something went wrong! Click here to see how to fix it."
 * (`NotificationReaderService.postStandardNotification`). Measured 2026-10-10: 22 of 72 bridged posts
 * carried no focus template, and the last one was followed by a user-visible fault banner
 * (`docs/superpowers/decisions/2026-10-09-vibration-illegalargument-root-cause.md` §10).
 *
 * So the verdict has exactly two arms, and the middle one — *post it without the island extras* — is
 * deliberately not representable: it is the arm that made an absent island indistinguishable from a
 * real fault. This is the pin the plan said the guard did not have (integration plan, Task 11: "the
 * island guard has no suite-level pin"); the *call site* in `NotificationReaderService` is still
 * measured by A10 on the device, because that class is an Android `Service` and no JVM test can post.
 */
class IslandRealisationTest {

    @Test
    fun `a suppressed island is absent, never a post without the focus template`() {
        assertEquals(
            "suppressing the island must stop this host posting for that alert at all: posting the " +
                "notification without the template is what surfaced notification_went_wrong",
            IslandRealisation.Verdict.ABSENT,
            IslandRealisation.verdict(CooldownPolicy.presentation(CooldownState.MUTED, ORIGIN, OWN, CooldownOptions(islandMuted = IslandOption.SUPPRESS))),
        )
    }

    @Test
    fun `the polite half suppresses the same way`() {
        assertEquals(
            IslandRealisation.Verdict.ABSENT,
            IslandRealisation.verdict(CooldownPolicy.presentation(CooldownState.POLITE, ORIGIN, OWN, CooldownOptions(islandPolite = IslandOption.SUPPRESS))),
        )
    }

    @Test
    fun `an inherited island is still raised, exactly as upstream posts it`() {
        assertEquals(
            "the control: with no island option set the island is raised as it always was",
            IslandRealisation.Verdict.RAISE,
            IslandRealisation.verdict(CooldownPolicy.presentation(CooldownState.MUTED, ORIGIN, OWN, CooldownOptions(islandMuted = IslandOption.INHERIT))),
        )
    }

    @Test
    fun `an origin no cooldown is holding is raised`() {
        assertEquals(
            "DEFAULT is AOSP's own treatment: the island renders",
            IslandRealisation.Verdict.RAISE,
            IslandRealisation.verdict(
                CooldownPolicy.presentation(CooldownState.DEFAULT, ORIGIN, OWN, CooldownOptions()),
            ),
        )
    }

    @Test
    fun `the host's own package is never suppressed`() {
        assertEquals(
            "CooldownPolicy refuses the host's own package unconditionally, and the island is the " +
                "host's own notification: a verdict of ABSENT here would silence the host itself",
            IslandRealisation.Verdict.RAISE,
            IslandRealisation.verdict(
                CooldownPolicy.presentation(
                    CooldownState.MUTED,
                    OWN,
                    OWN,
                    CooldownOptions(islandMuted = IslandOption.SUPPRESS),
                ),
            ),
        )
    }

    @Test
    fun `only the island option decides the verdict`() {
        assertEquals(
            "an unacknowledged heads-up suppress, a hidden visibility and a suppressed relay must not " +
                "take the island with them: the rows are separate on purpose (spec 4.1)",
            IslandRealisation.Verdict.RAISE,
            IslandRealisation.verdict(
                CooldownPolicy.presentation(
                    CooldownState.MUTED,
                    ORIGIN,
                    OWN,
                    CooldownOptions(
                        islandMuted = IslandOption.INHERIT,
                        headsUpMuted = HeadsUpOption.SUPPRESS,
                        mutedVisibility = VisibilityOption.HIDDEN,
                        relayDuringCooldown = RelayOption.SUPPRESS,
                    ),
                ),
            ),
        )
    }

    private companion object {
        const val ORIGIN = "com.example.chat"
        const val OWN = "com.d4viddf.hyperbridge"
    }
}
