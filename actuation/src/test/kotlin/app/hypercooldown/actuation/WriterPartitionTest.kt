package app.hypercooldown.actuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WriterPartitionTest {

    private val host = "com.d4viddf.hyperbridge"

    @Test
    fun `with no second writer armed, every package is ours`() {
        assertEquals(
            WriterPartition.Verdict.Write,
            WriterPartition.decide(secondWriterArmed = false, ownedPackages = emptySet(), packageName = "com.example.chat", ownPackage = host),
        )
    }

    @Test
    fun `with a second writer armed and no ownership list, the fork concedes everything`() {
        assertEquals(
            WriterPartition.Verdict.Refuse(WriterPartition.Refusal.SECOND_WRITER_ACTIVE),
            WriterPartition.decide(secondWriterArmed = true, ownedPackages = emptySet(), packageName = "com.example.chat", ownPackage = host),
        )
    }

    @Test
    fun `with a second writer armed, only the listed packages are ours`() {
        assertEquals(
            WriterPartition.Verdict.Write,
            WriterPartition.decide(secondWriterArmed = true, ownedPackages = setOf("com.example.chat"), packageName = "com.example.chat", ownPackage = host),
        )
    }

    @Test
    fun `with a second writer armed, an unlisted package is refused`() {
        assertEquals(
            WriterPartition.Verdict.Refuse(WriterPartition.Refusal.NOT_OWNED),
            WriterPartition.decide(secondWriterArmed = true, ownedPackages = setOf("com.example.chat"), packageName = "com.example.mail", ownPackage = host),
        )
    }

    @Test
    fun `the standalone app's own package is never writable, even if a list names it`() {
        assertEquals(
            WriterPartition.Verdict.Refuse(WriterPartition.Refusal.FOREIGN_WRITER),
            WriterPartition.decide(secondWriterArmed = false, ownedPackages = setOf(WriterPartition.STANDALONE_PACKAGE), packageName = WriterPartition.STANDALONE_PACKAGE, ownPackage = host),
        )
    }

    @Test
    fun `the host's own package is never writable, even if a list names it`() {
        assertEquals(
            WriterPartition.Verdict.Refuse(WriterPartition.Refusal.OWN_PACKAGE),
            WriterPartition.decide(secondWriterArmed = false, ownedPackages = setOf(host), packageName = host, ownPackage = host),
        )
    }

    @Test
    fun `a malformed package name is refused rather than sent to the transport`() {
        assertEquals(
            WriterPartition.Verdict.Refuse(WriterPartition.Refusal.OWN_PACKAGE),
            WriterPartition.decide(secondWriterArmed = false, ownedPackages = emptySet(), packageName = "", ownPackage = host),
        )
    }

    @Test
    fun `no argument combination turns a refusal into a write except the two named ones`() {
        // The property, stated as a test: for the whole cross-product of the inputs, a Refuse is only
        // ever produced by the second-writer precondition, the ownership list, or the two permanent
        // refusals -- never by a default that a caller could forget to set.
        var writes = 0
        for (armed in listOf(false, true)) {
            for (owned in listOf(emptySet<String>(), setOf("com.example.chat"), setOf("com.example.chat", "com.example.mail"))) {
                for (pkg in listOf("com.example.chat", "com.example.mail", host, "")) {
                    val verdict = WriterPartition.decide(
                        secondWriterArmed = armed,
                        ownedPackages = owned,
                        packageName = pkg,
                        ownPackage = host,
                    )
                    if (verdict is WriterPartition.Verdict.Write) {
                        writes++
                        assertTrue(!armed || owned.contains(pkg), "a write is only legal when nobody else is armed or the key is ours")
                        assertTrue(pkg != host && pkg.isNotEmpty())
                    }
                }
            }
        }
        // 9 of the 24 combinations are writes, and the plan's first number here (5) was wrong. Derived
        // from this file's own `decide`, not adjusted to taste: with nobody else armed both non-host,
        // non-empty names write against all three lists (2 x 3 = 6); with a second writer armed only a
        // listed name writes -- (chat,{chat}), (chat,{chat,mail}), (mail,{chat,mail}) -- so 3 more.
        // The own package and the empty name are refused on every path, and STANDALONE_PACKAGE is not
        // in the cross-product at all.
        assertEquals(9, writes)
    }

    @Test
    fun `a disabled cooldown writes nothing, whatever the rest of the inputs say`() {
        // Spec 4.5's last two rows: the master enable and the per-app enable, which resolves to the
        // same nullable field. "Off" has to be a refusal at the write path rather than something the
        // UI hides, because the option set is data and a caller can hand it over in any combination.
        for (armed in listOf(false, true)) {
            for (owned in listOf(emptySet<String>(), setOf("com.example.chat"))) {
                for (pkg in listOf("com.example.chat", "com.example.mail")) {
                    assertEquals(
                        WriterPartition.Verdict.Refuse(WriterPartition.Refusal.DISABLED),
                        WriterPartition.decide(
                            enabled = false,
                            secondWriterArmed = armed,
                            ownedPackages = owned,
                            packageName = pkg,
                            ownPackage = host,
                        ),
                        "a disabled cooldown must not write $pkg (armed=$armed, owned=$owned)",
                    )
                }
            }
        }
    }

    @Test
    fun `with the option absent the cooldown is enabled, which is AOSP's own default`() {
        // `notification_cooldown_enabled` defaults to 1 on AOSP (design spec 2.2), and the option
        // field is nullable, so "nobody has ever touched this setting" must read as on rather than
        // as off -- otherwise the feature ships inert and every device criterion fails silently.
        assertEquals(
            WriterPartition.Verdict.Write,
            WriterPartition.decide(
                secondWriterArmed = false,
                ownedPackages = emptySet(),
                packageName = "com.example.chat",
                ownPackage = host,
            ),
        )
    }
}
