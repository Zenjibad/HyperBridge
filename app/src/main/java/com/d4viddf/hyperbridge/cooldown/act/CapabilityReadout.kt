package com.d4viddf.hyperbridge.cooldown.act

/**
 * What the wizard shows, and the only place in the app that decides what to tell the user to do
 * about a capability the app does not have.
 *
 * Pure on purpose: no `android.*` import, and no `Intent` anywhere. Turning a [RemedyAction] into an
 * Intent is the Activity's job, so the part a JVM test can hold is the part that decides — which
 * remedy a failure has, and which failure is next (spec §5.7: every condition reported distinctly,
 * so a failure is diagnosable rather than mysterious).
 *
 * The capabilities themselves are neither listed nor derived here. [rows] walks `Capability.entries`
 * and asks the report that Plan 2's `TransportProbe.probe` produced, so a sixth capability added to
 * the probe becomes a sixth row with no edit to this file, and nothing can disagree with the log.
 */

/**
 * What the user has to do about a row.
 *
 * The Activity maps each member to an Intent and nothing else does, which is what keeps a deep link
 * out of the model: [OpenAppDetails] opens the details screen of the package the row is about, so
 * the row's own capability decides whose details those are (Shizuku's for [Capability.SHIZUKU_BOUND],
 * this app's otherwise).
 */
enum class RemedyAction {
    OpenNotificationAccess,
    OpenShizukuGrant,
    OpenAppDetails,
    OpenAutostart,
    RequestIgnoreBatteryOptimizations,
    ManualInstall,
    None,
}

/**
 * One capability, as the probe reported it.
 *
 * [passed] is true only when the report said so: a capability the report does not mention reads as
 * unverified, never as OK (spec §5.6, never silently pretend to work). A passed row carries an empty
 * [remedy] and [RemedyAction.None], so a capability that is fine can never render a button.
 */
data class CapabilityRow(
    val capability: String,
    val passed: Boolean,
    val remedy: String,
    val action: RemedyAction,
)

/**
 * Something only a human can do, and which the app therefore never claims to have verified — not
 * even after the user taps the button, because a tap is not a measurement.
 *
 * [detail] is the exact tap path or command, and [action] is the deep link for it where one exists.
 * [RemedyAction.None] means there is nothing to open and the instruction is the whole remedy.
 */
data class UserConfirmedItem(
    val id: String,
    val title: String,
    val detail: String,
    val action: RemedyAction,
)

object CapabilityReadout {

    /** One row per capability the probe knows about, in the probe's own order. */
    fun rows(report: ProbeReport): List<CapabilityRow> = Capability.entries.map { capability ->
        val passed = report.isUsable(capability)
        CapabilityRow(
            capability = capability.name,
            passed = passed,
            remedy = if (passed) "" else remedyFor(capability),
            action = if (passed) RemedyAction.None else actionFor(capability),
        )
    }

    /**
     * The first failure the user can actually do something about, in the probe's own order —
     * notification access first, because the sensor is what makes the rest observable at all.
     *
     * A row with no remedy is never returned. `ROM_ABI_PINNED` and `TRANSPORT_B_ASSOCIATION` are
     * measurements of the ROM rather than states a user can change, and offering one as "the next
     * step" would be a step that cannot be taken (spec §5.6).
     */
    fun nextUnmetStep(rows: List<CapabilityRow>): CapabilityRow? =
        rows.firstOrNull { !it.passed && it.action != RemedyAction.None }

    /**
     * The one state that used to look healthy and was not: the user granted notification access, and
     * the listener is nonetheless not bound, so the runtime (built in `onListenerConnected`) does not
     * exist and nothing is damped.
     *
     * It is here rather than in the Activity because it is a statement about the probe's answer —
     * and the two rows must be read together: with access *off* the listener is unbound too, but
     * that is not this state, it is the ordinary "you have not granted it yet", whose remedy is the
     * grant and not a rebind. A screen that showed the rebind remedy there would send the user to
     * toggle a switch that is already off.
     */
    fun listenerUnbound(report: ProbeReport): Boolean =
        report.isUsable(Capability.NOTIFICATION_ACCESS) &&
            !report.isUsable(Capability.NOTIFICATION_CONNECTED)

    /** Every failure with no remedy at all, so the screen can report it without dressing it up as a task. */
    fun unusableWithoutRemedy(rows: List<CapabilityRow>): List<CapabilityRow> =
        rows.filter { !it.passed && it.action == RemedyAction.None }

    /**
     * One line, `NAME=true NAME=false ...` — the same shape `ProbeReport.describe` prints. It goes to
     * logcat under the harness's own tag, so `adb logcat -s CooldownCheck:V` still shows one report
     * for the whole app, and this line states exactly what the screen is showing.
     */
    fun describe(rows: List<CapabilityRow>): String =
        rows.joinToString(" ") { "${it.capability}=${it.passed}" }

    /**
     * The steps no probe can check and no adb command can perform.
     *
     * They are a separate list from [rows] by construction: a user step has no pass condition, so it
     * has no place in a table of measurements, and the screen labels every one of them as the user's
     * word. The install step is here rather than in some other screen because it is the same kind of
     * thing — a thing only a person can do — and because a reader who has just been told the app
     * verifies everything deserves to be told the one thing it cannot.
     */
    fun userSteps(): List<UserConfirmedItem> = listOf(
        UserConfirmedItem(
            id = "autostart",
            title = "Autostart",
            detail = "Settings → Apps → Manage apps → Cooldown → Autostart → turn Autostart on",
            action = RemedyAction.OpenAutostart,
        ),
        UserConfirmedItem(
            id = "battery",
            title = "Battery: No restrictions",
            detail = "Settings → Apps → Manage apps → Cooldown → Battery saver → No restrictions",
            action = RemedyAction.RequestIgnoreBatteryOptimizations,
        ),
        UserConfirmedItem(
            id = "recents",
            title = "Lock in recents",
            detail = "Open Recents, swipe the Cooldown card down (long-press it on some builds), " +
                "then tap the padlock",
            action = RemedyAction.None,
        ),
        UserConfirmedItem(
            id = "install",
            title = "Install by hand",
            detail = "adb install is refused on this ROM (INSTALL_FAILED_USER_RESTRICTED: Invalid " +
                "apk), so the APK is pushed and tapped by hand: .\\gw.ps1 :app:assembleDebug, then " +
                "adb push app\\build\\outputs\\apk\\debug\\app-debug.apk " +
                "/sdcard/Download/hypercooldown-debug.apk, then Files → Downloads → " +
                "hypercooldown-debug.apk → Install.",
            action = RemedyAction.ManualInstall,
        ),
    )

    private fun remedyFor(capability: Capability): String = when (capability) {
        Capability.NOTIFICATION_ACCESS ->
            "Open Notification access and turn the Cooldown row on. This cannot be done over adb: " +
                "a write to Settings.Secure.enabled_notification_listeners is ignored because " +
                "ManagedServices owns the policy XML."

        // Written for the state that used to look healthy, and ordered by what was actually measured
        // on the device. (1) The app heals this itself: opening the screen asks the platform for the
        // listener through the allow-list, which is the path the device proved rebinds a listener the
        // platform had stopped re-binding. That is not a step for the user, so the row says what it
        // is doing and what to check rather than telling anyone to go and toggle something. (2) Force-
        // stop and reopen: measured, the platform re-delivers the bind about 9 s after the app is
        // started again -- so this is the slower fallback, not the remedy. (3) The Settings toggle,
        // documented as the fallback for when self-heal itself fails, which is the one thing this row
        // can name that needs nothing but fingers.
        Capability.NOTIFICATION_CONNECTED ->
            "Notification access is granted, but Cooldown's listener is not connected: the platform " +
                "has not handed it to the app, so no alert is being observed and nothing is being " +
                "damped. Cooldown asks the platform to reconnect it, by itself, whenever this screen " +
                "opens -- no toggle needed. Tap Re-check after a few seconds to see whether it came " +
                "back. If it did not, close the app and open it again and tap Re-check; the listener " +
                "can take about ten seconds of a reopen to arrive here. Only if self-heal keeps " +
                "failing, open Notification access and turn the Cooldown row off and on again -- " +
                "that toggle is the fallback that needs no adb."

        Capability.SHIZUKU_BOUND ->
            "Shizuku is not running. Open the Shizuku app and tap Start, or run: adb shell sh " +
                "/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"

        Capability.SHIZUKU_PERMISSION ->
            "Shizuku is running but has not granted this app. Tap Allow in the dialog this opens. " +
                "There is no adb equivalent: a Shizuku grant is per package, and the dialog is the " +
                "only thing that can make it. The activity the standalone app shipped for this " +
                "(app.hypercooldown/.act.ShizukuGrantActivity) is not part of this host."

        Capability.ROM_ABI_PINNED ->
            "Nothing to do. This ROM's fingerprint differs from the pinned one, so the transport " +
                "transaction codes are not the ones RomAbi records. Do not edit RomAbi to clear " +
                "this row: a stale code does not fail, it calls a different method."

        Capability.TRANSPORT_B_ASSOCIATION ->
            "Expected false on this ROM. Transport B needs a CompanionDeviceManager association, " +
                "and the permission it requires is signature|privileged. Transport A (Shizuku) is " +
                "the transport that runs."
    }

    private fun actionFor(capability: Capability): RemedyAction = when (capability) {
        Capability.NOTIFICATION_ACCESS -> RemedyAction.OpenNotificationAccess

        // The same screen as the row above, and for the same reason: the toggle that rebinds the
        // listener lives in the same list, so this is the one deep link the remedy needs.
        Capability.NOTIFICATION_CONNECTED -> RemedyAction.OpenNotificationAccess

        Capability.SHIZUKU_BOUND -> RemedyAction.OpenAppDetails
        Capability.SHIZUKU_PERMISSION -> RemedyAction.OpenShizukuGrant
        Capability.ROM_ABI_PINNED -> RemedyAction.None
        Capability.TRANSPORT_B_ASSOCIATION -> RemedyAction.None
    }
}
