package dev.lumen.launcher.auth

import dev.lumen.launcher.core.net.Http
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
    val message: String? = null,
)

@Serializable
data class MsaTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

/** Where the client ID comes from: a system property (set by Gradle from gradle.properties) or the environment. */
object MsaConfig {
    fun clientId(): String? =
        System.getProperty("lumen.msa.clientId")?.takeIf { it.isNotBlank() }
            ?: System.getenv("LUMEN_MSA_CLIENT_ID")?.takeIf { it.isNotBlank() }
}

/**
 * OAuth 2.0 device authorization grant against Microsoft identity (personal accounts).
 * The player signs in on Microsoft's own page in their browser; the launcher never sees a password.
 */
class MicrosoftAuthClient(private val http: HttpClient, private val clientId: String) {

    suspend fun startDeviceCode(): DeviceCode {
        val res = post(DEVICE_CODE_URL, "client_id" to clientId, "scope" to SCOPE)
        val body = res.bodyAsText()
        if (!res.status.isSuccess()) throw toException(body, "Starting Microsoft sign-in")
        return Http.json.decodeFromString(DeviceCode.serializer(), body)
    }

    /** Polls at the server's interval until the player finishes (or declines / lets the code expire). */
    suspend fun awaitToken(code: DeviceCode): MsaTokens {
        var intervalMs = code.interval.coerceAtLeast(1) * 1000L
        var waitedMs = 0L
        while (true) {
            delay(intervalMs)
            waitedMs += intervalMs
            val res = post(
                TOKEN_URL,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId,
                "device_code" to code.deviceCode,
            )
            val body = res.bodyAsText()
            if (res.status.isSuccess()) return Http.json.decodeFromString(MsaTokens.serializer(), body)
            when (errorCode(body)) {
                "authorization_pending" -> if (waitedMs > code.expiresIn * 1000L) throw AuthException.CodeExpired()
                "slow_down" -> intervalMs += 5_000 // RFC 8628 §3.5
                "authorization_declined", "access_denied" -> throw AuthException.Declined()
                "expired_token", "code_expired" -> throw AuthException.CodeExpired()
                else -> throw toException(body, "Microsoft sign-in")
            }
        }
    }

    /** Microsoft rotates refresh tokens: always persist the returned one. */
    suspend fun refresh(refreshToken: String): MsaTokens {
        val res = post(
            TOKEN_URL,
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "scope" to SCOPE,
        )
        val body = res.bodyAsText()
        if (res.status.isSuccess()) return Http.json.decodeFromString(MsaTokens.serializer(), body)
        if (errorCode(body) == "invalid_grant") throw AuthException.SessionExpired()
        throw toException(body, "Refreshing Microsoft sign-in")
    }

    private suspend fun post(url: String, vararg fields: Pair<String, String>): HttpResponse =
        http.submitForm(url, parameters { fields.forEach { (k, v) -> append(k, v) } }) {
            expectSuccess = false // OAuth signals "keep waiting" with HTTP 400
        }

    private fun errorCode(body: String): String? =
        runCatching { (Http.json.parseToJsonElement(body) as JsonObject)["error"]?.jsonPrimitive?.content }.getOrNull()

    private fun toException(body: String, what: String): AuthException {
        val description = runCatching {
            (Http.json.parseToJsonElement(body) as JsonObject)["error_description"]?.jsonPrimitive?.content
        }.getOrNull().orEmpty()
        return when {
            "AADSTS700016" in description -> AuthException.AppNotFound()
            "AADSTS7000218" in description || "AADSTS70002" in description -> AuthException.PublicClientDisabled()
            else -> AuthException.Service(what, description.lineSequence().firstOrNull()?.take(160) ?: (errorCode(body) ?: "unknown error"))
        }
    }

    companion object {
        const val SCOPE = "XboxLive.signin offline_access"
        const val DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        const val TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    }
}
