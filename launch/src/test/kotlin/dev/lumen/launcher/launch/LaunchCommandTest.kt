package dev.lumen.launcher.launch

import dev.lumen.launcher.core.net.Http
import dev.lumen.launcher.installer.PlatformInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchCommandTest {
    private val windows = PlatformInfo("windows", "amd64", "10.0")

    private fun args(json: String): List<JsonElement> = (Http.json.parseToJsonElement(json) as JsonArray).toList()

    @Test fun `conditional arguments follow os rules and skip unadvertised features`() {
        val raw = args(
            """
            [
              "--username", "${'$'}{auth_player_name}",
              {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"},
              {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=x"},
              {"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]}
            ]
            """,
        )
        val out = LaunchCommand.expand(raw, mapOf("auth_player_name" to "Steve"), windows)
        assertEquals(listOf("--username", "Steve", "-XX:HeapDumpPath=x"), out)
    }

    @Test fun `empty and unresolved option values are dropped with their flag`() {
        val out = LaunchCommand.dropEmptyOptions(listOf("--xuid", "", "--clientId", "\${clientid}", "--width", "854", "--fullscreen"))
        assertEquals(listOf("--width", "854", "--fullscreen"), out)
    }

    @Test fun `argfile escapes backslashes and quotes`() {
        assertEquals("\"C:\\\\Games\\\\a b\"\n\"say \\\"hi\\\"\"", LaunchCommand.toArgFile(listOf("C:\\Games\\a b", "say \"hi\"")))
    }

    @Test fun `real tokens are split out of the argfile, offline ones are not`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"
        val args = listOf("-Xmx2g", "Main", "--username", "Notch", "--accessToken", token, "--session", "token:$token:uuid", "--width", "854")
        val (file, secret) = LaunchCommand.splitSecrets(args, token)
        assertEquals(listOf("-Xmx2g", "Main", "--username", "Notch", "--width", "854"), file)
        assertEquals(listOf("--accessToken", token, "--session", "token:$token:uuid"), secret)

        val offline = listOf("Main", "--accessToken", "0")
        assertEquals(offline to emptyList(), LaunchCommand.splitSecrets(offline, "0"))
    }

    @Test fun `offline account uses vanilla's offline uuid scheme`() {
        val acc = LaunchAccount.offline("Notch")
        assertEquals("Notch", acc.name)
        assertEquals(32, acc.uuid.length)
        assertEquals("Player", LaunchAccount.offline("a!").name) // too short after sanitising
    }
}
