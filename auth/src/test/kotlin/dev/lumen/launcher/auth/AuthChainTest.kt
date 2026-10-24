package dev.lumen.launcher.auth

import dev.lumen.launcher.auth.secrets.SecretStore
import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.net.Http
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeSecretStore : SecretStore {
    val values = mutableMapOf<String, String>()
    override val isAvailable = true
    override val description = "fake"
    override suspend fun get(key: String) = values[key]
    override suspend fun put(key: String, secret: String) { values[key] = secret }
    override suspend fun delete(key: String) { values.remove(key) }
}

/** Scripted Microsoft / Xbox / Minecraft endpoints; records every request. */
class FakeServices {
    val requests = mutableListOf<HttpRequestData>()
    var pendingPolls = 1
    var xstsError: Long? = null
    var mcForbidden = false
    var profileMissing = false
    var tokenCalls = 0

    fun handler(): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { req ->
        requests += req
        val url = req.url.toString()
        when {
            url == MicrosoftAuthClient.DEVICE_CODE_URL -> ok(
                """{"device_code":"dev-123","user_code":"ABCD-EFGH","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":5}""",
            )
            url == MicrosoftAuthClient.TOKEN_URL -> {
                tokenCalls++
                val form = (req.body as FormDataContent).formData
                when {
                    form["grant_type"] == "refresh_token" ->
                        ok("""{"access_token":"msa-access-2","refresh_token":"refresh-rotated","expires_in":3600}""")
                    pendingPolls-- > 0 -> bad("""{"error":"authorization_pending"}""")
                    else -> ok("""{"access_token":"msa-access","refresh_token":"refresh-1","expires_in":3600}""")
                }
            }
            url == XboxMinecraftAuth.XBL_URL -> ok("""{"Token":"xbl-token","DisplayClaims":{"xui":[{"uhs":"uhs123"}]}}""")
            url == XboxMinecraftAuth.XSTS_URL -> xstsError?.let {
                respond("""{"Identity":"0","XErr":$it}""", HttpStatusCode.Unauthorized, jsonHeaders)
            } ?: ok("""{"Token":"xsts-token","DisplayClaims":{"xui":[{"uhs":"uhs123"}]}}""")
            url == XboxMinecraftAuth.MC_LOGIN_URL -> if (mcForbidden) {
                respond("""{"path":"/authentication/login_with_xbox","errorMessage":"Invalid app registration, see https://aka.ms/AppRegInfo for more information"}""", HttpStatusCode.Forbidden, jsonHeaders)
            } else ok("""{"username":"x","access_token":"mc-token","expires_in":86400,"token_type":"Bearer"}""")
            url == XboxMinecraftAuth.PROFILE_URL -> if (profileMissing) {
                respond("""{"error":"NOT_FOUND"}""", HttpStatusCode.NotFound, jsonHeaders)
            } else ok(
                """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch","skins":[{"url":"http://textures.minecraft.net/texture/abc","state":"ACTIVE","variant":"CLASSIC"}],"capes":[]}""",
            )
            else -> respond("unexpected $url", HttpStatusCode.NotFound)
        }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)
    private fun MockRequestHandleScope.bad(body: String) = respond(body, HttpStatusCode.BadRequest, jsonHeaders)

    fun bodyOf(url: String): String = (requests.last { it.url.toString() == url }.body as TextContent).text
}

class AuthChainTest {
    private fun setup(services: FakeServices = FakeServices(), secrets: FakeSecretStore = FakeSecretStore(), clock: () -> Long = { 1_000_000L }):
        Triple<AccountManager, FakeServices, FakeSecretStore> {
        val http = HttpClient(MockEngine(services.handler())) { install(ContentNegotiation) { json(Http.json) } }
        val paths = LauncherPaths(Files.createTempDirectory("lumen-auth-test"))
        return Triple(AccountManager(paths, http, "client-id", secrets, clock), services, secrets)
    }

    @Test fun `device code sign-in runs the whole chain and stores only the refresh token in the keychain`() = runTest {
        val (manager, services, secrets) = setup()
        var shownCode: String? = null
        val stages = mutableListOf<String>()

        val account = manager.signIn(onCode = { shownCode = it.userCode }, onStage = { stages += it })

        assertEquals("ABCD-EFGH", shownCode)
        assertEquals("Notch", account.name)
        assertEquals("https://textures.minecraft.net/texture/abc", account.skinUrl)
        assertEquals(2, services.tokenCalls) // one authorization_pending, then success
        assertTrue("\"RpsTicket\":\"d=msa-access\"" in services.bodyOf(XboxMinecraftAuth.XBL_URL))
        assertTrue("\"UserTokens\":[\"xbl-token\"]" in services.bodyOf(XboxMinecraftAuth.XSTS_URL))
        assertEquals("""{"identityToken":"XBL3.0 x=uhs123;xsts-token"}""", services.bodyOf(XboxMinecraftAuth.MC_LOGIN_URL))
        assertEquals("refresh-1", secrets.values["msa-refresh-token:069a79f444e94726a5befca90e38aaf5"])
        assertEquals(4, stages.size)
        assertEquals("069a79f444e94726a5befca90e38aaf5", manager.state.value.activeId)
    }

    @Test fun `accounts json holds metadata but never a token`() = runTest {
        val (manager, _, _) = setup()
        manager.signIn({}, {})
        val file = Files.list(Files.createTempDirectory("x").parent).use { s ->
            s.filter { it.fileName.toString().startsWith("lumen-auth-test") }.map { it.resolve("accounts.json") }.filter { it.exists() }.toList()
        }.maxBy { Files.getLastModifiedTime(it) }
        val json = file.readText()
        assertTrue("Notch" in json)
        listOf("refresh-1", "mc-token", "msa-access", "xsts-token").forEach { assertFalse(it in json, "$it leaked into accounts.json") }
    }

    @Test fun `launch reuses the cached token, then silently refreshes and stores the rotated refresh token`() = runTest {
        var time = 1_000_000L
        val (manager, services, secrets) = setup(clock = { time })
        manager.signIn({}, {})
        val before = services.requests.size

        val cached = manager.launchCredentials()!!
        assertEquals("mc-token", cached.accessToken)
        assertEquals(before, services.requests.size) // no network

        time += 24 * 3_600_000L // Minecraft token expired
        val refreshed = manager.launchCredentials()!!
        assertEquals("Notch", refreshed.name)
        assertEquals("refresh-rotated", secrets.values["msa-refresh-token:069a79f444e94726a5befca90e38aaf5"])
        assertTrue(services.requests.size > before)
    }

    @Test fun `no account means offline (null), and sign-out removes the keychain entry`() = runTest {
        val (manager, _, secrets) = setup()
        assertEquals(null, manager.launchCredentials())
        val acc = manager.signIn({}, {})
        manager.signOut(acc.id)
        assertTrue(secrets.values.isEmpty())
        assertEquals(null, manager.launchCredentials())
    }

    @Test fun `xsts child-account error becomes a readable message`() = runTest {
        val (manager, services, _) = setup(FakeServices().apply { xstsError = 2148916238 })
        assertFailsWith<AuthException.ChildAccount> { manager.signIn({}, {}) }
        services.xstsError = 2148916233
        assertFailsWith<AuthException.NoXboxAccount> { manager.signIn({}, {}) }
    }

    @Test fun `unapproved azure app is reported as such`() = runTest {
        val (manager, _, _) = setup(FakeServices().apply { mcForbidden = true })
        assertFailsWith<AuthException.AppNotApproved> { manager.signIn({}, {}) }
    }

    @Test fun `account without Minecraft gets NoMinecraft`() = runTest {
        val (manager, _, _) = setup(FakeServices().apply { profileMissing = true })
        assertFailsWith<AuthException.NoMinecraft> { manager.signIn({}, {}) }
    }

    @Test fun `missing client id fails before any request`() = runTest {
        val services = FakeServices()
        val http = HttpClient(MockEngine(services.handler())) { install(ContentNegotiation) { json(Http.json) } }
        val manager = AccountManager(LauncherPaths(Files.createTempDirectory("lumen-auth-test")), http, null, FakeSecretStore())
        assertFailsWith<AuthException.NotConfigured> { manager.signIn({}, {}) }
        assertTrue(services.requests.isEmpty())
    }

    @Test fun `microsoft error codes map to guidance`() = runTest {
        val http = HttpClient(MockEngine { _ ->
            respond(
                """{"error":"unauthorized_client","error_description":"AADSTS700016: Application with identifier 'x' was not found in the directory"}""",
                HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { install(ContentNegotiation) { json(Http.json) } }
        assertFailsWith<AuthException.AppNotFound> { MicrosoftAuthClient(http, "x").startDeviceCode() }
    }

    @Test fun `declined and slow_down are handled while polling`() = runTest {
        val replies = ArrayDeque(listOf("slow_down", "authorization_declined"))
        val http = HttpClient(MockEngine { _ ->
            respond("""{"error":"${replies.removeFirst()}"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json(Http.json) } }
        val code = DeviceCode("d", "U", "https://www.microsoft.com/link", expiresIn = 900, interval = 5)
        assertFailsWith<AuthException.Declined> { MicrosoftAuthClient(http, "x").awaitToken(code) }
        assertTrue(replies.isEmpty())
    }
}
