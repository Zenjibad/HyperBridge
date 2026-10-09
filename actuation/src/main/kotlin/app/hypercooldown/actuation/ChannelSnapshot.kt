package app.hypercooldown.actuation

/**
 * A channel as the platform handed it to us, plus everything needed to write it back unchanged.
 *
 * [channelBytes] is the base64 of the channel's own `Parcel` marshalling, taken from the object the
 * platform delivered in `Ranking.getChannel()`. It is what makes a restore faithful after a process
 * restart — the app cannot read another package's channels (spec §4: the read API is
 * `enforceSystemOrSystemUI`), and it is what preserves the fields the public API cannot set.
 *
 * A null [channelBytes] means "build the channel from [fields] alone", which is only safe for a
 * channel known to be at platform defaults; the only place that happens is Task 10's seed of the
 * shell's own `shell_cmd` channel, and it says so in the log.
 */
data class ChannelSnapshot(
    val fields: ChannelFields,
    val channelBytes: String?,
)
