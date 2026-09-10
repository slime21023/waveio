param(
    [string]$Version = '0.9.0-rc.1'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Version) -or $Version -match 'SNAPSHOT|^(LATEST|RELEASE)$') {
    throw 'Version must be a non-SNAPSHOT RC/release version.'
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Push-Location $root
try {

    function Invoke-Maven([string[]]$Arguments) {
        & .\mvnw.cmd @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Maven command failed with exit code $LASTEXITCODE"
        }
    }

    Invoke-Maven @('-B', '-ntp', '-pl', 'wave', '-am', 'install', '-DskipTests')
    Invoke-Maven @(
        '-B', '-ntp', 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file',
        '-Dfile=wave/target/wave-0.1.0-SNAPSHOT.jar', '-DpomFile=wave/pom.xml',
        '-DgroupId=io.wavejava', '-DartifactId=wave', "-Dversion=$Version", '-Dpackaging=jar'
    )
    Invoke-Maven @('-B', '-ntp', '-f', 'examples/pom.xml', 'clean', 'package', "-Dwave.version=$Version")

    $examples = @(
        @('hello-api', 'io.wavejava.examples.hello.HelloApi'),
        @('forms-and-files', 'io.wavejava.examples.forms.FormsAndFiles'),
        @('streaming-api', 'io.wavejava.examples.streaming.StreamingApi'),
        @('websocket-chat', 'io.wavejava.examples.websocket.WebSocketChat'),
        @('gateway-api', 'io.wavejava.examples.gateway.GatewayApi')
    )
    foreach ($example in $examples) {
        Invoke-Maven @(
            '-B', '-ntp', '-f', 'examples/pom.xml', '-pl', $example[0], 'exec:java',
            "-Dwave.version=$Version", "-Dexec.mainClass=$($example[1])"
        )
    }
} finally {
    Pop-Location
}
