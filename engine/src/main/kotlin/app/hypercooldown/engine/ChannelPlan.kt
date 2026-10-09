package app.hypercooldown.engine

/**
 * An ordered schedule of channel states. The first segment is the state to install now; later
 * segments are the relaxations to apply as time passes.
 */
class ChannelPlan(segments: List<Segment>) {

    data class Segment(val fromMs: Long, val state: CooldownState)

    private val segments: List<Segment> = segments.toList()

    init {
        require(this.segments.isNotEmpty()) { "a plan needs at least one segment" }
    }

    /**
     * The state the channel must carry at [atMs].
     *
     * Before the first segment the plan's first state is returned, so the mapping is total: a plan
     * is a description of "what to install now and when to relax", and a query earlier than the
     * first segment must not silently claim DEFAULT when the plan says otherwise.
     */
    fun stateAt(atMs: Long): CooldownState =
        segments.lastOrNull { it.fromMs <= atMs }?.state ?: segments.first().state

    /** The next time the state must change after [atMs], or null if it never changes again. */
    fun nextChangeAfter(atMs: Long): Long? =
        segments.firstOrNull { it.fromMs > atMs }?.fromMs

    fun segments(): List<Segment> = segments
}
