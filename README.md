# Record — Android 非 Root 音訊錄製

[產品需求書](PRD.md) · [技術說明書](TECHNICAL.md) · [參考專案授權](NOTICE.md)

## 目前內容

此儲存庫已包含 **Android 原始碼開發版（0.1.0-dev）**，尚未經 Android 編譯 CI 或實體裝置完整驗證。依需求**不發佈 APK**。

- Kotlin / Jetpack Compose 操作介面，中文設定精靈、錄音庫、權限與裝置診斷。
- **免 Root** 的使用者主動無線 ADB 配對與本機 ADB 連線。ADB client 依 JemRec (Apache-2.0) 授權改寫。
- shell 端通話錄音 daemon（AudioRecord VOICE_CALL，16kHz mono PCM），本機身份驗證 socket，App 收取後輸出 WAV。
- Android MediaProjection + AudioPlaybackCapture 遵循系統授權的音樂／遊戲聲音錄製（48kHz stereo PCM/WAV）。
- 以 TelephonyCallback 監控電信通話狀態；支援使用者手動／自動開始與停止。
- 本機 WAV 檔案列表、播放、分享、刪除、基本音訊品質檢查與 JUnit 測試。
- GitHub Actions：執行來源編譯與單元測試，**不建立／上傳 APK**。

## 執行前提

Android 12+，Android Studio/Gradle 9.4 和 Android SDK 36。將專案以 Android Studio 開啟，允許 Gradle 同步，在手機上由自己安裝並測試。

初次使用需授權 RE​​CORD_AUDIO／READ_PHONE_STATE，在開發人員選項打開無線偵錯，手機對自己完成一次性六碼配對，再輸入無線偵錯連接埠，啟動 shell daemon。這些設定和手機相容性取決於 OEM；**一般無 Root APK 無法直接取得受保護的 VOICE_CALL 音源**。

此版 daemon 直接透過安裝 APK 作為 app_process classpath，在部分系統可能因 SELinux、APK classloader 或權限限制而無法啟動，會顯示錯誤。不得將 UI 可操作等同於實機通話雙向錄音成功。

## 重要限制

- **不得假裝全部已完成**：多音軌／通話加媒體同步、TX/RX 真實分離、FLAC、跨廠牌兼容、重啟自動恢復、完整加密資料庫與正式商店發佈仍待實作及測試。
- 本專案不呼叫 Google Phone 的內建通話錄音按鈕，但不提供規避 Android 安全通知或法律要求的功能。
- 使用者須在需要的情況下取得通話對方同意；切勿將私人錄音提交公開 GitHub。
- MediaProjection 無法錄製拒絕擷取的 App，以及 Android 保護的語音通話用途。來源靜音不代表錄製成功。

## CI

提交 main 後查看 [Actions](https://github.com/hub-google/Record/actions)。工作流程僅執行 Gradle 編譯與 unit test，不產生可下載 APK。

## 來源

參考 JemRec、scrcpy、CallVault、ShizuCallRecorder 等 Repo 的取捨與授權，詳見 [TECHNICAL.md](TECHNICAL.md)。

