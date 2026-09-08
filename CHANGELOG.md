# Changelog

本專案遵循 0.x 規則：patch 不破壞 public API；minor 可以有 breaking change，但必須列出 migration impact。

## 0.1.0 (preview)

- 建立 Java 25、JPMS、Maven wrapper 與跨平台 CI 基線。
- 提供 typed registry、managed execution、lazy Task、bounded blocking 與 JDK Flow bridge。
- 提供 HTTP handler/routing、Netty HTTP/1.1/TLS transport、public `WaveServer` facade 與 testkit。
- 提供 distribution、consumer verification、release gates、reliability/benchmark runners。
- public facade 收斂為 `WaveApplication`、具名 `ServerProfile`／`ServerOptions`、`Routes`／`Endpoint`，
  並提供 `TaskHandle` 作為每次 Task 啟動的取消入口。

此為首個 preview，沒有已發布的 WaveIO API migration path。開發中曾使用的 `ServerSpec` 已移除；改以
`WaveApplication.builder().routes(...)` 建立 application，再使用 `WaveServer.start(port, application)` 或
含 `ServerOptions` 的 overload 啟動。
