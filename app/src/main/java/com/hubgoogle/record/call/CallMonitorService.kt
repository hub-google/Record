package com.hubgoogle.record.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.hubgoogle.record.adb.CallDaemonClient
import com.hubgoogle.record.core.RecordingAlert
import com.hubgoogle.record.data.RecordingDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User-initiated monitoring of cellular call state. This is not an Accessibility listener
 * and it does not secretly activate: a foreground service and visible notification remain.
 */
class CallMonitorService : Service() {
    companion object {
        const val ACTION_START = "com.hubgoogle.record.CALL_MONITOR_START"
        const val ACTION_STOP = "com.hubgoogle.record.CALL_MONITOR_STOP"
        @Volatile var running = false
            private set
        @Volatile var lastStatus = "通話監控未啟用"
            private set
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val client by lazy { CallDaemonClient(this) }
    private var callback: TelephonyCallback? = null
    private var session: String? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || running) return START_NOT_STICKY
        val permission = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val consent = getSharedPreferences("record", MODE_PRIVATE).getBoolean("consent", false)
        if (!permission || !consent) {
            lastStatus = "請授權電話狀態／音訊並確認錄音合規聲明"
            stopSelf()
            return START_NOT_STICKY
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel("record.call", "通話監控", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "record.call")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Record 通話錄音已就緒")
            .setContentText("目前監控電信通話狀態；可在 App 中停止")
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(55, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(55, notification)
            val tm = getSystemService(TelephonyManager::class.java)
            val listener = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    scope.launch {
                        mutex.withLock {
                            when (state) {
                                TelephonyManager.CALL_STATE_OFFHOOK -> begin()
                                TelephonyManager.CALL_STATE_IDLE -> end()
                            }
                        }
                    }
                }
            }
            tm.registerTelephonyCallback(mainExecutor, listener)
            callback = listener
            running = true
            lastStatus = "監控中；開始通話後將嘗試錄音"
        } catch (e: Exception) {
            lastStatus = "無法啟動監控：" + e.message
            Log.e("RecordCall", "Monitor failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private suspend fun begin() {
        if (session != null) return
        try {
            client.selectSource("CALL_MIX")
            val answer = client.start()
            if (!answer.startsWith("CAPTURING ")) {
                lastStatus = "通話來源無法錄製：" + answer
                RecordingAlert.show(this, "通話錄音未開始", lastStatus)
                return
            }
            session = answer.removePrefix("CAPTURING ").trim()
            lastStatus = "通話錄音中"
        } catch (e: Exception) {
            lastStatus = "通話錄音未啟動：" + e.message
            RecordingAlert.show(this, "通話錄音失敗", lastStatus)
        }
    }
    private suspend fun end() {
        if (session == null) return
        session = null
        try {
            val answer = client.stop()
            if (!answer.startsWith("STOPPED ")) {
                lastStatus = "通話錄音不完整：" + answer
                RecordingAlert.show(this, "通話錄音不完整", lastStatus)
                return
            }
            var count = 0
            var silent = 0
            val database = RecordingDatabase(this)
            for (id in client.pending()) {
                val file = client.fetch(id)
                val status = database.details(file.name)?.status
                if (status == "EMPTY" || status == "SILENT") silent++
                count++
            }
            lastStatus = when {
                count == 0 -> "沒有可保存的通話音訊片段"
                silent == count -> "已儲存 " + count + " 段，但全部可能是靜音"
                silent > 0 -> "已儲存 " + count + " 段，其中 " + silent + " 段可能靜音"
                else -> "已儲存 " + count + " 個音訊片段（雙向內容尚需回放確認）"
            }
            if (count == 0 || silent > 0) RecordingAlert.show(this, "通話音訊可能不完整", lastStatus)
        } catch (e: Exception) {
            lastStatus = "通話錄音傳輸失敗：" + e.message
            RecordingAlert.show(this, "通話片段儲存失敗", lastStatus)
        }
    }
    override fun onDestroy() {
        callback?.let { cb -> runCatching { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(cb) } }
        // Finish the current session before cancelling scope; cancelling immediately here
        // would abort the stop/fetch coroutine and lose the file.
        val finalization = scope.launch { mutex.withLock { end() } }
        finalization.invokeOnCompletion { scope.cancel() }
        running = false
        lastStatus = if (session == null) "通話監控已停止" else "監控停止，正在保存最後一通錄音"
        super.onDestroy()
    }
}

