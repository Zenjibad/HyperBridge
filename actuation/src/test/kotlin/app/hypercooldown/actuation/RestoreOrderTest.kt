package app.hypercooldown.actuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RestoreOrderTest {

    @Test
    fun `importance is restored before every other field`() {
        // Spec 8: importance has the widest blast radius -- it changes where the notification sits in
        // the shade, whether it has a status-bar icon and how the NEXT notification is ranked, and it
        // is the field a process death can leave lowered with no owner.
        assertEquals(
            listOf("importance", "lockscreenVisibility", "sound", "vibration"),
            RestoreOrder.fields(importanceTouched = true, visibilityTouched = true),
        )
    }

    @Test
    fun `visibility comes second, and a locked field nobody touched is simply absent`() {
        // The plan's first version of these two expectations was `listOf("importance")` and
        // `listOf("lockscreenVisibility")`, which contradicts both the implementation it printed and
        // its own KDoc ("sound and vibration are always listed last"): no single implementation
        // satisfies those two, the pair below in the first test, and the empty-list test together.
        // The KDoc is the coherent contract -- a restore always writes sound and vibration back; the
        // function's two booleans are about the *locked* fields, which are the ones with an order.
        assertEquals(
            listOf("importance", "sound", "vibration"),
            RestoreOrder.fields(importanceTouched = true, visibilityTouched = false),
        )
        assertEquals(
            listOf("lockscreenVisibility", "sound", "vibration"),
            RestoreOrder.fields(importanceTouched = false, visibilityTouched = true),
        )
    }

    @Test
    fun `sound and vibration come last, after every locked field that changes appearance`() {
        val fields = RestoreOrder.fields(importanceTouched = true, visibilityTouched = true)
        assertTrue(fields.indexOf("sound") > fields.indexOf("importance"))
        assertTrue(fields.indexOf("vibration") > fields.indexOf("lockscreenVisibility"))
    }

    @Test
    fun `a restore that touched no locked field still names the pair the actuator always writes`() {
        // Not an empty list: an empty list would describe a restore that restores nothing, and the
        // ordinary restore -- the one the cooldown performs on every alert -- always writes sound and
        // vibration back. "Nothing to order" is expressed by the locked fields being absent, not by
        // the list being empty.
        assertEquals(
            listOf("sound", "vibration"),
            RestoreOrder.fields(importanceTouched = false, visibilityTouched = false),
        )
    }
}
