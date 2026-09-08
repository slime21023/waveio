# 0.1.0 API 相容性基線

本文件是 `0.1.0` 預覽版的公開 API inventory。只有以下 JPMS exports 的 public/protected 型別與成員
屬於相容性承諾；未 export package、package-private 型別及所有 `io.waveio.netty` 型別都不是一般
使用者 API。

| Module | Exported package | 對象 |
|---|---|---|
| `io.waveio.foundation` | `io.waveio.registry` | application registry |
| `io.waveio.execution` | `io.waveio.execution` | managed execution runtime |
| `io.waveio.task` | `io.waveio.task` | Task、blocking、Flow bridge |
| `io.waveio.http` | `io.waveio.http` | HTTP values、handler、routing、memory fixture |
| `io.waveio.server` | `io.waveio.server` | server facade、lifecycle、rendering、observation |
| `io.waveio.testkit` | `io.waveio.testkit` | embedded public-facade fixture |

`io.waveio.netty` 只 qualified-export 給 `io.waveio.server`；consumer 不得直接依賴 transport package。
這個 module/export inventory 是 M7 API compatibility check 的 baseline，新增或移除 exports 都必須更新
本文件並在 changelog 說明。

## 0.x 規則

- patch release 不移除、不改名、不改變既有 public API 的 binary/source contract。
- minor release 可以有 breaking change，但必須在 `CHANGELOG.md` 指出 migration impact。
- 尚未宣告穩定的 internal package 可在任何版本調整；應用不得依賴它們。

## Java 支援政策

WaveIO `0.1.0` 只支援並在 CI 驗證 Java 25。建置不用 preview API 或 preview flag；未經 CI 驗證的
未來 JDK 不構成支援承諾。
