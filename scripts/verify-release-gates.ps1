$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$expected = @{ "waveio/src/main/java/module-info.java" = @("exports io.waveio.registry;", "exports io.waveio.execution;", "exports io.waveio.task;", "exports io.waveio.http;", "exports io.waveio.server;", "exports io.waveio.testkit;") }
foreach ($entry in $expected.GetEnumerator()) {
    $actual = Get-Content -LiteralPath (Join-Path $root $entry.Key) | Where-Object { $_.TrimStart().StartsWith("exports ") } | ForEach-Object { $_.Trim() }
    if (@(Compare-Object $entry.Value $actual).Count -ne 0) { throw "JPMS export baseline mismatch: $($entry.Key)" }
}
$preview = Get-ChildItem -LiteralPath $root -Recurse -File -Include "*.java", "*.xml", "*.yml", "*.yaml", "*.ps1", "*.sh" |
    Where-Object { $_.FullName -notmatch "[\\/]target[\\/]" -and $_.Name -ne "verify-release-gates.ps1" } |
    Select-String -Pattern "--enable-preview|enable-preview"
if ($preview) { throw "preview API or compiler flag is prohibited: $($preview[0].Path)" }
$javaVersion = & java -version 2>&1 | Select-Object -First 1
if ($javaVersion -notmatch '"25(?:\.|\+)') { throw "Java 25 is required, found: $javaVersion" }
