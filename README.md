# Lumen Launcher

A lightweight Minecraft launcher: Kotlin, Compose for Desktop, Ktor (CIO) and coroutines.

## Run

```bash
./gradlew :app:run                    # dev run
./gradlew :app:packageReleaseMsi      # ProGuard-shrunk native installer (Dmg / Deb on other OSes)
```

On Windows, install Lumen as a normal app, with its own Java, desktop and Start menu shortcuts, and no admin rights needed:

```bash
powershell -ExecutionPolicy Bypass -File scripts/install-windows.ps1
```

It installs to `%LOCALAPPDATA%\Programs\Lumen`. Run it again to update; your instances and accounts in `%APPDATA%\Lumen` are kept.

Requires JDK 21. If you fork Lumen, change the User-Agent in `AppState.kt` to your own name and contact; Modrinth asks every app to identify itself.

## Architecture

```
┌──────────────────────────── :app (Compose Desktop) ─────────────────────────────┐
│  Main.kt → LumenTheme → LauncherShell (Sidebar + AnimatedContent screens)       │
│  AppState: service graph + UI state; intents launch coroutines                  │
└───────────────┬──────────────────────────────┬──────────────────────────────────┘
                │                              │
     ┌──────────▼─────────┐        ┌───────────▼───────────┐   planned, same shape:
     │     :modrinth      │        │   :curseforge         │   :auth      (MSA → XBL → XSTS → MC)
     │ ModrinthClient     │        │   (official API key)  │   :installer (vanilla/Fabric/Quilt/NeoForge)
     │ ModInstaller (BFS  │        └───────────┬───────────┘   :launch    (classpath, natives, process)
     │  dependency graph) │                    │
     └──────────┬─────────┘                    │
                └──────────────┬───────────────┘
                     ┌─────────▼──────────┐
                     │       :core        │  no UI dependencies, so it is testable headless
                     │ Http (one client)  │
                     │ DownloadManager    │  bounded parallelism, hash-while-stream, atomic move
                     │ InstanceManager    │  StateFlow<List<Instance>>, atomic manifests
                     │ JvmOptimizer       │  RAM auto-sizing, ZGC / Shenandoah / G1 profiles
                     │ LauncherPaths      │  shared libs/assets + isolated instance dirs
                     └────────────────────┘
```

```
lumen-launcher/
├─ settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml
├─ core/src/main/kotlin/dev/lumen/launcher/core/
│  ├─ LauncherPaths.kt
│  ├─ net/Http.kt
│  ├─ download/DownloadManager.kt
│  ├─ instance/{Instance.kt, InstanceManager.kt}
│  └─ jvm/JvmOptimizer.kt
├─ modrinth/src/main/kotlin/dev/lumen/launcher/modrinth/
│  ├─ ModrinthModels.kt
│  ├─ ModrinthClient.kt
│  └─ ModInstaller.kt
└─ app/src/main/kotlin/dev/lumen/launcher/
   ├─ Main.kt · AppState.kt
   └─ ui/{Shell.kt, DashboardScreen.kt, InstancesScreen.kt, Components.kt, theme/Theme.kt}
```

## Performance decisions

- **One `HttpClient`** with a pooled CIO engine. Every network call is a suspending call, and none blocks a thread.
- **Downloads** hash while they stream (no second read pass). Each file is written to `.part` and atomically moved into place after it verifies. Files that are already valid are skipped. A `Semaphore` caps parallel downloads at 16, and a per-file lock stops two installs from writing the same file.
- **Game installs** hash-check every file the first time. After that, launches only check that each file exists and has the right size, which takes about a second instead of re-hashing roughly 600 MB.
- **Progress updates are throttled** to about 7 per second per file, so the UI isn't recomposed on every 64 KB chunk.
- **Shared, content-addressed** `libraries/assets/versions`, so instances never store duplicate game files.
- **The launcher's own JVM** is capped at 320 MB and uses SerialGC. The game gets its own tuned JVM from `JvmOptimizer`.

## Window and branding

- **Logo:** an isometric block with a spark of light on a green-to-teal tile. It's defined once as shape data in `ui/brand/Logo.kt`. Compose draws it in the app and for the window/taskbar icon. Running `./gradlew :app:generateIcons` renders the same shapes into `app/icons/lumen.ico` (16 to 256 px) and `lumen.png`, which the installers use. macOS needs a `.icns` file, which isn't generated yet.
- **Custom title bar:** the window draws its own dark title bar instead of the OS's white one (`ui/window/`), so it needs to replace things the OS normally provides:
  - drag the bar to move the window, and double-click it to maximize or restore;
  - drag to the top of the screen to maximize, and drag a maximized window to restore it;
  - resize from any edge or corner;
  - maximize fills the screen but leaves the taskbar visible;
  - Windows 11 rounded corners and border, via DWM; other systems get a 1 px border instead.
  - **Not provided:** Windows' Snap Layouts menu, which appears when you hover the maximize button.

## Launching

Pressing Play runs `GameInstaller.prepare()` from `:installer`, then `GameLauncher.launch()` from `:launch`:

- **Version files:** Mojang's version JSON, then the loader's JSON on top of it (Fabric/Quilt meta profile, or NeoForge's installer run headlessly with `--install-client`). These are merged along the `inheritsFrom` chain, filtered by OS rules and de-duplicated.
- **Game files:** game jar, libraries, natives (flattened into `shared/natives/<id>`), the asset index plus about 4,000 objects, and the log4j config.
- **Java:** a locally installed JDK with exactly the required major version, otherwise Mojang's own runtime for that component.
- **JVM arguments:** `JvmOptimizer.plan()` flags come first, then the version's JVM arguments, main class and game arguments. On Java 9+ they are passed as an `@argfile`, which avoids Windows' 32K command-line limit.
- **Output:** the game's output goes to `<instance>/launch.log`, and the exact arguments are saved to `<instance>/launch-args.txt`.

Tested on Windows with 1.21.1: vanilla, Fabric 0.20.1 (with Sodium, Iris and Lithium), and NeoForge 21.1.264.

## Microsoft accounts (`:auth`)

Sign-in uses the OAuth device-code flow:

1. Lumen shows a code. The player enters it on Microsoft's own sign-in page in their browser, so Lumen never sees the password.
2. Lumen exchanges the result for Microsoft tokens, then an Xbox Live token, then an XSTS token, then a Minecraft token and profile.

What is stored where:

- **Refresh token:** only in the OS keychain. On Windows that's Credential Manager (entries named `Lumen/msa-refresh-token:<uuid>`). On macOS it's the login Keychain via `security`, and on Linux the Secret Service via `secret-tool`. If no keychain is available, the token stays in memory and you sign in again each session; it is never written to a plain file.
- **`accounts.json`:** only the account name, UUID and skin URL.
- **Minecraft token:** kept in memory and reused for about 24 hours, then silently refreshed. Microsoft returns a new refresh token each time, and it's saved.
- **At launch:** the access token is appended to the command line after the `@argfile`, so it never appears in `launch-args.txt`.

Without a signed-in account, Play launches in **offline mode** (singleplayer and LAN only). With an account whose session has expired, Play stops and opens the sign-in dialog instead of silently going offline.

### One-time setup: your own Azure app

Microsoft sign-in needs an Azure app registration that you own. Mojang must also approve it for Minecraft logins.

1. In the Azure portal, open **Microsoft Entra ID**, then **App registrations**, then **New registration**. Set **Supported account types** to "Personal Microsoft accounts only". No redirect URI is needed.
2. Under **Authentication**, set **Allow public client flows** to **Yes**. Device-code sign-in needs this.
3. Copy the **Application (client) ID** into `~/.gradle/gradle.properties`:
   ```properties
   lumen.msaClientId=<your-client-id>
   ```
   Packaged builds can use the `LUMEN_MSA_CLIENT_ID` environment variable instead.
4. Request Minecraft API access for that client ID. Until Mojang approves it, the final step returns `403 Invalid app registration` (see https://aka.ms/AppRegInfo), and Lumen reports "isn't approved for Minecraft logins yet".

Lumen maps Microsoft's and Xbox's error codes to plain-language messages. For example: unknown client ID (AADSTS700016), public client flows disabled, no Xbox profile, child account, region restrictions, and accounts that don't own the game.

## Managing an instance's content

Click an instance (or **Manage** on the dashboard) to see its mods, resource packs and shaders:

- **Problems before you launch.** For Fabric and Quilt instances, Lumen reads each mod's `fabric.mod.json`, including mods bundled inside other mods, and runs the same dependency check Fabric does at startup. Wrong versions, missing dependencies, the wrong Minecraft version and declared incompatibilities each come with a one-click fix: switch to a version that fits, install the missing mod, or disable the offending one. Fixes that pick a version are double-checked against the downloaded jar's own version.
- **Enable or disable** a file by renaming it to `.disabled`. **Remove** warns when another mod depends on the file.
- **Updates.** One bulk Modrinth request checks every file by its hash. A stable install is only offered stable updates. An update is held back (with the reason) when another mod needs the current version, or when the new version pins a different version of something you have.
- **Change version** lists every compatible version and marks the ones that would break another mod.
- **Hand-added files** are recognised by hash and added to the instance's record, so they get names, icons and updates too.

NeoForge instances get listing, updates and version changes, but no dependency check yet: NeoForge declares dependencies in `neoforge.mods.toml`, which Lumen doesn't read yet.

## Roadmap (suggested order)

1. `.mrpack` modpack import.
2. NeoForge dependency checks (`neoforge.mods.toml`, Maven version ranges).
3. `:curseforge`: same interfaces as `:modrinth`, behind a common `ContentSource` abstraction.
4. Legacy versions (before 1.7.10): virtual/`map_to_resources` asset layouts, which aren't handled yet.
