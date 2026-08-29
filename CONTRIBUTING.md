# Contributing to WaveIO

Thank you for your interest in contributing to WaveIO!

---

## 🛠️ Prerequisites

- **Java**: JDK 21+ (OpenJDK or GraalVM)
- **Maven**: 3.9+

---

## 🏗️ Project Architecture

WaveIO is structured as a Maven multi-module project:

- **`waveio-parent`** (`pom.xml`): Root aggregator and parent POM managing dependency and plugin versions.
- **`waveio-core`** (`waveio-core/`): Core HTTP/1.1 runtime, routing engine, and streaming implementation.

---

## 🧪 Verification & Build Workflows

Before submitting a pull request, run the verification suite:

```bash
# 1. Fast unit tests
mvn test

# 2. Real TCP/TLS network integration tests
mvn verify

# 3. Full hardening verification with Netty Paranoid leak detection
mvn -Phardening clean verify

# 4. JMH benchmark smoke test
mvn -Pbenchmark clean verify -DskipTests
```

---

## 📜 Coding Conventions

- **AOT & Reflection**: Public APIs must never require reflection, classpath scanning, or annotation processors.
- **JPMS Encapsulation**: Keep internal implementation classes strictly inside `io.waveio.http.internal.*` packages without exporting them in `module-info.java`.
- **Zero-Allocation Hot Paths**: Optimize for minimal allocations in route matching, URI parsing, and header manipulation.
- **Concurrency Invariants**: Always verify that responses are queued in request arrival order and that event loops are never blocked.
