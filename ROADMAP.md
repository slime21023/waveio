# Bottom-up Roadmap

更新日期：2026-09-08。

本文件提供 WaveIO 新定位的進度摘要；唯一的任務追蹤來源是
[DEVELOPMENT_TASKS.md](DEVELOPMENT_TASKS.md)。產品範圍見 [README.md](README.md)，技術方向見
[ARCHITECTURE.md](ARCHITECTURE.md)。舊專案的實作與測試結果不計入本 roadmap。

## 推進原則與現況

以依賴與驗收推進，不以預設日期或程式碼數量判定完成。每層先固定必要契約，建立測試工具，
完成可驗證實作，再進入上層。不預先建立全部空模組，也不用上層 workaround 掩蓋下層問題。

| 階段 | 前置依賴 | 狀態 | 交付主題 |
|---|---|---|---|
| M0 | 無 | 完成 | 重置與重新定位 |
| M1 | M0 | 完成 | 基礎契約與建置 |
| M2 | M1 | 完成 | Execution runtime |
| M3 | M2 | 完成 | Task 與串流橋接 |
| M4 | M3 | 完成 | HTTP 與 handler 組合 |
| M5 | M4 | 完成 | Netty HTTP server |
| M6 | M5 | 完成 | 應用開發體驗 |
| M7 | M6 | 未開始 | 首版發布門檻 |
| M8 | M7 | 未開始 | 首版後擴充 |

每階段完成必須有對應實作、測試證據與同步更新的契約文件。容量、排程、ownership、
取消與終止行為是共同驗收項目。只有 M0 是文件交付；M1–M7 的設計稿不等同於實作完成。

## M0 — 重置與重新定位

**前置依賴：** 無。

**交付物：** 移除舊模組、程式、測試、建置產物、Maven 設定、設計與封存；保留 Git 歷史及
LICENSE。建立 README、ARCHITECTURE、ROADMAP，並簡化 `.gitignore`。

**驗收與證據：** 工作區只留下 `.git`、LICENSE、`.gitignore` 及三份文件；文件連結有效，
定位與進度一致。Git HEAD／refs 與 LICENSE 內容保持完整。沒有框架程式或可發布套件，
不執行舊建置作為驗收。原本未提交或未追蹤的內容已納入移除，未另建封存。

## M1 — 基礎契約與建置

**前置依賴：** M0。

**交付物：** Java 25 Maven 建置與 wrapper、CI、測試基礎、型別化 key、registry 及目前
確有需求的共用契約。先建立基礎層，後續模組隨里程碑加入。

**測試情境：** 型別查找與缺值、同一型別不同 key、局部覆蓋與外層恢復、request 間隔離、
不可變視圖及輸入驗證。

**完成門檻：** 在乾淨環境以 wrapper 通過 Java 25 編譯與測試，不需要 preview；CI 可重現，
基礎層只依賴 JDK、無 HTTP／Netty 依賴。測試依賴不進入 production runtime。

## M2 — Execution runtime

**前置依賴：** M1。

**交付物：** Execution 啟動與完成、segment 排程、context 綁定、deadline、取消、資源清理，
以及具可控排程與時間的 execution harness。明定排隊上限、拒絕行為及各終止狀態契約。

**測試情境：** 同一 execution segment 不重疊、不同 execution 可並行、跨執行緒恢復、
context 隔離與解除綁定、成功／失敗／取消／timeout 競態、清理失敗、晚到 callback、容量耗盡。

**完成門檻：** 不啟動 HTTP server 即可重現上述情境；終止只決定一次，每個清理動作只執行
一次，沒有 context 殘留。Execution runtime 不依賴 Netty；測試不依靠任意 sleep 猜測時序。

## M3 — Task 與串流橋接

**前置依賴：** M2。

**交付物：** 延遲 Task、組合與錯誤恢復、timeout 與清理、CompletionStage 互通、具併發及
等待上限的虛擬執行緒 blocking 橋接、Flow demand／取消基礎與測試 probes。

**測試情境：** 建立 Task 不執行、每次啟動獨立、同步及非同步 completion、map／串接失敗、
blocking 前後 context、已完成的外部 stage、取消後晚到結果、工作拒絕、零 demand 不交付、
慢速 consumer、串流中途取消及終止通知。

**完成門檻：** WaveIO continuation 回到所屬 execution；blocking 不佔用 compute segment；
所有等待及資料累積有界，取消釋放自有資源。文件說清外部 stage 可能已啟動、匯出 callback
不自動保留 context，以及不合作工作的取消限制。核心不依賴 HTTP 或 Netty。

## M4 — HTTP 與 handler 組合

**前置依賴：** M3。

**交付物：** Request／Response／Body 契約、Handler／Context／Chain、method／path routing、
路徑參數、局部 registry、錯誤處理與記憶體內 handler fixture。Buffered body 建立在有界
串流收集之上。

**測試情境：** 正常回應、下一個 handler、插入子鏈、局部覆蓋的恢復、未匹配路由、
method 不匹配、非同步失敗、重複回應、body 單次消費、大小限制及取消。

**完成門檻：** 不開 socket 即可驗證完整 handler 流程；一般 handler 無需了解 buffer 或
執行緒 ownership。HTTP 公開契約不暴露 Netty 型別；路由與委派共用同一處理模型。

## M5 — Netty HTTP server

**前置依賴：** M4。

**交付物：** Netty transport／HTTP codec／TLS 整合、HTTP/1.1 server、body 串流、connection
與 request lifecycle、背壓、timeout、graceful shutdown 及真實連線 fixture。
此階段選定並鎖定經 Java 25 驗證的 Netty 穩定版本。

**測試情境：** Plain／TLS、keep-alive、HEAD、chunked body、Expect: 100-continue、
同連線 response 順序、分段輸入、慢速讀寫、未消費的 request body、非法 framing、header／
body 限制、斷線、response 提交後失敗、逾時與 shutdown 期限到達。

**完成門檻：** 真實 socket 測試通過；Netty buffer 在完成與失敗路徑均被正確釋放，
不在 I/O 執行緒執行 blocking 工作。慢速對端不造成無界累積，斷線與 shutdown 能取消
所屬工作並清理連線資源。使用既有 codec／TLS，不新增自研協定引擎。

## M6 — 應用開發體驗

**前置依賴：** M5。

**交付物：** 使用者導向的 server 啟動 API、設定、service lifecycle、rendering 擴充點、
觀測 hooks、嵌入式測試入口與可執行範例。首版提供基本文字／位元組回應；JSON adapter
留在 M8，不將可選生態套件放入核心。

**測試情境：** 最小 HTTP API、registry 提供服務、blocking 呼叫、串流回應、service
初始化失敗與回滾、shutdown 清理，以及成功／失敗／取消的觀測事件。

**完成門檻：** 範例可由公開 API 啟動、測試及關閉，不依賴 internal 型別；初始化完成前
不接受流量，失敗可清理已啟動資源。使用文件與可執行範例一致，不留下偽裝成 Quick Start
的設計草案。

## M7 — 首版發布門檻

**前置依賴：** M6。

**交付物：** 整合與長時間壓力測試、可重現效能基準、公開 API／相容性政策、發布流程、
使用文件及依賴清單。效能報告記錄 JDK、硬體、負載、配置及測量方法。

**測試情境：** 正常與超額流量、blocking 依賴飽和、慢速 consumer、大型串流、頻繁取消、
持續連線建立與中斷、反覆啟停及長時間運作。至少測試 Java 25；其他支援 JDK 逐一在 CI 驗證。

**完成門檻：** M1–M6 gates 全部通過；資源使用符合配置上限且沒有持續累積的洩漏跡象，
終止與拒絕行為可觀測。發布 artifact 可在乾淨環境使用，不需要 preview。效能數字來自
本次可重現量測，不引用舊實作結果，也不在量測前承諾吞吐量或延遲目標。

## M8 — 首版後擴充

**前置依賴：** M7。依序評估 HTTP/2、WebSocket，再評估 JSON 與觀測生態整合；每項需有
獨立需求、設計及驗收才進入實作。此順序是評估順序，不代表已承諾功能或發布日期。

**交付物與測試情境：** HTTP/2 驗證 multiplexing、flow control 與 stream 取消；WebSocket
驗證 upgrade、訊息流與 close；JSON 驗證序列化錯誤及 body 限制；觀測整合驗證非同步
context 與終止事件。可選整合使用獨立套件，不成為核心必要依賴。

**完成門檻：** 各項沿用既有 execution、Task、串流及 lifecycle 契約，通過對應協定或整合
測試，不複製另一套 runtime。Groovy DSL、DI 容器與 preview API 沒有預排交付階段。

## 更新規則

實作開始時將對應階段改為「進行中」；只有交付物與 gate 都完成後才能標為「完成」，
並補上可查驗的測試命令與結果。新需求若改變依賴或首版範圍，需同時更新架構與本文件。
首版涵蓋 M1–M7；目前 M1–M6 均已完成，下一個實作工作為 M7 的發布門檻。
