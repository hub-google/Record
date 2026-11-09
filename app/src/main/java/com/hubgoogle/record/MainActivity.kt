package com.hubgoogle.record

import android.Manifest
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
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
import com.hubgoogle.record.call.VoipCallNotificationListener
import com.hubgoogle.record.call.VoipSourceRegistry
import com.hubgoogle.record.call.RecoveryScheduler
import com.hubgoogle.record.core.DeviceProbe
import com.hubgoogle.record.core.CompatibilityMatrix
import com.hubgoogle.record.core.AcceptanceJournal
import com.hubgoogle.record.data.Recordings
import com.hubgoogle.record.media.PlaybackCaptureService
import com.hubgoogle.record.media.MicCaptureService
import com.hubgoogle.record.media.TestTonePlayer
import com.hubgoogle.record.audio.TestToneWavVerifier
import com.hubgoogle.record.audio.WavWaveform
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import com.hubgoogle.record.audio.FlacTranscoder
import com.hubgoogle.record.audio.CompressedTranscoder
import com.hubgoogle.record.audio.MultiTrackExporter
import com.hubgoogle.record.audio.SynchronizedMixer
import com.hubgoogle.record.audio.SessionBundleExporter
import com.hubgoogle.record.audio.SessionMixer
import com.hubgoogle.record.security.SecureVault
import com.hubgoogle.record.data.RecordingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    val recordingLibraryUnlocked = mutableStateOf(false)
    override fun onStop() {
        recordingLibraryUnlocked.value = false
        super.onStop()
    }
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
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("record", android.content.Context.MODE_PRIVATE) }
    val bootstrap = remember { ShellBootstrap(context) }
    val daemon = remember { CallDaemonClient(context) }
    val library = remember { Recordings(context) }
    val matrix = remember { CompatibilityMatrix(context) }
    val journal = remember { AcceptanceJournal(context) }
    var activeTab by remember { mutableStateOf("首頁") }
    var consent by remember { mutableStateOf(prefs.getBoolean("consent", false)) }
    var clearConfirmation by remember { mutableStateOf(false) }
    var biometricLock by remember { mutableStateOf(prefs.getBoolean("biometricLock", false)) }
    var autoRestore by remember { mutableStateOf(prefs.getBoolean("autoRestore", false)) }
    var voipEnabled by remember { mutableStateOf(prefs.getBoolean("voipEnabled", false)) }
    var retentionDays by remember { mutableIntStateOf(prefs.getInt("retentionDays", 0)) }
    var restartNotice by remember { mutableStateOf(prefs.getBoolean("needs_recovery", false)) }
    var pairPort by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("請完成設定與診斷。此版本尚未通過實機驗證。") }
    var daemonReady by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf<String?>(null) }
    var mediaActive by remember { mutableStateOf(false) }
    var micActive by remember { mutableStateOf(false) }
    var micLevel by remember { mutableIntStateOf(0) }
    var micState by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf("CALL_MIX") }
    var combinedRequested by remember { mutableStateOf(false) }
    var includeMicWithCombined by remember { mutableStateOf(false) }
    var selectedFeature by remember { mutableStateOf("CALL_MIX") }
    var trialDirection by remember { mutableStateOf("OUTGOING") }
    var trialRoute by remember { mutableStateOf("EARPIECE") }
    var trialSelfAudible by remember { mutableStateOf(false) }
    var trialRemoteAudible by remember { mutableStateOf(false) }
    var trialFile by remember { mutableStateOf("") }
    var recordedTrials by remember { mutableStateOf(journal.list()) }
    var selectedOutcome by remember { mutableStateOf(matrix.result("CALL_MIX")) }
    var reportJson by remember { mutableStateOf("") }
    var monitorActive by remember { mutableStateOf(false) }
    var callState by remember { mutableStateOf("") }
    var callLevel by remember { mutableIntStateOf(0) }
    var callElapsedMs by remember { mutableLongStateOf(0L) }
    var mediaLevel by remember { mutableIntStateOf(0) }
    var mediaElapsedMs by remember { mutableLongStateOf(0L) }
    var mediaState by remember { mutableStateOf("") }
    var recordings by remember { mutableStateOf(library.list()) }
    var search by remember { mutableStateOf("") }
    var renameFile by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var exportFile by remember { mutableStateOf<java.io.File?>(null) }
    var waveformFile by remember { mutableStateOf<String?>(null) }
    var waveformData by remember { mutableStateOf<List<Float>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        feedback = if (granted.values.all { it }) "已授權應用所需權限" else "部分權限未授予：功能可能受限"
    }
    fun authenticateLibrary(onSuccess: () -> Unit) {
        try {
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val manager = context.getSystemService(BiometricManager::class.java)
            if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                feedback = "請先設定手機生物辨識或螢幕鎖定密碼"
                return
            }
            BiometricPrompt.Builder(context)
                .setTitle("驗證錄音庫身分")
                .setSubtitle("使用裝置的生物辨識或螢幕解鎖")
                .setAllowedAuthenticators(authenticators)
                .build()
                .authenticate(CancellationSignal(), context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            onSuccess()
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            feedback = "驗證未通過：" + errString
                        }
                    })
        } catch (e: Exception) {
            feedback = "裝置驗證功能不可用：" + e.message
        }
    }

    val reportExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                feedback = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(reportJson.toByteArray(Charsets.UTF_8))
                        } ?: error("Cannot create report document")
                    }
                    "已匯出不含電話號碼／錄音內容的診斷 JSON"
                } catch (e: Exception) { "診斷報告匯出失敗：" + e.message }
            }
        }
    }

    val documentExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val source = exportFile
        if (uri != null && source != null) {
            scope.launch {
                feedback = try {
                    withContext(Dispatchers.IO) {
                        val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                        output.use { library.copyTo(source, it) }
                    }
                    "已匯出檔案：" + source.name
                } catch (e: Exception) { "匯出失敗：" + e.message }
                exportFile = null
            }
        } else exportFile = null
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
                if (combinedRequested) {
                    combinedRequested = false
                    scope.launch {
                        feedback = try {
                            daemon.selectSource("CALL_MIX")
                            val answer = daemon.start()
                            if (!answer.startsWith("CAPTURING ")) {
                                "通話錄製失敗：" + answer + "；未啟動同期媒體錄製"
                            } else {
                                manualId = answer.removePrefix("CAPTURING ").trim()
                                context.startForegroundService(request)
                                if (includeMicWithCombined) {
                                    runCatching {
                                        context.startForegroundService(
                                            Intent(context, MicCaptureService::class.java)
                                                .setAction(MicCaptureService.ACTION_START))
                                    }.onFailure { feedback = "麥克風啟動請求失敗：" + it.message }
                                }
                                "通話與媒體錄音啟動請求已送出；請檢查兩邊實際電平並回放確認"
                            }
                        } catch (e: Exception) {
                            "同期錄製啟動失敗：" + e.message
                        }
                    }
                } else {
                    context.startForegroundService(request)
                    feedback = "已送出媒體錄製請求，請檢查錄音狀態"
                }
            } catch (e: Exception) {
                combinedRequested = false
                feedback = "無法啟動 MediaProjection：" + e.message
            }
        } else {
            combinedRequested = false
            feedback = "已取消 Android 系統音訊擷取授權"
        }
    }

    LaunchedEffect(Unit) {
        if (retentionDays > 0) library.purgeOlderThan(retentionDays)
        while (true) {
            daemonReady = daemon.ping()
            if (daemonReady && restartNotice) {
                prefs.edit().putBoolean("needs_recovery", false).apply()
                restartNotice = false
            }
            if (daemonReady) {
                val status = runCatching { daemon.status() }.getOrDefault("")
                callLevel = Regex("LEVEL (\\d+)").find(status)?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()?.coerceIn(0, 100) ?: 0
                callElapsedMs = Regex("ELAPSED_MS (\\d+)").find(status)?.groupValues?.getOrNull(1)
                    ?.toLongOrNull() ?: 0L
            } else {
                callLevel = 0
                callElapsedMs = 0L
            }
            micActive = MicCaptureService.active
            micLevel = MicCaptureService.level
            micState = MicCaptureService.status
            mediaActive = PlaybackCaptureService.active
            mediaLevel = PlaybackCaptureService.liveLevel
            mediaElapsedMs = if (mediaActive) android.os.SystemClock.elapsedRealtime() -
                PlaybackCaptureService.startedElapsedMs else 0L
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("首頁", "設定", "錄音", "錄音庫", "相容性", "隱私").forEach { tab ->
                OutlinedButton(onClick = { activeTab = tab },
                    colors = if (activeTab == tab) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) else ButtonDefaults.outlinedButtonColors()) { Text(tab) }
            }
        }
        if (activeTab == "首頁") {
        RecordSection("狀態與操作結果") {
            if (restartNotice) Text("偵測到手機重新啟動：通話錄音核心尚待驗證恢復", color = MaterialTheme.colorScheme.error)
            Text("通話音訊程序：" + if (daemonReady) "IPC 連線正常（音源尚須實機驗證）" else "尚未就緒")
            Text("通話監控：" + if (monitorActive) "已開啟" else "未啟用")
            Text("媒體錄音：" + if (mediaActive) "進行中" else "未錄製")
            Text(feedback)
        }

        Text("選擇「設定」完成 ADB 配對；「錄音」啟用通話／媒體；「錄音庫」管理檔案。")
        }
        if (activeTab == "設定") {
        RecordSection("1. 權限、告知與診斷") {
            Row {
                Checkbox(checked = consent, onCheckedChange = {
                    consent = it
                    prefs.edit().putBoolean("consent", it)
                        .putLong("consentAtEpochMs", if (it) System.currentTimeMillis() else 0L)
                        .apply()
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
            OutlinedButton(onClick = {
                authenticateLibrary {
                    biometricLock = !biometricLock
                    prefs.edit().putBoolean("biometricLock", biometricLock).apply()
                    activity.recordingLibraryUnlocked.value = true
                    feedback = if (biometricLock) "已開啟錄音庫身分鎖定" else "已關閉錄音庫身分鎖定"
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (biometricLock) "關閉錄音庫生物辨識／裝置密碼鎖" else "啟用錄音庫生物辨識／裝置密碼鎖")
            }
            val consentTimestamp = prefs.getLong("consentAtEpochMs", 0L)
            Text("本機告知確認時間：" +
                (if (consentTimestamp == 0L) "未確認" else
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(consentTimestamp))),
                style = MaterialTheme.typography.bodySmall)
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
                    scope.launch {
                        try {
                            val pending = daemon.pending()
                            var recovered = 0
                            for (id in pending) {
                                daemon.fetch(id)
                                recovered++
                            }
                            recordings = library.list()
                            feedback = "已恢復 " + recovered + " 筆尚未收取的通話音檔"
                        } catch (e: Exception) {
                            feedback = "恢復尚未收取音檔失敗：" + e.message
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("檢查／收取尚未儲存的錄音片段") }
                OutlinedButton(onClick = {
                    scope.launch { feedback = daemon.shutdown(); daemonReady = daemon.ping() }
                }, modifier = Modifier.fillMaxWidth()) { Text("停止通話錄音核心") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = autoRestore, onCheckedChange = { enabled ->
                    if (!consent) {
                        feedback = "請先閱讀並勾選錄音合規聲明"
                    } else {
                        autoRestore = enabled
                        RecoveryScheduler.setEnabled(context, enabled)
                        feedback = if (enabled) "已啟用 WorkManager 背景恢復（非即時，須 Wi-Fi／系統允許）" else "已關閉背景自動恢復"
                    }
                })
                Text("允許背景排程嘗試恢復錄音核心", modifier = Modifier.padding(start = 8.dp))
            }
            Text(prefs.getString("lastRecovery", "尚未有背景恢復紀錄") ?: "", style = MaterialTheme.typography.bodySmall)
            Text("背景排程通常最快 15 分鐘，不保證重新開機後立即錄音；系統或廠牌可能限制此功能。",
                style = MaterialTheme.typography.bodySmall)
            Text("若裝置無法提供 VOICE_CALL 音源，會顯示失敗，而不使用喇叭外放再收音。",
                style = MaterialTheme.typography.bodySmall)
        }

        }
        if (activeTab == "錄音") {
        RecordSection("3. 電信通話錄音") {
            Text(callState)
            Text("通話音源實際輸入電平：" + callLevel + " / 100 · 錄製 " + callElapsedMs / 1000 + " 秒")
            LinearProgressIndicator(progress = { callLevel / 100f }, modifier = Modifier.fillMaxWidth())
            Text("現有空間：" + (context.filesDir.usableSpace / 1048576) + " MiB")
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
            Text("手動測試音源（依裝置權限可用，非聲音成功保證）")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("CALL_MIX" to "雙方混合", "CALL_TX" to "自己的方向", "CALL_RX" to "對方方向")
                    .forEach { (source, name) ->
                        OutlinedButton(enabled = !monitorActive && manualId == null, onClick = {
                            selectedSource = source
                            feedback = "已選擇 $name，開始錄製時會嘗試使用此音源"
                        }, colors = if (selectedSource == source) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) else ButtonDefaults.outlinedButtonColors()) { Text(name) }
                    }
            }
            Text("手動測試（僅限與知情同意的對象通話）")
            Button(enabled = consent && daemonReady && !monitorActive && manualId == null, onClick = {
                scope.launch {
                    try {
                        daemon.selectSource(selectedSource)
                        val answer = daemon.start()
                        feedback = answer
                        if (answer.startsWith("CAPTURING ")) manualId = answer.removePrefix("CAPTURING ").trim()
                    } catch (e: Exception) { feedback = "通話錄製失敗：" + e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("手動開始通話錄製") }
            OutlinedButton(
                enabled = consent && daemonReady && !monitorActive && manualId == null,
                onClick = {
                    scope.launch {
                        feedback = try {
                            val result = daemon.startDual()
                            if (result.startsWith("CAPTURING_DUAL ")) {
                                manualId = "DUAL"
                                "正在嘗試同時錄製上行與下行；僅能透過回放證明有效"
                            } else result
                        } catch (error: Exception) { "雙音源錄製無法啟動：" + error.message }
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("實驗：同時開始 TX／RX（不支援即報錯）") }
            OutlinedButton(enabled = manualId != null, onClick = {
                scope.launch {
                    val id = manualId ?: return@launch
                    try {
                        val stopped = if (id == "DUAL") daemon.stopDual() else daemon.stop()
                        feedback = stopped
                        if (stopped.startsWith("STOPPED ") || stopped.startsWith("STOPPED_DUAL ")) {
                            var saved = 0
                            for (pendingId in daemon.pending()) {
                                daemon.fetch(pendingId)
                                saved++
                            }
                            feedback = "已保存 " + saved + " 個 WAV 片段；雙向分離需驗證聽到的內容"
                            manualId = null
                            recordings = library.list()
                        }
                    } catch (e: Exception) { feedback = "停止／保存失敗：" + e.message }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("停止並保存通話 WAV") }
        }

        RecordSection("手機同期多來源錄音（試驗）") {
            Text("依序要求電信通話音源及 Android 系統授權的媒體音訊。選用的麥克風會作為獨立音軌，不是對方通話聲音。")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = includeMicWithCombined, onCheckedChange = { includeMicWithCombined = it })
                Text("也錄製獨立麥克風音軌（通話中可能被系統靜音）")
            }
            Button(
                enabled = consent && daemonReady && !monitorActive && manualId == null && !mediaActive,
                onClick = {
                    combinedRequested = true
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("同時錄製通話＋音樂／遊戲") }
            OutlinedButton(enabled = manualId != null || mediaActive || micActive, onClick = {
                scope.launch {
                    val mode = manualId
                    if (mode != null) {
                        feedback = try {
                            val answer = if (mode == "DUAL") daemon.stopDual() else daemon.stop()
                            var saved = 0
                            if (answer.startsWith("STOPPED")) {
                                for (session in daemon.pending()) { daemon.fetch(session); saved++ }
                                manualId = null
                            }
                            "通話錄音停止：" + answer + "；收取 " + saved + " 個音檔"
                        } catch (e: Exception) { "通話停止或儲存失敗：" + e.message }
                    }
                    if (mediaActive) context.startService(Intent(context, PlaybackCaptureService::class.java)
                        .setAction(PlaybackCaptureService.ACTION_STOP))
                    if (micActive) context.startService(Intent(context, MicCaptureService::class.java)
                        .setAction(MicCaptureService.ACTION_STOP))
                    recordings = library.list()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("停止並保存所有已啟動的音軌") }
            Text("每個來源會分別呈現電平和錄製結果。通話中 Android 可能阻擋其他音源，不能將按鈕成功視作全部聲音成功。",
                style = MaterialTheme.typography.bodySmall)
        }

        RecordSection("4. 音樂、遊戲、App 內部聲音") {
            Text(mediaState)
            Text("媒體實際輸入電平：" + mediaLevel + " / 100 · 錄製 " + mediaElapsedMs / 1000 + " 秒")
            LinearProgressIndicator(progress = { mediaLevel / 100f }, modifier = Modifier.fillMaxWidth())
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
            OutlinedButton(onClick = {
                feedback = if (TestTonePlayer.play()) "正在播放 8 秒 440/880 Hz 測試音" else
                    "測試音已在播放中"
            }, modifier = Modifier.fillMaxWidth()) { Text("播放 440／880 Hz 音源驗證訊號") }
            OutlinedButton(onClick = { TestTonePlayer.stop(); feedback = "已停止測試音" },
                modifier = Modifier.fillMaxWidth()) { Text("停止測試音") }
            Text("僅錄製允許 AudioPlaybackCapture 的媒體／遊戲。通話與媒體同時錄製目前僅實驗性，不保證所有機型可用。",
                style = MaterialTheme.typography.bodySmall)
        }
        RecordSection("獨立麥克風音軌（實驗）") {
            Text("僅在你手動啟動時錄製。通話中 Android 可能讓麥克風音源靜音；本功能無法證明錄到通話對方。")
            Text(micState)
            Text("麥克風輸入電平：" + micLevel + " / 100")
            LinearProgressIndicator(progress = { micLevel / 100f }, modifier = Modifier.fillMaxWidth())
            Button(enabled = consent && !micActive, onClick = {
                try {
                    context.startForegroundService(Intent(context, MicCaptureService::class.java)
                        .setAction(MicCaptureService.ACTION_START))
                    feedback = "已要求啟用獨立麥克風錄音"
                } catch (e: Exception) { feedback = "麥克風啟動失敗：" + e.message }
            }, modifier = Modifier.fillMaxWidth()) { Text("開始錄製麥克風音軌") }
            OutlinedButton(enabled = micActive, onClick = {
                context.startService(Intent(context, MicCaptureService::class.java)
                    .setAction(MicCaptureService.ACTION_STOP))
            }, modifier = Modifier.fillMaxWidth()) { Text("停止並保存麥克風音軌") }
        }

        }
        if (activeTab == "設定") {
        RecordSection("5. 通訊 App 語音通話偵測（實驗）") {
            Text("可辨識 LINE、WhatsApp、Telegram、Signal、Messenger、Discord、Meet 等 App 的通話類別通知；不讀取訊息及聯絡人。通知辨識不等於可以錄到對方聲音。")
            Text("裝置是否允許擷取 VoIP 雙向音訊仍需要實機測試。")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = voipEnabled, onCheckedChange = {
                    if (!consent) {
                        feedback = "請先閱讀並勾選錄音合規聲明"
                    } else {
                        voipEnabled = it
                        prefs.edit().putBoolean("voipEnabled", it).apply()
                        feedback = if (it) "已允許 VoIP 測試；還需系統通知存取授權" else "VoIP 測試已停用"
                    }
                })
                Text("允許嘗試自動偵測 VoIP 通話", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text("開啟 Android 通知存取設定") }
            Text(VoipCallNotificationListener.state, style = MaterialTheme.typography.bodySmall)
            Text("已列入來源：" + VoipSourceRegistry.packages.values.distinct().joinToString("、"), style = MaterialTheme.typography.bodySmall)
        }
        }
        if (activeTab == "錄音庫") {
        if (biometricLock && !activity.recordingLibraryUnlocked.value) {
            RecordSection("5. 錄音庫已鎖定") {
                Text("錄音檔名與內容受到裝置身分驗證保護。")
                Button(onClick = {
                    authenticateLibrary { activity.recordingLibraryUnlocked.value = true }
                }, modifier = Modifier.fillMaxWidth()) { Text("驗證並解鎖錄音庫") }
            }
        } else {
        RecordSection("5. 本機錄音檔（WAV / FLAC / ZIP）") {
            Text("錄音檔只在手機私有空間；分享必須由你手動操作。")
            Text("自動清理規則（預設保留全部）")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(0 to "永久", 7 to "7天", 30 to "30天", 90 to "90天").forEach { (days, label) ->
                    OutlinedButton(onClick = {
                        retentionDays = days
                        prefs.edit().putInt("retentionDays", days).apply()
                        feedback = if (days == 0) "已關閉自動清理" else "保留最近 " + days + " 天的錄音"
                    }, colors = if (retentionDays == days) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) else ButtonDefaults.outlinedButtonColors()) { Text(label) }
                }
            }
            OutlinedButton(onClick = {
                val count = library.purgeOlderThan(retentionDays)
                recordings = library.list()
                feedback = if (retentionDays == 0) "未啟用自動清理" else "已清除 " + count + " 個過期檔案"
            }, modifier = Modifier.fillMaxWidth()) { Text("立即執行保留期限清理") }
            Button(onClick = { recordings = library.list() }, modifier = Modifier.fillMaxWidth()) { Text("重新整理錄音列表") }
            OutlinedButton(onClick = {
                library.stopPlaying()
                feedback = "已停止本機錄音播放"
            }, modifier = Modifier.fillMaxWidth()) { Text("停止播放") }
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                label = { Text("搜尋檔名或音訊來源") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            val shown = recordings.filter { entry ->
                search.isBlank() || entry.file.name.contains(search, ignoreCase = true) ||
                    entry.details?.source?.contains(search, ignoreCase = true) == true
            }
            if (shown.isEmpty()) Text(if (search.isBlank()) "目前沒有錄音" else "沒有符合搜尋條件的錄音")
            shown.forEach { item ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(item.displayDate + " · " + (item.size / 1024) + " KiB", style = MaterialTheme.typography.bodySmall)
                    item.details?.let { info ->
                        Text(info.source + " · " + info.status + " · " + (info.durationMs / 1000) + " 秒 · " +
                            info.sampleRate + " Hz / " + info.channels + " 聲道",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (item.file.extension == "wav" && item.details?.source == "MEDIA_PLAYBACK") {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    feedback = try {
                                        val result = withContext(Dispatchers.IO) {
                                            TestToneWavVerifier.check(item.file)
                                        }
                                        "測試聲音：440Hz " + (if (result.tone440) "檢出" else "未檢出") +
                                            "；880Hz " + (if (result.tone880) "檢出" else "未檢出")
                                    } catch (error: Exception) { "訊號分析失敗：" + error.message }
                                }
                            }) { Text("測試音檢測") }
                        }
                        if (item.file.extension == "wav") {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    feedback = "正在分析 WAV 波形"
                                    waveformData = withContext(Dispatchers.IO) {
                                        WavWaveform.peaks(item.file, 96)
                                    }
                                    waveformFile = item.file.name
                                    feedback = "已完成 WAV 波形分析"
                                }
                            }) { Text("波形") }
                        }
                        if (item.file.extension == "enc") {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    feedback = try {
                                        val restored = withContext(Dispatchers.IO) { SecureVault.decrypt(item.file) }
                                        RecordingDatabase(context).duplicateMetadata(item.file.name, restored.name)
                                        library.remove(item.file)
                                        recordings = library.list()
                                        "錄音已解密，已還原：" + restored.name
                                    } catch (e: Exception) { "解密失敗：" + e.message }
                                }
                            }) { Text("解密") }
                        }
                        if (item.file.extension != "enc" && item.file.extension != "zip") {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    feedback = try {
                                        val encrypted = withContext(Dispatchers.IO) { SecureVault.encrypt(item.file) }
                                        RecordingDatabase(context).duplicateMetadata(item.file.name, encrypted.name)
                                        library.remove(item.file)
                                        recordings = library.list()
                                        "已加密並移除明文錄音：" + encrypted.name
                                    } catch (e: Exception) { "加密失敗：" + e.message }
                                }
                            }) { Text("加密") }
                        }
                        if (item.file.extension == "wav" && item.details?.source in SessionBundleExporter.sourceTypes) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    feedback = try {
                                        val exported = withContext(Dispatchers.IO) {
                                            SessionBundleExporter.export(item, library.list())
                                        }
                                        recordings = library.list()
                                        "同期來源已封存：" + exported.name + "；音源缺漏已列於 session.json"
                                    } catch (e: Exception) {
                                        "來源封存失敗：" + e.message
                                    }
                                }
                            }) { Text("封存同期所有音軌") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    feedback = "正在合併同期可用的錄音來源"
                                    feedback = try {
                                        val result = withContext(Dispatchers.IO) {
                                            SessionMixer.mix(SessionBundleExporter.plan(item, library.list()))
                                        }
                                        recordings = library.list()
                                        "已產出同期混音：" + result.name +
                                            "；無法擷取的來源不會被補造"
                                    } catch (e: Exception) {
                                        "多來源混音失敗：" + e.message
                                    }
                                }
                            }) { Text("同期音源合成 WAV") }
                        }
                        if (item.file.extension !in setOf("enc", "zip") && item.details?.source in setOf("CALL_MIX", "CALL_TX", "CALL_RX")) {
                            val pair = MultiTrackExporter.findBestPair(item, recordings)
                            if (pair != null) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        feedback = try {
                                            val dest = withContext(Dispatchers.IO) {
                                                MultiTrackExporter.export(pair)
                                            }
                                            recordings = library.list()
                                            "多音軌封存完成：" + dest.name + "（兩路各自保留）"
                                        } catch (e: Exception) { "多音軌封存失敗：" + e.message }
                                    }
                                }) { Text("匯出同期雙音軌") }
                                if (item.file.extension == "wav" && pair.media.file.extension == "wav") {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            feedback = "正在按實際擷取時間戳混音（不能修復來源缺音）"
                                            feedback = try {
                                                val file = withContext(Dispatchers.IO) {
                                                    SynchronizedMixer.mix(pair)
                                                }
                                                recordings = library.list()
                                                "混音完成：" + file.name + "（48kHz PCM；來源音質未經雙向驗收）"
                                            } catch (error: Exception) {
                                                "同步混音失敗：" + error.message
                                            }
                                        }
                                    }) { Text("混音成 WAV") }
                                }
                            }
                        }
                        if (item.file.extension != "zip" && item.file.extension != "enc") OutlinedButton(onClick = {
                            try { library.play(item.file); feedback = "播放：" + item.file.name }
                            catch (e: Exception) { feedback = "播放失敗：" + e.message }
                        }) { Text("播放") }
                        if (item.file.extension != "enc") OutlinedButton(onClick = {
                            try { library.share(item.file) }
                            catch (e: Exception) { feedback = "分享失敗：" + e.message }
                        }) { Text("分享") }
                        if (item.file.extension == "wav") {
                            for (codec in CompressedTranscoder.Codec.entries) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        feedback = "正在編碼 " + codec.extension.uppercase()
                                        feedback = try {
                                            val encoded = withContext(Dispatchers.IO) {
                                                CompressedTranscoder.convert(item.file, codec).also {
                                                    RecordingDatabase(context).duplicateMetadata(item.file.name, it.name)
                                                }
                                            }
                                            recordings = library.list()
                                            "已儲存 " + codec.extension + "：" + encoded.name + "（有損備用格式）"
                                        } catch (e: Exception) { "轉碼失敗：" + e.message }
                                    }
                                }) { Text("轉 " + codec.extension.uppercase()) }
                            }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    feedback = "正在將 PCM WAV 無損轉為 FLAC"
                                    feedback = try {
                                        val flac = withContext(Dispatchers.IO) {
                                            FlacTranscoder.convert(item.file).also {
                                                RecordingDatabase(context).duplicateMetadata(item.file.name, it.name)
                                            }
                                        }
                                        recordings = library.list()
                                        "已轉換 FLAC：" + flac.name
                                    } catch (e: Exception) { "FLAC 轉換失敗：" + e.message }
                                }
                            }) { Text("轉 FLAC") }
                        }
                        OutlinedButton(onClick = {
                            renameFile = item.file.name
                            renameDraft = item.file.name.substringBeforeLast('.').removeSuffix(".wav").removeSuffix(".flac")
                        }) { Text("重新命名") }
                        OutlinedButton(onClick = {
                            exportFile = item.file
                            documentExport.launch(item.file.name)
                        }) { Text("匯出") }
                        OutlinedButton(onClick = {
                            if (library.remove(item.file)) {
                                feedback = "已刪除：" + item.file.name
                                recordings = library.list()
                            }
                        }) { Text("刪除") }
                    }
                    if (renameFile == item.file.name) {
                        OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it },
                            label = { Text("新的檔案名稱（不含副檔名）") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                feedback = try {
                                    val result = library.rename(item.file, renameDraft)
                                    recordings = library.list()
                                    renameFile = null
                                    "重新命名完成：" + result.name
                                } catch (e: Exception) { "重新命名失敗：" + e.message }
                            }) { Text("儲存名稱") }
                            OutlinedButton(onClick = { renameFile = null }) { Text("取消") }
                        }
                    }
                    if (waveformFile == item.file.name && waveformData.isNotEmpty()) {
                        val waveColor = MaterialTheme.colorScheme.primary
                        Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                            val half = size.height / 2
                            val step = size.width / waveformData.size
                            waveformData.forEachIndexed { index, value ->
                                val rise = value.coerceIn(0f, 1f) * half
                                val x = (index + 0.5f) * step
                                drawLine(waveColor, Offset(x, half - rise), Offset(x, half + rise),
                                    strokeWidth = maxOf(1.5f, step * 0.52f))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        }
        }
        if (activeTab == "相容性") {
        RecordSection("通話雙方驗收紀錄（只在你實際回放後填寫）") {
            Text("請選一段實際電話錄音，聽清楚自己和對方輪流說的測試內容，再儲存結果；不會讀取或上傳通話內容。")
            val eligible = recordings.filter { it.details?.source in setOf("CALL_MIX","CALL_TX","CALL_RX") &&
                it.file.extension == "wav" && it.details?.sha256?.length == 64 }
            if (eligible.isEmpty()) {
                Text("尚無可驗收的本機通話 WAV 錄音")
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    eligible.take(20).forEach { recording ->
                        OutlinedButton(onClick = { trialFile = recording.file.name },
                            colors = if (trialFile == recording.file.name)
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ButtonDefaults.outlinedButtonColors()) {
                            Text(recording.file.name.take(19))
                        }
                    }
                }
                Text("目前選擇：" + if (trialFile.isBlank()) "尚未選擇" else trialFile)
            }
            Text("方向")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("OUTGOING" to "撥出", "INCOMING" to "接聽").forEach { (value, label) ->
                    OutlinedButton(onClick = { trialDirection = value },
                        colors = if (trialDirection == value)
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else ButtonDefaults.outlinedButtonColors()) { Text(label) }
                }
            }
            Text("音訊路由")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EARPIECE" to "聽筒", "SPEAKER" to "擴音",
                    "BLUETOOTH" to "藍牙", "OTHER" to "其他").forEach { (value, label) ->
                    OutlinedButton(onClick = { trialRoute = value },
                        colors = if (trialRoute == value)
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else ButtonDefaults.outlinedButtonColors()) { Text(label) }
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = trialSelfAudible, onCheckedChange = { trialSelfAudible = it })
                Text("我回放後確認聽見自己聲音")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = trialRemoteAudible, onCheckedChange = { trialRemoteAudible = it })
                Text("我回放後確認聽見對方聲音")
            }
            Button(enabled = trialFile.isNotBlank(), onClick = {
                feedback = try {
                    val source = eligible.first { it.file.name == trialFile }
                    val record = journal.add(source, trialDirection, trialRoute,
                        trialSelfAudible, trialRemoteAudible)
                    recordedTrials = journal.list()
                    trialFile = ""
                    trialSelfAudible = false
                    trialRemoteAudible = false
                    "已紀錄 " + record.source + "：" +
                        (if (record.twoPartyPass) "人工確認雙方有聲" else "單邊／不完整；不計成功")
                } catch (error: Exception) { "無法儲存驗收：" + error.message }
            }, modifier = Modifier.fillMaxWidth()) { Text("保存這次人工驗收") }
            val summary = journal.summary()
            Text("本 Android Build：有檔案佐證的獨立測試 " + summary.attempts +
                " 通；人工確認雙方有聲 " + summary.bothAudible +
                " 通；來電 " + summary.incoming + "／去電 " + summary.outgoing)
            Text(if (summary.meetsSelfReportedBaseline)
                "已達人工回報 10 通至少 9 通雙方有聲的最低數字門檻；仍須獨立測試確認。"
                else "尚未達 10 通至少 9 通雙方有聲的最低數字門檻。")
            Text("僅限目前系統 Build；同一音檔的 SHA-256 不會重複計數。")
            OutlinedButton(onClick = {
                recordedTrials = journal.list()
            }, modifier = Modifier.fillMaxWidth()) { Text("重新整理驗收紀錄") }
        }
        RecordSection("6. 設備相容性與實際驗收紀錄") {
            Text("以下結果是使用者在實機回放後手動標註，沒有測試的項目預設 NOT_TESTED。")
            Text("Android 靜態能力診斷不等於數位通話雙方音源驗證。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompatibilityMatrix.FEATURES.forEach { feature ->
                    OutlinedButton(onClick = {
                        selectedFeature = feature
                        selectedOutcome = matrix.result(feature)
                    }, colors = if (selectedFeature == feature) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) else ButtonDefaults.outlinedButtonColors()) {
                        Text(feature)
                    }
                }
            }
            Text("測試項目：" + selectedFeature + "；目前紀錄：" + selectedOutcome.name)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompatibilityMatrix.Outcome.entries.forEach { outcome ->
                    OutlinedButton(onClick = {
                        selectedOutcome = outcome
                        matrix.record(selectedFeature, outcome)
                        feedback = "已儲存 " + selectedFeature + " 的使用者實測標註：" + outcome.name
                    }, colors = if (selectedOutcome == outcome) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ) else ButtonDefaults.outlinedButtonColors()) { Text(outcome.name) }
                }
            }
            OutlinedButton(onClick = {
                reportJson = journal.exportJson().toString(2)
                reportExport.launch("record-compatibility.json")
            }, modifier = Modifier.fillMaxWidth()) { Text("匯出匿名設備相容性診斷 JSON") }
            Text("不包含電話號碼、錄音檔、配對碼或 ADB token。", style = MaterialTheme.typography.bodySmall)
        }
        }
        if (activeTab == "隱私") {
        RecordSection("隱私、告知及錄音資料清除") {
            Text("音訊與索引只保存在手機。分享及匯出由你主動操作，完整說明請閱讀 GitHub PRIVACY.md。")
            OutlinedButton(
                enabled = !monitorActive && !mediaActive && manualId == null,
                onClick = { clearConfirmation = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("清除所有錄音檔") }
            Text("ADB 系統配對撤銷需要另外到 Android 開發人員選項操作。",
                style = MaterialTheme.typography.bodySmall)
            if (clearConfirmation) {
                AlertDialog(
                    onDismissRequest = { clearConfirmation = false },
                    title = { Text("確定刪除所有錄音？") },
                    text = { Text("將永久刪除本機 WAV、FLAC、M4A、OGG、加密檔與 ZIP 封存。此操作無法復原。") },
                    confirmButton = {
                        Button(onClick = {
                            clearConfirmation = false
                            scope.launch {
                                RecoveryScheduler.setEnabled(context, false)
                                autoRestore = false
                                prefs.edit().putBoolean("voipEnabled", false).apply()
                                voipEnabled = false
                                feedback = "正在刪除本機及可存取的 shell 錄音片段"
                                val shellResult = withContext(Dispatchers.IO) {
                                    if (daemon.ping()) runCatching {
                                        val removed = daemon.removeAllPending()
                                        daemon.shutdown()
                                        removed
                                    } else Result.failure(
                                        IllegalStateException("shell 錄音核心無法連線，無法確認其暫存已清除"))
                                }
                                val count = withContext(Dispatchers.IO) { library.deleteAll() }
                                recordings = library.list()
                                feedback = shellResult.fold(
                                    { removed -> "已刪除本機 " + count + " 個檔案，及 shell " + removed + " 個片段" },
                                    { err -> "已刪除本機 " + count + " 個檔案，但 shell 片段尚未確認清除：" + err.message }
                                )
                            }
                        }) { Text("永久刪除") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { clearConfirmation = false }) { Text("取消") }
                    }
                )
            }
        }
        }
        Text("相容性仍須以實體 Android 手機驗證。版本 0.1.0-dev，沒有雲端上傳或登入。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

