package com.hubgoogle.record

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hubgoogle.record.adb.CallDaemonClient
import com.hubgoogle.record.adb.ShellBootstrap
import com.hubgoogle.record.call.CallMonitorService
import com.hubgoogle.record.core.DeviceProbe
import com.hubgoogle.record.data.Recordings
import com.hubgoogle.record.media.PlaybackCaptureService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                RecordScreen()
            }
        }
    }
}

@Composable
private fun RecordSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        })
    }
}

@Composable
private fun RecordScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("record", android.content.Context.MODE_PRIVATE) }
    val bootstrap = remember { ShellBootstrap(context) }
    val daemon = remember { CallDaemonClient(context) }
    val library = remember { Recordings(context) }
    var consent by remember { mutableStateOf(prefs.getBoolean("consent", false)) }
    var pairPort by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("請完成設定與診斷。此版本尚未通過實機驗證。") }
    var daemonReady by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf<String?>(null) }
    var mediaActive by remember { mutableStateOf(false) }
    var monitorActive by remember { mutableStateOf(false) }
    var callState by remember { mutableStateOf("") }
    var mediaState by remember { mutableStateOf("") }
    var recordings by remember { mutableStateOf(library.list()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        feedback = if (granted.values.all { it }) "已授權應用所需權限" else "部分權限未授予：功能可能受限"
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                val request = Intent(context, PlaybackCaptureService::class.java).apply {
                    action = PlaybackCaptureService.ACTION_START
                    putExtra(PlaybackCaptureService.EXTRA_CODE, result.resultCode)
                    putExtra(PlaybackCaptureService.EXTRA_DATA, result.data)
                }
                context.startForegroundService(request)
                feedback = "已送出錄製請求，檢查錄音狀態"
            } catch (e: Exception) { feedback = "無法啟動 MediaProjection：" + e.message }
        } else feedback = "已取消 Android 系統音訊擷取授權"
    }

    LaunchedEffect(Unit) {
        while (true) {
            daemonReady = daemon.ping()
            mediaActive = PlaybackCaptureService.active
            monitorActive = CallMonitorService.running
            callState = CallMonitorService.lastStatus
            mediaState = PlaybackCaptureService.lastStatus
            recordings = library.list()
            delay(1800)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Record", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Android 非 Root 通話／內部聲音錄製", style = MaterialTheme.typography.bodyLarge)
        Text("程式碼開發版 • 系統與機型相容性仍需實機測試", color = MaterialTheme.colorScheme.error)
        RecordSection("狀態與操作結果") {
            Text("通話音訊服務：" + if (daemonReady) "就緒" else "尚未就緒")
            Text("通話監控：" + if (monitorActive) "已開啟" else "未啟用")
            Text("媒體錄音：" + if (mediaActive) "進行中" else "未錄製")
            Text(feedback)
        }

        RecordSection("1. 權限、告知與診斷") {
            Row {
                Checkbox(checked = consent, onCheckedChange = {
                    consent = it
                    prefs.edit().putBoolean("consent", it).apply()
                })
                Text(
                    "我了解錄音須符合適用法律，必要時應先取得通話對方同意。" +
                    "本 App 不會自動替我取得同意，也不會規避 Android 必要的系統提示。",
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
            }
            Button(onClick = {
                val requested = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_PHONE_STATE
                )
                if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
                permissionLauncher.launch(requested.toTypedArray())
            }, modifier = Modifier.fillMaxWidth()) { Text("授予通話狀態、音訊及通知權限") }
            Text(DeviceProbe.inspect(context).report(), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { feedback = DeviceProbe.inspect(context).report() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("重新檢查裝置") }
        }

        RecordSection("2. 不需 Root 的 ADB 配對與錄音程序") {
            Text(
                "先在 Android『開發人員選項 → 無線偵錯』開啟『使用配對碼配對裝置』。" +
                "輸入對話框顯示的配對連接埠與 6 位數代碼，再輸入主畫面顯示的無線偵錯連接埠。" +
                "這是手機自己連自己，不需要電腦。"
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text("開啟 Android 開發人員選項") }
            OutlinedTextField(value = pairPort, onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                label = { Text("配對連接埠") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pairingCode, onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                label = { Text("6 位數配對代碼") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    scope.launch {
                        feedback = "正在配對"
                        feedback = bootstrap.pair(pairPort.toIntOrNull() ?: 0, pairingCode)
                            .fold({ "ADB 配對成功" }, { "配對失敗：" + it.message })
                        pairingCode = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("配對本機 ADB") }
            OutlinedTextField(value = connectPort, onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                label = { Text("無線偵錯連線埠（不是上面的配對連接埠）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    feedback = bootstrap.connect(connectPort.toIntOrNull() ?: 0)
                        .fold({ "ADB 已連線" }, { "ADB 連線失敗：" + it.message })
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("連線至本機 ADB") }
            Button(onClick = {
                scope.launch {
                    feedback = "啟動通話錄音程序中"
                    feedback = bootstrap.startDaemon().fold({ it }, { "啟動失敗：" + it.message })
                    daemonReady = daemon.ping()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("啟動通話錄音核心（shell daemon）") }
            if (daemonReady) {
                OutlinedButton(onClick = {
                    scope.launch { feedback = daemon.shutdown(); daemonReady = daemon.ping() }
                }, modifier = Modifier.fillMaxWidth()) { Text("停止通話錄音核心") }
            }
            Text("若裝置無法提供 VOICE_CALL 音源，會顯示失敗，而不使用喇叭外放再收音。",
                style = MaterialTheme.typography.bodySmall)
        }

        RecordSection("3. 電信通話錄音") {
            Text(callState)
            Button(
                enabled = consent && daemonReady && !monitorActive,
                onClick = {
                    try {
                        context.startForegroundService(Intent(context, CallMonitorService::class.java)
                            .setAction(CallMonitorService.ACTION_START))
                        feedback = "已要求啟用通話監控"
                    } catch (e: Exception) { feedback = "啟用失敗：" + e.message }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("啟用自動通話錄製") }
            OutlinedButton(
                enabled = monitorActive,
                onClick = {
                    context.startService(Intent(context, CallMonitorService::class.java)
                        .setAction(CallMonitorService.ACTION_STOP))
                    feedback = "正在停止通話監控"
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("停止自動通話監控") }
            HorizontalDivider()
            Text("手動測試（僅限與知情同意的對象通話）")
            Button(enabled = consent && daemonReady && !monitorActive && manualId == null, onClick = {
                scope.launch {
                    try {
                        val answer = daemon.start()
                        feedback = answer
                        if (answer.startsWith("CAPTURING ")) manualId = answer.removePrefix("CAPTURING ").trim()
                    } catch (e: Exception) { feedback = "通話錄製失敗：" + e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("手動開始通話錄製") }
            OutlinedButton(enabled = manualId != null, onClick = {
                scope.launch {
                    val id = manualId ?: return@launch
                    try {
                        val stopped = daemon.stop()
                        feedback = stopped
                        if (stopped.startsWith("STOPPED ")) {
                            val file = daemon.fetch(id)
                            feedback = "已保存：" + file.name
                            manualId = null
                            recordings = library.list()
                        }
                    } catch (e: Exception) { feedback = "停止／保存失敗：" + e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("停止並保存通話 WAV") }
        }

        RecordSection("4. 音樂、遊戲、App 內部聲音") {
            Text(mediaState)
            Button(
                enabled = consent && !mediaActive,
                onClick = {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("向 Android 取得授權並開始媒體錄音") }
            OutlinedButton(
                enabled = mediaActive,
                onClick = {
                    context.startService(Intent(context, PlaybackCaptureService::class.java)
                        .setAction(PlaybackCaptureService.ACTION_STOP))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止並保存媒體錄音") }
            Text("僅錄製允許 AudioPlaybackCapture 的媒體／遊戲。通話與媒體同時錄製目前僅實驗性，不保證所有機型可用。",
                style = MaterialTheme.typography.bodySmall)
        }

        RecordSection("5. 本機錄音檔（WAV）") {
            Text("錄音檔只在手機私有空間；分享必須由你手動操作。")
            Button(onClick = { recordings = library.list() }, modifier = Modifier.fillMaxWidth()) { Text("重新整理錄音列表") }
            if (recordings.isEmpty()) Text("目前沒有錄音")
            recordings.forEach { item ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(item.displayDate + " · " + (item.size / 1024) + " KiB", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try { library.play(item.file); feedback = "播放：" + item.file.name }
                            catch (e: Exception) { feedback = "播放失敗：" + e.message }
                        }) { Text("播放") }
                        OutlinedButton(onClick = {
                            try { library.share(item.file) }
                            catch (e: Exception) { feedback = "分享失敗：" + e.message }
                        }) { Text("分享") }
                        OutlinedButton(onClick = {
                            if (library.remove(item.file)) {
                                feedback = "已刪除：" + item.file.name
                                recordings = library.list()
                            }
                        }) { Text("刪除") }
                    }
                    HorizontalDivider()
                }
            }
        }
        Text("相容性仍須以實體 Android 手機驗證。版本 0.1.0-dev，沒有雲端上傳或登入。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

