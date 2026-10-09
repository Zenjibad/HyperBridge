package com.d4viddf.hyperbridge.cooldown.act

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import java.util.concurrent.Executor

/**
 * The step that would make transport B reachable: a self-managed CompanionDeviceManager association,
 * which is what turns `hasCompanionDevice(info)` true for our listener and so satisfies
 * `verifyPrivilegedListener`.
 *
 * On this ROM that cannot be created by a sideloaded app. `associate()` requires
 * `android.permission.REQUEST_COMPANION_SELF_MANAGED`; `adb shell pm list permissions -f -g` reports
 * its protection level as `signature|privileged|role` (AOSP's own declaration is `signature|privileged`,
 * `research3/aosp/core__res__AndroidManifest.xml:4576`), so declaring it in the manifest is inert and
 * `associate` throws `SecurityException`. This class therefore exists to *establish* that outcome on the
 * device rather than assume it: Q3's record established the refusal on this ROM, and this is the
 * assertion that would notice if a future ROM ever relaxed it.
 */
class CdmAssociation(private val context: Context) {

    private val manager: CompanionDeviceManager
        get() = context.getSystemService(CompanionDeviceManager::class.java)

    /** The device addresses of every association this package has, or an empty list. */
    fun associations(): List<String> = try {
        manager.associations
    } catch (e: SecurityException) {
        emptyList()
    }

    /** Attempts the association and reports what happened, in one line, whatever it is. */
    fun associate(onFinished: (String) -> Unit) {
        val request = AssociationRequest.Builder()
            .setSelfManaged(true)
            .setDisplayName(DISPLAY_NAME)
            .setSingleDevice(true)
            .build()
        try {
            manager.associate(
                request,
                Executor { command -> command.run() },
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        onFinished("association created: id=${associationInfo.id}")
                    }

                    override fun onAssociationPending(intentSender: IntentSender) {
                        // Self-managed associations still need a confirmation dialog; if this fires,
                        // the user has to answer it before the association exists.
                        onFinished("association pending user confirmation (IntentSender=$intentSender)")
                    }

                    override fun onFailure(error: CharSequence?) {
                        onFinished("association failed: $error")
                    }
                },
            )
        } catch (e: SecurityException) {
            // The measured outcome on this ROM: the caller does not hold
            // android.permission.REQUEST_COMPANION_SELF_MANAGED.
            onFinished("SecurityException: ${e.message}")
        } catch (e: IllegalStateException) {
            onFinished("IllegalStateException: ${e.message}")
        }
    }

    private companion object {
        const val DISPLAY_NAME = "Cooldown"
    }
}
