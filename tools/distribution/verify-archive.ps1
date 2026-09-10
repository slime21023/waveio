param(
    [string]$Archive = 'target/wave-0.9.0-rc-distribution.zip'
)

$ErrorActionPreference = 'Stop'
$archivePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $Archive))
if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
    throw "Distribution archive does not exist: $archivePath"
}

$entries = & jar tf $archivePath
if ($LASTEXITCODE -ne 0) {
    throw 'jar could not inspect the distribution archive.'
}
$required = @('lib/wave-0.1.0-SNAPSHOT.jar', 'docs/operations-guide.md', 'docs/migration-guide.md',
    'docs/security-dependency-report.md', 'spec/wave-roadmap.md', 'DISTRIBUTION.md')
foreach ($entry in $required) {
    if ($entries -notcontains $entry) {
        throw "Distribution is missing required entry: $entry"
    }
}
if ($entries | Where-Object { $_ -match '(^|/)(target|\.git)(/|$)|(^|/).*(\.key|\.p12|\.pem)$' }) {
    throw 'Distribution contains a forbidden build/private-key entry.'
}
Write-Host "Verified $archivePath ($($entries.Count) entries)."
