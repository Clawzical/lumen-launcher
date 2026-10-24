package dev.lumen.launcher.auth

import dev.lumen.launcher.auth.secrets.SecretStore
import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.net.Http
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Non-secret account metadata, stored in accounts.json. Tokens are never in here. */
@Serializable
data class StoredAccount(val id: String, val name: String, val skinUrl: String? = null, val addedAt: Long)

@Serializable
data class AccountsState(val accounts: List<StoredAccount> = emptyList(), val activeId: String? = null) {
    val active: StoredAccount? get() = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
}

/** What the launch step needs: a fresh Minecraft token for the active profile. */
data class LaunchCredentials(val name: String, val uuid: String, val accessToken: String)

/**
 * Owns the sign-in chain and account lifecycle:
 *  - sign-in: device code → MSA tokens → Xbox Live → XSTS → Minecraft token → profile
 *  - launch: reuse the cached Minecraft token (~24 h) or silently refresh from the stored refresh token
 *  - refresh tokens live only in the OS keychain (or memory when there is none)
 */
class AccountManager(
    private val paths: LauncherPaths,
    private val http: HttpClient,
    private val clientId: String?,
    private val secrets: SecretStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = paths.root.resolve("accounts.json")
    private val mutex = Mutex()
    private val xbox = XboxMinecraftAuth(http)
    private val sessions = ConcurrentHashMap<String, CachedSession>()
    private val memoryRefreshTokens = ConcurrentHashMap<String, String>()

    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    val isConfigured: Boolean get() = clientId != null
    val keychain: SecretStore get() = secrets

    private data class CachedSession(val token: String, val expiresAt: Long)

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists()) {
            runCatching { _state.value = Http.json.decodeFromString(AccountsState.serializer(), file.readText()) }
        }
    }

    /**
     * Full interactive sign-in. [onCode] fires once the player has a code to enter; [onStage]
     * narrates the Xbox/Minecraft steps afterwards. Cancelling the coroutine stops polling.
     */
    suspend fun signIn(onCode: (DeviceCode) -> Unit, onStage: (String) -> Unit): StoredAccount {
        val msa = MicrosoftAuthClient(http, clientId ?: throw AuthException.NotConfigured())
        val code = msa.startDeviceCode()
        onCode(code)
        val tokens = msa.awaitToken(code)
        return mutex.withLock { completeSignIn(tokens, onStage) }
    }

    suspend fun setActive(id: String) = mutex.withLock {
        update { it.copy(activeId = id) }
    }

    suspend fun signOut(id: String) = mutex.withLock {
        sessions.remove(id)
        memoryRefreshTokens.remove(id)
        runCatching { secrets.delete(secretKey(id)) }
        update { s ->
            val rest = s.accounts.filterNot { it.id == id }
            s.copy(accounts = rest, activeId = if (s.activeId == id) rest.firstOrNull()?.id else s.activeId)
        }
    }

    /**
     * Credentials for the active account, refreshing if the cached Minecraft token is within
     * 5 minutes of expiry. Returns null when no account is signed in (the caller may go offline).
     */
    suspend fun launchCredentials(): LaunchCredentials? = mutex.withLock {
        val account = _state.value.active ?: return@withLock null
        sessions[account.id]?.takeIf { it.expiresAt - now() > 5 * 60_000 }?.let {
            return@withLock LaunchCredentials(account.name, account.id, it.token)
        }
        val msa = MicrosoftAuthClient(http, clientId ?: throw AuthException.NotConfigured())
        val refreshToken = memoryRefreshTokens[account.id] ?: secrets.get(secretKey(account.id))
            ?: throw AuthException.SessionExpired()
        val refreshed = completeSignIn(msa.refresh(refreshToken)) {}
        LaunchCredentials(refreshed.name, refreshed.id, sessions.getValue(refreshed.id).token)
    }

    private suspend fun completeSignIn(tokens: MsaTokens, onStage: (String) -> Unit): StoredAccount {
        onStage("Signing in to Xbox Live")
        val xbl = xbox.xboxLive(tokens.accessToken)
        onStage("Getting an Xbox security token")
        val xsts = xbox.xsts(xbl)
        onStage("Signing in to Minecraft")
        val mc = xbox.minecraft(xsts)
        onStage("Loading your Minecraft profile")
        val profile = xbox.profile(mc)

        val id = profile.id
        tokens.refreshToken?.let { rt ->
            if (secrets.isAvailable) {
                runCatching { secrets.put(secretKey(id), rt) }.onFailure { memoryRefreshTokens[id] = rt }
            } else {
                memoryRefreshTokens[id] = rt
            }
        }
        sessions[id] = CachedSession(mc.accessToken, now() + mc.expiresIn * 1000)

        val existing = _state.value.accounts.firstOrNull { it.id == id }
        val account = StoredAccount(id, profile.name, profile.activeSkinUrl, existing?.addedAt ?: now())
        update { s -> s.copy(accounts = s.accounts.filterNot { it.id == id } + account, activeId = id) }
        return account
    }

    private suspend fun update(transform: (AccountsState) -> AccountsState) {
        val next = transform(_state.value)
        _state.value = next
        withContext(Dispatchers.IO) {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("accounts.json.tmp")
            tmp.writeText(Http.json.encodeToString(AccountsState.serializer(), next))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun secretKey(id: String) = "msa-refresh-token:$id"
}
