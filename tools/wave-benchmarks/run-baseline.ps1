param(
    [int]$Warmups = 1,
    [int]$Iterations = 1,
    [int]$Forks = 1
)

$ErrorActionPreference = 'Stop'
foreach ($value in @($Warmups, $Iterations, $Forks)) {
    if ($value -lt 1 -or $value -gt 10) {
        throw 'Warmups, Iterations, and Forks must each be between 1 and 10.'
    }
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outputDirectory = Join-Path $PSScriptRoot 'target'
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$metadataPath = Join-Path $outputDirectory 'baseline-metadata.txt'
$resultPath = Join-Path $outputDirectory 'baseline-result.txt'
$javaVersion = (& cmd /c 'java -version 2>&1' | Select-Object -First 1)

@(
    "date_utc=$([DateTime]::UtcNow.ToString('o'))"
    "commit=$((& git -C $root rev-parse HEAD).Trim())"
    "worktree_dirty=$([bool]((& git -C $root status --porcelain)))"
    "jdk=$javaVersion"
    "os=$([Environment]::OSVersion.VersionString)"
    "cpu_count=$([Environment]::ProcessorCount)"
    "warmups=$Warmups"
    "iterations=$Iterations"
    "forks=$Forks"
    'jvm_options=-Dwave.reliability.tests=false'
) | Set-Content -LiteralPath $metadataPath -Encoding UTF8

Push-Location $root
try {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & .\mvnw.cmd -q -B -ntp -f tools\wave-benchmarks\pom.xml dependency:build-classpath `
        "-Dmdep.outputFile=target/dependency-classpath.txt" 2>&1 | Out-Null
    $classpathStatus = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($classpathStatus -ne 0) {
        throw "Could not build the JMH dependency classpath (exit code $classpathStatus)."
    }
    $dependencyClasspath = (Get-Content tools\wave-benchmarks\target\dependency-classpath.txt -Raw).Trim()
    $javaClasspath = "tools\wave-benchmarks\target\classes;$dependencyClasspath"
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $output = & java -cp $javaClasspath org.openjdk.jmh.Main `
        WaveRouteBenchmark -wi $Warmups -i $Iterations -f $Forks 2>&1
    $status = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    $output | Tee-Object -FilePath $resultPath
    if ($status -ne 0) {
        throw "JMH baseline failed with exit code $status."
    }
    $resultText = $output -join "`n"
    if ($resultText -notmatch 'Result "' -or $resultText -match '<forked VM failed|ClassNotFoundException') {
        throw 'JMH did not produce a successful benchmark result.'
    }
}
finally {
    Pop-Location
}
Write-Host "Metadata: $metadataPath"
Write-Host "Result: $resultPath"
