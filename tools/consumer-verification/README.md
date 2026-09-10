# Consumer verification

From the repository root, install the current Wave build under a local, non-SNAPSHOT RC
coordinate, compile all five named-module examples, and run bounded smoke mains. The local
coordinate is consumer evidence only; it is not an immutable compatibility baseline.

The repeatable helpers are:

```text
bash tools/consumer-verification/verify-consumer.sh
powershell -File tools/consumer-verification/verify-consumer.ps1
```

The Bash helper is intended for Unix-like CI runners and also detects Git Bash on Windows (using
`mvnw.cmd` there); PowerShell remains the native Windows alternative. Both default to
`0.9.0-rc.1`; set `WAVE_CONSUMER_VERSION` for Bash or pass `-Version` for PowerShell. They reject
SNAPSHOT/LATEST/RELEASE selectors before touching Maven.

```text
./mvnw -B -ntp -pl wave -am install -DskipTests
./mvnw -B -ntp org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile=wave/target/wave-0.1.0-SNAPSHOT.jar \
  -DpomFile=wave/pom.xml -DgroupId=io.wavejava -DartifactId=wave \
  -Dversion=0.9.0-rc.1 -Dpackaging=jar
./mvnw -B -ntp -f examples/pom.xml package -Dwave.version=0.9.0-rc.1
./mvnw -B -ntp -f examples/pom.xml -pl hello-api exec:java \
  -Dwave.version=0.9.0-rc.1 -Dexec.mainClass=io.wavejava.examples.hello.HelloApi
./mvnw -B -ntp -f examples/pom.xml -pl forms-and-files exec:java \
  -Dwave.version=0.9.0-rc.1 -Dexec.mainClass=io.wavejava.examples.forms.FormsAndFiles
./mvnw -B -ntp -f examples/pom.xml -pl streaming-api exec:java \
  -Dwave.version=0.9.0-rc.1 -Dexec.mainClass=io.wavejava.examples.streaming.StreamingApi
./mvnw -B -ntp -f examples/pom.xml -pl websocket-chat exec:java \
  -Dwave.version=0.9.0-rc.1 -Dexec.mainClass=io.wavejava.examples.websocket.WebSocketChat
./mvnw -B -ntp -f examples/pom.xml -pl gateway-api exec:java \
  -Dwave.version=0.9.0-rc.1 -Dexec.mainClass=io.wavejava.examples.gateway.GatewayApi
```

The gateway main only binds and closes its app; it does not make a network call to the intentionally
unavailable demo upstream.
