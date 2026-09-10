# Reproducible JMH baseline

Install the current published-style artifact and build the benchmark:

```text
./mvnw -B -ntp -pl wave -am install -DskipTests
./mvnw -B -ntp -f tools/wave-benchmarks/pom.xml clean package
./mvnw -B -ntp -f tools/wave-benchmarks/pom.xml exec:java \
  -Dexec.mainClass=org.openjdk.jmh.Main -Dexec.args='WaveRouteBenchmark -wi 1 -i 1 -f 0'
```

For a metadata-capturing run use `bash tools/wave-benchmarks/run-baseline.sh 1 1 1` (or
`powershell -File tools/wave-benchmarks/run-baseline.ps1 -Warmups 1 -Iterations 1 -Forks 1`).
The scripts write `target/baseline-metadata.txt` and `target/baseline-result.txt`, including JDK,
OS, CPU, commit, JVM options, warmups, iterations, and forks. To sample a running JVM's heap and
direct/native memory for a finite window, use `bash tools/wave-benchmarks/observe-memory.sh <pid> 30`
or `powershell -File tools/wave-benchmarks/observe-memory.ps1 -TargetJvmPid <pid> -DurationSeconds 30`.
Record the output with the RC evidence. The
benchmark deliberately measures only deterministic route lookup; socket, Flow, HTTP/2, SSE, and
WebSocket behavior belongs to the reliability/transport profiles.

The scheduled reliability workflow also runs a bounded `WaveServerReliabilityTarget` with Native
Memory Tracking for 120 seconds, samples it for 30 seconds, and uploads the baseline and memory
files as workflow artifacts. The target sends finite batches through a real Wave HTTP/1.1 server
and requires exact `{"ok":true}` success bodies plus application-failure responses before exiting.
The scheduled gate also requires all six five-second samples and both heap/NMT sections in the
observation file, plus a completed result line with positive request, success, and application-
failure counts, before accepting the workload result.

To reproduce the server workload locally after packaging the benchmark module:

```text
./mvnw -B -ntp -f tools/wave-benchmarks/pom.xml clean package
./mvnw -B -ntp -f tools/wave-benchmarks/pom.xml dependency:build-classpath \
  -Dmdep.outputFile=target/dependency-classpath.txt
java -XX:NativeMemoryTracking=summary \
  -Dwave.benchmark.durationSeconds=120 -Dwave.benchmark.concurrency=32 \
  -cp "tools/wave-benchmarks/target/classes:<dependency-classpath>" \
  io.wavejava.benchmarks.WaveServerReliabilityTarget
```

Attach `observe-memory.sh` or `observe-memory.ps1` to the running JVM for the finite sampling
window. The target rejects duration values outside `1..600` seconds and concurrency outside
`1..128`.
