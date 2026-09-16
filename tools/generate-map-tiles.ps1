param(
    [string]$SourceDirectory = (Join-Path $PSScriptRoot '..\map-source'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\app\src\main\assets\map'),
    [int]$TileSize = 512,
    [int]$JpegQuality = 90
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$sourceRoot = (Resolve-Path -LiteralPath $SourceDirectory).Path
$configPath = Join-Path $sourceRoot 'campus_map.json'
$buildingsPath = Join-Path $sourceRoot 'campus_buildings.geojson'
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$imagePath = Join-Path $sourceRoot $config.image.file

if ($config.coordinateSystem -ne 'WGS84') {
    throw "Only WGS84 map data is supported; found '$($config.coordinateSystem)'."
}
if ($TileSize -lt 128 -or $TileSize -gt 1024) {
    throw 'TileSize must be between 128 and 1024.'
}
if ($JpegQuality -lt 1 -or $JpegQuality -gt 100) {
    throw 'JpegQuality must be between 1 and 100.'
}

$image = [System.Drawing.Image]::FromFile($imagePath)
try {
    if ($image.Width -ne $config.image.width -or $image.Height -ne $config.image.height) {
        throw "Image dimensions $($image.Width)x$($image.Height) do not match campus_map.json."
    }

    $maxZoom = [int][Math]::Ceiling([Math]::Log([Math]::Max($image.Width, $image.Height) / $TileSize, 2))
    $outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
    $tilesRoot = Join-Path $outputRoot 'tiles'
    if (Test-Path -LiteralPath $tilesRoot) {
        Remove-Item -LiteralPath $tilesRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $tilesRoot -Force | Out-Null

    $jpegCodec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() |
        Where-Object MimeType -eq 'image/jpeg' |
        Select-Object -First 1
    $encoderParameters = [System.Drawing.Imaging.EncoderParameters]::new(1)
    $encoderParameters.Param[0] = [System.Drawing.Imaging.EncoderParameter]::new(
        [System.Drawing.Imaging.Encoder]::Quality,
        [long]$JpegQuality
    )

    try {
        for ($zoom = 0; $zoom -le $maxZoom; $zoom++) {
            $scale = [Math]::Pow(2, $zoom - $maxZoom)
            $levelWidth = [int][Math]::Ceiling($image.Width * $scale)
            $levelHeight = [int][Math]::Ceiling($image.Height * $scale)
            $levelImage = $image
            $resized = $null

            if ($zoom -ne $maxZoom) {
                $resized = [System.Drawing.Bitmap]::new($levelWidth, $levelHeight, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
                $graphics = [System.Drawing.Graphics]::FromImage($resized)
                try {
                    $graphics.Clear([System.Drawing.Color]::White)
                    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                    $graphics.DrawImage($image, 0, 0, $levelWidth, $levelHeight)
                } finally {
                    $graphics.Dispose()
                }
                $levelImage = $resized
            }

            try {
                $columns = [int][Math]::Ceiling($levelWidth / $TileSize)
                $rows = [int][Math]::Ceiling($levelHeight / $TileSize)
                for ($column = 0; $column -lt $columns; $column++) {
                    $columnDirectory = Join-Path (Join-Path $tilesRoot $zoom) $column
                    New-Item -ItemType Directory -Path $columnDirectory -Force | Out-Null
                    for ($row = 0; $row -lt $rows; $row++) {
                        $left = $column * $TileSize
                        $top = $row * $TileSize
                        $width = [Math]::Min($TileSize, $levelWidth - $left)
                        $height = [Math]::Min($TileSize, $levelHeight - $top)
                        $tile = [System.Drawing.Bitmap]::new($width, $height, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
                        $tileGraphics = [System.Drawing.Graphics]::FromImage($tile)
                        try {
                            $tileGraphics.Clear([System.Drawing.Color]::White)
                            $destination = [System.Drawing.Rectangle]::new(0, 0, $width, $height)
                            $tileGraphics.DrawImage($levelImage, $destination, $left, $top, $width, $height, [System.Drawing.GraphicsUnit]::Pixel)
                            $tile.Save((Join-Path $columnDirectory "$row.jpg"), $jpegCodec, $encoderParameters)
                        } finally {
                            $tileGraphics.Dispose()
                            $tile.Dispose()
                        }
                    }
                }
            } finally {
                if ($null -ne $resized) { $resized.Dispose() }
            }
        }
    } finally {
        $encoderParameters.Dispose()
    }

    Copy-Item -LiteralPath $buildingsPath -Destination (Join-Path $outputRoot 'buildings.geojson') -Force
    $manifest = [ordered]@{
        schemaVersion = 1
        coordinateSystem = $config.coordinateSystem
        width = $image.Width
        height = $image.Height
        tileSize = $TileSize
        minZoom = 0
        maxZoom = $maxZoom
        extension = 'jpg'
        controlPoints = $config.controlPoints
    }
    $manifestJson = $manifest | ConvertTo-Json -Depth 8
    $manifestJson | Set-Content -LiteralPath (Join-Path $outputRoot 'manifest.json') -Encoding utf8
    $buildingsJson = Get-Content -LiteralPath $buildingsPath -Raw
    @"
window.CAMPUS_MAP_MANIFEST = $manifestJson;
window.CAMPUS_BUILDINGS = $buildingsJson;
"@ | Set-Content -LiteralPath (Join-Path $outputRoot 'map-data.js') -Encoding utf8
    Write-Output "Generated local map tiles: $outputRoot (zoom 0-$maxZoom)"
} finally {
    $image.Dispose()
}
