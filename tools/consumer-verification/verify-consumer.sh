#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="${WAVE_CONSUMER_VERSION:-0.9.0-rc.1}"
case "$version" in
  ''|*SNAPSHOT*|LATEST|RELEASE)
    echo 'WAVE_CONSUMER_VERSION must be a non-SNAPSHOT RC/release version.' >&2
    exit 2
    ;;
esac

cd "$root"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) maven=(./mvnw.cmd) ;;
  *) maven=(bash ./mvnw) ;;
esac
run_maven() {
  "${maven[@]}" "$@"
}

run_maven -B -ntp -pl wave -am install -DskipTests
run_maven -B -ntp org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  "-Dfile=wave/target/wave-0.1.0-SNAPSHOT.jar" \
  "-DpomFile=wave/pom.xml" \
  "-DgroupId=io.wavejava" \
  "-DartifactId=wave" \
  "-Dversion=$version" \
  "-Dpackaging=jar"
run_maven -B -ntp -f examples/pom.xml clean package "-Dwave.version=$version"

run_example() {
  local module="$1"
  local main_class="$2"
  run_maven -B -ntp -f examples/pom.xml -pl "$module" exec:java \
    "-Dwave.version=$version" "-Dexec.mainClass=$main_class"
}
run_example hello-api io.wavejava.examples.hello.HelloApi
run_example forms-and-files io.wavejava.examples.forms.FormsAndFiles
run_example streaming-api io.wavejava.examples.streaming.StreamingApi
run_example websocket-chat io.wavejava.examples.websocket.WebSocketChat
run_example gateway-api io.wavejava.examples.gateway.GatewayApi
