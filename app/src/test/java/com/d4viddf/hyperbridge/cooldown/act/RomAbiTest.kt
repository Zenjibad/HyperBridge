package com.d4viddf.hyperbridge.cooldown.act

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins [RomAbi]'s transaction codes to the disassembly extract they were read from.
 *
 * A transaction code cannot be checked at runtime: sending a wrong one silently invokes a different
 * `INotificationManager` method, and shell passes the caller guard for all of them. The only defence
 * is that the number in the source is the number in the ROM's bytecode, so this test re-reads the
 * extract and fails if the two ever disagree.
 */
class RomAbiTest {

    private val dump: String by lazy {
        val stream = javaClass.getResourceAsStream("/rom-abi/inotificationmanager.txt")
        assertNotNull("the disassembly extract is missing from app/src/test/resources/rom-abi/", stream)
        stream!!.bufferedReader().readText()
    }

    @Test
    fun `updateNotificationChannelForPackage transacts with the pinned code`() {
        assertEquals(
            RomAbi.UPDATE_NOTIFICATION_CHANNEL_FOR_PACKAGE,
            transactCodeOf("updateNotificationChannelForPackage"),
        )
    }

    @Test
    fun `unlockNotificationChannel transacts with the pinned code`() {
        assertEquals(RomAbi.UNLOCK_NOTIFICATION_CHANNEL, transactCodeOf("unlockNotificationChannel"))
    }

    @Test
    fun `the privileged-listener variant transacts with the pinned code`() {
        assertEquals(
            RomAbi.UPDATE_NOTIFICATION_CHANNEL_FROM_PRIVILEGED_LISTENER,
            transactCodeOf("updateNotificationChannelFromPrivilegedListener"),
        )
    }

    @Test
    fun `the listener re-grant transacts with the pinned code`() {
        assertEquals(
            RomAbi.SET_NOTIFICATION_LISTENER_ACCESS_GRANTED_FOR_USER,
            transactCodeOf("setNotificationListenerAccessGrantedForUser"),
        )
    }

    /**
     * The second corroboration, in the same dump and from the other side of the interface: the
     * `$Stub`'s own static final `TRANSACTION_…` field. The proxy's `const/16` and this number are
     * written by two different code generators from the same AIDL, so an extract that carried one but
     * not the other could pin a plausible-looking number that no server would ever dispatch.
     */
    @Test
    fun `the stub declares the same code the proxy sends`() {
        assertEquals(
            RomAbi.SET_NOTIFICATION_LISTENER_ACCESS_GRANTED_FOR_USER,
            stubTransactionFieldOf("setNotificationListenerAccessGrantedForUser"),
        )
    }

    /**
     * The third corroboration: the `onTransact` case for that code. It must read the signature the
     * proxy writes — a `ComponentName`, an `int` and two `boolean`s — because a code that dispatched
     * to a method with a different argument list would pass a wrong parcel and silently mangle it.
     */
    @Test
    fun `the stub's case for that code reads the arguments the proxy writes`() {
        val dispatch = dump.substringAfter("$STUB.onTransact:case ").substringBefore("\ncalls ")
        assertEquals(
            RomAbi.SET_NOTIFICATION_LISTENER_ACCESS_GRANTED_FOR_USER,
            dispatch.substringBefore(" ->").trim().toInt(),
        )

        val called = dump.substringAfter("calls $STUB;.").trim()
        // `trim`, because the extract is checked out with CRLF line endings on this machine.
        val sent = dump
            .substringAfter("$PROXY.setNotificationListenerAccessGrantedForUser:")
            .substringBefore("\n")
            .trim()
        assertEquals(sent, called.substringAfter("setNotificationListenerAccessGrantedForUser:"))
    }

    @Test
    fun `every proxy method writes the same interface token`() {
        val tokens = WRITE_INTERFACE_TOKEN.findAll(dump).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(RomAbi.DESCRIPTOR), tokens)
    }

    /**
     * The code is whatever was loaded into the register the following `transact` call passes as its
     * first argument, which is how the extract was read by hand from the dump as well. That register
     * differs per method, so it is taken from the call rather than assumed.
     */
    private fun transactCodeOf(method: String): Int {
        val body = dump.substringAfter("$PROXY.$method:").substringBefore("\nandroid.app.")
        val transact = TRANSACT.find(body)
        assertNotNull("no transact call found for $method", transact)
        // `transact(code, data, reply, flags)` is called with {binder, code, data, reply, flags}, so
        // the code is the second register of the list.
        val register = transact!!.groupValues[2]
        val code = Regex("const/16 $register, #int (\\d+)").find(body)
        assertNotNull("no const/16 into $register for $method", code)
        return code!!.groupValues[1].toInt()
    }

    /**
     * The `$Stub` field's pinned value, read from the same extract. `$Stub` is the server side of the
     * interface, so this is the number the ROM's own `onTransact` switches on.
     */
    private fun stubTransactionFieldOf(method: String): Int {
        val body = dump
            .substringAfter("$STUB.TRANSACTION_$method:I")
            .substringBefore("\nandroid.app.")
        val value = Regex("value\\s+(\\d+)").find(body)
        assertNotNull("no pinned value for TRANSACTION_$method", value)
        return value!!.groupValues[1].toInt()
    }

    private companion object {
        const val PROXY = "INotificationManager\$Stub\$Proxy"
        const val STUB = "android.app.INotificationManager\$Stub"
        val WRITE_INTERFACE_TOKEN = Regex("const-string v2, \"([^\"]+)\"")
        val TRANSACT = Regex(
            "invoke-interface \\{(v\\d+), (v\\d+), v\\d+, v\\d+, v\\d+\\}, Landroid/os/IBinder;\\.transact:",
        )
    }
}
