param(
    [string]$Version = "0.1.0-SNAPSHOT"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root "target/distribution/waveio-$Version"
if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Recurse -Force }
New-Item -ItemType Directory -Path $output, (Join-Path $output "artifacts"), (Join-Path $output "reports") | Out-Null
Copy-Item -LiteralPath (Join-Path $root "LICENSE"), (Join-Path $root "NOTICE") -Destination $output
$source = Join-Path $root "waveio/target"
Get-ChildItem -LiteralPath $source -Filter "waveio-$Version*.jar" | Copy-Item -Destination (Join-Path $output "artifacts")
Copy-Item -LiteralPath (Join-Path $source "dependency-report.txt") -Destination (Join-Path $output "reports/waveio-dependencies.txt")
Get-ChildItem -LiteralPath $output -Recurse -File | Where-Object { $_.Name -ne "SHA256SUMS" } |
    ForEach-Object { "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.FullName.Substring($output.Length + 1).Replace("\", "/") } |
    Sort-Object | Set-Content -LiteralPath (Join-Path $output "SHA256SUMS") -Encoding utf8NoBOM
