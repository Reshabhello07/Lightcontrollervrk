package com.ledstudio.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

// Invisible helper opened by the tiles: asks for the screen/audio capture permission, then starts the chosen music light (style 0 = breathing, 2 = smart tempo).
class TileActivity : ComponentActivity() {
    private val cap = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            startForegroundService(Intent(this, LedService::class.java)
                .putExtra("code", r.resultCode).putExtra("data", r.data)
                .putExtra("mode", "music").putExtra("style", intent.getIntExtra("style", 2)).putExtra("cover", true)
                .putExtra("color", Led.hsv(0f)).putExtra("sens", 1f))
        }
        finish()
    }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) go() else finish()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Engine.init(applicationContext)
        if (perms().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) perm.launch(perms()) else go()
    }

    private fun go() {
        val s = Led.status.value
        val busy = s == "Connected" || s.startsWith("Scanning") || s.startsWith("Connecting") || s.startsWith("Discovering") || s.startsWith("Trying")
        if (!busy) Led.connect(applicationContext)
        cap.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
