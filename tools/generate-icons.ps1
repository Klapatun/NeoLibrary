# Regenerates the PNG header icons in app/src/main/res/drawable from their SVG
# sources in app/icons.
#
# Requirements: .NET 9 SDK on PATH (the tool uses the OS's built-in SVG decoder
# and has zero NuGet dependencies, so it works offline).
#
# The generated PNGs are committed to the repository: the Android build itself
# never needs this tool, it stays 100% offline.
#
# Usage:  powershell -File tools\generate-icons.ps1
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot            # <repo>\tools\... -> <repo>
$iconsDir = Join-Path $repo 'app\icons'
$outDir   = Join-Path $repo 'app\src\main\res\drawable'
$toolDir  = Join-Path $PSScriptRoot 'SvgToPng'

# First build (and re-build after tool changes); the dll path is stable per
# TFM. -v quiet keeps the console readable.
& dotnet build $toolDir --nologo -v quiet
if ($LASTEXITCODE -ne 0) { throw "dotnet build failed" }

$bin = Join-Path $toolDir 'bin\Debug\net9.0-windows\SvgToPng.dll'
if (-not (Test-Path $bin)) { throw "SvgToPng.dll not found at $bin" }

$svgFiles = Get-ChildItem $iconsDir -Filter '*.svg'
if ($svgFiles.Count -eq 0) { throw "no *.svg found in $iconsDir" }

foreach ($svg in $svgFiles) {
    $out = Join-Path $outDir ($svg.BaseName + '.png')
    & dotnet $bin $svg.FullName $out 48
    if ($LASTEXITCODE -ne 0) { throw "SvgToPng failed for $($svg.Name)" }
}

Write-Output ("Regenerated {0} icon(s) into {1}" -f $svgFiles.Count, $outDir)
