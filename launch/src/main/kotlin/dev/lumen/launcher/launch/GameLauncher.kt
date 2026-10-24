package dev.lumen.launcher.launch

import dev.lumen.launcher.core.jvm.JvmPlan
import dev.lumen.launcher.installer.PreparedGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class RunningGame(private val process: Process, val logFile: Path) {
    val pid: Long get() = process.pid()
    val isAlive: Boolean get() = process.isAlive

    /** Suspends (without blocking a thread) until the game exits; returns its exit code. */
    suspend fun awaitExit(): Int = process.onExit().await().exitValue()

    fun stop() {
        process.destroy() // graceful first; the game saves worlds on shutdown
    }
}

/**
 * Spawns the game. Output goes to `<instance>/launch.log`. Every argument except the access token goes
 * into `launch-args.txt` (a Java @argfile, also handy for debugging); the token pair is appended on the
 * real command line after the argfile, so it is never written to disk.
 */
class GameLauncher(private val launcherName: String = "Lumen", private val launcherVersion: String = "1.0.0") {

    suspend fun launch(game: PreparedGame, instanceRoot: Path, gameDir: Path, account: LaunchAccount, jvm: JvmPlan): RunningGame =
        withContext(Dispatchers.IO) {
            Files.createDirectories(gameDir)
            val args = LaunchCommand.arguments(game, gameDir, account, jvm, launcherName, launcherVersion)
            val (fileArgs, secretArgs) = LaunchCommand.splitSecrets(args, account.accessToken)

            val argFile = instanceRoot.resolve("launch-args.txt")
            Files.writeString(argFile, LaunchCommand.toArgFile(fileArgs))

            val java = game.java.executable.toString()
            val command = if (game.java.major >= 9) {
                // Secrets are game arguments, so they can follow everything the argfile expands to.
                listOf(java, "@$argFile") + secretArgs
            } else {
                listOf(java) + args // Java 8 has no @argfiles
            }

            val log = instanceRoot.resolve("launch.log")
            val process = ProcessBuilder(command)
                .directory(gameDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
            RunningGame(process, log)
        }
}
