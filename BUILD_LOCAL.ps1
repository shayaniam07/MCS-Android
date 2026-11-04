# Source project build helper. Requires Android SDK + Gradle, for example via Android Studio.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command gradle -ErrorAction SilentlyContinue)) {
    throw 'Gradle is unavailable on PATH. Open this project in Android Studio, or use the included GitHub Actions workflow.'
}
& gradle :app:testDebugUnitTest :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android Gradle build did not complete successfully.' }
Write-Host 'APK created: app\build\outputs\apk\debug\app-debug.apk' -ForegroundColor Green
