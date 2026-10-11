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

    /**
     * Whether [soundUri] is a copy **this source published** for POLITE, rather than a sound the user
     * or the origin app owns.
     *
     * The publisher is the only thing that can answer this, which is why the question lives here
     * rather than in a registry of its own. It is asked by `ChannelActuator` *before* a pristine
     * snapshot is taken, because a channel already carrying our damping is not the user's original: a
     * record taken from it makes our attenuation the baseline — the next POLITE scales an already
     * scaled copy (0.30 x 0.30 = 9 %) and the restore puts the copy back for good. On this device that
     * happened for `com.android.shell/shell_cmd`, whose archived record holds our own 10 % copy as its
     * `soundUri` (root-cause §3.3).
     *
     * The in-process write marker in `ChannelActuator` cannot cover this: it is this process's memory
     * of its own writes, so it knows nothing about a copy an earlier install, another process or the
     * standalone `app.hypercooldown` published — the cross-writer route, and on this ROM the *usual*
     * one, because every development iteration is a reinstall.
     *
     * Answers `false` by default, which is the honest answer for a source that publishes nothing
     * ([NoPoliteSound]) and for every test double. The default is deliberately the *permissive* one:
     * this is a provenance question about a URI, and a source that cannot answer it must not be read as
     * "everything is ours", which would refuse to damp every channel on the device.
     */
    fun isOurPublishedCopy(soundUri: String?): Boolean = false
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
