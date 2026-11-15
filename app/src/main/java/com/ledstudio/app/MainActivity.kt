package com.ledstudio.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import com.wexpa.liquidglass.LiquidGlassTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // Ask Android for the fastest mode at the current panel resolution. The OS may still
        // lower the rate for power, thermal, or user display settings.
        val display = windowManager.defaultDisplay
        val currentMode = display.mode
        val fastestMode = display.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .maxByOrNull { it.refreshRate }
        if (fastestMode != null) {
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = fastestMode.modeId
                preferredRefreshRate = fastestMode.refreshRate
            }
        }
        Engine.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LiquidGlassTheme(background = Color(0xFF05060B)) {
                    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) { App() }
                }
            }
        }
    }
}

fun perms(): Array<String> {
    val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 31) { l += Manifest.permission.BLUETOOTH_SCAN; l += Manifest.permission.BLUETOOTH_CONNECT }
    else l += Manifest.permission.ACCESS_FINE_LOCATION
    if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
    return l.toTypedArray()
}

fun loadFavs(c: Context): Map<Int, String> {
    val o = JSONObject(c.getSharedPreferences("fx", 0).getString("f", "{}") ?: "{}")
    return o.keys().asSequence().associate { it.toInt() to o.getString(it) }
}
fun savePixelCount(c: Context, pixels: Int) {
    c.getSharedPreferences("led", 0).edit().putInt("pixels", pixels.coerceIn(1, 255)).apply()
}
fun saveFavs(c: Context, m: Map<Int, String>) {
    val o = JSONObject(); m.forEach { (k, v) -> o.put(k.toString(), v) }
    c.getSharedPreferences("fx", 0).edit().putString("f", o.toString()).apply()
}

fun stopAll(ctx: Context, afterglow: Boolean = true) { Engine.stop(afterglow); ctx.stopService(Intent(ctx, LedService::class.java)) }
