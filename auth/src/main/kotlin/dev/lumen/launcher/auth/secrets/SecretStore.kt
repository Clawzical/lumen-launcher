package dev.lumen.launcher.auth.secrets

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The OS keychain. Only refresh tokens go here; nothing secret is ever written to Lumen's own files.
 * When no keychain is reachable the store reports [isAvailable] = false and the app keeps tokens
 * in memory for the session instead of falling back to a plaintext file.
 */
interface SecretStore {
    val isAvailable: Boolean
    /** Human name for UI copy, e.g. "Windows Credential Manager". */
    val description: String

    suspend fun get(key: String): String?
    suspend fun put(key: String, secret: String)
    suspend fun delete(key: String)

    companion object {
        fun forCurrentOs(service: String = "Lumen"): SecretStore {
            val os = System.getProperty("os.name").lowercase()
            val store = runCatching {
                when {
                    "win" in os -> WindowsCredentialStore(service)
                    "mac" in os -> MacKeychainStore(service)
                    else -> LinuxSecretToolStore(service)
                }
            }.getOrNull()
            return store?.takeIf { it.isAvailable } ?: UnavailableSecretStore
        }
    }
}

object UnavailableSecretStore : SecretStore {
    override val isAvailable = false
    override val description = "no system keychain"
    override suspend fun get(key: String): String? = null
    override suspend fun put(key: String, secret: String) = Unit
    override suspend fun delete(key: String) = Unit
}

/** Runs a CLI keychain tool; secrets travel via stdin, never argv (argv is visible to other processes). */
internal fun runTool(command: List<String>, stdin: String? = null): Pair<Int, String> {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.use { out -> if (stdin != null) out.write(stdin.toByteArray()) }
    val stdout = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("${command.first()} timed out")
    }
    return process.exitValue() to stdout
}

internal fun onPath(tool: String): Boolean =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, tool).canExecute() }
