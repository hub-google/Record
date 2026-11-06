package com.hubgoogle.record.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.hubgoogle.record.MainActivity

/**
 * Android restricts starting microphone/mediaProjection foreground services from boot.
 * Do not attempt a hidden recording process here: remind the owner to restore the
 * shell recording service, whose ADB bootstrap may require Wi-Fi/user interaction.
 */
class BootRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val preferences = context.getSharedPreferences("record", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("consent", false)) return
        preferences.edit().putBoolean("needs_recovery", true).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("record.restore", "重新啟動恢復", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val launch = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(56, Notification.Builder(context, "record.restore")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Record 需恢復通話錄音服務")
            .setContentText(if (preferences.getBoolean("autoRestore", false))
                "已安排背景恢復檢查；尚未成功前不會顯示為就緒"
                else "裝置重新開機後，請開啟 Record 檢查 ADB 與錄音狀態")
            .setContentIntent(launch).setAutoCancel(true).build())
    }
}

