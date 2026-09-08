$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$expected = @{
    "waveio-foundation/src/main/java/module-info.java" = @("exports io.waveio.registry;")
    "waveio-execution/src/main/java/module-info.java" = @("exports io.waveio.execution;")
    "waveio-task/src/main/java/module-info.java" = @("exports io.waveio.task;")
    "waveio-http/src/main/java/module-info.java" = @("exports io.waveio.http;")
    "waveio-server/src/main/java/module-info.java" = @("exports io.waveio.server;")
    "waveio-testkit/src/main/java/module-info.java" = @("exports io.waveio.testkit;")
    "waveio-netty/src/main/java/module-info.java" = @("exports io.waveio.netty to io.waveio.server;")
}
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
