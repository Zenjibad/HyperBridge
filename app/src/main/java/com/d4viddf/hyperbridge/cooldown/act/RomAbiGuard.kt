package com.d4viddf.hyperbridge.cooldown.act

import android.os.Build
import app.hypercooldown.actuation.WriteResult
import rikka.shizuku.SystemServiceHelper

/**
 * The two gates that stand between a stale transaction code and a corrupted channel.
 *
 * 1. `Build.FINGERPRINT` is `ro.build.fingerprint`, so an OTA that changes it may have renumbered
 *    every transaction in `INotificationManager`. Guessing again would be worse than standing down.
 * 2. If a runtime oracle for the code ever *does* answer — Q3 measured that it does not today, because
 *    reflection into the hidden `$Stub` is denied and `getTransactionCode` returns null — and it
 *    disagrees with the pinned constant, transacting anyway would call an unknown method. Q3's record
 *    asks for exactly this: fail closed rather than transact on a code that cannot be trusted.
 *
 * Both transports call [check] before they touch the binder.
 */
object RomAbiGuard {

    fun check(): WriteResult {
        val pinned = fingerprintCheck()
        if (pinned is WriteResult.Failed) return pinned
        return oracleCheck()
    }

    private fun fingerprintCheck(): WriteResult =
        if (Build.FINGERPRINT == RomAbi.BUILD_FINGERPRINT) {
            WriteResult.Ok
        } else {
            WriteResult.Failed(
                "ROM ABI not pinned: this build is ${Build.FINGERPRINT}, but the transaction codes " +
                    "were read from ${RomAbi.BUILD_FINGERPRINT}",
            )
        }

    /**
     * A null answer from the oracle is the expected case on this ROM and is not a failure; a
     * *disagreeing* answer is.
     */
    private fun oracleCheck(): WriteResult {
        val probed = try {
            @Suppress("DEPRECATION")
            SystemServiceHelper.getTransactionCode(
                ORACLE_CLASS,
                "updateNotificationChannelForPackage",
            )
        } catch (e: RuntimeException) {
            // Denied reflection, reported as a throw rather than as null by some ROMs.
            null
        } ?: return WriteResult.Ok

        return if (probed == RomAbi.UPDATE_NOTIFICATION_CHANNEL_FOR_PACKAGE) {
            WriteResult.Ok
        } else {
            WriteResult.Failed(
                "the runtime oracle reports transaction $probed for updateNotificationChannelForPackage, " +
                    "but the pinned constant is ${RomAbi.UPDATE_NOTIFICATION_CHANNEL_FOR_PACKAGE}",
            )
        }
    }

    private const val ORACLE_CLASS = "android.app.INotificationManager\$Stub"
}
