param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [ValidateSet('pattern','phoenix')][string]$Backend = 'pattern',
    [string]$PackageRoot,
    [switch]$Pyrowave,
    [switch]$FaceDemo,
    [string]$FaceImage,
    [switch]$Follow,
    [string[]]$TestCase,
    [string]$Adb = (Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!(Test-Path -LiteralPath (Join-Path $root '../dlss5-everything/app/Dlss5CompatApp/Dlss5CompatApp.csproj'))) {
    throw 'Integration tests require the sibling dlss5-everything Windows checkout. See docs/BUILD.md.'
}
if ($FaceDemo) {
    if (!$FaceImage -or !(Test-Path -LiteralPath $FaceImage -PathType Leaf)) { throw 'Face tests require an explicitly supplied -FaceImage fixture.' }
    $FaceImage = (Resolve-Path -LiteralPath $FaceImage).Path
}
& (Join-Path $PSScriptRoot 'Prepare-Pyrowave.ps1')
$run = Join-Path $root ('local-build/AndroidEmulator-' + [guid]::NewGuid().ToString('N'))
$hostRoot = Join-Path $run 'host'
[void][IO.Directory]::CreateDirectory($run)
if ((& $Adb -s $Serial shell getprop sys.boot_completed) -ne '1') { throw 'Boot the selected emulator first.' }
if ($Backend -eq 'phoenix' -and !$PackageRoot) { throw 'Phoenix testing requires a compatible local -PackageRoot.' }
if ($FaceDemo -and $Backend -ne 'phoenix') { throw 'Face screenshots must use the real Phoenix backend.' }
if ($PackageRoot) { $PackageRoot = (Resolve-Path -LiteralPath $PackageRoot).Path }
$oldPackage = $env:PHOENIX_TEST_PACKAGE_ROOT
$hostProcess = $null
$oldAccessibility = $null
$oldAccessibilityEnabled = $null
try {
    Push-Location $root
    try {
        & dotnet build tests/AndroidEmulatorHost/AndroidEmulatorHost.csproj -c Release -o $hostRoot
        if ($LASTEXITCODE) { throw 'Emulator host build failed.' }
        Push-Location android
        try {
            & ./gradlew.bat assembleDebug assembleDebugAndroidTest testDebugUnitTest '-PclientAbi=x86_64' --no-daemon
            if ($LASTEXITCODE) { throw 'Emulator APK build or unit tests failed.' }
        } finally { Pop-Location }
        & $Adb -s $Serial install -r android/app/build/outputs/apk/debug/app-debug.apk
        if ($LASTEXITCODE) { throw 'Client install failed.' }
        & $Adb -s $Serial install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
        if ($LASTEXITCODE) { throw 'Instrumentation install failed.' }
    } finally { Pop-Location }
    & $Adb -s $Serial shell appops set io.phoenixlink SYSTEM_ALERT_WINDOW allow
    & $Adb -s $Serial shell pm grant io.phoenixlink android.permission.POST_NOTIFICATIONS
    $env:PHOENIX_TEST_PACKAGE_ROOT = $PackageRoot
    $hostProcess = Start-Process (Join-Path $hostRoot 'Dlss5CompatApp.Tests.exe') -ArgumentList "$Backend `"$run`"" -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run/host.stdout.txt" -RedirectStandardError "$run/host.stderr.txt"
    $deadline = (Get-Date).AddSeconds(15)
    while (!(Test-Path -LiteralPath "$run/pairing.json")) {
        if ($hostProcess.HasExited -or (Get-Date) -gt $deadline) { throw 'Loopback test host did not start.' }
        Start-Sleep -Milliseconds 100
    }
    & $Adb -s $Serial push "$run/pairing.json" /data/local/tmp/phoenix-pairing.json | Out-Null
    & $Adb -s $Serial shell run-as io.phoenixlink mkdir -p files
    & $Adb -s $Serial shell run-as io.phoenixlink cp /data/local/tmp/phoenix-pairing.json files/emulator-pairing.json
    & $Adb -s $Serial shell rm /data/local/tmp/phoenix-pairing.json
    if($Follow) {
        $oldAccessibility=(& $Adb -s $Serial shell settings get secure enabled_accessibility_services).Trim()
        $oldAccessibilityEnabled=(& $Adb -s $Serial shell settings get secure accessibility_enabled).Trim()
        $component='io.phoenixlink/io.phoenixlink.FollowAccessibilityService'
        $enabled=if($oldAccessibility -eq 'null' -or !$oldAccessibility){$component}else{$oldAccessibility+':'+$component}
        & $Adb -s $Serial shell settings put secure enabled_accessibility_services $enabled
        & $Adb -s $Serial shell settings put secure accessibility_enabled 1
        Start-Sleep -Seconds 2
    }
    if ($FaceDemo) {
        & $Adb -s $Serial push $FaceImage /data/local/tmp/phoenix-face.jpg | Out-Null
        & $Adb -s $Serial shell run-as io.phoenixlink.test mkdir -p files
        & $Adb -s $Serial shell run-as io.phoenixlink.test cp /data/local/tmp/phoenix-face.jpg files/face.jpg
        & $Adb -s $Serial shell rm /data/local/tmp/phoenix-face.jpg
    }
    $cases = if ($Backend -eq 'pattern') {
        @('testRawRoundTripAndControls','testPairingRejectionAndRecovery','testStopFromMainThread',
            'testOverlayPixelsVisibilityAndCleanup','testOverlayImmediateStop','testConnectionStorage','testUiSnapshots','testPrepareCaptureUi')
    } else { @('testPhoenixRoundTrip','testPrepareCaptureUi') }
    if ($Pyrowave) { $cases += 'testPyrowaveRoundTrip' }
    if ($FaceDemo) { $cases = @('testFaceCaptureScreenshots') }
    if ($Follow) { $cases = @('testFollowActiveApp') }
    if ($TestCase) {
        foreach ($requested in $TestCase) {
            if ($requested -notin $cases) { throw "Test case is unavailable for the selected backend/options: $requested" }
        }
        $cases = @($TestCase)
    }
    $results = foreach ($case in $cases) {
        $source = if($Follow -and $FaceDemo){'face'}else{'pattern'}
        $output = (& $Adb -s $Serial shell am instrument -w -r -e case $case -e source $source io.phoenixlink.test/io.phoenixlink.EmulatorTests) -join "`n"
        $output | Set-Content -LiteralPath "$run/$case.txt"
        $passed = $output.Contains('INSTRUMENTATION_RESULT: passed=true')
        Write-Host "$case : $(if($passed){'PASS'}else{'FAIL'})"
        if (!$passed) { Write-Host $output }
        [pscustomobject]@{ Case=$case; Passed=$passed }
    }
    $results | ConvertTo-Json | Set-Content -LiteralPath "$run/results.json"
    if ($Backend -eq 'pattern' -and !$Follow) {
        foreach ($image in 'ui-portrait.png','ui-landscape.png') {
            & $Adb -s $Serial pull "/sdcard/Android/data/io.phoenixlink/files/$image" "$run/$image" | Out-Null
        }
    }
    if ($FaceDemo) {
        $captures=if($Follow){@('follow-original.png','follow-phoenix.png','follow-reenabled.png')}else{@('face-original.png','face-phoenix.png','face-returned-frame.png')}
        foreach ($image in $captures) {
            & $Adb -s $Serial pull "/sdcard/Android/data/io.phoenixlink/files/$image" "$run/$image" | Out-Null
        }
    }
    Write-Host "Private test results: $run"
    if (@($results | Where-Object { !$_.Passed }).Count) { throw 'One or more emulator checks failed; inspect the private test results.' }
} finally {
    if($null -ne $oldAccessibility) {
        if($oldAccessibility -eq 'null'){& $Adb -s $Serial shell settings delete secure enabled_accessibility_services | Out-Null}
        else {& $Adb -s $Serial shell settings put secure enabled_accessibility_services $oldAccessibility}
        if($oldAccessibilityEnabled -eq 'null'){& $Adb -s $Serial shell settings delete secure accessibility_enabled | Out-Null}
        else {& $Adb -s $Serial shell settings put secure accessibility_enabled $oldAccessibilityEnabled}
    }
    & $Adb -s $Serial shell am force-stop io.phoenixlink
    & $Adb -s $Serial shell am force-stop io.phoenixlink.test
    & $Adb -s $Serial shell run-as io.phoenixlink rm -f files/emulator-pairing.json shared_prefs/connection.xml
    if ($hostProcess -and !$hostProcess.HasExited) {
        New-Item -ItemType File -Path "$run/stop" -Force | Out-Null
        if (!$hostProcess.WaitForExit(10000)) { $hostProcess.Kill($true); $hostProcess.WaitForExit() }
    }
    if (Test-Path -LiteralPath "$run/pairing.json") { Remove-Item -LiteralPath "$run/pairing.json" }
    $env:PHOENIX_TEST_PACKAGE_ROOT = $oldPackage
}
