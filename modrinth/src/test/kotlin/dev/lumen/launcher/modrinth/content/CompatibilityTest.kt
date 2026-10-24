package dev.lumen.launcher.modrinth.content

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FabricVersionsTest {
    private fun ok(v: String, p: String) = assertTrue(FabricVersions.satisfies(v, p), "$v should satisfy $p")
    private fun no(v: String, p: String) = assertFalse(FabricVersions.satisfies(v, p), "$v should not satisfy $p")

    @Test fun `x ranges and wildcards`() {
        ok("0.6.13+mc1.21.1", "0.6.x")
        no("0.8.13+mc1.21.1", "0.6.x")
        ok("1.21.1", "1.21.x")
        ok("5.0.0", "*")
    }

    @Test fun `comparison operators and AND`() {
        ok("0.16.10", ">=0.16.0")
        no("0.15.9", ">=0.16.0")
        ok("1.5.0", ">=1.2 <2")
        no("2.0.0", ">=1.2 <2")
    }

    @Test fun `tilde and caret`() {
        ok("1.21.4", "~1.21")
        no("1.22.0", "~1.21")
        ok("1.9.0", "^1.2.0")
        no("2.0.0", "^1.2.0")
    }

    @Test fun `pre-releases sort below releases`() {
        no("1.0.0-beta.2", ">=1.0.0")
        ok("1.0.0-beta.10", ">1.0.0-beta.2")
    }

    @Test fun `non-semver versions are accepted leniently`() = ok("build.12", ">=3.0")

    @Test fun `modrinth version numbers are matched by their tokens, ignoring the game version`() {
        assertTrue(FabricVersions.anyTokenSatisfies("mc1.21.1-0.6.13-fabric", listOf("0.6.x"), setOf("1.21.1")))
        assertFalse(FabricVersions.anyTokenSatisfies("mc1.21.1-0.8.13-fabric", listOf("0.6.x"), setOf("1.21.1")))
        // Without ignoring it, "1.21.1" would wrongly satisfy ">=1.0".
        assertFalse(FabricVersions.anyTokenSatisfies("mc1.21.1-0.5.0", listOf(">=1.0"), setOf("1.21.1")))
    }
}

class CompatibilityTest {
    private fun mod(id: String, version: String, depends: Map<String, List<String>> = emptyMap(), bundled: List<FabricModMeta> = emptyList()) =
        ModEntry("$id.jar", id.replaceFirstChar(Char::uppercase), FabricModMeta(id, version, null, depends = depends, bundled = bundled))

    @Test fun `iris with the wrong sodium is reported with the provider file`() {
        val issues = Compatibility.check(
            listOf(mod("iris", "1.8.8", mapOf("sodium" to listOf("0.6.x"), "minecraft" to listOf("1.21.1"))), mod("sodium", "0.8.13+mc1.21.1")),
            gameVersion = "1.21.1", loaderVersion = "0.20.1",
        )
        val issue = assertIs<CompatIssue.WrongVersion>(issues.single())
        assertEquals("sodium.jar", issue.providerKey)
        assertEquals("0.8.13+mc1.21.1", issue.installed)
    }

    @Test fun `bundled modules satisfy dependencies (fabric api)`() {
        val api = mod("fabric-api", "0.116.17", bundled = listOf(FabricModMeta("fabric-api-base", "0.4.42", null)))
        val issues = Compatibility.check(listOf(api, mod("modmenu", "11.0.5", mapOf("fabric-api-base" to listOf("*")))), "1.21.1", "0.20.1")
        assertTrue(issues.isEmpty(), issues.toString())
    }

    @Test fun `missing dependency and wrong game version`() {
        val issues = Compatibility.check(listOf(mod("jade", "15.0", mapOf("fabric-api" to listOf("*"), "minecraft" to listOf("~1.20")))), "1.21.1", null)
        assertTrue(issues.any { it is CompatIssue.Missing && it.depId == "fabric-api" })
        assertTrue(issues.any { it is CompatIssue.WrongGame })
    }

    @Test fun `requirements on a provider come from its dependents`() {
        val mods = listOf(mod("iris", "1.8.8", mapOf("sodium" to listOf("0.6.x"))), mod("sodium", "0.6.13"))
        val reqs = Compatibility.requirementsOn("sodium.jar", mods)
        assertEquals(listOf("0.6.x"), reqs.single().second)
    }
}

class FabricModReaderTest {
    private fun jar(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()

    @Test fun `reads descriptor, array predicates, comments and nested jars`() {
        val inner = jar(mapOf("fabric.mod.json" to """{"id":"fabric-api-base","version":"0.4.42"}""".toByteArray()))
        val outer = jar(
            mapOf(
                "fabric.mod.json" to """
                    {
                      // comments are tolerated
                      "id": "fabric-api", "version": "0.116.17+1.21.1", "name": "Fabric API",
                      "depends": { "fabricloader": ">=0.16.0", "minecraft": ["1.21", "1.21.1"] },
                      "jars": [ { "file": "META-INF/jars/base.jar" } ],
                    }
                """.trimIndent().toByteArray(),
                "META-INF/jars/base.jar" to inner,
            ),
        )
        val path: Path = Files.createTempFile("mod", ".jar").also { Files.write(it, outer) }
        val meta = FabricModReader.read(path)!!
        assertEquals("fabric-api", meta.id)
        assertEquals(listOf("1.21", "1.21.1"), meta.depends["minecraft"])
        assertEquals(listOf("fabric-api", "fabric-api-base"), meta.flatten().map { it.id })
    }

    @Test fun `non-fabric jars return null`() {
        val path = Files.createTempFile("plain", ".jar").also { Files.write(it, jar(mapOf("a.txt" to byteArrayOf(1)))) }
        assertEquals(null, FabricModReader.read(path))
    }
}
