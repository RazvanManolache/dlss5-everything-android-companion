param([ValidateSet('arm64-v8a', 'x86_64')][string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& (Join-Path $PSScriptRoot 'Prepare-Pyrowave.ps1')
Push-Location (Join-Path $root 'android')
try {
    & .\gradlew.bat assembleDebug compileDebugUnitTestJavaWithJavac "-PclientAbi=$Abi" --no-daemon
    if ($LASTEXITCODE) { throw 'Android build failed.' }
} finally { Pop-Location }
$destination = Join-Path $root $(if ($Abi -eq 'arm64-v8a') { 'local-build/PhoenixLink' } else { 'local-build/PhoenixLink-emulator' })
[void][IO.Directory]::CreateDirectory($destination)
Copy-Item -LiteralPath (Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk') -Destination (Join-Path $destination 'PhoenixLink-preview.apk') -Force
Copy-Item -LiteralPath (Join-Path $root 'android/README.md') -Destination (Join-Path $destination 'README.md') -Force
Write-Host 'Android preview APK built. This build-only script does not run tests, install apps or initialize GPUs.'
