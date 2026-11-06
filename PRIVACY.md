# Record 隱私與錄音告知政策

日期：2026-11-06。開發階段，功能尚待實體手機驗收。

## 本機資料與權限

Record 無帳號、無雲端音檔儲存或背景分析服務。錄音 WAV/FLAC/AAC/Opus、加密檔、ZIP 與 SQLite 資料庫保存在本機 App 私有目錄。只有使用者按下分享或文件匯出時，才經 Android 系統選擇器交付指定檔案。

使用者自行取得 Android RECORD_AUDIO、READ_PHONE_STATE、通知及 MediaProjection 系統權限。ADB shell 需由使用者開啟無線偵錯、輸入自己手機產生的配對碼，後續連線限 127.0.0.1。通知監控限選擇性啟用的 WhatsApp／Telegram CATEGORY_CALL 類別，無須閱讀通話對象、電話號碼或通知內文。

## 加密與隱私

可選擇使用 Android Keystore AES-256-GCM 加密保存音檔，或用裝置身分驗證遮蔽錄音庫 UI。原始錄製期間仍會暫時在私有目錄產生 PCM/WAV，只有使用者啟動加密後才移除明文。不能把裝置身分鎖視為相等於音檔靜態加密。

診斷 JSON 僅記載機型、Android/安全修補版本、Build fingerprint 雜湊前綴、權限能力、人工驗證的通話類型與結果；不包含音檔、電話號碼、通知文字、ADB 配對碼、私鑰或 socket token。

## 保存與清除

使用者可逐一刪除錄音、設定保留期限及手動匯出；若要完全撤回 ADB 授權，需在 Android 開發者選項移除已配對裝置。若清除應用程式資料或移除 Keystore 金鑰，已加密檔案可能無法復原。

## 重要限制

一般 Android App 無公開權限直接錄製雙向電信通話。採用自我配對的 ADB shell 方案不代表每個 OS/OEM 都支援。MediaProjection 只能擷取來源允許的音訊。Record 不移除 Android 法定系統指示，也不能代替使用者對通話當事人履行錄音告知及同意義務。

此倉庫不應含真實私人通話、配對碼或 token。