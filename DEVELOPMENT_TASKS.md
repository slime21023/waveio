# WaveIO 開發任務

更新日期：2026-09-07。這是 WaveIO 唯一的 in-repo 任務追蹤文件；進度摘要位於 [ROADMAP.md](ROADMAP.md)，設計契約位於 [ARCHITECTURE.md](ARCHITECTURE.md)。不使用 GitHub Issues 或 Project 作為此 backlog 的替代來源。

## 共同基線

- Maven 座標為 `io.waveio`，初始版本為 `0.1.0-SNAPSHOT`。
- 使用 Java 25、JPMS、UTF-8、`-Xlint:all -Werror`，不使用 preview。
- Maven 3.9.16 only-script Wrapper 必須固定 distribution SHA-256；Linux 與 Windows 均不得依賴全域 Maven。
- CI 在 Ubuntu 與 Windows 使用 Microsoft JDK 25 與 Maven cache 執行 `verify`，最小權限為 `contents: read`，不使用 `pull_request_target`。
- 公開文件使用繁體中文；Java public API、package 與 Javadoc 使用英文。
- 0.x patch 不破壞 API；minor 可以有 breaking change，且必須寫入 changelog。M7 僅產出 GitHub Release 附件，不發佈 Maven Central。

## 任務狀態

狀態只能是「未開始」、「進行中」或「完成」。每個任務是可獨立審查的 PR，開始時必須填入依賴證據；完成時必須補上實際驗證命令與結果。每個任務都要有 2–4 個 key results、`docs/development/rules/<ID>.rule.yml` 與驗證證據。

| ID | 依賴 | 狀態 | Goal 與 key results | 驗證證據 |
|---|---|---|---|---|
| P0-01 | 無 | 完成 | 建立本文件（唯一追蹤來源）；列出 M1–M7 的工作、依賴與 gate；README 與 ROADMAP 都連到本文件。 | `git diff --check`；README／ROADMAP 的相對連結。 |
| P0-02 | P0-01 | 完成 | 將 M0 重置、三份定位文件、本文件與 `.gitignore` 以單一 commit 固定；確認工作樹乾淨；確認無舊模組或建置設定。 | Baseline commit；`git status --short`；`rg --files`。 |
| M1-01 | P0 | 完成 | 建立根 Maven reactor；建立 `waveio-foundation` artifact；建立僅 exports `io.waveio.registry` 的 `io.waveio.foundation` module。 | `./mvnw -B -ntp verify`、`.\mvnw.cmd -B -ntp verify`；`jar --describe-module`。 |
| M1-02 | M1-01 | 完成 | 加入 Wrapper、版本鎖定與 Enforcer；加入 JUnit Jupiter、Surefire、`.gitattributes`；固定 Maven 3.9.16 SHA-256。 | `./mvnw -B -ntp verify` 與 `.\mvnw.cmd -B -ntp verify` 通過。 |
| M1-03 | M1-02 | 完成 | 建立 push、pull request、手動觸發 CI；Ubuntu／Windows 都執行 wrapper `verify`；僅授予 `contents: read`。 | GitHub Actions run `34100452868`：Ubuntu 與 Windows `verify` 成功。 |
| M1-04 | M1-01 | 完成 | 實作 `Key<T>`、`Registry`、`Registry.Builder`、`MissingRegistryEntryException`；固定 `(type,name)` equality 與 eager non-null binding。 | `RegistryTest` 覆蓋 lookup、qualifier、缺值與非法 binding。 |
| M1-05 | M1-04 | 完成 | 完成 immutable snapshot 與 overlay；覆蓋非法輸入及 request scope 隔離；補 M1 文件證據。 | 本機 wrapper `verify`、`jar --describe-module` 通過；CI run 待推送後補入。 |
| M2-01 | M1 | 完成 | 建立 `waveio-execution`／`io.waveio.execution`；固定 execution 狀態、拒絕、deadline、取消與 cleanup 契約。 | wrapper `verify` 通過；`ExecutionStateTest` 與 module inspection 通過。 |
| M2-02 | M2-01 | 完成 | 建立可手動推進的 clock、scheduler、callback deterministic harness。 | `DeterministicSchedulerTest` 通過；不使用 sleep。 |
| M2-03 | M2-02 | 完成 | 實作有界 serial segment dispatcher；同 execution 不重疊、不同 execution 可並行、飽和明確拒絕。 | `SerialSegmentDispatcherTest` 覆蓋 serial、parallel scheduling 與 rejection。 |
| M2-04 | M2-03 | 完成 | 實作 `ExecutionRuntime`、`Execution`、`ExecutionRef`、`ExecutionHandle`、`ExecutionConfig` 與 `ScopedValue` binding。 | `ExecutionRuntimeTest` 覆蓋 managed context 與 mandatory config。 |
| M2-05 | M2-04 | 完成 | 實作 first-terminal-wins、deadline、外部取消、LIFO cleanup、cleanup failure observation、晚 callback 丟棄。 | `ExecutionTerminationTest` 覆蓋 terminal arbitration、deadline、cleanup；未使用 sleep。 |
| M2-06 | M2-05 | 完成 | 完成競態、容量、context isolation 與 leak-free gate；低層 config 顯式提供 capacity、parallelism、deadline。 | wrapper `verify` 14 tests 通過；latch-based context isolation 與 cleanup gate。 |
| M3-01 | M2 | 完成 | 建立 `waveio-task`／`io.waveio.task` 與延遲 `Task<T>` graph。 | `TaskTest` 驗證 defer laziness 與 independent start。 |
| M3-02 | M3-01 | 完成 | 實作 `defer`、成功／失敗、`map`、`flatMap`、`recover`、`timeout`、finalizer、terminal runner。 | `TaskTest` 覆蓋 lazy、composition、timeout 與 finalizer failure contract。 |
| M3-03 | M3-02 | 完成 | 實作 `CompletionStage` import/export；外部 completion 回到 execution。 | `TaskTest` 驗證 imported stage completion re-enters execution。 |
| M3-04 | M3-02 | 未開始 | 實作 virtual-thread blocking bridge，具 explicit concurrent slots 與 waiting queue。 | rejection/cancellation tests。 |
| M3-05 | M3-02 | 未開始 | 實作最小 JDK Flow bridge、demand/cancellation probes、bounded byte-buffer collector。 | backpressure tests。 |
| M3-06 | M3-03, M3-04, M3-05 | 未開始 | 完成 cancellation、timeout、context、backpressure gate；不建 reactive operator framework。 | wrapper `verify`。 |
| M4-01 | M3 | 未開始 | 建立 `waveio-http`／`io.waveio.http`；實作 immutable HTTP value types。 | value-type tests。 |
| M4-02 | M4-01 | 未開始 | 實作 Flow-based single-consumption `Body` 與 bounded collector。 | consumption/limit tests。 |
| M4-03 | M4-02 | 未開始 | 實作 `Handler`、`Context`、`Chain`、local registry overlay、response transaction。 | handler-flow tests。 |
| M4-04 | M4-03 | 未開始 | 實作 method/path router、parameters、404、405、`Allow`、HEAD 與 error flow；精確 segment path 規則。 | routing tests。 |
| M4-05 | M4-04 | 未開始 | 建立 memory-only handler fixture，以正式 execution、Task、Chain 驗證端到端。 | fixture integration tests。 |
| M4-06 | M4-05 | 未開始 | 完成 public API 與 Netty-isolation gate；回應 commit 後不得再變更或第二次回應。 | wrapper `verify`。 |
| M5-01 | M4 | 未開始 | 建立 `waveio-netty`／`io.waveio.netty`；鎖定 Netty `4.2.17.Final`，不用 `netty-all`。 | dependency report。 |
| M5-02 | M5-01 | 未開始 | 實作 plaintext bootstrap、acceptor、connection lifecycle、首個 request-response path。 | real-socket tests。 |
| M5-03 | M5-02 | 未開始 | 實作 inbound body→Flow、demand-driven read control、所有 `ByteBuf` release path。 | leak-detection tests。 |
| M5-04 | M5-03 | 未開始 | 實作 outbound writer、commit/flush、write failure、keep-alive、response ordering。 | socket ordering tests。 |
| M5-05 | M5-04 | 未開始 | 實作 TLS、limits、timeouts、Expect 100、chunked body、protocol failures。 | TLS/protocol tests。 |
| M5-06 | M5-05 | 未開始 | 實作 graceful shutdown、real-socket fixture、paranoid leak-detection gate；僅支援 NIO TCP、HTTP/1.1、TLS。 | socket/leak gate。 |
| M6-01 | M5 | 未開始 | 建立 `waveio-server`／`io.waveio.server` 與 immutable server、limit、timeout、execution assembly specs。 | API tests。 |
| M6-02 | M6-01 | 未開始 | 實作 `WaveServer.start`、`RunningServer.address`、`RunningServer.stop(Duration)` 與 runtime ownership。 | lifecycle integration tests。 |
| M6-03 | M6-02 | 未開始 | 實作 `Service` lifecycle：順序啟動、反向關閉、init failure rollback。 | lifecycle tests。 |
| M6-04 | M6-02 | 未開始 | 實作 text/bytes helpers 與 exact-class `Renderer<T>` SPI；不內建 JSON。 | rendering tests。 |
| M6-05 | M6-02 | 未開始 | 實作 immutable observation events、bounded observer dispatcher、failure metrics。 | observer tests。 |
| M6-06 | M6-03, M6-04 | 未開始 | 建立 `waveio-testkit`、embedded fixture、最小 API/blocking/streaming examples。 | executable examples。 |
| M6-07 | M6-05, M6-06 | 未開始 | 完成 public-facade、lifecycle、example consistency gate；所有 server capacities 與 timeouts 必須顯式。 | wrapper `verify`。 |
| M7-01 | M6 | 未開始 | 固定 public/internal boundary、0.x SemVer、Java 25 support policy、API baseline。 | API compatibility check。 |
| M7-02 | M7-01 | 未開始 | 建立 reproducible JAR/sources/javadoc/checksum/LICENSE/NOTICE/dependency report distribution。 | clean distribution verification。 |
| M7-03 | M7-02 | 未開始 | 從 packaged artifacts 編譯與執行最小服務，不使用 reactor classpath。 | clean consumer test。 |
| M7-04 | M7-03 | 未開始 | 擴充 CI：JPMS direction、no-preview、JDK 25、Linux/Windows、public API baseline。 | CI evidence。 |
| M7-05 | M7-04 | 未開始 | 執行 reliability suite：restarts、slow clients、cancellation storms、blocking saturation、large streams、leak profile。 | reproducible suite report。 |
| M7-06 | M7-04 | 未開始 | 建立可重現 benchmarks，記錄 hardware、JDK、OS、configuration、load，分離 microbenchmark 與 HTTP workload。 | benchmark report。 |
| M7-07 | M7-05, M7-06 | 未開始 | 建立 `CHANGELOG.md`、release notes、release dry-run 與 `0.1.0` GitHub Release 附件清單；不得自行發布。 | verified attachments 與 dry-run command。 |

## 目前 Gate 與下一步

P0、M1 與 M2 已完成：M0 重置內容、定位文件、backlog 與 ignore 規則已由單一基線 commit 固定；M1 與 M2 的 Ubuntu／Windows CI gate 都已通過。M3-01 是唯一可開始的後續工作。
