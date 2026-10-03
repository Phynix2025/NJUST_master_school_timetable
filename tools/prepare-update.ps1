param(
    [ValidateSet('debug', 'release')][string]$Variant = 'release'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot = Join-Path $projectRoot "app/build/outputs/apk/$Variant"
$metadata = Get-Content -LiteralPath (Join-Path $buildRoot 'output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.elements.Count -ne 1) { throw 'Expected one universal APK.' }
$entry = $metadata.elements[0]
if ($metadata.applicationId -ne 'cn.edu.njust.kezaizhangxin' -or $entry.versionCode -le 0 -or $entry.versionName -notmatch '^\d+\.\d+\.\d+$') { throw 'Unexpected package/version metadata.' }
$apkPath = [IO.Path]::GetFullPath((Join-Path $buildRoot $entry.outputFile))
if (-not $apkPath.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Unexpected APK path.' }
$outputDirectory = Join-Path $projectRoot "dist/$Variant"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$apkName = "KeZaiZhangXin-v$($entry.versionName).apk"
Copy-Item -LiteralPath $apkPath -Destination (Join-Path $outputDirectory $apkName)
$digest = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
"$digest  $apkName" | Set-Content -LiteralPath (Join-Path $outputDirectory "$apkName.sha256") -Encoding ascii
$update = [ordered]@{
    versionCode = [long]$entry.versionCode
    versionName = $entry.versionName
    minSdk = [int]$metadata.minSdkVersionForDexing
    apk = $apkName
    size = (Get-Item -LiteralPath $apkPath).Length
    sha256 = $digest
}
$json = $update | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $outputDirectory 'update.json'), $json, [Text.UTF8Encoding]::new($false))
Write-Output "Prepared $Variant artifacts in $outputDirectory (no upload performed)."
