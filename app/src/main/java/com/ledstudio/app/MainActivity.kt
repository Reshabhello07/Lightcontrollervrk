package com.ledstudio.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Engine.init(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
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
fun saveFavs(c: Context, m: Map<Int, String>) {
    val o = JSONObject(); m.forEach { (k, v) -> o.put(k.toString(), v) }
    c.getSharedPreferences("fx", 0).edit().putString("f", o.toString()).apply()
}

fun stopAll(ctx: Context) { Engine.stop(); ctx.stopService(Intent(ctx, LedService::class.java)) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var hue by remember { mutableStateOf(0f) }
    var spd by remember { mutableStateOf(1f) }
    var sens by remember { mutableStateOf(1f) }
    var smooth by remember { mutableStateOf(0.9f) }
    var useCover by remember { mutableStateOf(true) }
    var pending by remember { mutableStateOf("music") }
    var style by remember { mutableStateOf(0) }
    var pix by remember { mutableStateOf(200f) }
    var fxName by remember { mutableStateOf("") }
    val favs = remember { mutableStateMapOf<Int, String>().apply { putAll(loadFavs(ctx)) } }
    fun playFx(id: Int) { stopAll(ctx); Engine.fx.value = id; Led.effect(id) }
    LaunchedEffect(Unit) {   // auto-connect on open when permissions are already granted
        if (Build.VERSION.SDK_INT < 31 || (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) Led.connect(ctx)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Led.connect(ctx) }
    val cap = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ctx.startForegroundService(Intent(ctx, LedService::class.java)
                .putExtra("code", r.resultCode).putExtra("data", r.data).putExtra("mode", pending).putExtra("style", style)
                .putExtra("cover", useCover).putExtra("color", Led.hsv(hue)).putExtra("sens", sens))
        }
    }
    fun go(mode: String, st: Int = 0) {
        stopAll(ctx); pending = mode; style = st
        cap.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LED Studio", style = MaterialTheme.typography.headlineMedium)
        Text(Led.status.value)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { perm.launch(perms()) }) { Text("Connect") }
            OutlinedButton(onClick = { stopAll(ctx); Led.fadeTo(Color.BLACK, 400, true) }) { Text("Off") }
        }
        Text("Brightness: ${(Led.bright.value * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(value = Led.bright.value * 100f, onValueChange = { Led.bright.value = it.roundToInt() / 100f },
            valueRange = 5f..Led.MAX_PCT.toFloat(), steps = Led.MAX_PCT - 6)
        Text("Speed")
        Slider(value = spd, onValueChange = { spd = it; Engine.speed = it }, valueRange = 0.25f..4f)
        Text("Color")
        Slider(value = hue, onValueChange = { hue = it; stopAll(ctx); Led.fadeTo(Led.hsv(it), 120) }, valueRange = 0f..360f)

        HorizontalDivider()
        Text("Music (phone audio, no mic)", style = MaterialTheme.typography.titleMedium)
        Text("Sensitivity")
        Slider(value = sens, onValueChange = { sens = it }, valueRange = 0.3f..3f)
        Text("Smoothness")
        Slider(value = smooth, onValueChange = { smooth = it; Engine.decay = it }, valueRange = 0.75f..0.97f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useCover, onCheckedChange = { useCover = it }); Spacer(Modifier.width(8.dp)); Text("Use album cover colors")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { go("music", 0) }) { Text("Breathing") }
            Button(onClick = { go("music", 2) }) { Text("Smart tempo") }
            Button(onClick = { go("music", 3) }) { Text("Beat drop") }
        }
        if (Engine.bpm.value > 0) Text("Tempo: ${Engine.bpm.value} BPM")

        HorizontalDivider()
        Text("Screen sync", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { go("screen") }) { Text("Turn on screen sync") }

        HorizontalDivider()
        Text("Scenes", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Thunderstorm" to "thunder", "Candle" to "candle", "Aurora" to "aurora", "Ocean" to "ocean", "Lava" to "lava").forEach { (n, k) ->
                Button(onClick = { stopAll(ctx); Engine.scene(k) }) { Text(n) }
            }
        }

        HorizontalDivider()
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = Engine.notifOn.value, onCheckedChange = { Engine.notifOn.value = it }); Spacer(Modifier.width(8.dp)); Text("Pulse on notifications")
        }
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Grant notification access") }

        HorizontalDivider()
        Text("Phone events", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = Extras.battOn.value, onCheckedChange = { Extras.battOn.value = it }); Spacer(Modifier.width(8.dp)); Text("Battery alerts")
        }
        Button(onClick = { Extras.showBattery(ctx) }) { Text("Show battery level") }

        HorizontalDivider()
        Text("Built-in effects", style = MaterialTheme.typography.titleMedium)
        Text("Effect #${Engine.fx.value}" + (favs[Engine.fx.value]?.let { " - $it" } ?: ""))
        Slider(value = Engine.fx.value.toFloat(), onValueChange = { Engine.fx.value = it.toInt() },
            onValueChangeFinished = { playFx(Engine.fx.value) }, valueRange = 1f..210f)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { playFx((Engine.fx.value - 2).mod(210) + 1) }) { Text("Prev") }
            Button(onClick = { playFx(Engine.fx.value.mod(210) + 1) }) { Text("Next") }
            OutlinedButton(onClick = { stopAll(ctx); Engine.cycle(1, 210, 4) }) { Text("Auto-browse") }
        }
        OutlinedTextField(value = fxName, onValueChange = { fxName = it }, label = { Text("Name this effect") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { favs[Engine.fx.value] = fxName.ifBlank { "Effect ${Engine.fx.value}" }; saveFavs(ctx, favs); fxName = "" }) { Text("Save favorite") }
            if (favs.containsKey(Engine.fx.value)) TextButton(onClick = { favs.remove(Engine.fx.value); saveFavs(ctx, favs) }) { Text("Remove") }
        }
        favs.toSortedMap().forEach { (id, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$n (#$id)", Modifier.weight(1f))
                Button(onClick = { playFx(id) }) { Text("Play") }
            }
        }

        HorizontalDivider()
        Text("Pixel count: ${pix.toInt()}", style = MaterialTheme.typography.titleMedium)
        Slider(value = pix, onValueChange = { pix = it }, valueRange = 1f..255f)
        Button(onClick = { Led.setPixels(pix.toInt()) }) { Text("Apply pixel count") }

        HorizontalDivider()
        Text("Effects", style = MaterialTheme.typography.titleMedium)
        PRESETS.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, Modifier.weight(1f))
                Button(onClick = { stopAll(ctx); Engine.play(e) }) { Text("Play") }
            }
        }
    }
}
