# Wave operations guide (0.9 RC)

## Start and stop

Build the framework with Java 25, construct a `WaveApp`, then bind a finite port with
`Wave.server(app).listen(port).start()`. Keep the returned `RunningServer` in try-with-resources so
graceful shutdown runs even when the application fails. Port `0` is suitable for local smoke tests;
production deployments should choose a service-managed port.

## Required limits

Set `ServerLimits` and `ServerTimeouts` explicitly for production traffic. At minimum review
connection count, in-flight requests, request/header/body bytes, pending response bytes, streaming
outbound bytes, request/read/write/idle timeouts, and shutdown timeout. Do not replace a finite
budget with a queue or timeout disabled by convention.

## TLS and public address

Use `TlsConfig` for TLS and `Http2Config` only with TLS/ALPN `h2`. Configure
`ForwardedHeaderPolicy` with a trusted proxy CIDR list; untrusted forwarded headers are ignored and
the wire authority/scheme are never rewritten.

## Health and observability

Register liveness and readiness handlers from `HealthEndpoints`. Readiness follows service
lifecycle, so load balancers must stop routing before a drain completes. Enable `Observability`
only with finite queue/callback limits and treat dropped slow-sink events as an operational signal.

## Drain procedure

Stop accepting new traffic, wait at most `shutdownTimeout`, and then close the server. HTTP/2 sends
GOAWAY before cancelling accepted streams. A forced process stop is the final fallback; investigate
timeouts and direct-memory/leak reports before increasing budgets.

## Verification commands

```text
./mvnw -B -ntp verify
./mvnw -B -ntp -pl wave -am verify -Ptransport
./mvnw -B -ntp -pl wave -am verify "-Ptransport,reliability" "-Dwave.reliability.waves=600"
```

The transport profile enables Netty `PARANOID` leak detection. Reliability runs are finite,
bounded to 600 waves of 128 requests in the scheduled gate, and record the JDK, OS, CPU, commit,
concurrency, and duration in their output. The scheduled server workload separately runs for 120
seconds with a 30-second native-memory sample window.
