package dev.lumen.launcher.core.download

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

data class DownloadTask(
    val url: String,
    val target: Path,
    val sha512: String? = null,
    val sha1: String? = null,
    val size: Long? = null,
    val label: String = target.fileName.toString(),
)

data class DownloadProgress(
    val label: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
) {
    val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
}

class HashMismatchException(val file: Path, expected: String, actual: String) :
    IllegalStateException("Hash mismatch for ${file.fileName}: expected $expected, got $actual")

/**
 * Streams files to disk with bounded parallelism, hashing while writing (no second pass),
 * writing to `.part` and atomically moving into place only after verification.
 *
 * [active] is a throttled snapshot of every in-flight download — the UI renders progress
 * bars and speeds straight from it.
 */
class DownloadManager(
    private val client: HttpClient,
    maxParallel: Int = 6,
) {
    private val permits = Semaphore(maxParallel)
    private val targetLocks = ConcurrentHashMap<Path, Mutex>()
    private val _active = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val active: StateFlow<Map<String, DownloadProgress>> = _active.asStateFlow()

    suspend fun downloadAll(tasks: List<DownloadTask>): List<Path> = coroutineScope {
        tasks.distinctBy { it.target }.map { async { download(it) } }.awaitAll()
    }

    /**
     * Two installs can need the same file at once (e.g. two mods both requiring Fabric API).
     * The per-target lock makes the second caller wait, then hit [isAlreadyValid] and skip,
     * instead of both streaming into the same `.part` file. Taken before the permit so waiters
     * don't hold download slots.
     */
    suspend fun download(task: DownloadTask): Path = targetLock(task.target).withLock {
        permits.withPermit { transfer(task) }
    }

    private fun targetLock(target: Path): Mutex = targetLocks.computeIfAbsent(target.toAbsolutePath().normalize()) { Mutex() }

    private suspend fun transfer(task: DownloadTask): Path =
        withContext(Dispatchers.IO) {
            if (isAlreadyValid(task)) return@withContext task.target

            Files.createDirectories(task.target.parent)
            val tmp = task.target.resolveSibling("${task.target.fileName}.part")
            val (algorithm, expected) = expectedHash(task)
            val digest = algorithm?.let(MessageDigest::getInstance)
            val key = task.target.toString()

            try {
                client.prepareGet(task.url).execute { response ->
                    val total = response.contentLength() ?: task.size ?: -1L
                    val channel = response.bodyAsChannel()
                    val meter = SpeedMeter()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var done = 0L

                    Files.newOutputStream(tmp).use { out ->
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read == -1) break
                            if (read == 0) continue
                            out.write(buffer, 0, read)
                            digest?.update(buffer, 0, read)
                            done += read
                            meter.sample(done)?.let { speed ->
                                _active.update { it + (key to DownloadProgress(task.label, done, total, speed)) }
                            }
                        }
                    }
                }

                if (digest != null && expected != null) {
                    val actual = HexFormat.of().formatHex(digest.digest())
                    if (!actual.equals(expected, ignoreCase = true)) throw HashMismatchException(task.target, expected, actual)
                }
                Files.move(tmp, task.target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                task.target
            } catch (t: Throwable) {
                Files.deleteIfExists(tmp)
                throw t
            } finally {
                _active.update { it - key }
            }
        }

    /** Skip work when the file is already present and its hash matches (re-installs, repairs). */
    private fun isAlreadyValid(task: DownloadTask): Boolean {
        if (!Files.exists(task.target)) return false
        val (algorithm, expected) = expectedHash(task)
        if (algorithm == null || expected == null) return task.size != null && Files.size(task.target) == task.size
        if (task.size != null && Files.size(task.target) != task.size) return false
        val digest = MessageDigest.getInstance(algorithm)
        Files.newInputStream(task.target).use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return HexFormat.of().formatHex(digest.digest()).equals(expected, ignoreCase = true)
    }

    private fun expectedHash(task: DownloadTask): Pair<String?, String?> = when {
        task.sha512 != null -> "SHA-512" to task.sha512
        task.sha1 != null -> "SHA-1" to task.sha1
        else -> null to null
    }

    /** Emits a smoothed bytes/sec at most every [intervalMs], which also throttles UI updates. */
    private class SpeedMeter(private val intervalMs: Long = 150) {
        private var lastTime = System.nanoTime()
        private var lastBytes = 0L
        private var smoothed = 0.0

        fun sample(bytes: Long): Long? {
            val now = System.nanoTime()
            val elapsedMs = (now - lastTime) / 1_000_000
            if (elapsedMs < intervalMs) return null
            val instant = (bytes - lastBytes) * 1000.0 / elapsedMs
            smoothed = if (smoothed == 0.0) instant else smoothed * 0.7 + instant * 0.3
            lastTime = now
            lastBytes = bytes
            return smoothed.toLong()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
