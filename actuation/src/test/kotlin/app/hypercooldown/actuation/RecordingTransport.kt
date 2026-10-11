package app.hypercooldown.actuation

/**
 * A [ChannelTransport] that records every write.
 *
 * It also lazily builds the channel's pristine content for [pristine]'s channel id, so tests that
 * want to observe what "the platform would have handed back" do not have to invent it.
 *
 * It models the channel's **lock mask** as well, because that is the only way "a restore releases the
 * importance lock" can be a statement about the channel rather than about which methods happened to be
 * called: `lockFieldsForUpdateLocked` locks a field whose value a write changed, and transaction 43
 * clears the importance bit and nothing else. So [userLockedFields] is the thing a test asserts on,
 * and deleting the unlock call leaves a bit set in it.
 */
class RecordingTransport(
    private val operations: MutableList<String> = mutableListOf(),
    private val channelId: String = "messages",
) : ChannelTransport {

    data class Call(val target: ChannelTarget, val snapshot: ChannelSnapshot, val edit: ChannelEdit)

    val calls = mutableListOf<Call>()

    /** Set to make the next write fail. */
    var nextResult: WriteResult = WriteResult.Ok

    /** Set to make the next unlock fail, the way a dead binder would. */
    var nextUnlockResult: WriteResult = WriteResult.Ok

    /**
     * Whether this transport can send `unlockNotificationChannel` (43). True by default so that the
     * transport is a *capable* one unless a test is specifically about an incapable one; the interface
     * default is `false`, which is what a real transport with no transaction 43 gets.
     */
    override var canUnlockImportance: Boolean = true

    /** Every unlock this transport was asked to send — its target, as the transaction addresses it. */
    val unlocks = mutableListOf<ChannelTarget>()

    /** Every channel id an unlock named, in the order they were asked for. */
    val unlockedChannels = mutableListOf<String>()

    /**
     * The channel's `mUserLockedFields`, as the platform's own lock arithmetic would leave it.
     *
     * Only the importance bit is modelled: it is the one these tests are about, and `:actuation` has
     * no Android dependency, so `:app`'s `LockResidue` — which owns the whole mask and the derivation
     * for every field — is not reachable from here. The full arithmetic is pinned by
     * `RestoreGuardTest` on the other side of the module boundary.
     */
    var userLockedFields: Int = 0
        private set

    override val name: String = "recording"

    override fun write(target: ChannelTarget, snapshot: ChannelSnapshot, edit: ChannelEdit): WriteResult {
        operations += "write:${target.key}"
        calls += Call(target, snapshot, edit)
        if (nextResult is WriteResult.Failed) return nextResult
        if (snapshot.fields.importance != edit.fields.importance) {
            userLockedFields = userLockedFields or IMPORTANCE_LOCK
        }
        return nextResult
    }

    override fun unlockImportance(target: ChannelTarget, channelId: String): WriteResult {
        operations += "unlock:${target.key}"
        unlocks += target
        unlockedChannels += channelId
        if (nextUnlockResult is WriteResult.Failed) return nextUnlockResult
        userLockedFields = userLockedFields and IMPORTANCE_LOCK.inv()
        return WriteResult.Ok
    }

    /**
     * This transport normalises nothing, so the channel carries exactly the edit — which is also what
     * [observed] reports. The two are separate calls on purpose: a fake that derived something would
     * have to override this, and that is the whole point of the seam.
     */
    override fun appliedContent(
        target: ChannelTarget,
        snapshot: ChannelSnapshot,
        edit: ChannelEdit,
    ): ChannelFields = edit.fields

    fun lastEdit(): ChannelEdit? = calls.lastOrNull()?.edit

    /** What the channel carries now, as the platform would report it. */
    fun observed(): ChannelSnapshot = snapshot(
        fields = lastEdit()?.fields ?: channelFields(channelId = channelId),
    )

    companion object {
        /** `NotificationChannel.USER_LOCKED_IMPORTANCE` — see [userLockedFields]. */
        const val IMPORTANCE_LOCK = 4
    }
}
