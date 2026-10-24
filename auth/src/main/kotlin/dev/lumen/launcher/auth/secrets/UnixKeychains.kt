package dev.lumen.launcher.auth.secrets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * macOS login keychain via `/usr/bin/security`. Writes go through `security -i` (commands on stdin)
 * so the token never appears in another process's view of our command line.
 */
class MacKeychainStore(private val service: String) : SecretStore {
    override val isAvailable: Boolean get() = java.io.File(SECURITY).canExecute()
    override val description = "macOS Keychain"

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val (code, out) = runTool(listOf(SECURITY, "find-generic-password", "-s", service, "-a", key, "-w"))
        if (code == 0) out.trimEnd('\n') else null // 44 = item not found
    }

    override suspend fun put(key: String, secret: String) = withContext(Dispatchers.IO) {
        val cmd = "add-generic-password -U -s ${q(service)} -a ${q(key)} -w ${q(secret)}\n"
        val (code, _) = runTool(listOf(SECURITY, "-i"), stdin = cmd)
        check(code == 0) { "security add-generic-password failed ($code)" }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        runTool(listOf(SECURITY, "delete-generic-password", "-s", service, "-a", key))
        Unit
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val SECURITY = "/usr/bin/security"
    }
}

/** Linux Secret Service (GNOME Keyring, KWallet) via libsecret's `secret-tool`; secrets on stdin. */
class LinuxSecretToolStore(private val service: String) : SecretStore {
    override val isAvailable: Boolean get() = onPath("secret-tool")
    override val description = "the system keyring"

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val (code, out) = runTool(listOf("secret-tool", "lookup", "service", service, "account", key))
        if (code == 0 && out.isNotEmpty()) out else null
    }

    override suspend fun put(key: String, secret: String) = withContext(Dispatchers.IO) {
        val (code, _) = runTool(
            listOf("secret-tool", "store", "--label=$service ($key)", "service", service, "account", key),
            stdin = secret,
        )
        check(code == 0) { "secret-tool store failed ($code)" }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        runTool(listOf("secret-tool", "clear", "service", service, "account", key))
        Unit
    }
}
