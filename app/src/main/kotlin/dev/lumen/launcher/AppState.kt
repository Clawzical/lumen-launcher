package dev.lumen.launcher

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import dev.lumen.launcher.auth.AccountManager
import dev.lumen.launcher.auth.AuthException
import dev.lumen.launcher.auth.MsaConfig
import dev.lumen.launcher.auth.secrets.SecretStore
import dev.lumen.launcher.core.LauncherPaths
import dev.lumen.launcher.core.download.DownloadManager
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.InstanceManager
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.core.jvm.JvmOptimizer
import dev.lumen.launcher.core.net.Http
import dev.lumen.launcher.installer.GameInstaller
import dev.lumen.launcher.launch.GameLauncher
import dev.lumen.launcher.launch.LaunchAccount
import dev.lumen.launcher.launch.RunningGame
import java.util.concurrent.ConcurrentHashMap
import dev.lumen.launcher.core.version.VersionManifest
import dev.lumen.launcher.core.version.VersionRepository
import dev.lumen.launcher.modrinth.ContentIndexStore
import dev.lumen.launcher.modrinth.ModInstaller
import dev.lumen.launcher.modrinth.content.InstanceContentManager
import dev.lumen.launcher.modrinth.ModrinthClient
import dev.lumen.launcher.modrinth.ProjectType
import dev.lumen.launcher.modrinth.SearchHit
import dev.lumen.launcher.ui.browser.ModBrowserModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop

enum class Screen(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Rounded.Dashboard),
    Instances("Instances", Icons.Rounded.GridView),
    ModBrowser("Mod Browser", Icons.Rounded.Explore),
    Settings("Settings", Icons.Rounded.Settings),
}

sealed interface LaunchPhase {
    /** Installing / verifying. [fraction] is null while the stage has no countable work. */
    data class Preparing(val stage: String, val fraction: Float? = null) : LaunchPhase
    data object Starting : LaunchPhase
    data object Running : LaunchPhase
}

/** A starting point for a new instance: a loader plus Modrinth slugs installed (with dependencies) on creation. */
data class InstanceTemplate(
    val title: String,
    val description: String,
    val loader: ModLoader,
    val mods: List<String>,
)

val defaultTemplates = listOf(
    InstanceTemplate("Performance", "Sodium, Lithium and FerriteCore for higher FPS and lower memory use.", ModLoader.FABRIC, listOf("sodium", "lithium", "ferrite-core")),
    InstanceTemplate("Vanilla+", "Quality-of-life mods that keep the vanilla feel: Mod Menu, AppleSkin, Jade.", ModLoader.FABRIC, listOf("modmenu", "appleskin", "jade")),
    InstanceTemplate("Pure Vanilla", "The game exactly as Mojang ships it, in its own isolated folder.", ModLoader.VANILLA, emptyList()),
)

/**
 * App-wide state + service graph. Composables read Compose state / collect StateFlows from
 * here and call intent functions; every intent hops off the UI thread inside the services.
 */
class AppState(private val scope: CoroutineScope) : AutoCloseable {
    // Modrinth requires a User-Agent that identifies the app and a way to reach its author.
    // Internal so UI image loading reuses the same connection pool.
    internal val http = Http.createClient("Clawzical/lumen-launcher/1.0.0 (https://github.com/Clawzical/lumen-launcher)")

    val paths = LauncherPaths().ensure()
    val instances = InstanceManager(paths)
    val downloads = DownloadManager(http, maxParallel = 16)
    val modrinth = ModrinthClient(http)
    // One index store for every writer, so installs, swaps and removals serialise per instance.
    private val contentIndex = ContentIndexStore()
    val installer = ModInstaller(modrinth, downloads, contentIndex)
    val content = InstanceContentManager(modrinth, downloads, contentIndex, installer)
    private val versions = VersionRepository(http)
    private val gameInstaller = GameInstaller(http, paths, downloads, versions)
    private val gameLauncher = GameLauncher()
    private val running = ConcurrentHashMap<String, RunningGame>()
    val browser = ModBrowserModel()
    val accounts = AccountManager(paths, http, MsaConfig.clientId(), SecretStore.forCurrentOs())
    var signInOpen by mutableStateOf(false)

    /** Per-instance launch pipeline state; absent = idle. */
    var launchPhases by mutableStateOf<Map<String, LaunchPhase>>(emptyMap())
        private set

    var screen by mutableStateOf(Screen.Dashboard)
    var selectedInstanceId by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)

    /** null while loading; stays null when offline (the picker then accepts free text). */
    var versionManifest by mutableStateOf<VersionManifest?>(null)
        private set

    /** Bumped whenever instance content changes, so mod counts / memory estimates recompute. */
    var contentRevision by mutableIntStateOf(0)
        private set

    /** Instance whose detail view (installed content) is open; null shows the instances grid. */
    var managingInstanceId by mutableStateOf<String?>(null)

    fun selected(list: List<Instance>): Instance? = list.firstOrNull { it.id == selectedInstanceId } ?: list.firstOrNull()

    fun manage(instance: Instance) {
        selectedInstanceId = instance.id
        managingInstanceId = instance.id
        screen = Screen.Instances
    }

    /** Content changed outside the browser (toggle, swap, remove): refresh mod counts and memory estimates. */
    fun contentChanged() {
        contentRevision++
    }

    suspend fun loadInstances() {
        scope.launch { runCatching { versionManifest = versions.manifest() } }
        runCatching { accounts.load() }
        runCatching { instances.load() }.onFailure { status = "Couldn't read instances: ${it.message}" }
    }

    fun createInstance(name: String, gameVersion: String, loader: ModLoader, mods: List<String> = emptyList()) =
        action("Couldn't create instance") {
            val created = instances.create(name, gameVersion, loader)
            selectedInstanceId = created.id
            if (mods.isEmpty()) {
                status = "Created ${created.name}"
                return@action
            }
            status = "Created ${created.name} — installing ${mods.size} mods…"
            val p = instances.pathsOf(created)
            val failures = coroutineScope {
                mods.map { slug ->
                    async { slug to runCatching { installer.installLatest(slug, ProjectType.MOD, created, p) }.exceptionOrNull() }
                }.awaitAll()
            }.filter { it.second != null }
            contentRevision++
            status = if (failures.isEmpty()) {
                "Installed ${mods.size} mods into ${created.name}"
            } else {
                "Installed ${mods.size - failures.size}/${mods.size} mods — couldn't install ${failures.joinToString { it.first }}: " +
                    (failures.first().second?.message?.lineSequence()?.first()?.take(90) ?: "unknown error")
            }
        }

    suspend fun refreshInstalled(target: Instance?) {
        browser.installed = target?.let { runCatching { installer.installedProjectIds(instances.pathsOf(it)) }.getOrNull() }.orEmpty()
    }

    /** One-click install from the browser: resolves required dependencies, downloads, records. */
    fun installProject(hit: SearchHit, type: ProjectType, instance: Instance) = scope.launch {
        val id = hit.projectId
        browser.installing += id
        browser.failures -= id
        try {
            val plan = installer.installLatest(id, type, instance, instances.pathsOf(instance))
            val deps = plan.files.size - 1
            status = buildString {
                append("Installed ${hit.title}")
                if (deps > 0) append(" + $deps ${if (deps == 1) "dependency" else "dependencies"}")
                append(" into ${instance.name}")
                if (plan.conflicts.isNotEmpty()) append(" — warning: conflicts with an installed mod")
            }
            contentRevision++
            refreshInstalled(instance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = if (e.message?.startsWith("No version") == true) {
                "No version for ${instance.loader.displayName} ${instance.gameVersion}"
            } else {
                e.message?.lineSequence()?.firstOrNull()?.take(90) ?: "Install failed"
            }
            browser.failures += id to reason
            status = "Couldn't install ${hit.title}: $reason"
        } finally {
            browser.installing -= id
        }
    }

    fun rename(instance: Instance, newName: String) = action("Rename failed") { instances.rename(instance.id, newName) }

    fun delete(instance: Instance) = action("Delete failed") {
        instances.delete(instance.id)
        if (selectedInstanceId == instance.id) selectedInstanceId = null
        status = "Deleted ${instance.name}"
    }

    fun openFolder(instance: Instance) = action("Couldn't open folder") {
        withContext(Dispatchers.IO) { Desktop.getDesktop().open(instances.pathsOf(instance).gameDir.toFile()) }
    }

    /**
     * Install whatever is missing (version, loader, libraries, assets, Java), then start the game
     * with JvmOptimizer's flags and follow the process until it exits.
     */
    fun play(instance: Instance) {
        val id = instance.id
        if (id in launchPhases) return
        selectedInstanceId = id
        setPhase(id, LaunchPhase.Preparing("Checking game files"))
        scope.launch {
            try {
                // Auth first: a revoked session should fail in a second, not after a 3-minute install.
                setPhase(id, LaunchPhase.Preparing("Signing in"))
                val credentials = accounts.launchCredentials()
                val account = credentials?.let { LaunchAccount(it.name, it.uuid, it.accessToken, userType = "msa") }
                    ?: LaunchAccount.offline(System.getProperty("user.name") ?: "Player")

                val game = gameInstaller.prepare(instance) { p -> setPhase(id, LaunchPhase.Preparing(p.stage, p.fraction)) }
                if (game.loaderVersion != null && game.loaderVersion != instance.loaderVersion) {
                    instances.update(id) { it.copy(loaderVersion = game.loaderVersion) } // pin it: updates are explicit
                }
                setPhase(id, LaunchPhase.Starting)
                val plan = JvmOptimizer.plan(
                    javaMajor = game.java.major,
                    modCount = instances.countMods(instance),
                    loader = instance.loader,
                    requestedHeapMb = instance.maxMemoryMb,
                    profile = instance.gcProfile,
                    extra = instance.extraJvmArgs,
                )
                val p = instances.pathsOf(instance)
                val run = gameLauncher.launch(game, p.root, p.gameDir, account, plan)
                running[id] = run
                instances.markPlayed(id)
                setPhase(id, LaunchPhase.Running)
                val who = if (credentials != null) "as ${account.name}" else "offline as ${account.name}"
                status = "Started ${instance.name} $who · Java ${game.java.major} · ${formatHeapMb(plan.heapMb)} · ${plan.gc.name}"

                val code = run.awaitExit()
                status = if (code == 0) "${instance.name} closed" else "${instance.name} exited with code $code — see launch.log in the instance folder"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't start ${instance.name}: ${e.message?.lineSequence()?.firstOrNull()?.take(110) ?: e::class.simpleName}"
                if (e is AuthException.SessionExpired) signInOpen = true // straight to the fix
            } finally {
                running.remove(id)
                setPhase(id, null)
            }
        }
    }

    fun stop(instance: Instance) {
        running[instance.id]?.stop()
    }

    private fun setPhase(id: String, phase: LaunchPhase?) {
        launchPhases = if (phase == null) launchPhases - id else launchPhases + (id to phase)
    }

    private fun formatHeapMb(mb: Int) = if (mb % 1024 == 0) "${mb / 1024} GB" else "%.1f GB".format(mb / 1024.0)

    private fun action(errorPrefix: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { status = "$errorPrefix: ${it.message}" }
    }

    override fun close() = http.close()
}
