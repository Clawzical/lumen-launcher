package dev.lumen.launcher.auth

import dev.lumen.launcher.auth.secrets.WindowsCredentialStore
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Talks to the real Credential Manager (Windows only). Uses a throwaway service name and cleans up. */
class WindowsCredentialStoreTest {
    private val isWindows = "win" in System.getProperty("os.name").lowercase()

    @Test fun `round-trips short and chunked secrets, then deletes them`() = runTest {
        if (!isWindows) return@runTest
        val store = WindowsCredentialStore("LumenTest-${UUID.randomUUID()}")
        val key = "unit-test"
        try {
            store.put(key, "short-secret")
            assertEquals("short-secret", store.get(key))

            // Longer than one 2,560-byte credential blob → stored in chunks.
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789-_.!*"
            val long = (1..6_000).map { alphabet[it % alphabet.length] }.joinToString("")
            store.put(key, long)
            assertEquals(long, store.get(key))

            store.put(key, "shorter-again") // must drop the old chunks
            assertEquals("shorter-again", store.get(key))
        } finally {
            store.delete(key)
        }
        assertNull(store.get(key))
    }
}
