# Changelog

本專案遵循 0.x 規則：patch 不破壞 public API；minor 可以有 breaking change，但必須列出 migration impact。

## 0.1.0 (preview)

- 建立 Java 25、JPMS、Maven wrapper 與跨平台 CI 基線。
- 提供 typed registry、managed execution、lazy Task、bounded blocking 與 JDK Flow bridge。
- 提供 HTTP handler/routing、Netty HTTP/1.1/TLS transport、public `WaveServer` facade 與 testkit。
- 提供 distribution、consumer verification、release gates、reliability/benchmark runners。

此為首個 preview，沒有先前 WaveIO API migration path。
