package com.hubgoogle.record.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hubgoogle.record.adb.CallDaemonClient
import com.hubgoogle.record.data.RecordingDatabase
import com.hubgoogle.record.data.Recordings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Experimental best-effort VoIP detection using Notification.CATEGORY_CALL.
 * Explicit Android notification-listener authorization is required. Notification
 * text, contact names and phone numbers are never inspected or persisted.
 *
 * A notification marked CATEGORY_CALL does not guarantee the call is active or
 * that the shell-UID audio source can capture VoIP audio. End-to-end evidence
 * is still required per package and Android build before marking support.
 */
class VoipCallNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var state: String = "VoIP 監控未啟用"
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val daemon by lazy { CallDaemonClient(this) }
    private var currentKey: String? = null
    private var currentSource: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        state = "通知監聽已授權；僅監測 LINE／WhatsApp／Telegram 等通訊 App 的通話類別"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val source = VoipSourceRegistry.source(sbn.packageName) ?: return
        if (notification.category != Notification.CATEGORY_CALL) return
        val prefs = getSharedPreferences("record", MODE_PRIVATE)
        if (!prefs.getBoolean("consent", false) || !prefs.getBoolean("voipEnabled", false)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            state = "無法顯示錄音通知：請授予通知權限後再開啟 VoIP 監控"
            return
        }
        if (CallMonitorService.running) {
            state = "電信通話監控已啟用；VoIP 不會共用同一個 shell 來源"
            return
        }
        scope.launch {
            mutex.withLock {
                if (currentKey != null) return@withLock
                // An incoming call notification can disappear before this
                // coroutine acquires the lock. Never start after removal.
                if (activeNotifications?.none { it.key == sbn.key } != false) return@withLock
                try {
                    daemon.selectSource("CALL_MIX")
                    val start = daemon.start()
                    if (!start.startsWith("CAPTURING ")) {
                        state = "該 VoIP App 的音訊無法擷取：" + start
                        return@withLock
                    }
                    currentKey = sbn.key
                    currentSource = source
                    state = source + " 通話錄製嘗試中（音源需回放驗證）"
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(
                        NotificationChannel("record.voip", "VoIP 錄音通知", NotificationManager.IMPORTANCE_LOW)
                    )
                    manager.notify(57, Notification.Builder(this@VoipCallNotificationListener, "record.voip")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setOngoing(true)
                        .setContentTitle("Record 正在嘗試錄製 VoIP 音訊")
                        .setContentText("僅限使用者已開啟的 LINE／WhatsApp／Telegram 等通訊 App 來源").build())
                } catch (error: Exception) {
                    state = "VoIP 啟動失敗：" + error.message
                    Log.w("RecordVoip", "Start failed", error)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        scope.launch {
            mutex.withLock {
                if (key == currentKey) finish()
            }
        }
    }

    private suspend fun finish() {
        val oldSource = currentSource ?: return
        currentKey = null
        currentSource = null
        try {
            val stopped = daemon.stop()
            if (!stopped.startsWith("STOPPED ")) {
                state = "VoIP 片段未完成：" + stopped
                return
            }
            var saved = 0
            for (id in daemon.pending()) {
                val file = daemon.fetch(id)
                RecordingDatabase(this).updateSource(file.name, oldSource)
                saved++
            }
            state = "VoIP 已保存 " + saved + " 段音訊；請人工驗證雙向內容"
        } catch (error: Exception) {
            state = "VoIP 片段傳輸失敗：" + error.message
        } finally {
            getSystemService(NotificationManager::class.java).cancel(57)
        }
    }

    override fun onListenerDisconnected() {
        scope.launch {
            mutex.withLock { finish() }
            scope.cancel()
        }
        state = "VoIP 通知存取中斷"
        super.onListenerDisconnected()
    }
}

