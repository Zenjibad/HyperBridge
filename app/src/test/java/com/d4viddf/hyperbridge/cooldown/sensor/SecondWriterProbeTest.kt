package com.d4viddf.hyperbridge.cooldown.sensor

import app.hypercooldown.actuation.WriterPartition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondWriterProbeTest {

    @Test
    fun `the standalone app's component in the mirror means a second writer is armed`() {
        assertTrue(
            SecondWriterProbe.armedFromMirror(
                enabledListeners = "com.d4viddf.hyperbridge/.service.NotificationReaderService:app.hypercooldown/app.hypercooldown.sensor.NotificationSensor",
                standaloneInstalled = true,
            ),
        )
    }

    @Test
    fun `the long form on its own is recognised, and it is the shape the platform writes`() {
        // `ManagedServices` writes `Settings.Secure.enabled_notification_listeners` from a
        // `Set<ComponentName>` via `TextUtils.join(":", …)`, i.e. `ComponentName.toString()` =
        // `flattenToString()` = "package/full.class". This is that shape, alone in the mirror.
        //
        // `standaloneInstalled = false` on purpose, in this test and the next one: the substring
        // fallback below matches any `app.hypercooldown/…` entry whenever the package is installed, so
        // an installed instrumented app makes these two tests pass whether or not the entry-equality
        // arms exist -- a pin that cannot fail. With the package unreadable, only the arm can answer.
        assertTrue(
            SecondWriterProbe.armedFromMirror(
                enabledListeners = "app.hypercooldown/app.hypercooldown.sensor.NotificationSensor",
                standaloneInstalled = false,
            ),
        )
    }

    @Test
    fun `the short form is accepted too, because a wrong false costs two writers on one channel`() {
        // `flattenToShortString()` ("package/.Class") is what `dumpsys` and other tooling print, and
        // nothing guarantees every reader of this mirror writes the long form. Accepting both is the
        // fail-closed direction: a wrong `true` costs a missed cooldown, a wrong `false` costs two
        // writers on one channel (spec 1.1).
        assertTrue(
            SecondWriterProbe.armedFromMirror(
                enabledListeners = "app.hypercooldown/.sensor.NotificationSensor",
                standaloneInstalled = false,
            ),
        )
    }

    @Test
    fun `our own component alone is not a second writer`() {
        assertFalse(
            SecondWriterProbe.armedFromMirror(
                enabledListeners = "com.d4viddf.hyperbridge/.service.NotificationReaderService",
                standaloneInstalled = true,
            ),
        )
    }

    @Test
    fun `an empty or absent mirror is not a second writer`() {
        assertFalse(SecondWriterProbe.armedFromMirror(enabledListeners = null, standaloneInstalled = true))
        assertFalse(SecondWriterProbe.armedFromMirror(enabledListeners = "", standaloneInstalled = true))
    }

    @Test
    fun `the mirror is the authority even when the package is not installed, because the mirror is what binds`() {
        assertTrue(
            SecondWriterProbe.armedFromMirror(
                enabledListeners = WriterPartition.STANDALONE_COMPONENT,
                standaloneInstalled = false,
            ),
        )
    }

    @Test
    fun `a component whose package merely contains the name is not the standalone app`() {
        assertFalse(
            SecondWriterProbe.armedFromMirror(
                enabledListeners = "app.hypercooldown.pro/app.hypercooldown.pro/.Listener",
                standaloneInstalled = true,
            ),
        )
    }
}
