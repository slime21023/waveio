# Wave 公開 API 相容性政策與預發布邊界快照

**狀態：** pre-release boundary snapshot；不是 released binary compatibility baseline  
**更新日期：** 2026-09-09  
**適用座標：** `io.wavejava:wave`  
**JPMS module：** `io.wavejava.wave`

本文件固定目前 source tree 的公開邊界，讓 0.8 起的變更可以被審查與測試。它不能取代不可變
Maven release artifact 的二進位相容性比較；`0.1.0-SNAPSHOT`、本機 `.m2` 產物、`target/` JAR，
以及舊 `io.waveio` release 都不是 baseline。

## 公開 compatibility surface

Revapi 只分析下列 surface：

- 根入口型別：`Wave`；`WaveApp`、`WaveServer`、`RunningServer` 位於各自的 `api.*` package。
- 所有 `io.wavejava.wave.api.*` 與 `io.wavejava.wave.spi.*` 型別。

`runtime.*`、`netty.*`、`internal.*`、`codec.*` 與非 API 的 `observability.*` 永遠不屬於
公開 binary compatibility surface。公開型別不得暴露 Netty 型別。

## 0.9 命名與邊界對照

| 舊名稱／位置 | 定案名稱／位置 |
|---|---|
| `ClientPool` | `ClientRequestPool`（限制同時 request，不是 connection pool） |
| `ClientPoolRejectedException` | `ClientRequestPoolRejectedException` |
| `maximumConnections`／`leasedConnections` | `maximumConcurrentRequests`／`leasedRequests`（只限 client request pool） |
| `WaveClient.pool(...)` | `WaveClient.requestPool(...)` |
| `SseResponse` | `SseResponseInfo` |
| `ApplicationDispatch`／`ApplicationDispatcher` | `ApplicationResult`／`RequestDispatcher` |
| `CompletionStageAwaiter` | 未 export 的 `runtime.client.BlockingResultWaiter` |
| public `ResponseBody`、`Response.body()` | 未 export 的 `internal.http.ResponseData`；transport 只經 `ResponseDataReader` |
| `api.testing` production package | test source `io.wavejava.wave.testing`；不 export |

這份對照與 `ModuleDescriptorTest`、`validate-naming.ps1` 一起固定 0.9 RC 的最後 public
surface；不建立 alias、facade 或正式 binary baseline。

## JPMS boundary

`ModuleDescriptorTest` 必須與下列清單完全相等，而不是只驗證 package prefix。每一個 export
必須是無 targets 的 unqualified export；module 不得是 open module，且 `opens`、`provides` 必須為空。

### Exports

```text
io.wavejava.wave
io.wavejava.wave.api.application
io.wavejava.wave.api.client
io.wavejava.wave.api.config
io.wavejava.wave.api.file
io.wavejava.wave.api.form
io.wavejava.wave.api.health
io.wavejava.wave.api.http
io.wavejava.wave.api.lifecycle
io.wavejava.wave.api.middleware
io.wavejava.wave.api.multipart
io.wavejava.wave.api.observability
io.wavejava.wave.api.registry
io.wavejava.wave.api.render
io.wavejava.wave.api.resilience
io.wavejava.wave.api.routing
io.wavejava.wave.api.server
io.wavejava.wave.api.session
io.wavejava.wave.api.sse
io.wavejava.wave.api.websocket
io.wavejava.wave.spi
io.wavejava.wave.spi.lifecycle
io.wavejava.wave.spi.render
io.wavejava.wave.spi.session
io.wavejava.wave.spi.testing
```

### `uses`

```text
io.wavejava.wave.spi.lifecycle.ServiceProvider
io.wavejava.wave.spi.render.ParserProvider
io.wavejava.wave.spi.render.RendererProvider
io.wavejava.wave.spi.session.SessionStoreProvider
```

`java.net.http` 僅是 module 的非 transitive implementation/test requirement；測試工具位於
`wave/src/test/java/io/wavejava/wave/testing`，不屬於 production API，也不會被 export。其餘
implementation dependencies 不會被 re-export。`Response` 是 abstract public control surface；
response payload representation 位於未 export 的 `internal.http.ResponseData`。

## 現在可執行的安全檢查

`compatibility` Maven profile 僅在明確使用時執行，且 baseline 必須符合：

```text
io.wavejava:wave:<immutable SemVer version>
```

它會在 `validate` 拒絕缺少 property、非 Wave 座標、包含 `SNAPSHOT`、`LATEST`、`RELEASE` 的
version 與非 SemVer version；看似合法但無法解析的 artifact 會在 Revapi 失敗。GitHub Actions 的
`compatibility-profile-safety` job 每次驗證這些 fail-closed 路徑。這個 job 不是 binary
compatibility gate，因為尚無可比較的 release artifact。

正式 artifact 出現後，使用：

```text
./mvnw -B -ntp -pl wave -am verify -Pcompatibility \
  "-Dwave.api.baseline=io.wavejava:wave:<immutable-version>"
```

## 建立真正 baseline 的必要條件

專案所有者必須先授權並完成以下不可由本機 source tree 代替的動作：

1. 決定正式 SemVer baseline 版本與不允許覆寫的 Maven release repository。
2. 發布 `io.wavejava:wave:<version>` 的 binary、sources 與 Javadoc artifacts。
3. 建立受保護的 annotated/signed Git tag，並記錄 tag、commit、repository URL 與各 artifact SHA-256。
4. 將上述 immutable coordinate 固定在版本控制中的 CI；該 CI 必須無條件執行 Revapi。
5. 對刻意移除已發布 public member 的 candidate 執行 expected-failure proof，確認 binary break 會阻擋 CI。

在正式 artifact 尚未發布前，repository-local proof 可用 `tools/revapi-break-proof` 重現：它比較
獨立的 old/new fixture，預期以 `java.method.removed` 非零失敗；這只驗證 gate 行為，不提供 Wave
baseline 證據。

完成後才可以將本文件的狀態改為 released baseline，並把 0.8-T3 的真正 compatibility gate 標示為完成。

## 版本政策

在 1.0 前，任何 public-surface 變更都必須同步更新架構決策、此 manifest、契約測試與 roadmap，
但不得宣稱已具 binary compatibility。1.0 後遵守 SemVer：移除或不相容改變公開 API/SPI 需要
major version；相容新增為 minor；相容修正為 patch。每一版都以最近的 immutable release artifact
作為 Revapi 比較基準。
