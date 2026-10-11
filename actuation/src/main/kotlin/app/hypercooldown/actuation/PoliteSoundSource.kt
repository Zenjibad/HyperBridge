package app.hypercooldown.actuation

/**
 * Supplies the attenuated copy of a channel's own sound for POLITE (spec §5.5).
 *
 * Plan 2 ships [NoPoliteSound]. Producing the copy — read the bytes, decode, scale to 0.30, encode,
 * publish somewhere the player can open — is `AttenuatedSoundFactory`, and that is Plan 3's. The
 * seam is fixed here because Q1 settled that it can be filled: on this ROM the sound is played by
 * SystemUI's `RingtonePlayer` (uid 10287), and a 30 %-amplitude WAV published at
 * `content://media/external/audio/media/8161` was opened and played by it (Plan 1 Task 15,
 * commit `39162d9`). Replacing [NoPoliteSound] is therefore a drop-in change, not a redesign.
 */
fun interface PoliteSoundSource {
    /**
     * A `content://` URI the platform can open at alert time, or null when no copy is available.
     *
     * [factor] is the attenuation **for this alert**, passed per call rather than held by the source:
     * it is `EngineConfig.politeVolume` for the app being written, and the user can change it between
     * two alerts. A source that captured it once would keep serving the copy the process started with,
     * which is the reported "a change needs a reboot"; `EditFactory` has the live config in hand, so
     * this is where the number belongs.
     */
    fun politeSoundFor(snapshot: ChannelSnapshot, factor: Float): String?
}

/**
 * Plan 2's source: no copy is produced, so POLITE damps the vibration only, leaves the channel's own
 * sound exactly where it was, and records [FidelityGap.POLITE_SOUND_UNAVAILABLE].
 *
 * Leaving the sound alone — rather than clearing it — is the whole of spec §5.6's degradation rule as
 * the device forced it to be read: a POLITE state that silences a channel cannot be recovered once the
 * record that would undo it is gone (root-cause §3.5, §4). It publishes nothing, so it can never
 * recognise a channel's sound as its own copy and the inherited answer to
 * [PoliteSoundSource.isOurPublishedCopy] is the correct one.
 */
object NoPoliteSound : PoliteSoundSource {
    override fun politeSoundFor(snapshot: ChannelSnapshot, factor: Float): String? = null
}
