package app.hypercooldown.actuation

/**
 * The key cooldown state is kept under.
 *
 * AOSP's `getChannelKey` is `"$userId:$packageName:$channelId"`, but with `notification_cooldown_all`
 * enabled (its default) and a channel that has no user-set sound, the channel id is **blanked** —
 * which collapses the key to the per-app `"$userId:$packageName"`. Spec §5.1 pins that per-app form,
 * so every channel of one app shares one cooldown, exactly as AOSP's default behaviour does.
 */
object CooldownKeys {
    fun perApp(userId: Int, packageName: String): String = "$userId:$packageName"
}
