# Wave consumer examples

These five small applications are the 0.9 release-candidate consumer contract:

* `hello-api` — root entry point, routing, query values, and bounded shutdown.
* `forms-and-files` — URL-encoded form parsing and traversal-safe static files.
* `streaming-api` — one-item `Flow.Publisher<ByteBuffer>` response with explicit demand.
* `websocket-chat` — bounded text/binary echo and close handling.
* `gateway-api` — a finite-timeout `WaveClient` call from an application handler.

Failure/limit paths are intentionally visible: `hello-api` exposes `/failure` (500),
`forms-and-files` returns 404/413 for missing/oversized files, `streaming-api` uses an explicit
64 KiB stream budget, `websocket-chat` closes protocol errors, and `gateway-api` maps an unavailable
upstream to an application failure. All examples use finite server/client timeouts and close their
resources after the smoke scenario.

They are ordinary named-module consumers and do not import `runtime.*`, `netty.*`, or
`internal.*`. For consumer verification, install the current build under the local non-SNAPSHOT
RC coordinate `0.9.0-rc.1`, then compile against that coordinate. This is published-style
consumer evidence, not an immutable compatibility baseline:

```text
./mvnw -B -ntp -pl wave -am install -DskipTests
./mvnw -B -ntp org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile=wave/target/wave-0.1.0-SNAPSHOT.jar -DpomFile=wave/pom.xml \
  -DgroupId=io.wavejava -DartifactId=wave -Dversion=0.9.0-rc.1 -Dpackaging=jar
./mvnw -B -ntp -f examples/pom.xml package -Dwave.version=0.9.0-rc.1
```

Run a bounded smoke main with `exec:java`, for example:

```text
./mvnw -B -ntp -f examples/pom.xml -pl hello-api exec:java \
  -Dexec.mainClass=io.wavejava.examples.hello.HelloApi
```

The examples intentionally use only APIs already implemented in 0.1–0.7; multipart upload,
provider discovery, and HTTP/2 client policy remain exercised by the framework contract suites.
