package com.hubgoogle.record.call

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hubgoogle.record.adb.AdbTransport
import com.hubgoogle.record.adb.CallDaemonClient
import com.hubgoogle.record.adb.ShellBootstrap
import java.util.concurrent.TimeUnit

/**
 * Opt-in, best-effort recovery when WorkManager is allowed to run and wireless
 * debugging is reachable. Android periodic tasks are NOT real-time and may be
 * delayed or suppressed by OEM battery optimization and Wi-Fi availability.
 */
class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("record", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("consent", false) || !prefs.getBoolean("autoRestore", false)) {
            return Result.success()
        }
        val daemon = CallDaemonClient(applicationContext)
        if (daemon.ping()) {
            prefs.edit().putString("lastRecovery", "服務仍在執行").apply()
            return Result.success()
        }
        return try {
            // Only local, authenticated ADB; no remote ADB hosts accepted.
            AdbTransport.autoConnect(applicationContext).getOrThrow()
            ShellBootstrap(applicationContext).startDaemon().getOrThrow()
            check(daemon.ping()) { "Daemon did not respond to authenticated probe" }
            prefs.edit().putBoolean("needs_recovery", false)
                .putString("lastRecovery", "背景復原成功").apply()
            Result.success()
        } catch (e: Exception) {
            prefs.edit().putBoolean("needs_recovery", true)
                .putString("lastRecovery", "背景復原未成功：" + (e.message ?: "未知錯誤")).apply()
            Result.retry()
        }
    }
}

object RecoveryScheduler {
    private const val UNIQUE_NAME = "record-owner-approved-recovery"
    fun setEnabled(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        context.getSharedPreferences("record", Context.MODE_PRIVATE)
            .edit().putBoolean("autoRestore", enabled).apply()
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<RecoveryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

