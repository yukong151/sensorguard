# build_apk.ps1
# Run this in YOUR OWN PowerShell (not the agent sandbox).
# Gradle 8.7 is already extracted, so no download is needed.

$ErrorActionPreference = "Stop"

# 1. Toolchain paths (already verified on this machine)
$env:JAVA_HOME         = "C:\Users\ew\jdk17"
$env:ANDROID_HOME      = "C:\Users\ew\AppData\Local\Android\Sdk"
$env:GRADLE_USER_HOME  = "C:\Users\ew\.gradle"
$env:PATH              = "$env:JAVA_HOME\bin;" + $env:PATH"

# 2. Project root
Set-Location "F:\KTV"

# 3. Clean stale build output (recommended after agent edits)
if (Test-Path "app\build") { Remove-Item -Recurse -Force "app\build" }

# 4. Build debug APK (gradlew reuses extracted Gradle 8.7)
.\gradlew.bat assembleDebug --console=plain --no-daemon

Write-Host "============================================"
Write-Host "APK -> F:\KTV\app\build\outputs\apk\debug\app-debug.apk"
Write-Host "============================================"

# 5. (Optional) If gradlew ever fails, call the extracted Gradle binary directly:
# & "C:\Users\ew\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat" assembleDebug --console=plain --no-daemon
