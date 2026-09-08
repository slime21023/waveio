# WaveIO 開發狀態

更新日期：2026-09-08。WaveIO 已收斂為單一 `io.waveio:waveio` artifact 與 `io.waveio` JPMS module。
舊的多 Maven module 開發過程、規則檔與里程碑證據已清理；目前架構見
[`docs/architecture/single-artifact.md`](docs/architecture/single-artifact.md)。

## 已完成的基線

- Java 25、UTF-8、JPMS、`-Xlint:all -Werror` 與 Maven 3.9.16 wrapper。
- 一個 artifact，公開 registry、execution、task、HTTP、server、testkit packages；Netty 為不 export 的 internal package。
- HTTP/1.1、TLS、bounded Flow body、managed execution、service lifecycle、embedded testkit。
- reproducible distribution、consumer verification、release API/JPMS gate、reliability suite、benchmark runner 與 release dry-run。

## Public API 強化（完成）

目標：以穩固的 managed core 支撐較少設定、可讀且可擴充的應用入口，而不洩漏 Netty 或放寬資源邊界。

- **KR1 — Task ownership：** `Task.start(ExecutionRuntime)` 回傳 `TaskHandle`，可觀察 completion 與取消
  該次 execution；Task timeout 由 execution runtime 的 scheduler 擁有並於 cleanup 取消。
- **KR2 — 高階 HTTP：** `Routes`、`Endpoint`、`EndpointContext`、`Middleware`、`Next` 與 `ErrorHandler`
  將 `Task<HttpResponse>` 的 endpoint 流程轉接至既有一次性 response transaction；低階 handler API 保留。
- **KR3 — 顯式但不繁瑣的啟動：** `WaveApplication` 組裝 routes、registry、services、observers 與 error
  policy；`WaveServer.start(port, application)` 使用 DEVELOPMENT profile，`ServerProfile` 與 `ServerOptions`
  提供可檢視、具上限的預設與覆寫。
- **KR4 — 受控擴充：** `WaveExtension` 只能透過 application builder 的 service、route、lifecycle、error
  handling 與 observation 入口擴充，依安裝順序套用；`ServerSpec` 已移除，不保留 preview 相容層。

證據：`./mvnw -B -ntp verify`、`git diff --check`、`./scripts/assemble-distribution.ps1`、
`./scripts/verify-consumer.ps1`、`./scripts/verify-release-gates.ps1` 與 `./scripts/release-dry-run.ps1`。

## 持續驗證

```powershell
.\mvnw.cmd -B -ntp verify
.\scripts\verify-release-gates.ps1
.\scripts\assemble-distribution.ps1
.\scripts\verify-consumer.ps1
.\scripts\run-reliability.ps1 -Iterations 3
.\scripts\release-dry-run.ps1
```

## 後續方向

新工作以單一 artifact 的 public API 為中心，任何新能力必須保持 `io.waveio.netty` 不 export，且不引入
無界容量或隱性 timeout。JSON、HTTP/2、WebSocket、DI 與語言 DSL 仍是獨立的後續評估項目，不屬於目前 core。
