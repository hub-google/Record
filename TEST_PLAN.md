# Record 測試計畫（免 Root Android 通話＋媒體錄音）

> 2026-11-06｜未實機測試，**下列為應執行案例**，不是已完成結果。GitHub Actions 負責程式編譯與純單元測試；音源權限一定要實體手機。

## 裝置測試矩陣

| 測試群組 | 裝置與 Android | Cell Call TX/RX | Bluetooth | Media only | Call+Media | FLAC lossless |
|---|---|---|---|---|---|---|
| Pixel | 待提供手機／Build | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| Samsung Galaxy | 待提供手機／Build | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |
| Xiaomi/Poco | 待提供手機／Build | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED | NOT_TESTED |

每項記錄 OS patch、測試日期、Build fingerprint hash、擷取來源、採樣率、聲道、音量、開始／停止時刻、結果及失敗原因。

### 驗收結果狀態

- **PASS**：實機操作完成、檔案可回放、受控音訊片段符合測試預期。
- **PARTIAL**：只錄單邊、丟失片段、限制部分設備／音路或來源。
- **FAIL**：異常、靜音、不可建立輸入、檔案損毀。
- **NOT_TESTED**：尚未取得環境或尚未執行，**不得寫 PASS**。

## 案例清單

| ID | 測試項目 | 步驟與判定依據 | 目前 |
|---|---|---|---|
| T01 | 無 Root 新機安裝 | Bootloader 保持鎖定；查看 app 設定 | NOT_TESTED |
| T02 | 開發者選項設定 | 顯示使用者必須操作的系統步驟 | NOT_TESTED |
| T03 | ADB 六碼配對 | 使用者從手機系統取得配對埠和碼；顯示真正成功 | NOT_TESTED |
| T04 | ADB 連線埠區分 | 配對埠與連線埠不同，錯誤會提示 | NOT_TESTED |
| T05 | shell daemon 啟動 | 127.0.0.1 PING 返回 READY | NOT_TESTED |
| T06 | 未配對阻擋錄音 | 應顯示不支援或尚未就緒，無假成功 | NOT_TESTED |
| T07 | 去電：自己說話 | A 對 B 念唯一測試詞，回放確認 A 存在 | NOT_TESTED |
| T08 | 去電：對方說話 | B 對 A 念另一詞，回放確認 B 存在 | NOT_TESTED |
| T09 | 來電 | 兩人輪流說話，記錄兩端是否清晰 | NOT_TESTED |
| T10 | 未接電話 | 不得產出假完成的錄音 | NOT_TESTED |
| T11 | 通話中自動開始 | CallMonitorService 在電話接通時開始 | NOT_TESTED |
| T12 | 通話結束自動保存 | 音訊結束後 WAV 時長一致且沒有殘缺 | NOT_TESTED |
| T13 | 手動開始停止 | STOP 以後不再錄；保存一份檔案 | NOT_TESTED |
| T14 | 鎖螢幕 10 分鐘 | 持續錄製、無靜音落差 | NOT_TESTED |
| T15 | 連續 60 分鐘 | 音檔無破損／中斷；容量與耗電量 | NOT_TESTED |
| T16 | 藍牙耳機 | 通話雙向及路由切換是否造成中斷 | NOT_TESTED |
| T17 | 聽筒／擴音模式 | 路由改變後兩側均存在 | NOT_TESTED |
| T18 | 移除 Wi-Fi | daemon 是否能持續；無法則告警 | NOT_TESTED |
| T19 | 重新開機 | 顯示需要恢復；ADB daemon 自動恢復尚未保證 | NOT_TESTED |
| T20 | App 被 OEM 清理 | daemon 是否持續工作，片段是否可回收 | NOT_TESTED |
| T21 | 低儲存空間 | 錄音中止但不要發布損毀 WAV | NOT_TESTED |
| T22 | 媒體錄製／系統授權 | 使用 MediaProjection，先核對使用者同意 | NOT_TESTED |
| T23 | 允許擷取的測試音樂 | 440 Hz、880 Hz 交替，回放正確 | NOT_TESTED |
| T24 | 禁止擷取的第三方 App | 尊重 policy，標示無效，不繞過保護 | NOT_TESTED |
| T25 | 音遊同時播音 | MediaPlayback 允許時捕獲混合媒體音 | NOT_TESTED |
| T26 | 先通話後媒體錄音 | 錄兩軌，驗證是否各有有效音量 | NOT_TESTED |
| T27 | 先媒體後通話錄音 | 驗證音源優先級與路由變化 | NOT_TESTED |
| T28 | 雙音軌 ZIP 封存 | 封存兩個來源，含 session.json、時間起點 | NOT_TESTED |
| T29 | FLAC 轉換 | 將 FLAC 解碼回 PCM，與 WAV 原始 PCM byte-for-byte 相符 | NOT_TESTED |
| T30 | AES-GCM 加密解密 | 內容完全一致；密文被竄改時應拒絕並保留原檔 | NOT_TESTED |
| T31 | 音檔留存／清除 | 0／7／30／90 天及單檔刪除按預期執行 | NOT_TESTED |
| T32 | 分享權限 | 只在使用者動作時分享單一檔案；加密檔不能直接分享 | NOT_TESTED |
| T33 | 通話等待／切換 SIM | 不應把不同通話混入錯誤的會話 | NOT_TESTED |
| T34 | VoLTE／VoWiFi | 按裝置能力記錄 PASS/PARTIAL/FAIL | NOT_TESTED |
| T35 | 前景服務與權限撤銷 | 撤權後不繼續錄，正確顯示系統通知 | NOT_TESTED |
| T36 | 錄音合規告知 | 使用者明示同意紀錄；必要法律告知須自行履行 | NOT_TESTED |

## 自動化程式碼測試

目前 CI 執行下列任務（**不發佈 APK**）：

\`\`\`bash
./gradlew --no-daemon :app:compileDebugKotlin :app:compileDebugJavaWithJavac :app:testDebugUnitTest
\`\`\`

已有的 JUnit 案例涵蓋：
- PCM 靜音辨識、峰值及 clipping 判斷。
- PCM → WAV 正確 44-byte RIFF header、檔案內容一致。
- 音訊不完整時不提交成可供播放的 WAV。
- WAV 波形資料抽樣及峰值。
- 通話與媒體音訊來源的時間重疊匹配、加密來源排除（已加入測試碼）。

需再補：FLAC 媒體編碼器真機測試、AES AndroidKeyStore 真機測試、shell daemon IPC 端到端測試、AudioPlaybackCapture 授權 UI instrumentation、ZIP 解壓及 JSON 同步測試（目前只有配對邏輯單測）。

## 實機證據規範

- 測試呼叫請雙方清楚知情同意；不要上傳私人通話到公開 GitHub。
- 證據可保留音軌 duration、sample rate、sha256、RMS、zero ratio、機型與 Android Build、必要的去識別化錯誤。
- 至少一輪測試須由兩個受控聲源交替發聲，用回放判定是否確實錄到雙方；單純檔案存在、WAV Header 正確或 RMS 不為 0 **不足證明**雙向通話錄音成功。
- 一項正式裝置支援聲明必須附其 OS Build 與完整通過的案例紀錄。


## 本輪新增自動化測試與結果判定

- 多音軌 ZIP：確認兩個獨立音檔及 session.json 的時間戳欄位；來源靜音時不應直接宣稱多訊號有效。
- WAV：PCM16 RIFF header、失敗片段不得發布為正式 WAV、波形峰值分析。
- 合成訊號：440 Hz / 880 Hz 交替播放器的數學訊號及 Goertzel 頻譜偵測，確認拒絕靜音及錯誤頻率。
- shell：新增 16 MiB 分段與持久化開始時戳、SHA256 傳輸檢查及 UUID 去重。這些仍須實機測試 IPC 與檔案系統。
- 媒體：新增 32 MiB 分段、空間不足後保存先前完整片段、靜音／錯誤通知。
- 仍須 Android 真機才能驗證 FLAC／AAC／Opus、MediaProjection、ADB 自我配對、Android Keystore 與雙向通話，全部標為 NOT_TESTED，不能以 CI 綠燈冒充結果。

CI 紀錄：https://github.com/hub-google/Record/actions
