# 手機訊息 (MsgBox)

從 deskctl 專案抽出的「手機訊息資料庫」, 獨立成一個 App (套件名稱 `tw.msgbox`, 可與舊 App 並存)。
把通知收錄到本機 SQLite, 可依 App → 對話 → 訊息串瀏覽、搜尋、只看未讀, 並有 2x2 桌面小工具。

## 檔案 (android/app/src/main/java/tw/msgbox)
| 檔案 | 作用 |
|---|---|
| NotifListener.kt | 通知監聽服務, 轉給 MsgCapture |
| MsgCapture.kt | 過濾通知 (常駐/進度/媒體/群組摘要) 並寫入資料庫 |
| MsgDb.kt | SQLite (msgs.db) 存取層 |
| MessagesActivity.kt | 主畫面 (三層瀏覽) |
| SettingsActivity.kt | 新增: 通知存取狀態、收錄開關、App 白名單、保留天數 |
| Widgets.kt | 2x2 小工具 |
| Prefs.kt / AppLog.kt / AppPicker.kt | 設定 / 記錄 / 選 App 對話框 (皆已精簡) |

## 與原專案的差異
- 移除: 藍牙橋接、控制器轉發 (MessageFeature/MsgBus)、其他小工具、無障礙服務、開機自啟、前景服務與相關權限。
- 設定檔改名 `msgbox` (原 `deskctl`), 資料庫檔名仍為 msgs.db 但存在新 App 自己的資料夾, **不會自動帶入舊資料**。
- 沿用同一把 debug.keystore 與 GitHub Actions 流程 (`.github/workflows/build-apk.yml`), 推上 GitHub 即可打包 APK。
- 尚未在 Android SDK 環境實際編譯 (此環境沒有 SDK); 已逐一檢查所有符號引用皆存在。
