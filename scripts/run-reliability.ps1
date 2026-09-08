param([int]$Iterations = 3)

$ErrorActionPreference = "Stop"
if ($Iterations -lt 1) { throw "Iterations must be positive" }
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
        & .\mvnw.cmd -B -ntp "-Dio.netty.leakDetection.level=paranoid" -pl waveio test
        if ($LASTEXITCODE -ne 0) { throw "transport reliability iteration $iteration failed" }
        & .\mvnw.cmd -B -ntp -pl waveio -Dtest=TaskTest,ExecutionRuntimeTest,ExecutionTerminationTest test
        if ($LASTEXITCODE -ne 0) { throw "runtime reliability iteration $iteration failed" }
    }
} finally {
    Pop-Location
}
