param(
    [Parameter(Mandatory = $true)]
    [string]$Archive,
    [Parameter(Mandatory = $true)]
    [string]$Sha256File,
    [Parameter(Mandatory = $true)]
    [string]$Signature,
    [Parameter(Mandatory = $true)]
    [string]$PublicKey
)

$ErrorActionPreference = 'Stop'

function Resolve-RequiredFile([string]$Path, [string]$Name) {
    $resolved = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $Path))
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "$Name does not exist: $resolved"
    }
    return $resolved
}

$archivePath = Resolve-RequiredFile $Archive 'Archive'
$sha256Path = Resolve-RequiredFile $Sha256File 'SHA-256 sidecar'
$signaturePath = Resolve-RequiredFile $Signature 'Detached signature'
$publicKeyPath = Resolve-RequiredFile $PublicKey 'Public key'

$expected = ((Get-Content -LiteralPath $sha256Path -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
if ($expected -notmatch '^[0-9a-f]{64}$') {
    throw "SHA-256 sidecar must start with exactly 64 hexadecimal characters: $sha256Path"
}
$actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) {
    throw "Archive SHA-256 mismatch: expected $expected, actual $actual"
}

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if ($null -eq $openssl) {
    throw 'openssl is required to verify the detached release signature.'
}
$previousErrorAction = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $verification = & $openssl.Source dgst -sha256 -verify $publicKeyPath -signature $signaturePath $archivePath 2>&1
    $verificationExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorAction
}
$verificationText = $verification -join "`n"
if ($verificationExitCode -ne 0 -or $verificationText -notmatch 'Verified OK') {
    throw "Detached release signature verification failed: $verificationText"
}

Write-Host "Verified immutable release inputs: $archivePath"
Write-Host "sha256=$actual"
