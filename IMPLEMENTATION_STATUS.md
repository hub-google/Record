# Record 開發交付與驗收狀態

> 日期：2026-11-06 ｜分支：main ｜策略：不產出 APK。此文是 **實際交付情況**，不是 PRD 的重新宣稱。
>
> [PRD.md](PRD.md)｜[TECHNICAL.md](TECHNICAL.md)｜[TEST_PLAN.md](TEST_PLAN.md)｜[GitHub Actions](https://github.com/hub-google/Record/actions)

## 1. 完成定義

- **已實作**：GitHub main 分支存在可檢視的 Kotlin／Java 原始碼。
- **已編譯**：GitHub Actions 的 compileDebugKotlin、compileDebugJavaWithJavac 全通過。
- **單元測試通過**：GitHub Actions testDebugUnitTest 全通過。
- **實機驗證通過**：須有 Android 實體裝置、對方同意的通話、原始音檔證據和測試紀錄。**目前沒有此證據，不可宣稱通過。**
- **商用品質**：須完成長時間實機壓力測試、安全審查、法規檢查及裝置相容性矩陣。**目前未達成。**

## 2. PRD 功能對照

| PRD 編號 | 模組 | 原始碼 | 編譯與測試 | 功能驗證／已知限制 |
|---|---|---|---|---|
| FR-01 | 開發者選項、無線 ADB 配對引導 | ✅ | 以 CI 為準 | 未完成真機手機對自己配對驗證；使用者需自行操作 |
| FR-02 | 裝置能力診斷 | 已實作待實機驗證 | 以 CI 為準 | 權限／型號／空間／音源 buffer 診斷；**不等於雙向錄音能力實測** |
| FR-03 | 自動／手動電信通話錄音 | 已實作 | 以 CI 為準 | 依賴 shell UID + 系統相容性；未實機通話 |
| FR-04 | WAV/PCM、FLAC／AAC／Opus | 已實作 | WAV 單測 + CI；FLAC 編譯 | FLAC 經 Android MediaCodec 轉碼，尚未以真機 FLAC 解碼回 PCM 做位元比對 |
| FR-05 | 錄音庫、播放、搜尋、波形、重新命名、文件匯出、分享、刪除與保留期限 | 已實作 | 波形圖單測＋CI | 搜尋與 WAV 波形已開發；UI 真機操作尚未驗證 |
| FR-06 | 程序生存／恢復、SHA256 去重、分段錄製、低儲存及錯誤通知 | 已實作待實機驗證 | 以 CI 為準 | Daemon 獨立於 App、片段回收、開機通知、使用者同意的 WorkManager 每 15 分鐘最佳努力自癒；**不保證開機後立即或所有 OEM 都可恢復** |
| FR-07 | 本機隱私、同意時間戳、AES-GCM 與系統身份驗證 | 已實作待實機驗證 | 以 CI 為準 | Android Keystore AES-GCM 可選、明確同意、私有儲存、系統生物辨識或裝置密碼驗證；缺真機加解密與驗證測試 |
| FR-08 | 音樂／遊戲內部聲音 | 已實作 | 以 CI 為準 | MediaProjection + AudioPlaybackCapture；必須由系統授權且來源允許，未實機回放 |
| FR-09 | 通話＋媒體同時擷取 | 實驗性 | 兩個獨立錄音程序已編譯 | **未證明 Android 可以同時提供兩路有效音訊**，不能承諾全機型支援 |
| FR-10 | 多音軌／上行下行分離 | 部分 | 以 CI 為準 | 可將同時間的 CALL_MIX 與 MEDIA_PLAYBACK 各自封存為 ZIP＋時間戳 JSON；**沒有真正 TX/RX 分離、沒有漂移補償** |
| FR-11 | WhatsApp／Telegram 通話通知分類實驗模式 | 已實作待實機驗證 | 手動音源測試入口 | 手動 shell 音源或有機會擷取；**沒有 VoIP 自動通話偵測／個別相容性測試** |
| FR-12 | 多品牌相容性矩陣及匿名 JSON 匯出 | 已實作待實機驗證 | N/A | 尚缺 Pixel、Samsung、Xiaomi/Poco 實體測試證據 |

### 技術上已有，但尚未達 PRD 完成標準

1. 相容性、雙方通話音質、SIM／VoLTE／VoWiFi、藍牙路由必須有實際手機與通話對象。
2. Android shell 端使用非公開 API 路線，Android 每次大版本更新都可能改變音訊擷取權限；沒有「所有 Android 免 Root 都可錄」的保證。
3. 音源互斥、MediaProjection 策略與部分 App 拒絕擷取是 Android 的政策限制，程式碼不能宣稱可繞過。
4. 完整 VoIP 自動通話偵測、真正 TX/RX 拆軌及漂移補償尚未完成；搜尋、WAV 波形與裝置身分鎖介面已寫入原始碼，但未經實機驗證。
5. GitHub Actions 僅做原始碼編譯與單元測試，不運行實體 Android，也不發佈任何 APK。

## 3. 已提交的程式碼路徑

- Android App / UI：app/src/main/java/com/hubgoogle/record/MainActivity.kt
- 使用者自行 ADB 配對：adb/AdbIdentity.kt、adb/AdbTransport.kt、adb/ShellBootstrap.kt
- Shell 音訊來源：shell/ShellCaptureMain.java、shell/ShellAudioContext.java
- 通話監控及重開機：call/CallMonitorService.kt、call/BootRestoreReceiver.kt
- Android 媒體擷取：media/PlaybackCaptureService.kt
- WAV、FLAC、雙音軌：audio/WavPcmFile.kt、audio/FlacTranscoder.kt、audio/MultiTrackExporter.kt
- 音質及裝置診斷：core/RecordingQuality.kt、core/DeviceDiagnostics.kt
- SQLite 索引、保留期限、播放分享：data/RecordingDatabase.kt、data/Recordings.kt
- Android Keystore：security/SecureVault.kt
- 單元測試：app/src/test/...
- CI：.github/workflows/verify.yml
- 第三方授權：NOTICE.md、LICENSE-APACHE-2.0

## 4. 尚待實體手機的封版關卡

請依 TEST_PLAN.md 驗證，記錄：
- 三個不同廠牌／OS Build。
- 至少 10 通雙方同意的實際測試電話／機型。
- 不使用 Google Phone 內建錄音時可錄取雙方，回放由測試人員確認。
- 鎖螢幕、重新開機、斷 Wi-Fi、聽筒／擴音／藍牙、60 分鐘電話。
- 通話同時遊戲音訊：兩個檔案是否都不為靜音？是否來源重疊、同時有不同訊號？
- 完成 FLAC 編碼後再解碼，比對原 WAV PCM 的 SHA-256，保證無損。

如測試不通過，須如實回報「此型號不支援」，不要把音檔存在當成測試成功。任何未被實機驗證的功能都不能對使用者保證。

## 5. 不能藉由增加程式碼就解決的障礙

- 一般 Android App 無權直接從電話音軌獲取受保護的雙向電話聲音；ADB shell 權限方案也受 OEM 實作限制。
- 電信壓縮損失的聲音無法因 FLAC/WAV 恢復；無損是指保存取得的 PCM。
- 媒體 App 可拒絕音訊擷取，通話音源與媒體音源在通話中可能互斥。
- 錄音同意法律要求、Android 系統必要的通知／權限，不能藉由避開 Google Phone 內建錄音按鈕而免除。

**總狀態：** Android 原始碼版本有實際可操作的實作和 CI，但截至本紀錄日期 **仍不是通過 PRD 全部驗收的正式產品**。不提供 APK 是使用者明確要求，**不是測試完成的證明**。


## 2026-11-06 補充開發記錄

新增實際源碼：CALL_TX/CALL_RX 手動音源選擇，通話 16 MiB 與媒體 32 MiB 分段，傳輸 PCM SHA-256 校驗、UUID 去重與意外中斷收取；AAC/Opus 選擇性轉碼；錄音庫重新命名和 Android SAF 文件匯出；受控 440/880Hz 測試播放器及頻譜辨識、匿名兼容性報告；VoIP CATEGORY_CALL 白名單、錄音錯誤通知、低空間保護、隱私清除含可存取的 shell 片段。增加 WAV/頻譜/ZIP JSON 單元測試。

**重要狀態不變：** CI 通過只代表編譯和單元測試，不能驗證真實手機的雙向電話聲音、通話與媒體並行、VoIP 或獨立 TX/RX 同時擷取。完整實機 P0–P3 驗收仍為 NOT_TESTED；無設備與實際雙方同意通話不能宣稱 100% 完成。

## 2026-11-09 原始碼進度補充（覆蓋上方舊版未實作描述）

| 需求 | 這次實作 | 自動化檢查 | 手機實測 |
|---|---|---|---|
| FR-09 多訊源同步／混音 | 增加 SynchronizedMixer 按 capture start 對齊 CALL 和 MEDIA，合成 48kHz stereo PCM；保留原始來源；另增 MIC 可見前景錄製 | WAV header、不同採樣率及起始時間混音測試 | NOT_TESTED |
| FR-10 TX/RX | shell 增加 START_DUAL／STOP_DUAL，兩個 AudioRecord 同時請求 VOICE_UPLINK 和 VOICE_DOWNLINK，各寫自己 UUID／.info／PCM，偵測不支援即報錯 | Android Java 原始碼編譯 | NOT_TESTED；系統可能拒絕雙來源或其中一軌靜音 |
| FR-02／12 兼容性驗收 | 每支手機 Build 個別儲存含 SHA-256 的人工雙方聽覺驗收；10 通／9 通數值門檻、來去電均需有，避免同一錄音反覆計數 | JVM TrialEvaluator 多案例測試 | 無設備操作；NOT_TESTED |
| FR-05 檔案播放 | 僅允許單一 MediaPlayer；新增停止播放鍵和資源釋放 | Android 編譯 | NOT_TESTED |

**以上新增功能不改變核心限制：沒有真實 Pixel、Galaxy、Xiaomi/Poco 機器與雙方合法通話音訊，不能將 PRD 各階段標記為 PASS。** Android lint 靜態安全檢查和編譯單元測試結果以 GitHub Actions 每次 main 的實際紀錄為準。原始碼沒有宣稱可繞過 Google Phone／Android 的法定提示或安全權限。

參閱 [實機驗收操作手冊](DEVICE_ACCEPTANCE_PROTOCOL.md) 與 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)。
