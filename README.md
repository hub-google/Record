# Record — Android 非 Root 通話與內部音訊錄製

[PRD（產品需求）](PRD.md) · [技術設計](TECHNICAL.md) · [開發驗收狀態](IMPLEMENTATION_STATUS.md) · [測試計畫](TEST_PLAN.md) · [GitHub Actions](https://github.com/hub-google/Record/actions)

**Android 12+ 原生原始碼專案。開發中，未經實機電話錄音驗證；按需求不產出或發佈 APK。**

## 目前實作

- 使用者主動的免 Root 無線 ADB 配對，將錄音程序以 shell UID 啟動，從 Android 的 VOICE_CALL 音源嘗試擷取通話音訊。
- 使用者授權 MediaProjection 後錄製允許被擷取的音樂／遊戲播放音訊。
- 16-bit PCM → WAV；Android MediaCodec FLAC 無損轉換（須由設備支援）。
- 自動和手動電信通話錄音控制、前景狀態通知、重啟提醒、恢復待收取的 shell 錄音片段。
- 將重疊的通話及媒體錄音封存成含時間戳 JSON 的多軌 ZIP；不是宣稱分開 TX/RX，也未證明每支手機都能同時擷取。
- 本機 SQLite 錄音索引、音量／靜音品質判定、檔名／來源搜尋、WAV 波形、播放、分享、刪除、7/30/90 天保留期限。
- Android Keystore AES-256-GCM 選擇性加密與解密、Android 系統生物辨識／裝置密碼錄音庫鎖；加密音訊先驗證成功後才可移除明文。
- Compose 介面、JUnit 測試及 GitHub Actions 原始碼編譯。原始碼授權見 [NOTICE.md](NOTICE.md)、[LICENSE-APACHE-2.0](LICENSE-APACHE-2.0)。

## 不符合或尚未驗收

**最重要：** 還沒有真實 Android 手機執行的 ADB 配對與雙向通話錄音測試結果。必須在受控且依法取得同意的實機上做驗證，不能因程式碼能編譯就宣稱通話實際可用。

已提供使用者同意的 WorkManager 最佳努力定期恢復（不保證即時）。尚未完成完整的 FLAC 解碼驗證、各 OEM 相容性、真正 uplink/downlink 獨立音軌、漂移補償、VoIP 自動識別、跨檔逐字稿全文搜尋；以上需原始碼擴充或實機證據。詳細落差參閱 [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)。

**此 App 不使用 Google Phone 內建錄音流程，也不提供繞過系統安全通知或當地錄音告知／同意義務的功能。**

## 開發

以 Android Studio 開啟專案，Android SDK 36、Gradle Wrapper 9.7.1 和 JDK 21；執行：

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:testDebugUnitTest
```

CI 只做原始碼編譯與測試，不發佈或上傳 APK。Android 12+ 真機需自行完成無線偵錯配對；不安裝 Root／Magisk／KernelSU。

## 2026-11-06 追加開發項目

已提交完整原始碼的增量功能：
- CALL_MIX / CALL_TX / CALL_RX 手動音源選擇；通話 16 MiB 分段；每段存開始時間、來源與 SHA-256，UUID 防重複收取；媒體 32 MiB 分段及低空間保護。
- WAV、FLAC、AAC/M4A、Opus/Ogg；錄音庫搜尋、重新命名、波形、播放、加密、系統文件匯出、清除與留存規則。
- 使用者自主許可的 VoIP 通知分類偵測、通話失敗及靜音通知、可選背景自癒。
- 440Hz / 880Hz 原創測試音播放及回放檢測、裝置相容性結果人工標記與匿名 JSON 匯出。
- PCM 音質、WAV 原子保存、測試頻譜與多音軌 ZIP JSON 的 JVM 單元測試。

**狀態：** GitHub CI 的綠色檢查只保證原始碼編譯與單元測試通過；本工作環境無法操作實體手機或發起真正的雙方同意通話，因此 PRD 要求的 3 品牌 × 每機 10 次真實雙向通話、60 分鐘通話、VoIP／藍牙／重開機、通話＋媒體互斥測試都仍未驗收。不可視為正式產品已完成。

最新逐項表見 [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)，發布清單見 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)。
