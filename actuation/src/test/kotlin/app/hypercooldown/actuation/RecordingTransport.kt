package app.hypercooldown.actuation

/**
 * A [ChannelTransport] that records every write.
 *
 * It also lazily builds the channel's pristine content for [pristine]'s channel id, so tests that
 * want to observe what "the platform would have handed back" do not have to invent it.
 */
class RecordingTransport(
    private val operations: MutableList<String> = mutableListOf(),
    private val channelId: String = "messages",
) : ChannelTransport {

    data class Call(val target: ChannelTarget, val snapshot: ChannelSnapshot, val edit: ChannelEdit)

    val calls = mutableListOf<Call>()

    /** Set to make the next write fail. */
    var nextResult: WriteResult = WriteResult.Ok

    override val name: String = "recording"

    override fun write(target: ChannelTarget, snapshot: ChannelSnapshot, edit: ChannelEdit): WriteResult {
        operations += "write:${target.key}"
        calls += Call(target, snapshot, edit)
        return nextResult
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
}
