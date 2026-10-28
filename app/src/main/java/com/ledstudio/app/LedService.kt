package com.ledstudio.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder

@Suppress("DEPRECATION")
class LedService : Service() {
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Engine.init(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("led", "LED Studio", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "led").setContentTitle("LED Studio").setContentText("Lights are syncing")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
        val data = i?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        p.registerCallback(object : MediaProjection.Callback() {}, null)
        mp = p
        if (i.getStringExtra("mode") == "screen") {
            val dm = resources.displayMetrics
            val w = dm.widthPixels / 10; val h = dm.heightPixels / 10
            val rd = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd = p.createVirtualDisplay("led", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null)
            Engine.screen(rd)
        } else {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val rec = AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(16384).build()
            Engine.music(rec, if (i.getBooleanExtra("cover", true)) null else i.getIntExtra("color", Color.RED),
                i.getFloatExtra("sens", 1f), i.getIntExtra("style", 0))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { Engine.stop(); vd?.release(); mp?.stop(); super.onDestroy() }
}
