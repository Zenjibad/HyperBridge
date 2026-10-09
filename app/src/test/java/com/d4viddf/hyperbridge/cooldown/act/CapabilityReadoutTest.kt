package com.d4viddf.hyperbridge.cooldown.act

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityReadoutTest {

    private fun everythingExcept(vararg broken: Capability): ProbeReport =
        ProbeReport(Capability.entries.associateWith { it !in broken })

    private fun allUsable(): ProbeReport = ProbeReport(Capability.entries.associateWith { true })

    private fun failure(capability: Capability): CapabilityRow =
        CapabilityReadout.rows(everythingExcept(capability)).single { it.capability == capability.name }

    @Test
    fun `every capability the probe reports becomes one row, in the probe's order`() {
        val rows = CapabilityReadout.rows(allUsable())
        assertEquals(Capability.entries.map { it.name }, rows.map { it.capability })
        assertEquals(
            "NOTIFICATION_ACCESS=true NOTIFICATION_CONNECTED=true SHIZUKU_BOUND=true " +
                "SHIZUKU_PERMISSION=true ROM_ABI_PINNED=true TRANSPORT_B_ASSOCIATION=true",
            CapabilityReadout.describe(rows),
        )
    }

    @Test
    fun `a fully usable report has no next step and offers no remedy`() {
        val rows = CapabilityReadout.rows(allUsable())
        assertTrue(rows.all { it.passed })
        assertTrue(rows.all { it.remedy.isEmpty() })
        assertTrue(rows.all { it.action == RemedyAction.None })
        assertNull(CapabilityReadout.nextUnmetStep(rows))
        assertTrue(CapabilityReadout.unusableWithoutRemedy(rows).isEmpty())
    }

    @Test
    fun `a capability the report does not mention reads as unverified, never as passed`() {
        val rows = CapabilityReadout.rows(ProbeReport(emptyMap()))
        assertTrue(rows.all { !it.passed })
        assertTrue(rows.all { it.remedy.isNotEmpty() })
    }

    @Test
    fun `missing notification access offers the notification-access screen`() {
        val row = failure(Capability.NOTIFICATION_ACCESS)
        assertEquals(RemedyAction.OpenNotificationAccess, row.action)
        assertTrue(row.remedy.contains("Notification access"))
    }

    @Test
    fun `access granted with an unconnected listener reads as unverified and is never a pass`() {
        // The defect this pins: the allow-list granting access while the listener was torn down was
        // reported as NOTIFICATION_ACCESS=true, so the screen looked healthy while nothing damped.
        val report = everythingExcept(Capability.NOTIFICATION_CONNECTED)
        val row = CapabilityReadout.rows(report)
            .single { it.capability == Capability.NOTIFICATION_CONNECTED.name }

        assertTrue(CapabilityReadout.rows(report).single {
            it.capability == Capability.NOTIFICATION_ACCESS.name
        }.passed)
        assertTrue(!row.passed)
        assertEquals(RemedyAction.OpenNotificationAccess, row.action)
        assertTrue(row.remedy.contains("not connected"))
        // Self-heal leads, and the Settings toggle is named as the fallback rather than the remedy:
        // the device proved the app's own allow-list request reconnects the listener, so sending the
        // user to Settings first would be asking them to do work the app now does itself.
        assertTrue(row.remedy.contains("by itself"))
        assertTrue(row.remedy.contains("fallback"))
        assertTrue(row.remedy.contains("off and on"))
        // The instruction must not be "go and toggle": the toggle sentence has to come after the
        // self-heal sentence, which is what "only if" pins.
        assertTrue(row.remedy.indexOf("by itself") < row.remedy.indexOf("off and on"))
        assertTrue(row.remedy.contains("Only if self-heal"))
        assertTrue(CapabilityReadout.listenerUnbound(report))
        assertEquals(row, CapabilityReadout.nextUnmetStep(CapabilityReadout.rows(report)))
    }

    @Test
    fun `access that was never granted is not reported as the unbound-listener state`() {
        // The report a user with no grant actually produces: both conditions are false, because a
        // listener the platform was never allowed to bind is not bound either. That is not this
        // predicate's state — its remedy is the grant, not a rebind — and a screen that showed the
        // toggle here would send the user to a switch that is already off.
        val revoked = ProbeReport(
            Capability.entries.associateWith {
                it != Capability.NOTIFICATION_ACCESS && it != Capability.NOTIFICATION_CONNECTED
            },
        )

        assertTrue(failure(Capability.NOTIFICATION_ACCESS).remedy.contains("Notification access"))
        assertTrue(!CapabilityReadout.listenerUnbound(revoked))
        assertEquals(
            Capability.NOTIFICATION_ACCESS.name,
            CapabilityReadout.nextUnmetStep(CapabilityReadout.rows(revoked))?.capability,
        )
    }

    @Test
    fun `a connected listener is not reported as unbound`() {
        assertTrue(!CapabilityReadout.listenerUnbound(allUsable()))
    }

    @Test
    fun `a dead Shizuku binder offers the Shizuku app details screen`() {
        val row = failure(Capability.SHIZUKU_BOUND)
        assertEquals(RemedyAction.OpenAppDetails, row.action)
        assertTrue(row.remedy.contains("start.sh"))
    }

    @Test
    fun `a missing Shizuku grant offers the grant activity`() {
        val row = failure(Capability.SHIZUKU_PERMISSION)
        assertEquals(RemedyAction.OpenShizukuGrant, row.action)
        assertTrue(row.remedy.contains("ShizukuGrantActivity"))
    }

    @Test
    fun `the ROM pin and transport B are reported with no remedy and are never the next step`() {
        val rows = CapabilityReadout.rows(
            everythingExcept(Capability.ROM_ABI_PINNED, Capability.TRANSPORT_B_ASSOCIATION),
        )
        val pin = rows.single { it.capability == Capability.ROM_ABI_PINNED.name }
        val transportB = rows.single { it.capability == Capability.TRANSPORT_B_ASSOCIATION.name }
        assertEquals(RemedyAction.None, pin.action)
        assertEquals(RemedyAction.None, transportB.action)
        assertTrue(pin.remedy.contains("Do not edit RomAbi"))
        assertTrue(transportB.remedy.contains("Transport A"))
        assertNull(CapabilityReadout.nextUnmetStep(rows))
        assertEquals(listOf(pin, transportB), CapabilityReadout.unusableWithoutRemedy(rows))
    }

    @Test
    fun `the next step is the first unmet capability that can be acted on, in the probe's order`() {
        val rows = CapabilityReadout.rows(
            everythingExcept(
                Capability.NOTIFICATION_ACCESS,
                Capability.SHIZUKU_PERMISSION,
                Capability.TRANSPORT_B_ASSOCIATION,
            ),
        )
        assertEquals(
            Capability.NOTIFICATION_ACCESS.name,
            CapabilityReadout.nextUnmetStep(rows)?.capability,
        )
        val grantOnly = CapabilityReadout.rows(everythingExcept(Capability.SHIZUKU_PERMISSION))
        assertEquals(
            Capability.SHIZUKU_PERMISSION.name,
            CapabilityReadout.nextUnmetStep(grantOnly)?.capability,
        )
    }

    @Test
    fun `the steps no probe can check are user steps, and the install reality is one of them`() {
        val steps = CapabilityReadout.userSteps()
        assertEquals(listOf("autostart", "battery", "recents", "install"), steps.map { it.id })
        assertEquals(RemedyAction.OpenAutostart, steps.single { it.id == "autostart" }.action)
        assertEquals(
            RemedyAction.RequestIgnoreBatteryOptimizations,
            steps.single { it.id == "battery" }.action,
        )
        assertEquals(RemedyAction.None, steps.single { it.id == "recents" }.action)
        val install = steps.single { it.id == "install" }
        assertEquals(RemedyAction.ManualInstall, install.action)
        assertTrue(install.detail.contains("/sdcard/Download/hypercooldown-debug.apk"))
        assertTrue(install.detail.contains("INSTALL_FAILED_USER_RESTRICTED"))
        assertTrue(steps.none { it.detail.isEmpty() })
    }
}
