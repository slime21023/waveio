param(
    [Parameter(Mandatory = $true)]
    [string]$Archive,
    [string]$Output
)

$ErrorActionPreference = 'Stop'
$archivePath = [IO.Path]::GetFullPath($Archive)
if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
    throw "Archive does not exist: $archivePath"
}

if ([string]::IsNullOrWhiteSpace($Output)) {
    $outputPath = "$archivePath.sha256"
} else {
    $outputPath = [IO.Path]::GetFullPath($Output)
}
if ([string]::Equals($archivePath, $outputPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'SHA-256 sidecar must not overwrite the archive.'
}

$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
$line = "$hash  $([IO.Path]::GetFileName($archivePath))$([Environment]::NewLine)"
[IO.File]::WriteAllText($outputPath, $line, [Text.UTF8Encoding]::new($false))
Write-Host "Wrote SHA-256 sidecar: $outputPath"
Write-Host "sha256=$hash"
