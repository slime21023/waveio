param(
    [int]$TargetJvmPid,
    [int]$DurationSeconds = 30
)

$ErrorActionPreference = 'Stop'
if ($TargetJvmPid -le 0) {
    throw 'TargetJvmPid must be a positive jcmd process id.'
}
if ($DurationSeconds -lt 1 -or $DurationSeconds -gt 600) {
    throw 'DurationSeconds must be between 1 and 600.'
}

$outputDirectory = Join-Path $PSScriptRoot 'target'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$outputPath = Join-Path $outputDirectory 'memory-observation.txt'
Set-Content -LiteralPath $outputPath -Value '' -Encoding UTF8
for ($elapsed = 0; $elapsed -lt $DurationSeconds; $elapsed += 5) {
    "`n--- sample elapsed=${elapsed}s utc=$([DateTime]::UtcNow.ToString('o')) ---" |
        Tee-Object -FilePath $outputPath -Append
    & jcmd $TargetJvmPid GC.heap_info | Tee-Object -FilePath $outputPath -Append
    if ($LASTEXITCODE -ne 0) {
        throw "jcmd GC.heap_info failed for PID $TargetJvmPid."
    }
    & jcmd $TargetJvmPid VM.native_memory summary | Tee-Object -FilePath $outputPath -Append
    if ($LASTEXITCODE -ne 0) {
        throw "jcmd VM.native_memory failed for PID $TargetJvmPid."
    }
    if ($elapsed + 5 -lt $DurationSeconds) {
        Start-Sleep -Seconds 5
    }
}
Write-Host "Memory samples written to $outputPath"
