$ErrorActionPreference = 'Stop'
$baseDirectory = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$properties = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'maven-wrapper.properties')
$distributionUrl = ($properties | Where-Object { $_ -like 'distributionUrl=*' }).Substring('distributionUrl='.Length)
$expectedHash = ($properties | Where-Object { $_ -like 'distributionSha256Sum=*' }).Substring('distributionSha256Sum='.Length).ToUpperInvariant()
$archiveName = Split-Path -Leaf $distributionUrl
$distributionName = $archiveName -replace '-bin\.zip$', ''
$cacheDirectory = if ($env:MAVEN_WRAPPER_CACHE) { $env:MAVEN_WRAPPER_CACHE } else { Join-Path $baseDirectory '.mvn\wrapper\dists' }
$distributionHome = Join-Path $cacheDirectory $distributionName
$archive = Join-Path $cacheDirectory $archiveName

if (-not (Test-Path -LiteralPath (Join-Path $distributionHome 'bin\mvn.cmd'))) {
    New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri $distributionUrl -OutFile $archive
    }
    $stream = [System.IO.File]::OpenRead($archive)
    try {
        $actualHash = ([System.Security.Cryptography.SHA256]::Create().ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $stream.Dispose()
    }
    $actualHash = $actualHash.ToUpperInvariant()
    if ($actualHash -ne $expectedHash) {
        Remove-Item -LiteralPath $archive -Force
        throw 'Maven distribution SHA-256 mismatch.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $cacheDirectory -Force
}

& (Join-Path $distributionHome 'bin\mvn.cmd') @args
exit $LASTEXITCODE
