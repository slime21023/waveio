# WaveIO

WaveIO 是以 **Java 25+** 開發、承接 **Ratpack 設計理念**的輕量 HTTP 應用框架。
面向希望以明確、可組合的非同步 API 建立 HTTP 服務的 Java 後端開發者。

目前已完成 Java 25 Maven reactor、foundation、execution、Task／Flow、HTTP／handler、Netty
HTTP/1.1 transport、public server facade 與 embedded testkit；發布套件仍待 M7 驗證。舊實作、
測試、設計與封存已移除，不保留舊 WaveIO 或 Ratpack API 相容層。

## 設計方向

- **受管理的非同步執行**：execution 管理一次邏輯操作的 context、錯誤、取消及清理；
  精簡的 `Task<T>` API 負責單一結果的非同步組合。
- **可組合的 HTTP 處理**：以 handler、context、chain、routing 與型別化 registry
  組織應用，支援下一個 handler 的委派及局部子鏈。
- **現代 Java**：使用 Java 25 正式特性，包括 records、sealed types、pattern matching、
  虛擬執行緒與 `ScopedValue`；核心不要求啟用 preview。
- **明確的 blocking 邊界**：透過虛擬執行緒橋接 blocking 工作，完成後回到所屬 execution；
  工作數量及等待容量都有上限。
- **Netty 網路底層**：由 Netty 提供 transport、HTTP codec 與 TLS；應用 API 不暴露 Netty 型別。
- **由底層往上驗證**：基礎契約 → execution → Task／串流 → HTTP／handler → server
  → 應用體驗 → 發布驗證。測試工具隨各層建立。

Ratpack 的 execution、handler 組合與型別化 registry 是設計參考；WaveIO 自行設計 API
與實作，不是 Ratpack 的包裝層或移植版本。參見
[Ratpack 架構](https://ratpack.io/manual/current/architecture.html)。

## 首版目標

首版以 HTTP/1.1 應用為範圍，包含 TLS、buffered／streaming body、routing、registry、
Task 組合、blocking 橋接、錯誤處理、service lifecycle、觀測 hooks 與 graceful shutdown。
首版須具備獨立 execution 測試、記憶體內 handler 測試與真實連線測試。

HTTP/2、WebSocket、JSON 與觀測生態整合屬於首版後的擴充；Groovy DSL、DI 容器整合、
preview API 未排入首版。核心不提供 ORM、完整 DI 容器或通用 reactive operator 生態。

## 閱讀順序

1. 本文件：產品定位、受眾與首版範圍。
2. [架構與 API 方向](ARCHITECTURE.md)：分層、執行語意、資源邊界與技術決策。
3. [Bottom-up roadmap](ROADMAP.md)：各階段的依賴、交付物與驗收門檻。
4. [開發任務 backlog](DEVELOPMENT_TASKS.md)：可獨立審查的任務、key results 與驗證證據。
5. [API 相容性基線](docs/API_COMPATIBILITY.md)：0.1.0 public boundary 與 Java 支援政策。

目前已完成 M6 的 public server facade、service lifecycle、response helpers、observation hooks
及 embedded testkit；最小 API、blocking、streaming 使用說明見 [範例](docs/examples.md)。下一步為
M7 發布驗證。各階段的可重現驗證證據位於 `docs/development/`；release dry-run 與附件清單見
[0.1.0 release notes](docs/releases/0.1.0.md)。
版本、依賴版本及發布日期尚未設定；不沿用舊版本的測試數字或效能宣稱。

## 授權

[MIT License](LICENSE)。
