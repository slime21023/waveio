param([int]$Iterations = 5)

$ErrorActionPreference = "Stop"
if ($Iterations -lt 1) { throw "Iterations must be positive" }
$root = Split-Path -Parent $PSScriptRoot
$result = Join-Path $root "target/benchmark-report.md"
$java = (& java -version 2>&1 | Select-Object -First 1)
$os = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$lines = @("# WaveIO benchmark report", "", "- JDK: $java", "- OS: $($os.Caption) $($os.Version)", "- CPU: $($cpu.Name)", "- Logical processors: $($cpu.NumberOfLogicalProcessors)", "- Iterations: $Iterations", "- Execution config: queue capacity 8, parallelism 1, deadline 1 second", "- HTTP config: 1024/4096/1024 limits; 1 second request and idle timeout", "", "| Workload | Total milliseconds | Average milliseconds |", "|---|---:|---:|")
Push-Location $root
try {
    $workloads = @(
        @{ Name = "execution-task micro workload"; Args = @("-pl", "waveio", "-Dtest=TaskTest,ExecutionRuntimeTest", "test") },
        @{ Name = "HTTP real-socket workload"; Args = @("-pl", "waveio", "-Dtest=PlaintextServerTest", "test") }
    )
    foreach ($workload in $workloads) {
        $elapsed = 0.0
        for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
            $measure = Measure-Command { & .\mvnw.cmd -B -ntp "-Dsurefire.failIfNoSpecifiedTests=false" @($workload.Args) }
            if ($LASTEXITCODE -ne 0) { throw "$($workload.Name) iteration $iteration failed" }
            $elapsed += $measure.TotalMilliseconds
        }
        $lines += "| $($workload.Name) | $([math]::Round($elapsed, 2)) | $([math]::Round($elapsed / $Iterations, 2)) |"
    }
} finally { Pop-Location }
$lines | Set-Content -LiteralPath $result -Encoding utf8NoBOM
Get-Content -LiteralPath $result
