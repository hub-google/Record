package com.hubgoogle.record.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.media.AudioRecord
import android.media.AudioFormat

data class DeviceDiagnostics(
    val device: String,
    val androidVersion: String,
    val phoneGranted: Boolean,
    val audioGranted: Boolean,
    val wirelessDebugging: Boolean?,
    val freeSpaceMiB: Long,
    val mediaBufferBytes: Int
) {
    fun report(): String = buildString {
        appendLine("設備：" + device + " / Android " + androidVersion)
        appendLine("通話狀態權限：" + if (phoneGranted) "已授予" else "未授予")
        appendLine("音訊擷取權限：" + if (audioGranted) "已授予" else "未授予")
        appendLine("無線偵錯設定：" + when (wirelessDebugging) { true -> "已開啟"; false -> "未開啟"; null -> "一般 App 無法讀取" })
        appendLine("本機空間：" + freeSpaceMiB + " MiB")
        appendLine("媒體錄音 buffer：" + mediaBufferBytes)
        appendLine("注意：所有靜態項目正常，不代表已驗證通話雙向錄音或通話＋媒體並行。")
    }
}
object DeviceProbe {
    fun inspect(context: Context): DeviceDiagnostics {
        val wireless = try {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        } catch (_: SecurityException) { null }
        return DeviceDiagnostics(
            device = Build.MANUFACTURER + " " + Build.MODEL,
            androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            phoneGranted = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
            audioGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            wirelessDebugging = wireless,
            freeSpaceMiB = context.filesDir.usableSpace / (1024 * 1024),
            mediaBufferBytes = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        )
    }
}

