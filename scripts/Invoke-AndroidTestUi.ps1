param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [string]$Text,
    [string]$ResourceId,
    [string]$Adb = (Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe')
)
$ErrorActionPreference = 'Stop'
& $Adb -s $Serial shell uiautomator dump /sdcard/phoenix-test-ui.xml | Out-Null
[xml]$ui = (& $Adb -s $Serial shell cat /sdcard/phoenix-test-ui.xml) -join "`n"
$nodes = @($ui.SelectNodes('//node'))
if (!$Text -and !$ResourceId) {
    $nodes | Where-Object { $_.text -or $_.'content-desc' } | Select-Object text,resource-id,content-desc,bounds
    exit
}
$match = @($nodes | Where-Object {
    (!$Text -or $_.text -eq $Text -or $_.'content-desc' -eq $Text) -and
    (!$ResourceId -or $_.'resource-id' -eq $ResourceId)
})
if ($match.Count -ne 1) { throw "Expected one UI match; found $($match.Count)." }
if ($match[0].bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Invalid UI bounds.' }
$x = [int]( ([int]$Matches[1]+[int]$Matches[3])/2 )
$y = [int]( ([int]$Matches[2]+[int]$Matches[4])/2 )
& $Adb -s $Serial shell input tap $x $y
