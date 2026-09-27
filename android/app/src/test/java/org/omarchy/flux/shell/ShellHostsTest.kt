package org.omarchy.flux.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellHostsTest {
    @Test
    fun aBareNameBecomesTheShellAddress() {
        assertEquals("https://desktop.example.ts.net/native/", normalizeShellUrl("desktop.example.ts.net", false))
    }

    @Test
    fun anExistingNativePathIsNotDoubled() {
        for (address in listOf("https://a.example", "https://a.example/", "https://a.example/native", "https://a.example/native/")) {
            assertEquals("https://a.example/native/", normalizeShellUrl(address, false))
        }
    }

    @Test
    fun aCustomPathIsRefused() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            normalizeShellUrl("https://a.example/other", false)
        }
        assertEquals("Use the host address without a custom path", error.message)
    }

    @Test
    fun aQueryOrFragmentIsRefusedBecauseTheOriginIsWhatIsTrusted() {
        assertThrows(IllegalArgumentException::class.java) { normalizeShellUrl("https://a.example/?x=1", false) }
        assertThrows(IllegalArgumentException::class.java) { normalizeShellUrl("https://a.example/#f", false) }
    }

    @Test
    fun credentialsInTheAddressAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { normalizeShellUrl("https://user:pw@a.example", false) }
    }

    @Test
    fun plainHttpIsRefusedExceptForTheLocalAliasesOfADebugBuild() {
        assertThrows(IllegalArgumentException::class.java) { normalizeShellUrl("http://a.example", false) }
        assertThrows(IllegalArgumentException::class.java) { normalizeShellUrl("http://a.example", true) }
        assertEquals("http://127.0.0.1:4187/native/", normalizeShellUrl("http://127.0.0.1:4187", true))
        assertEquals("http://10.0.2.2:4187/native/", normalizeShellUrl("http://10.0.2.2:4187", true))
    }

    @Test
    fun anAddressWithNoHostIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { normalizeShellUrl("https://", false) }
    }

}
