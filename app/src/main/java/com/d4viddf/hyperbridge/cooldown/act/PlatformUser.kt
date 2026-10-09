package com.d4viddf.hyperbridge.cooldown.act

import android.os.Process
import android.os.UserHandle

/**
 * The current user, in the two shapes the platform makes available without hidden API.
 *
 * `UserHandle.myUserId()`, `UserHandle.of(int)` and `UserHandle.getIdentifier()` are all `@hide`, so
 * `targetSdk 36` denies them at *link* time and naming any of them does not compile — the same
 * failure mode Q3 measured for a bundled `INotificationManager` AIDL, and for the same reason: the
 * public `android.jar` does not carry them and the runtime refuses the linkage. The two public routes
 * are [Process.myUserHandle], which returns the [UserHandle] itself, and `UserHandle.hashCode()`,
 * which AOSP implements as `mHandle` — the numeric user id — and which is consequently the only
 * public way to read that number back out.
 *
 * One derivation, used everywhere, is what keeps the harness's key and the sensor's key the same key.
 * Plan 2 handles the current user only, so the fact that a hash could in principle collide across
 * users is outside what this layer claims: the sensor skips a notification from another user before
 * it ever asks for a key.
 */
object PlatformUser {

    fun handle(): UserHandle = Process.myUserHandle()

    fun id(): Int = idOf(handle())

    /** The numeric user id of [handle]. See the class comment for why this is `hashCode`. */
    fun idOf(handle: UserHandle): Int = handle.hashCode()
}
