package app.hypercooldown.actuation

/**
 * The channel a cooldown state applies to: the engine's key plus everything the platform needs in
 * order to address the channel. One value object rather than three loose parameters, so it can be
 * persisted next to the snapshot and passed through the coordinator unchanged.
 */
data class ChannelTarget(
    /** [CooldownKeys.perApp] of [userId] and [packageName]; the key the engine is driven under. */
    val key: String,
    val packageName: String,
    val uid: Int,
    val userId: Int,
) {
    init {
        require(key.isNotEmpty()) { "a target needs a key" }
        require(packageName.isNotEmpty()) { "a target needs a package name" }
    }

    companion object {
        fun perApp(userId: Int, packageName: String, uid: Int): ChannelTarget =
            ChannelTarget(CooldownKeys.perApp(userId, packageName), packageName, uid, userId)
    }
}
