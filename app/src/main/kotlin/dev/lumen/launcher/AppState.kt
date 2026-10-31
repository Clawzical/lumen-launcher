package dev.lumen.launcher

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
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
import dev.lumen.launcher.core.settings.AccountMode
import dev.lumen.launcher.core.settings.Language
import dev.lumen.launcher.core.settings.LauncherSettings
import dev.lumen.launcher.core.settings.SettingsStore
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
import dev.lumen.launcher.bedrock.BedrockEdition
import dev.lumen.launcher.bedrock.BedrockInstall
import dev.lumen.launcher.bedrock.BedrockLauncher
import dev.lumen.launcher.ui.browser.ModBrowserModel
import dev.lumen.launcher.ui.tr
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop

/** The title bar's tabs, left to right. */
enum class Screen(val en: String, val zh: String, val icon: ImageVector) {
    Launch("Launch", "启动", Icons.Outlined.PlayCircle),
    Instances("Instances", "实例", Icons.Outlined.GridView),
    ModBrowser("Download", "下载", Icons.Outlined.Download),
    Bedrock("Bedrock", "基岩版", Icons.Outlined.ViewInAr),
    Settings("Settings", "设置", Icons.Outlined.Settings),
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
    val titleZh: String = title,
    val descriptionZh: String = description,
)

val defaultTemplates = listOf(
    InstanceTemplate(
        "Performance", "Sodium, Lithium and FerriteCore for higher FPS and lower memory use.", ModLoader.FABRIC,
        listOf("sodium", "lithium", "ferrite-core"),
        "性能优化", "Sodium、Lithium 和 FerriteCore：更高帧率，更少内存。",
    ),
    InstanceTemplate(
        "Vanilla+", "Quality-of-life mods that keep the vanilla feel: Mod Menu, AppleSkin, Jade.", ModLoader.FABRIC,
        listOf("modmenu", "appleskin", "jade"),
        "原版+", "保持原版手感的实用模组：Mod Menu、AppleSkin、Jade。",
    ),
    InstanceTemplate(
        "Pure Vanilla", "The game exactly as Mojang ships it, in its own isolated folder.", ModLoader.VANILLA, emptyList(),
        "纯净原版", "与 Mojang 发布的完全一致，独立文件夹存放。",
    ),
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
    val settings = SettingsStore(paths)
    val bedrock = BedrockLauncher()

    /** Installed Bedrock editions; null until the first scan finishes. */
    var bedrockInstalls by mutableStateOf<Map<BedrockEdition, BedrockInstall>?>(null)
        private set
    var bedrockScanning by mutableStateOf(false)
        private set

    /** Status messages are built outside composition, so they read the language here. */
    private val language: Language get() = settings.settings.value.language
    fun t(en: String, zh: String) = tr(language, en, zh)

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) = action(t("Couldn't save settings", "无法保存设置")) {
        settings.update(transform)
    }

    /**
     * Copies the picture into Lumen's folder first, so moving or deleting the original
     * doesn't blank the background.
     */
    fun setBackground(source: java.io.File) = action(t("Couldn't use that image", "无法使用该图片")) {
        val target = withContext(Dispatchers.IO) {
            val dir = paths.root.resolve("background")
            java.nio.file.Files.createDirectories(dir)
            dir.toFile().listFiles()?.forEach { it.delete() } // only ever one
            // A new name each time, so the UI (keyed on the path) reloads the picture.
            val dest = dir.resolve("background-${System.currentTimeMillis()}." + source.extension.lowercase().ifBlank { "png" })
            java.nio.file.Files.copy(source.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            dest
        }
        settings.update { it.copy(backgroundPath = target.toString()) }
    }

    /** Per-instance launch pipeline state; absent = idle. */
    var launchPhases by mutableStateOf<Map<String, LaunchPhase>>(emptyMap())
        private set

    // LUMEN_SCREEN=Bedrock etc. opens straight on a tab (handy for screenshots and UI work).
    var screen by mutableStateOf(System.getenv("LUMEN_SCREEN")?.let { n -> Screen.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } } ?: Screen.Launch)
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
        runCatching { settings.load() }
        runCatching { accounts.load() }
        runCatching { instances.load() }.onFailure { status = t("Couldn't read instances: ", "无法读取实例：") + it.message }
    }

    fun createInstance(name: String, gameVersion: String, loader: ModLoader, mods: List<String> = emptyList()) =
        action(t("Couldn't create instance", "无法创建实例")) {
            val created = instances.create(name, gameVersion, loader)
            selectedInstanceId = created.id
            if (mods.isEmpty()) {
                status = t("Created ${created.name}", "已创建 ${created.name}")
                return@action
            }
            status = t("Created ${created.name} — installing ${mods.size} mods…", "已创建 ${created.name}，正在安装 ${mods.size} 个模组…")
            val p = instances.pathsOf(created)
            val failures = coroutineScope {
                mods.map { slug ->
                    async { slug to runCatching { installer.installLatest(slug, ProjectType.MOD, created, p) }.exceptionOrNull() }
                }.awaitAll()
            }.filter { it.second != null }
            contentRevision++
            status = if (failures.isEmpty()) {
                t("Installed ${mods.size} mods into ${created.name}", "已向 ${created.name} 安装 ${mods.size} 个模组")
            } else {
                val failed = failures.joinToString { it.first }
                t(
                    "Installed ${mods.size - failures.size}/${mods.size} mods — couldn't install $failed: ",
                    "已安装 ${mods.size - failures.size}/${mods.size} 个模组，以下安装失败 $failed：",
                ) + (failures.first().second?.message?.lineSequence()?.first()?.take(90) ?: t("unknown error", "未知错误"))
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
                append(t("Installed ${hit.title}", "已安装 ${hit.title}"))
                if (deps > 0) append(t(" + $deps ${if (deps == 1) "dependency" else "dependencies"}", " 及 $deps 个前置"))
                append(t(" into ${instance.name}", "到 ${instance.name}"))
                if (plan.conflicts.isNotEmpty()) append(t(" — warning: conflicts with an installed mod", "，注意：与已安装的模组冲突"))
            }
            contentRevision++
            refreshInstalled(instance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = if (e.message?.startsWith("No version") == true) {
                t("No version for ${instance.loader.displayName} ${instance.gameVersion}", "没有适用于 ${instance.loader.displayName} ${instance.gameVersion} 的版本")
            } else {
                e.message?.lineSequence()?.firstOrNull()?.take(90) ?: t("Install failed", "安装失败")
            }
            browser.failures += id to reason
            status = t("Couldn't install ${hit.title}: ", "无法安装 ${hit.title}：") + reason
        } finally {
            browser.installing -= id
        }
    }

    fun rename(instance: Instance, newName: String) = action(t("Rename failed", "重命名失败")) { instances.rename(instance.id, newName) }

    fun delete(instance: Instance) = action(t("Delete failed", "删除失败")) {
        instances.delete(instance.id)
        if (selectedInstanceId == instance.id) selectedInstanceId = null
        status = t("Deleted ${instance.name}", "已删除 ${instance.name}")
    }

    fun openFolder(instance: Instance) = action(t("Couldn't open folder", "无法打开文件夹")) {
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
        setPhase(id, LaunchPhase.Preparing(t("Checking game files", "正在检查游戏文件")))
        scope.launch {
            try {
                // Auth first: a revoked session should fail in a second, not after a 3-minute install.
                val prefs = settings.settings.value
                val account = when (prefs.accountMode) {
                    AccountMode.OFFLINE -> {
                        if (!LauncherSettings.isValidName(prefs.offlineName)) {
                            error(t("Offline name must be 3–16 letters, digits or _", "离线名称须为 3–16 位字母、数字或下划线"))
                        }
                        LaunchAccount.offline(prefs.offlineName)
                    }
                    AccountMode.MICROSOFT -> {
                        setPhase(id, LaunchPhase.Preparing(t("Signing in", "正在登录")))
                        val credentials = accounts.launchCredentials()
                        if (credentials == null) {
                            signInOpen = true
                            error(t("Sign in with Microsoft first, or switch to Offline", "请先登录微软账号，或切换到离线模式"))
                        }
                        LaunchAccount(credentials.name, credentials.uuid, credentials.accessToken, userType = "msa")
                    }
                }

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
                val who = if (prefs.accountMode == AccountMode.MICROSOFT) account.name else t("${account.name} (offline)", "${account.name}（离线）")
                status = t("Started ${instance.name} as $who", "已启动 ${instance.name}，玩家 $who") +
                    " · Java ${game.java.major} · ${formatHeapMb(plan.heapMb)} · ${plan.gc.name}"

                val code = run.awaitExit()
                status = if (code == 0) {
                    t("${instance.name} closed", "${instance.name} 已关闭")
                } else {
                    t("${instance.name} exited with code $code — see launch.log in the instance folder", "${instance.name} 异常退出（代码 $code），请查看实例文件夹中的 launch.log")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = t("Couldn't start ${instance.name}: ", "无法启动 ${instance.name}：") +
                    (e.message?.lineSequence()?.firstOrNull()?.take(110) ?: e::class.simpleName)
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

    fun scanBedrock() {
        if (bedrockScanning) return
        bedrockScanning = true
        scope.launch {
            try {
                bedrockInstalls = bedrock.detect()
            } finally {
                bedrockScanning = false
            }
        }
    }

    fun playBedrock(install: BedrockInstall) = action(t("Couldn't start Bedrock", "无法启动基岩版")) {
        bedrock.launch(install)
        status = t("Starting ${install.edition.en}…", "正在启动${install.edition.zh}…")
    }

    fun openBedrockStore(edition: BedrockEdition) = action(t("Couldn't open the Microsoft Store", "无法打开微软商店")) {
        bedrock.openStore(edition)
    }

    fun openBedrockData(install: BedrockInstall) = action(t("Couldn't open folder", "无法打开文件夹")) {
        val folder = bedrock.dataFolder(install)
        if (folder == null) {
            status = t("No data folder yet — start the game once first", "还没有数据文件夹，请先启动一次游戏")
        } else {
            bedrock.openFolder(folder)
        }
    }

    private fun setPhase(id: String, phase: LaunchPhase?) {
        launchPhases = if (phase == null) launchPhases - id else launchPhases + (id to phase)
    }

    private fun formatHeapMb(mb: Int) = if (mb % 1024 == 0) "${mb / 1024} GB" else "%.1f GB".format(mb / 1024.0)

    private fun action(errorPrefix: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { status = t("$errorPrefix: ${it.message}", "$errorPrefix：${it.message}") }
    }

    override fun close() = http.close()
}
