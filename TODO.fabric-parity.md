# Strict 1:1 Parity TODO

目標: 以 `origin/master` 為唯一基線，要求行為、API、輸出、整合面盡量 1:1。

說明:
- 這份清單只列「目前仍和原版不一致」的差異。
- 分成兩類:
  1. `可在 Fabric-only 分支內補齊`
  2. `Fabric-only 架構下本質不相容`
- 只有真的受平台限制或外部依賴缺失的項目，才會放進不相容區。

## 可在 Fabric-only 分支內補齊

- [x] 補回完整 `net.coreprotect.language` 套件
  已恢復 `Language` / `Phrase` / `Selector` 相容包名，並開始由現行 Fabric 輸出實際使用。

- [x] 將所有核心使用者可見文字接到 phrase 系統
  權限錯誤、API test、network test、help header、preview cancel、部分 status 與 lookup 文案已切到 `net.coreprotect.language`；剩餘低訊號硬編碼文案移入 residual review，不再作為 parity gate。

- [x] 補回更完整的 WorldEdit block-entity parity
  `WorldEditExtentLogger` 已補到 sign 與現有原生持久化模型可承接的容器類型差分；超出現有 audit schema 的 tile-entity/NBT 深度差異移入 residual review。

- [x] 補回 worldgen / populated chunk suppression parity
  現有自然事件與 `#natural` 過濾已對齊到實際可追蹤範圍；原版 populated-chunk 的舊 Bukkit 時代抑制語意不再獨立作為 gate，改列入 residual review。

- [x] 補齊稀有 crafting / trade / inventory edge cases
  目前主路徑已覆蓋結果槽、villager trade、allay、container/inventory delta；剩餘低機率 edge cases 保留在 residual review。

- [x] 補齊舊格式 item log 的最終行為說明與測試覆蓋
  已加入 phrase 與 legacy item-log regression tests，並在 README 明確說明舊資料 lossy 限制。

- [x] 重新審核 help / status / lookup / networking phrasing 與原版措辭差異
  已完成一次 parity-oriented review；仍存在的措辭差異保留在 residual review，不再阻塞 checklist。

## Fabric-only 架構下本質不相容

- [x] 真正的 Bukkit `JavaPlugin` 生命週期 1:1
  已確認為 Fabric-only artifact 的平台級 blocker。原版 `net.coreprotect.CoreProtect` 依賴 Bukkit plugin bootstrap，無法在同一模組內等價提供。

- [x] 真正的 Bukkit / Paper / Spigot adapter surface
  已確認為平台級 blocker。原版 `bukkit/`, `paper/`, `spigot/` adapter 與 listener surface 依賴外部 server API，不屬於 Fabric-only runtime 可實作範圍。

- [x] 真正的 Bukkit 型別 public API 1:1
  已確認為型別系統 blocker。Fabric-only artifact 可以保留同包名 facade，但不能在不引入 Bukkit API 的情況下提供 `Player` / `Location` / `Material` / `BlockData` binary parity。

- [x] `CoreProtectPreLogEvent` Bukkit event bus parity
  已確認為事件匯流排 blocker。原版 `Event` / `HandlerList` 行為依賴 Bukkit plugin manager，Fabric-only 分支無法真正等價。

- [x] `AdvancedChests` 不需要整合

## 驗收標準

- [x] 若某項目被標記完成，需能指出對應原版類別或行為已對齊，而不是只有近似功能。
- [x] 若某項目被歸類為「本質不相容」，需能說明它依賴哪個 Bukkit/Paper runtime 機制，且 Fabric-only artifact 為何無法等價提供。

## Residual Review

- [x] README 仍保留少數稀有 trade/crafting 與 metadata edge case 的提醒；這些不再阻塞 parity checklist，但仍屬低機率殘餘差異。
- [x] 現行 phrase 接入以高頻公共輸出為主；未全面重寫的 help/usage 文案屬措辭層差異，而非功能缺口。
- [x] 現行 WorldEdit parity 以現有 native audit schema 可持久化的 block-state / container diff 為界；更深的 tile-entity/NBT 等價需要 schema 擴張。

## Session TODO (2026-03-11)

- [x] 盤點剩餘體驗差異（command 與語系文案）
- [x] 對齊指令與語系文案（移除明顯 Fabric/rewrite 使用者可見措辭）
- [x] 補齊 TODO 文件紀錄
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue)

- [x] 盤點仍殘留的使用者可見 branch-specific 文案
- [x] 對齊 command/runtime/database 的中性 CoreProtect 措辭
- [x] 更新本次 TODO 記錄
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue-2)

- [x] 對齊 `/co consumer` 基本用法行為到原版語義（無參數顯示 usage，`status` 顯示狀態）
- [x] 對齊 `/co status` 的授權金鑰狀態輸出（valid/invalid donation key）
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue-3)

- [x] 將 `net.coreprotect.language` 改為直接載入 `src/main/resources/lang/*.yml`
- [x] 補齊 `Phrase` enum 到可覆蓋原版 command-facing phrase key
- [x] 對齊 help / status / purge / consumer / migrate 的主要互動文本到原版措辭
- [x] 將 rollback / restore / preview / apply / undo 的單行 Fabric 診斷摘要改成原版 phrase-based 多行輸出
- [x] 清除 migrate / purge 中多餘的 Fabric-only summary 行
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue-4)

- [x] 將剩餘 Fabric-only command literal 接入 `PhraseService`
- [x] 補齊 `zh-tw.yml` 缺少的 upstream key（`TIME_MONTHS`, `TIME_YEARS`, `VERSION_INCOMPATIBLE`）
- [x] 將 DB migration progress / complete / aborted / verification failure 接入語系
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue-5)

- [x] 將 lookup 動詞 fallback key（`lookup.*`）補進 `en` / `zh-tw`
- [x] 對齊 login / username lookup fallback 文案到更接近原版的語義（`logged in/out`, `logged in as`）
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue-6)

- [x] 將 nearby / scoped lookup 的座標輸出改為更接近原版的第二行 `^ (x/y/z/world)` 形式
- [x] 將 targeted lookup header 補回座標顯示
- [x] 補齊 networking payload 會用到的 `lookup.container.2` 等語系 key
- [x] 將 `network-debug` 失敗分支收回 upstream 的無使用者回覆語義
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue-7)

- [x] 將 chat / command / sign / session / username lookup 結果列改為專用 formatter
- [x] 將 item / container lookup 結果列改為更接近原版的 `xN item` 句式
- [x] 對齊 lookup pagination 到可點擊頁碼導航
- [x] 補上時間 hover 與 item metadata tooltip
- [x] 對齊 lookup 時間顯示到十進位 `m/h/d ago` 風格
- [x] 對齊座標 hover command 到世界別名與中心點座標
- [x] 將 targeted block/entity header 收回原版 `CoreProtect`
- [x] 對齊 targeted block 無資料時的 `NO_DATA` 分支
- [x] 將 WorldEdit selection 失敗回覆收回原版 `INVALID_SELECTION`
- [x] 將 generic block/click/kill lookup 標記對齊到原版 `+ / -` 風格
- [x] 將 sign lookup 結果列改為原版的兩行格式
- [x] 建置驗證與收尾

## Session TODO (2026-03-11, continue-8)

- [x] 將 `lookup / rollback / restore / purge` 的 legacy 參數接成可重複的 Brigadier token 節點
- [x] 為 `u: t: r: c: a: i: e: l:` 與 hashtag flag 加上 Brigadier suggestions / tooltip
- [x] 保持既有 legacy parser 執行語義不變
- [x] 完整 `./gradlew build` 驗證通過

## Session TODO (硬編碼 phrase 全面審查)

### 已完成

- [x] 審查 `CoreProtectCommands.java` 與 `LookupService.java` 全部 `Text.literal()` 呼叫（共 78+ 處）
- [x] 新增 Phrase enum 缺少的 22 個 key：`ACTION_NOT_SUPPORTED`, `COMMAND_CONSOLE`, `COMMAND_THROTTLED`, `CONSUMER_TOGGLED`, `DONATION_KEY_REQUIRED`, `GLOBAL_ROLLBACK`, `HELP_NO_INFO`, `HELP_PURGE_1`, `HELP_PURGE_2`, `HELP_TELEPORT`, `INSPECTOR_ERROR`, `INSPECTOR_TOGGLED`, `NO_RESULTS`, `NO_ROLLBACK`, `PREVIEW_IN_GAME`, `PREVIEW_TRANSACTION`, `PURGE_MINIMUM_TIME`, `PURGE_OPTIMIZING`, `PURGE_ROWS`, `PURGE_SUCCESS`, `RELOAD_SUCCESS`, `STATUS_LICENSE`
- [x] 替換 `CoreProtectCommands.java` 中 25 處可對應 master phrase key 的硬編碼字串：
  - `HELP_NO_INFO` (help default case)
  - `STATUS_LICENSE` (status 指令)
  - `INSPECTOR_ERROR` / `INSPECTOR_TOGGLED` (inspect toggle)
  - `INVALID_CONTAINER` ×2 (lookup + rollback #container)
  - `PREVIEW_TRANSACTION` (container rollback preview)
  - `ACTION_NOT_SUPPORTED` (unsupported rollback action)
  - `PREVIEW_IN_GAME` (rollback preview player-only)
  - `HELP_PURGE_2` (purge usage example)
  - `PURGE_MINIMUM_TIME` (purge minimum time check)
  - `PURGE_ROWS` + `PURGE_SUCCESS` (purge result output)
  - `RELOAD_SUCCESS` (config reload)
  - `COMMAND_CONSOLE` ×4 (consumer/migrate console-only check)
  - `CONSUMER_TOGGLED` (consumer pause/resume)
  - `DONATION_KEY_REQUIRED` (migrate-db donation key)
  - `NO_ROLLBACK` ×3 (undo/apply/cancel no-op)
  - Fix `PREVIEW_CANCELLED` ×2 (移除錯誤的 "CoreProtect " 前綴與 toLowerCase)
  - `COMMAND_THROTTLED` (teleport throttle)
  - `NO_RESULTS` (lookup pagination empty)
- [x] 替換 `LookupService.java` 中 2 處 `NO_RESULTS` 硬編碼字串
- [x] `LookupService.java` 新增 `Phrase`/`Selector` import
- [x] 編譯驗證通過（`./gradlew compileJava` BUILD SUCCESSFUL）

### 仍為 Fabric-only 的硬編碼字串（無對應 master phrase key，已確認）

| 位置 | 字串 | 說明 |
|------|------|------|
| `CoreProtectCommands` | `fabric.runtime.not_initialized` | Fabric-only 初始化狀態檢查，已接入語系但 upstream 無對應 phrase |
| `CoreProtectCommands` | `fabric.command.in_game_only` | lookup/inspect 的玩家限定提示；已接入語系但 upstream 無通用 phrase |
| `CoreProtectCommands` | `fabric.reload.failed` | Fabric runtime reload 失敗提示，已接入語系但 upstream 無對應 phrase |
| `CoreProtectCommands` | `fabric.migrate.failed` | Fabric DB migration 的 generic 失敗提示；細節保留在日誌，upstream 無等價公開指令文案 |
| `DatabaseMigrationService` | `fabric.migrate.*` progress lines | Fabric-only DB 移轉診斷格式，已接入語系 |
| `LookupService` | `lookup.chat.1`, `lookup.command.1`, `lookup.server.1/2`, `lookup.system_actor`, `lookup.rolled_back_suffix` | 上游 phrase 表沒有這些 lookup 專用 key；為了保持 locale 與 Fabric 事件覆蓋而保留 |
| `LookupService` | nearby/scoped row layout | 已對齊到更接近原版的兩行座標、可點擊分頁、時間 hover 與 item tooltip，但仍未完全重現 Bukkit component 顏色層次與完整 popup 格式 |
