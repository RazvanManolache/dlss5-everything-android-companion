$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Join-Path $root '.build-deps/pyrowave'
$revision = 'a0d2e71e98b85f6a23f62407df21a39e9745b875'
if (!(Test-Path -LiteralPath $source)) {
    & git clone https://github.com/Themaister/pyrowave.git $source
    if ($LASTEXITCODE) { throw 'Pyrowave clone failed.' }
}
& git -C $source checkout --detach $revision
if ($LASTEXITCODE) { throw 'Cannot select the pinned Pyrowave revision.' }
$granite = Join-Path $source 'Granite'
if (!(Test-Path -LiteralPath $granite)) {
    & git clone https://github.com/Themaister/Granite.git $granite
    if ($LASTEXITCODE) { throw 'Granite clone failed.' }
}
& git -C $granite checkout --detach fb178c8080d163419e8d20f10715c61c53c1ec9b
if ($LASTEXITCODE) { throw 'Cannot select the pinned Granite revision.' }
# This helper is referenced only by assertions, which shipping builds omit.
# Keep the pinned source and annotate that intentional unused-function case.
$shippingPatch = Join-Path $PSScriptRoot 'granite-shipping-warning.patch'
& git -C $granite apply --reverse --check --unidiff-zero $shippingPatch 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $granite apply --check --unidiff-zero $shippingPatch
    if ($LASTEXITCODE) { throw 'Granite shipping annotation does not match pinned source.' }
    & git -C $granite apply --unidiff-zero $shippingPatch
    if ($LASTEXITCODE) { throw 'Cannot apply Granite shipping annotation.' }
}
& git -C $granite submodule update --init third_party/volk third_party/khronos/vulkan-headers
if ($LASTEXITCODE) { throw 'Granite dependency checkout failed.' }
$licenses = Join-Path $root 'source/codec/licenses'
[void][IO.Directory]::CreateDirectory($licenses)
$texts = @{
    'Pyrowave.txt' = (Join-Path $source 'LICENSE')
    'Granite.txt' = (Join-Path $granite 'LICENSE')
    'Volk.txt' = (Join-Path $granite 'third_party/volk/LICENSE.md')
    'Vulkan-Headers.txt' = (Join-Path $granite 'third_party/khronos/vulkan-headers/LICENSE.md')
    'Apache-2.0.txt' = (Join-Path $granite 'third_party/khronos/vulkan-headers/LICENSES/Apache-2.0.txt')
    'MIT.txt' = (Join-Path $granite 'third_party/khronos/vulkan-headers/LICENSES/MIT.txt')
}
foreach ($entry in $texts.GetEnumerator()) { Copy-Item -LiteralPath $entry.Value -Destination (Join-Path $licenses $entry.Key) }
Write-Host 'Pinned dependencies prepared. No GPU operations or tests were run.'
