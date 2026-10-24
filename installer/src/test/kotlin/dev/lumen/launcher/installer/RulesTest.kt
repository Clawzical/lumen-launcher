package dev.lumen.launcher.installer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesTest {
    private val windows = PlatformInfo("windows", "amd64", "10.0")
    private val macArm = PlatformInfo("osx", "aarch64", "14.4")

    @Test fun `no rules means allowed`() = assertTrue(Rules.allowed(emptyList(), windows))

    @Test fun `allow only on matching os`() {
        val rules = listOf(Rule("allow", OsRule(name = "osx")))
        assertTrue(Rules.allowed(rules, macArm))
        assertFalse(Rules.allowed(rules, windows))
    }

    @Test fun `later disallow overrides earlier allow`() {
        val rules = listOf(Rule("allow"), Rule("disallow", OsRule(name = "osx")))
        assertTrue(Rules.allowed(rules, windows))
        assertFalse(Rules.allowed(rules, macArm))
    }

    @Test fun `features we do not advertise are false`() {
        val demo = listOf(Rule("allow", features = mapOf("is_demo_user" to true)))
        assertFalse(Rules.allowed(demo, windows))
        assertTrue(Rules.allowed(demo, windows, features = mapOf("is_demo_user" to true)))
    }

    @Test fun `arch rules`() {
        val x86Only = listOf(Rule("allow", OsRule(arch = "x86")))
        assertFalse(Rules.allowed(x86Only, windows))
        assertTrue(Rules.allowed(x86Only, PlatformInfo("windows", "x86", "10.0")))
    }

    @Test fun `native classifiers match machine arch`() {
        assertTrue(windows.acceptsNativeClassifier("lwjgl-3.3.3-natives-windows"))
        assertFalse(windows.acceptsNativeClassifier("lwjgl-3.3.3-natives-windows-arm64"))
        assertFalse(windows.acceptsNativeClassifier("lwjgl-3.3.3-natives-windows-x86"))
        assertTrue(macArm.acceptsNativeClassifier("lwjgl-3.3.3-natives-macos-arm64"))
        assertFalse(macArm.acceptsNativeClassifier("lwjgl-3.3.3-natives-macos"))
    }

    @Test fun `maven coordinates map to repository paths`() {
        assertEquals("org/ow2/asm/asm/9.7/asm-9.7.jar", MavenCoordinate.parse("org.ow2.asm:asm:9.7").path)
        val natives = MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.3:natives-windows")
        assertEquals("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar", natives.path)
        assertEquals("org.lwjgl:lwjgl:natives-windows", natives.dedupeKey)
        assertEquals("de/oceanlabs/mcp/mcp_config/1.21/mcp_config-1.21.zip", MavenCoordinate.parse("de.oceanlabs.mcp:mcp_config:1.21@zip").path)
    }

    @Test fun `java version strings`() {
        assertEquals(21, JavaRuntimes.parseMajor("21.0.12"))
        assertEquals(8, JavaRuntimes.parseMajor("1.8.0_402"))
        assertEquals(25, JavaRuntimes.parseMajor("25"))
    }
}
