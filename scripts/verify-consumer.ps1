param([string]$Version = "0.1.0-SNAPSHOT")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$distribution = Join-Path $root "target/distribution/waveio-$Version/artifacts"
if (-not (Test-Path -LiteralPath $distribution)) { throw "distribution artifacts are missing; run assemble-distribution.ps1 first" }
$output = Join-Path $root "target/consumer-verification"
if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Recurse -Force }
New-Item -ItemType Directory -Path $output | Out-Null
$netty = Get-ChildItem -Path (Join-Path $env:USERPROFILE ".m2/repository/io/netty") -Recurse -Filter "*.jar" |
    Where-Object { $_.FullName -match "4\.2\.17\.Final" } | Select-Object -ExpandProperty FullName
if ($netty.Count -lt 1) { throw "Netty runtime dependencies are unavailable in the local Maven repository" }
$artifacts = Get-ChildItem -LiteralPath $distribution -Filter "*.jar" | Select-Object -ExpandProperty FullName
$modulePath = ($artifacts + $netty) -join [IO.Path]::PathSeparator
$source = Join-Path $root "consumer-verification/src"
& javac --release 25 --module-path $modulePath -d $output (Get-ChildItem -LiteralPath $source -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
if ($LASTEXITCODE -ne 0) { throw "consumer compilation failed" }
& java --module-path "$output$([IO.Path]::PathSeparator)$modulePath" -m io.waveio.consumer.verification/io.waveio.consumer.verification.Main
if ($LASTEXITCODE -ne 0) { throw "consumer execution failed" }
