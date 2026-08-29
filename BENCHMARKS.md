# WaveIO Benchmark Baselines

This document records the repeatable JMH (Java Microbenchmark Harness) performance baselines for core routing and parsing components in WaveIO.

---

## 🚀 Running the Benchmarks

To compile with annotation processing and execute the JMH benchmark scenarios:

```bash
mvn -Pbenchmark clean verify -DskipTests
```

> [!NOTE]
> The default benchmark profile runs 1 fork, 2 warmup iterations (200 ms each), and 3 measurement iterations (200 ms each). It is designed as a **fast regression smoke test** to detect order-of-magnitude regressions during development, rather than a final release-grade hardware saturation test.

---

## 🔬 Benchmark Scenarios

| Scenario | Class & Method | Description |
| :--- | :--- | :--- |
| **Route Matching** | [`CoreBenchmark.routeMatch`](waveio-core/src/benchmark/java/io/waveio/http/benchmark/CoreBenchmark.java) | Evaluates parameterized route resolution (`/users/:id`), dynamic path parameter map creation, and metadata lookup against a multi-route table. |
| **Strict URI Parsing** | [`CoreBenchmark.strictRequestTargetParse`](waveio-core/src/benchmark/java/io/waveio/http/benchmark/CoreBenchmark.java) | Evaluates strict UTF-8 percent-decoding, query string decoding (`?tag=a+b&tag=c%2Bd`), path segment normalization, and anti-traversal validation. |

---

## 📈 Reference Results

### 2026-08-29 Reference Baseline

- **JVM**: OpenJDK 64-Bit Server VM (25.0.3+9-LTS / Java 21 runtime), Windows
- **Settings**: 1 Fork, 2 Warmup (200ms), 3 Measurement (200ms), Blackhole compiler mode

| Benchmark Scenario | Mode | Score (Average Throughput) | Error (99.9% CI) | Units |
| :--- | :---: | :---: | :---: | :---: |
| `CoreBenchmark.routeMatch` | `thrpt` | **6,659,999.52** | ± 24,723,472.73 | `ops/s` |
| `CoreBenchmark.strictRequestTargetParse` | `thrpt` | **1,613,395.09** | ± 297,132.21 | `ops/s` |

*Observed iteration ranges: `routeMatch` (5.10M – 7.45M ops/s), `strictRequestTargetParse` (1.60M – 1.63M ops/s).*

---


## ⚠️ Interpretation Guidelines

- **Variance in Short Iterations**: Short 200 ms measurement windows exhibit higher variance due to JIT C2 tiering and Windows scheduling.
- **Cross-Framework Claims**: These microbenchmarks isolate hot-path algorithms in memory without socket I/O. They prove internal algorithm efficiency and detect regressions, but should not be used in isolation to claim cross-framework throughput superiority.

