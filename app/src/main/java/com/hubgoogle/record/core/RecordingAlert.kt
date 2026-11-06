package com.hubgoogle.record.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager

/** Visible, privacy-preserving capture failures; no caller IDs or audio snippets. */
object RecordingAlert {
    fun show(context: Context, title: String, message: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("record.error", "錄音異常通知", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.notify(58, Notification.Builder(context, "record.error")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title.take(48))
            .setContentText(message.take(120))
            .setAutoCancel(true).build())
    }
}

