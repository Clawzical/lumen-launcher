package dev.lumen.launcher.ui.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lumen.launcher.core.instance.Instance
import dev.lumen.launcher.core.instance.ModLoader
import dev.lumen.launcher.modrinth.ModrinthClient
import dev.lumen.launcher.modrinth.ProjectType
import dev.lumen.launcher.modrinth.SearchHit
import dev.lumen.launcher.modrinth.SearchQuery
import dev.lumen.launcher.modrinth.SortIndex
import kotlin.coroutines.cancellation.CancellationException

/** Everything that changes what the result list contains. A new key = a fresh search from page 0. */
data class SearchKey(
    val text: String,
    val type: ProjectType,
    val sort: SortIndex,
    val categories: Set<String>,
    val gameVersion: String?,
    val loader: ModLoader?,
)

enum class InstallState { Available, Installing, Installed, Incompatible }

/** Popular Modrinth category ids per project type (the API's ids, not display names). */
val browserCategories: Map<ProjectType, List<String>> = mapOf(
    ProjectType.MOD to listOf("optimization", "utility", "library", "technology", "adventure", "magic", "worldgen", "decoration", "storage", "mobs"),
    ProjectType.RESOURCE_PACK to listOf("vanilla-like", "simplistic", "realistic", "themed", "gui", "16x", "32x"),
    ProjectType.SHADER to listOf("vanilla-like", "semi-realistic", "realistic", "fantasy", "cartoon", "potato", "high"),
)

/** Loader ids that Modrinth mixes into `categories`; shown as compatibility, not as tags. */
val loaderCategoryIds = setOf(
    "fabric", "quilt", "forge", "neoforge", "liteloader", "modloader", "rift", "babric", "legacy-fabric",
    "ornithe", "nilloader", "java-agent", "iris", "optifine", "canvas", "vanilla", "minecraft", "datapack",
    "bukkit", "paper", "spigot", "purpur", "folia", "sponge", "velocity", "bungeecord", "waterfall",
)

/**
 * State for the Mod Browser. Held by AppState (not the composable) so results, scroll-worthy
 * pages and filters survive switching screens.
 */
class ModBrowserModel {
    var query by mutableStateOf("")
    var type by mutableStateOf(ProjectType.MOD)
    var sort by mutableStateOf(SortIndex.RELEVANCE)
    var categories by mutableStateOf<Set<String>>(emptySet())
    var compatibleOnly by mutableStateOf(true)
    var targetInstanceId by mutableStateOf<String?>(null)

    var results by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var totalHits by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Key the current [results] belong to; pagination only appends to the matching search. */
    var loadedKey by mutableStateOf<SearchKey?>(null)
        private set

    var installing by mutableStateOf<Set<String>>(emptySet())
    var installed by mutableStateOf<Set<String>>(emptySet())
    var failures by mutableStateOf<Map<String, String>>(emptyMap())

    val canLoadMore: Boolean get() = results.size < totalHits

    fun keyFor(target: Instance?) = SearchKey(
        text = query.trim(),
        type = type,
        sort = sort,
        categories = categories,
        gameVersion = target?.gameVersion?.takeIf { compatibleOnly },
        loader = target?.loader?.takeIf { compatibleOnly && type == ProjectType.MOD && it != ModLoader.VANILLA },
    )

    fun selectType(newType: ProjectType) {
        if (newType == type) return
        type = newType
        categories = emptySet() // category ids differ per type
        query = "" // a mod name searched among shaders is almost never what's wanted
    }

    fun toggleCategory(id: String) {
        categories = if (id in categories) categories - id else categories + id
    }

    suspend fun search(client: ModrinthClient, key: SearchKey) = load(client, key, reset = true)

    suspend fun loadMore(client: ModrinthClient, key: SearchKey) {
        if (loading || !canLoadMore || loadedKey != key) return
        load(client, key, reset = false)
    }

    private suspend fun load(client: ModrinthClient, key: SearchKey, reset: Boolean) {
        loading = true
        if (reset) error = null
        try {
            val page = client.search(
                SearchQuery(
                    text = key.text,
                    type = key.type,
                    gameVersion = key.gameVersion,
                    loader = key.loader,
                    categories = key.categories.toList(),
                    sort = key.sort,
                    offset = if (reset) 0 else results.size,
                    limit = PAGE_SIZE,
                ),
            )
            results = if (reset) page.hits else (results + page.hits).distinctBy { it.projectId }
            totalHits = page.totalHits
            loadedKey = key
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.lineSequence()?.firstOrNull()?.take(120) ?: "Search failed"
        } finally {
            loading = false
        }
    }

    /** Local compatibility check from search metadata — no extra request per card. */
    fun stateOf(hit: SearchHit, target: Instance?): InstallState = when {
        hit.projectId in installing -> InstallState.Installing
        hit.projectId in installed -> InstallState.Installed
        target == null -> InstallState.Incompatible
        // Mods and shaders (via Iris) both need a mod loader; resource packs work everywhere.
        type != ProjectType.RESOURCE_PACK && target.loader == ModLoader.VANILLA -> InstallState.Incompatible
        hit.versions.isNotEmpty() && target.gameVersion !in hit.versions -> InstallState.Incompatible
        type == ProjectType.MOD && hit.categories.none { it in acceptedLoaders(target.loader) } -> InstallState.Incompatible
        else -> InstallState.Available
    }

    /** Quilt runs most Fabric mods, so it accepts both tags. */
    private fun acceptedLoaders(loader: ModLoader): Set<String> =
        if (loader == ModLoader.QUILT) setOf("quilt", "fabric") else setOf(loader.apiId)

    companion object {
        const val PAGE_SIZE = 20
    }
}
