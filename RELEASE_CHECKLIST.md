# Record 正式發行檢核（未取得發行驗收）

截至 2026-11-06，使用者明確表示不用產出 APK，因此本輪只提交 Android 原始碼與 Gradle 編譯／單元測試 CI，沒有安裝檔或 Play Store 發行成果。

## 開源授權

- JemRec：自我無線 ADB 及傳輸參考，Apache-2.0，改寫部分與來源聲明見 NOTICE.md、LICENSE-APACHE-2.0。
- libadb-android：以 Gradle 外部相依使用，採上游提供的 Apache-2.0 授權選項。
- scrcpy：Apache-2.0 的音源／shell 架構參考。
- CallVault、ShizuCallRecorder、Ever-Call-Recorder：GPL 系，未直接納入原始碼；Koel 授權需進一步確認，不直接複製。

## 正式發行前必要條件

1. 至少三個品牌 Android 實體手機，逐機完成 PRD P0 所要求的 10 通合法雙向測試通話、聲音回放及測試紀錄。
2. 驗證 60 分鐘長通話、藍牙、VoLTE、VoWiFi、雙 SIM、Wi-Fi 中斷、重開機、權限撤銷與 OEM 背景限制。
3. 驗證 MediaProjection 授權及音樂/遊戲受控測試訊號；通話和媒體能否同時擷取，不能從 UI 或 CI 通過來推斷。
4. 驗證 FLAC 解碼還原 PCM hash、AAC/Opus 編碼回放、Keystore 加密解密及失敗後保留原檔。
5. 完成隱私政策、開源相依／漏洞審查、通知存取及錄音權限政策、必要錄音同意告知的法律複核。
6. 若實測失敗，設備必須明確標為 UNSUPPORTED 或 NEEDS_USER_ACTION；不得宣稱「所有 Android 手機可用」。

## 專案限制

Android 普通 App 沒有能保證雙向通話數位音軌的公開 API。使用者自行配對 ADB 的 shell 方案仍依賴廠牌音訊 HAL、Android 版本及政策。Android MediaProjection 不允許繞過來源 App 的擷取限制。

此清單是尚未完成的發行驗收門檻，不是宣稱已通過驗收。