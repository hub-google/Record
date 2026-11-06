package com.hubgoogle.record.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Pair to the owner's own phone, never a remote host.
 * ADB remains a privileged developer feature and must be explicitly enabled by the owner.
 */
class ShellBootstrap(private val context: Context) {
    suspend fun pair(pairPort: Int, pairingCode: String): Result<Unit> {
        if (pairPort !in 1024..65535 || !Regex("[0-9]{6}").matches(pairingCode)) {
            return Result.failure(IllegalArgumentException("Use the 6-digit pairing code and pairing port"))
        }
        return AdbTransport.pair(context, AdbTransport.LOOPBACK, pairPort, pairingCode)
    }
    suspend fun connect(connectPort: Int): Result<Unit> {
        if (connectPort !in 1024..65535) return Result.failure(IllegalArgumentException("Invalid wireless debugging port"))
        return AdbTransport.connect(context, AdbTransport.LOOPBACK, connectPort)
    }
    suspend fun startDaemon(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // App APK is dex-containing, usable as CLASSPATH by app_process on supported devices.
            // If the OEM prevents shell from reading this APK, report the compatibility failure.
            val apk = context.applicationInfo.sourceDir
            check(apk.matches(Regex("[A-Za-z0-9_./+=-]+"))) { "Unexpected APK path" }
            val token = CallDaemonClient(context).token()
            val command = "CLASSPATH=" + apk +
                " setsid nohup app_process /system/bin com.hubgoogle.record.shell.ShellCaptureMain " +
                CallDaemonClient.PORT + " " + token +
                " </dev/null >/data/local/tmp/record_shell.log 2>&1 &"
            val output = AdbTransport.exec(command).getOrThrow()
            // ADB can report launch success before the process has bound its socket.
            var connected = false
            for (attempt in 0 until 15) {
                kotlinx.coroutines.delay(250)
                if (CallDaemonClient(context).ping()) { connected = true; break }
            }
            check(connected) { "Shell daemon did not start. Check /data/local/tmp/record_shell.log via ADB." }
            output.ifBlank { "Recorder daemon running" }
        }
    }
}

