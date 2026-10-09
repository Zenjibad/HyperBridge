package app.hypercooldown.actuation

/** A clock the test drives by hand, in milliseconds. */
class MutableClock(var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs
}
