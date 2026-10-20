param(
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [string[]]$Files = @('face-original.png','face-phoenix.png','face-returned-frame.png'),
    [string[]]$Labels = @('Original','Live overlay (80%)','Returned output (100%)'),
    [string]$Caption = 'Android emulator | Native 1080 x 2400 | Raw transport | Phoenix: 1 iteration'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
if($Files.Count -ne 3 -or $Labels.Count -ne 3){throw 'Provide exactly three images and labels.'}
$images = @($files | ForEach-Object { [Drawing.Bitmap]::new((Join-Path $root $_)) })
$canvas = [Drawing.Bitmap]::new(2224,1012)
$graphics = [Drawing.Graphics]::FromImage($canvas)
$font = [Drawing.Font]::new('Segoe UI',24,[Drawing.FontStyle]::Regular,[Drawing.GraphicsUnit]::Pixel)
$note = [Drawing.Font]::new('Segoe UI',18,[Drawing.FontStyle]::Regular,[Drawing.GraphicsUnit]::Pixel)
try {
    $graphics.Clear([Drawing.Color]::FromArgb(23,26,29))
    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    # Identical crop and scale for all three genuine captures. No pixel retouching.
    $crop = [Drawing.Rectangle]::new(40,580,1000,1240)
    for ($i=0; $i -lt 3; $i++) {
        if($images[$i].Width -ne 1080 -or $images[$i].Height -ne 2400) { throw 'Unexpected screenshot dimensions; review crop coordinates.' }
        $x = 16 + $i*736
        $graphics.DrawString($labels[$i],$font,[Drawing.Brushes]::White,[single]$x,[single]16)
        $graphics.DrawImage($images[$i],[Drawing.Rectangle]::new($x,64,720,893),$crop,[Drawing.GraphicsUnit]::Pixel)
    }
    $graphics.DrawString($Caption,$note,[Drawing.Brushes]::LightGray,[single]16,[single]974)
    $canvas.Save((Join-Path $root 'face-comparison.png'),[Drawing.Imaging.ImageFormat]::Png)
    [pscustomobject]@{
        Sources = @($files | ForEach-Object { @{ File=$_; Sha256=(Get-FileHash -LiteralPath (Join-Path $root $_) -Algorithm SHA256).Hash } })
        Crop = @{X=40;Y=580;Width=1000;Height=1240}
        Retouched = $false
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $root 'face-comparison-provenance.json')
} finally {
    $font.Dispose();$note.Dispose();$graphics.Dispose();$canvas.Dispose()
    foreach($image in $images){$image.Dispose()}
}
Join-Path $root 'face-comparison.png'
