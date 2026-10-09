package com.d4viddf.hyperbridge.cooldown.act

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSelectorTest {

    private fun report(vararg usable: Capability): ProbeReport =
        ProbeReport(Capability.entries.associateWith { it in usable })

    private val shizukuReady = arrayOf(
        Capability.SHIZUKU_BOUND,
        Capability.SHIZUKU_PERMISSION,
        Capability.ROM_ABI_PINNED,
    )

    @Test
    fun `a companion-device association wins, because it needs no hidden ABI`() {
        assertEquals(
            TransportChoice.PRIVILEGED_LISTENER,
            TransportSelector.choose(report(*shizukuReady, Capability.TRANSPORT_B_ASSOCIATION)),
        )
    }

    @Test
    fun `shizuku is used when it is bound, permitted and the ROM ABI is pinned`() {
        assertEquals(TransportChoice.SHIZUKU, TransportSelector.choose(report(*shizukuReady)))
    }

    @Test
    fun `shizuku without its permission is not a transport`() {
        assertEquals(
            TransportChoice.NONE,
            TransportSelector.choose(report(Capability.SHIZUKU_BOUND, Capability.ROM_ABI_PINNED)),
        )
    }

    @Test
    fun `an unpinned ROM ABI is never used, however healthy Shizuku looks`() {
        // The transaction codes may have moved with the ROM; sending a stale one calls a different
        // method rather than failing.
        assertEquals(
            TransportChoice.NONE,
            TransportSelector.choose(report(Capability.SHIZUKU_BOUND, Capability.SHIZUKU_PERMISSION)),
        )
    }

    @Test
    fun `with nothing available the choice is NONE rather than a hopeful guess`() {
        assertEquals(TransportChoice.NONE, TransportSelector.choose(report()))
        assertTrue(TransportSelector.describe(TransportChoice.NONE).isNotEmpty())
    }
}
