# Builds Lumen as a standalone Windows app (Lumen.exe with its own Java runtime) and installs it
# for the current user only: no admin rights, nothing in Program Files.
#
#   powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1
#
# Re-run it to update. Instances, accounts and settings live in %APPDATA%\Lumen and are untouched.
# Needs JDK 21 (with jpackage) in JAVA_HOME to build.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$installDir = Join-Path $env:LOCALAPPDATA 'Programs\Lumen'

Write-Host 'Building Lumen...'
Push-Location $root
try {
    & .\gradlew.bat :app:createDistributable --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}
$built = Join-Path $root 'app\build\compose\binaries\main\app\Lumen'
if (-not (Test-Path (Join-Path $built 'Lumen.exe'))) { throw "Build output not found at $built" }

# The old copy can't be replaced while it's running.
$running = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($installDir, [StringComparison]::OrdinalIgnoreCase) }
if ($running) { throw 'Lumen is running from the installed copy. Close it and run this script again.' }

Write-Host "Installing to $installDir..."
if (Test-Path $installDir) { Remove-Item -LiteralPath $installDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $installDir) | Out-Null
Copy-Item -LiteralPath $built -Destination $installDir -Recurse

$exe = Join-Path $installDir 'Lumen.exe'
$shell = New-Object -ComObject WScript.Shell
foreach ($folder in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $link = $shell.CreateShortcut((Join-Path $folder 'Lumen.lnk'))
    $link.TargetPath = $exe
    $link.WorkingDirectory = $installDir
    $link.IconLocation = "$exe,0"
    $link.Description = 'Lumen Minecraft launcher'
    $link.Save()
}
Write-Host 'Done: Lumen is on your desktop and in the Start menu.'
