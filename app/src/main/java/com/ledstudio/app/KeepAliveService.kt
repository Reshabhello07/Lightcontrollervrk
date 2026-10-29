package com.ledstudio.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

// Keeps the app (and the strip connection) alive in the background, e.g. while you play Minecraft.
class KeepAliveService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Engine.init(applicationContext)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("keep", "Strip connection", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "keep").setContentTitle("LED Studio").setContentText("Connected to your strip")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(2, n)
        return START_STICKY
    }
}
