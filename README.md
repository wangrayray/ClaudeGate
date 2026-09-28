# Claude 守門員（ClaudeGate）

把 IG、LINE 這類 App 和指定網站鎖起來。想打開時會先跳到跟 Claude 的對話，
要說明為什麼要打開，Claude 同意才放行；使用期間如果在看不相關的東西，Claude 會在畫面上方提醒你。

## 功能

| 功能 | 怎麼運作 |
|---|---|
| 鎖 App | 無障礙服務偵測到前景是被鎖的 App、又沒有有效的解鎖 → 立刻蓋上「解鎖對話」畫面 |
| 鎖網站 | 讀瀏覽器網址列（Chrome、Samsung 網際網路、Firefox、Edge、Brave、Opera、Vivaldi、DuckDuckGo），網域相符就擋；子網域一起擋 |
| 跟 Claude 解鎖 | 在 App 內跟 Claude 對話。Claude 會回「放行 N 分鐘／不放行／先問清楚」，放行時會記下你說的具體目的 |
| 時間到 | 解鎖到期前 1 分鐘提醒；到期後再用那個 App 會再跳回對話（可以說明還差什麼、要求延長） |
| 離題提醒 | 解鎖期間，每隔一段時間（預設 60 秒，換頁時也會）把畫面文字交給 Claude，跟你說的目的比對；離題就在畫面上方跳提醒，可以選「回到正事」或「結束使用」 |
| 專注模式 | 寫下現在要做的事＋時間，專注期間你在瀏覽器看的任何網頁都會被檢查是否相關 |
| 自訂規則 | 用自己的話寫給 Claude，例如「平日晚上 11 點後不要讓我滑 IG」，Claude 判斷時優先照這個 |
| 緊急通行 | API 連不上時，一天最多 2 次、每次 5 分鐘的逃生口（會記錄，下次 Claude 看得到） |

Claude 判斷時還會看到：現在時間、今天這個目標已經放行幾次／幾分鐘／被拒絕幾次。

## 為什麼不是「跳到 Claude App」

官方 Claude App 沒辦法把「同意」傳回給別的 App，所以沒辦法用它當開關。
這個 App 直接用 Claude API（官方 Anthropic Java SDK）在 App 裡對話，效果一樣，
而且 Claude 的決定是結構化的（JSON schema），App 才能確實依照「放行幾分鐘」去解鎖。

## 安裝

1. 用 Android Studio 開這個 repo 的根目錄，接手機按 Run（minSdk 26 = Android 8.0）。
2. 打開 App：
   - 按「開啟無障礙設定」→ 找到「Claude 守門員」→ 開啟。
     Android 13 以上用 Android Studio 直接裝的 App 通常可以直接開；如果顯示「受限設定」，
     到 系統設定 → 應用程式 → Claude 守門員 → 右上角 ⋮ →「允許受限設定」後再開一次。
   - 填入 Anthropic API 金鑰（到 console.anthropic.com 建立）。
   - 「選擇 App」勾選要鎖的 App（有「一鍵勾選常見社群／影音 App」）。
   - 加入要鎖的網域，例如 `youtube.com`。
3. 打開 IG 試試看。

## 模型與費用

- 解鎖對話預設 `claude-opus-5`，effort = medium。
- 離題偵測預設也是 `claude-opus-5`，effort = low。這個會定期呼叫，
  如果在意費用，可以在設定頁把「離題偵測用的模型」改成 `claude-haiku-4-5`。
- Opus 5 的請求有開伺服器端備援（`fallbacks: "default"`）：安全分類器偶爾誤判拒答時，
  由伺服器自動改用別的模型回答，不會卡住解鎖。
- 同一個畫面內容不會重複送；換到非監看的 App 就停止。

## 隱私

- 只有兩種情況會送出畫面文字：被鎖的目標「解鎖期間」、以及「專注模式」下的瀏覽器。
  其他 App 的內容不會離開手機。
- 送出的是畫面上的文字（最多約 2500 字），**不含密碼欄**，不截圖。
- 金鑰與設定存在 App 私有空間；`allowBackup=false`，不會被雲端備份帶走。

## 已知限制

- 瀏覽器網址列的 view id 各家、各版本可能改名；讀不到網址就擋不到該網站
  （`Browsers.kt` 的 `URL_BAR_IDS` 可以補）。使用者正在網址列打字時不判斷。
- 無痕分頁、App 內建瀏覽器（例如 IG 內開的網頁）不一定讀得到網址 —
  但如果那個 App 本身被鎖，就一樣受限制。
- 只要自己到系統設定把無障礙服務關掉，或解除安裝，就能繞過。這是自律工具，不是家長監護。
- 設定、撥號不能被鎖（避免鎖到緊急電話）。

## 程式結構

```
app/src/main/java/tw/claudegate/app/
  GateAccessibilityService.kt  偵測前景 App／網址、封鎖、到期、離題偵測、畫面上方提醒
  GateActivity.kt              解鎖對話畫面
  ClaudeJudge.kt               Claude API：decide()（解鎖）、checkRelevance()（離題）
  MainActivity.kt              設定頁
  AppPickerActivity.kt         勾選要鎖的 App
  Grants.kt                    解鎖紀錄（到期時間、目的）、每日用量
  Browsers.kt                  各家瀏覽器網址列、網域比對
  Settings.kt / Target.kt / Ui.kt
```

刻意不依賴 AndroidX，畫面都是框架元件在程式裡組的；相依套件只有
`com.anthropic:anthropic-java` 和 `kotlinx-coroutines-android`。

## 驗證狀態

- ✅ 全部 Kotlin 在沙箱裡用 Kotlin 2.0.21、Android 14（API 34）的 framework jar、
  真的 Anthropic SDK 2.65.0 編譯通過，0 錯誤 0 警告。
- ✅ 網址解析／網域比對的單元檢查通過；確認送出的請求 JSON（結構化輸出 schema、effort、fallbacks）格式正確。
- ⚠️ 沙箱連不到 Google Maven，**沒有跑過 AGP / Gradle 的完整建置，也沒上手機**。
  資源檔只檢查了 XML 格式。第一次在 Android Studio 建置時如果有問題，多半會在 Gradle 設定或資源連結。
- ⚠️ 沙箱沒有 API 金鑰，沒有實際呼叫 Claude。
