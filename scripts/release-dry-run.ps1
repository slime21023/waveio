param([string]$Version = "0.1.0-SNAPSHOT")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\scripts\assemble-distribution.ps1 -Version $Version
    $distribution = Join-Path $root "target/distribution/waveio-$Version"
    $manifest = Join-Path $distribution "SHA256SUMS"
    if (-not (Test-Path -LiteralPath $manifest)) { throw "checksum manifest is missing" }
    $files = Get-ChildItem -LiteralPath (Join-Path $distribution "artifacts") -File
    if ($files.Count -ne 3) { throw "expected 3 binary/sources/Javadoc JAR attachments, found $($files.Count)" }
    $attachments = Join-Path $root "target/release-dry-run/ATTACHMENTS.md"
    New-Item -ItemType Directory -Path (Split-Path -Parent $attachments) -Force | Out-Null
    @("# Release dry-run attachments", "", "- Version: $Version", "- JAR attachments: $($files.Count)", "- External publication: not performed", "", "## Files") +
        ($files.Name | Sort-Object | ForEach-Object { "- $_" }) + @("- LICENSE", "- NOTICE", "- SHA256SUMS", "- reports/*.txt") |
        Set-Content -LiteralPath $attachments -Encoding utf8NoBOM
    Get-Content -LiteralPath $attachments
} finally { Pop-Location }
