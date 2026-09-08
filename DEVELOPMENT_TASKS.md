# WaveIO 開發狀態

更新日期：2026-09-08。WaveIO 已收斂為單一 `io.waveio:waveio` artifact 與 `io.waveio` JPMS module。
舊的多 Maven module 開發過程、規則檔與里程碑證據已清理；目前架構見
[`docs/architecture/single-artifact.md`](docs/architecture/single-artifact.md)。

## 已完成的基線

- Java 25、UTF-8、JPMS、`-Xlint:all -Werror` 與 Maven 3.9.16 wrapper。
- 一個 artifact，公開 registry、execution、task、HTTP、server、testkit packages；Netty 為不 export 的 internal package。
- HTTP/1.1、TLS、bounded Flow body、managed execution、service lifecycle、embedded testkit。
- reproducible distribution、consumer verification、release API/JPMS gate、reliability suite、benchmark runner 與 release dry-run。

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
