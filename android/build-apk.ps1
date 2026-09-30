# Build the Android APK from the command line (no Android Studio needed).
#   usage: powershell -ExecutionPolicy Bypass -File build-apk.ps1
#
# Requires the local toolchain installed by the companion setup script:
#   - JDK 21           (JAVA_HOME already set by the installer)
#   - Gradle 8.11.1    at D:\Android\gradle-8.11.1
#   - Android SDK       at D:\Android\Sdk  (platform 35, build-tools 35.0.0)
# This file is intentionally ASCII-only: PowerShell 5.1 reads .ps1 as GBK here.
$ErrorActionPreference = 'Stop'

$env:ANDROID_HOME     = 'D:\Android\Sdk'
$env:ANDROID_SDK_ROOT = 'D:\Android\Sdk'
$env:GRADLE_USER_HOME = 'D:\Android\gradle-home'

$gradle = 'D:\Android\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path $gradle)) { throw "Gradle not found at $gradle" }

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$log = Join-Path $root 'build.log'
Write-Host "Building in $root"
# Gradle writes its progress to stderr. With ErrorActionPreference=Stop that would abort
# this script on the first stderr line (NativeCommandError) instead of recording the exit
# code, so relax it just for this call and look at $LASTEXITCODE instead.
$ErrorActionPreference = 'Continue'
& $gradle lintDebug assembleDebug testDebugUnitTest --console=plain *> $log
$exit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'

Write-Host ""
Get-Content $log -Tail 60

if ($exit -ne 0) { throw "Gradle failed (exit $exit), see $log" }

$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $apk) {
    $item = Get-Item $apk
    Write-Host ""
    Write-Host "APK:" -ForegroundColor Green
    Write-Host ("  {0}   {1:N2} MB" -f $item.FullName, ($item.Length / 1MB))
} else {
    Write-Host "APK not found - check $log" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Unit test reports:"
Get-ChildItem (Join-Path $root 'app\build\test-results\testDebugUnitTest') -Filter '*.xml' -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host ("  " + $_.Name) }
