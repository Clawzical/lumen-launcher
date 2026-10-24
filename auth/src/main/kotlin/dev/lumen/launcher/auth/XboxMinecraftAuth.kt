package dev.lumen.launcher.auth

import dev.lumen.launcher.core.net.Http
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class XboxToken(val token: String, val userHash: String)

@Serializable private data class XboxResponse(
    @SerialName("Token") val token: String,
    @SerialName("DisplayClaims") val displayClaims: XboxClaims,
)
@Serializable private data class XboxClaims(val xui: List<Map<String, String>> = emptyList())

@Serializable
data class MinecraftToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 86_400,
)

@Serializable
data class MinecraftProfile(val id: String, val name: String, val skins: List<Skin> = emptyList()) {
    @Serializable data class Skin(val url: String, val state: String = "ACTIVE", val variant: String? = null)

    val activeSkinUrl: String? get() = skins.firstOrNull { it.state == "ACTIVE" }?.url?.replace("http://", "https://")
}

/** Steps 2–5 of the chain: MSA token → Xbox Live user token → XSTS token → Minecraft token → profile. */
class XboxMinecraftAuth(private val http: HttpClient) {

    suspend fun xboxLive(msaAccessToken: String): XboxToken = xbox(
        "Xbox Live sign-in",
        XBL_URL,
        buildJsonObject {
            putJsonObject("Properties") {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$msaAccessToken") // "d=" marks a token from an Azure AD (MSAL) app
            }
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        },
    )

    suspend fun xsts(xbl: XboxToken): XboxToken = xbox(
        "Xbox security token",
        XSTS_URL,
        buildJsonObject {
            putJsonObject("Properties") {
                put("SandboxId", "RETAIL")
                putJsonArray("UserTokens") { add(xbl.token) }
            }
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
        },
    )

    suspend fun minecraft(xsts: XboxToken): MinecraftToken {
        val res = http.post(MC_LOGIN_URL) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(json(buildJsonObject { put("identityToken", "XBL3.0 x=${xsts.userHash};${xsts.token}") }))
        }
        val body = res.bodyAsText()
        if (res.status.isSuccess()) return Http.json.decodeFromString(MinecraftToken.serializer(), body)
        // Unapproved Azure apps get 403 "Invalid app registration, see https://aka.ms/AppRegInfo".
        if (res.status == HttpStatusCode.Forbidden && "Invalid app registration" in body) throw AuthException.AppNotApproved()
        throw AuthException.Service("Minecraft sign-in", "${res.status.value} ${body.take(120)}")
    }

    suspend fun profile(token: MinecraftToken): MinecraftProfile {
        val res = http.get(PROFILE_URL) {
            expectSuccess = false
            bearerAuth(token.accessToken)
        }
        if (res.status == HttpStatusCode.NotFound) throw AuthException.NoMinecraft()
        val body = res.bodyAsText()
        if (!res.status.isSuccess()) throw AuthException.Service("Loading the Minecraft profile", "${res.status.value}")
        return Http.json.decodeFromString(MinecraftProfile.serializer(), body)
    }

    private suspend fun xbox(what: String, url: String, payload: JsonObject): XboxToken {
        val res: HttpResponse = http.post(url) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(json(payload))
        }
        val body = res.bodyAsText()
        if (res.status == HttpStatusCode.Unauthorized) throw xErr(body) ?: AuthException.Service(what, "401")
        if (!res.status.isSuccess()) throw AuthException.Service(what, "${res.status.value}")
        val parsed = Http.json.decodeFromString(XboxResponse.serializer(), body)
        val uhs = parsed.displayClaims.xui.firstOrNull()?.get("uhs") ?: throw AuthException.Service(what, "no user hash")
        return XboxToken(parsed.token, uhs)
    }

    /** Pre-serialized body; OutgoingContent bypasses ContentNegotiation so it isn't re-encoded as a JSON string. */
    private fun json(obj: JsonObject) = TextContent(obj.toString(), ContentType.Application.Json)

    /** XSTS explains account problems with numeric XErr codes. */
    private fun xErr(body: String): AuthException? {
        val code = runCatching { (Http.json.parseToJsonElement(body) as JsonObject)["XErr"]?.jsonPrimitive?.content?.toLong() }.getOrNull()
        return when (code) {
            2148916233 -> AuthException.NoXboxAccount()
            2148916235 -> AuthException.XboxUnavailableInRegion()
            2148916236, 2148916237 -> AuthException.AdultVerificationRequired()
            2148916238 -> AuthException.ChildAccount()
            else -> null
        }
    }

    companion object {
        const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
        const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
        const val PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
    }
}
