@file:OptIn(ExperimentalLayoutApi::class)

package com.ledstudio.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** All UI state that the tabs share. */
class Ctl(ctx: Context) {
    var hue by mutableStateOf(0f)
    var spd by mutableStateOf(1f)
    var sens by mutableStateOf(1f)
    var smooth by mutableStateOf(0.9f)
    var useCover by mutableStateOf(true)
    var pix by mutableStateOf(200f)
    var fxName by mutableStateOf("")
    var active by mutableStateOf("")          // which music mode button is lit
    var pending = "music"
    var style = 0
    val favs = mutableStateMapOf<Int, String>().apply { putAll(loadFavs(ctx)) }
    var go: (String, Int) -> Unit = { _, _ -> }
    fun playFx(ctx: Context, id: Int) { stopAll(ctx); active = ""; Engine.fx.value = id; Led.effect(id) }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val c = remember { Ctl(ctx) }
    var tab by remember { mutableStateOf(0) }
    var rootSize by remember { mutableStateOf(IntSize(1080, 2400)) }

    // glass accent follows the colour the strip is showing
    var live by remember { mutableStateOf(Color(0xFF8B7BFF)) }
    LaunchedEffect(Unit) {
        while (true) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(Led.cur, hsv)
            if (hsv[2] > 0.25f && hsv[1] > 0.25f)
                live = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], max(hsv[1], 0.6f), 1f)))
            delay(500)
        }
    }
    val accent by animateColorAsState(live, tween(900), label = "accent")

    LaunchedEffect(Unit) {   // auto-connect on open when permissions are already granted
        if (Build.VERSION.SDK_INT < 31 || (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) Led.connect(ctx)
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Led.connect(ctx) }
    val cap = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ctx.startForegroundService(Intent(ctx, LedService::class.java)
                .putExtra("code", r.resultCode).putExtra("data", r.data).putExtra("mode", c.pending).putExtra("style", c.style)
                .putExtra("cover", c.useCover).putExtra("color", Led.hsv(c.hue)).putExtra("sens", c.sens))
        }
    }
    c.go = { mode, st ->
        stopAll(ctx); c.pending = mode; c.style = st
        cap.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    CompositionLocalProvider(LocalAccent provides accent, LocalRoot provides rootSize) {
        Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
            Canvas(Modifier.fillMaxSize()) { drawBackdrop(size, accent) }
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 14.dp)) {
                Header(c, onConnect = { perm.launch(perms()) })
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f)) {
                    Crossfade(tab, label = "tab") { t ->
                        when (t) {
                            0 -> MusicTab(c)
                            1 -> ScenesTab(c)
                            2 -> SyncTab(c)
                            else -> SettingsTab(c, onConnect = { perm.launch(perms()) })
                        }
                    }
                }
                GlassTabBar(tab, { tab = it }, Modifier.navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun Header(c: Ctl, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    val st = Led.status.value
    val ok = st == "Connected"
    GlassCard(strong = true, pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LED Studio", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF4ADE80) else Color(0xFFFBBF24)))
                    Spacer(Modifier.width(7.dp))
                    Text(st, fontSize = 13.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            GlassButton("Connect", primary = true, onClick = onConnect)
            Spacer(Modifier.width(8.dp))
            GlassButton("Off") { stopAll(ctx); c.active = ""; Led.fadeTo(android.graphics.Color.BLACK, 400, true) }
        }
        SliderRow("Brightness", "${(Led.bright.value * 100).roundToInt()}%", Led.bright.value * 100f, 5f..Led.MAX_PCT.toFloat(),
            onChange = { Led.bright.value = it.roundToInt() / 100f }, step = 1f)
    }
}

@Composable
private fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MusicTab(c: Ctl) {
    val ctx = LocalContext.current
    TabColumn {
        GlassCard {
            Label("Music · phone audio, no mic")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Breathing", primary = c.active == "m0") { c.active = "m0"; c.go("music", 0) }
                GlassButton("Spectrum", primary = c.active == "m1") { c.active = "m1"; c.go("music", 1) }
                GlassButton("Smart tempo", primary = c.active == "m2") { c.active = "m2"; c.go("music", 2) }
                GlassButton("Beat drop", primary = c.active == "m3") { c.active = "m3"; c.go("music", 3) }
                GlassButton("Cover mesh", primary = c.active == "cover") { stopAll(ctx); c.active = "cover"; Engine.cover() }
            }
            if (Engine.bpm.value > 0) Body("♪  ${Engine.bpm.value} BPM", 0.7f)
        }
        GlassCard {
            Label("Tuning")
            SliderRow("Sensitivity", "%.1f×".format(c.sens), c.sens, 0.3f..3f, onChange = { c.sens = it })
            SliderRow("Smoothness", "${((c.smooth - 0.75f) / 0.22f * 100).roundToInt()}%", c.smooth, 0.75f..0.97f,
                onChange = { c.smooth = it; Engine.decay = it })
            SwitchRow("Use album cover colors", c.useCover) { c.useCover = it }
        }
    }
}

@Composable
private fun ScenesTab(c: Ctl) {
    val ctx = LocalContext.current
    TabColumn {
        GlassCard {
            Label("Color")
            SliderRow("Hue", "${c.hue.roundToInt()}°", c.hue, 0f..360f, rainbow = true,
                onChange = { c.hue = it; stopAll(ctx); c.active = ""; Led.fadeTo(Led.hsv(it), 120) })
            SliderRow("Effect speed", "%.2f×".format(c.spd), c.spd, 0.25f..4f, onChange = { c.spd = it; Engine.speed = it })
        }
        GlassCard {
            Label("Scenes")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Thunderstorm" to "thunder", "Candle" to "candle", "Aurora" to "aurora", "Ocean" to "ocean", "Lava" to "lava").forEach { (n, k) ->
                    GlassButton(n) { stopAll(ctx); c.active = ""; Engine.scene(k) }
                }
            }
        }
        GlassCard {
            Label("Built-in effects")
            val id = Engine.fx.value
            Text("Effect #$id" + (c.favs[id]?.let { " · $it" } ?: ""), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            GlassSlider(id.toFloat(), { Engine.fx.value = it.roundToInt() }, 1f..210f, step = 1f, onFinish = { c.playFx(ctx, Engine.fx.value) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Prev") { c.playFx(ctx, (Engine.fx.value - 2).mod(210) + 1) }
                GlassButton("Next") { c.playFx(ctx, Engine.fx.value.mod(210) + 1) }
                GlassButton("Auto-browse") { stopAll(ctx); c.active = ""; Engine.cycle(1, 210, 4) }
            }
            GlassField(c.fxName, { c.fxName = it }, "Name this effect")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Save favorite", primary = true) {
                    c.favs[Engine.fx.value] = c.fxName.ifBlank { "Effect ${Engine.fx.value}" }; saveFavs(ctx, c.favs); c.fxName = ""
                }
                if (c.favs.containsKey(Engine.fx.value)) GlassButton("Remove") { c.favs.remove(Engine.fx.value); saveFavs(ctx, c.favs) }
            }
            c.favs.toSortedMap().forEach { (fid, n) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Body("$n  (#$fid)", 0.9f)
                    Spacer(Modifier.weight(1f))
                    GlassButton("Play") { c.playFx(ctx, fid) }
                }
            }
        }
        GlassCard {
            Label("Effect presets")
            PRESETS.forEach { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Body(e.name, 0.9f)
                    Spacer(Modifier.weight(1f))
                    GlassButton("Play") { stopAll(ctx); c.active = ""; Engine.play(e) }
                }
            }
        }
    }
}

@Composable
private fun SyncTab(c: Ctl) {
    val ctx = LocalContext.current
    TabColumn {
        GlassCard {
            Label("Screen sync")
            Body("Mirrors the colors on your screen onto the strip.", 0.65f)
            GlassButton("Turn on screen sync", primary = c.active == "screen") { c.active = "screen"; c.go("screen", 0) }
        }
        GlassCard {
            Label("Notifications")
            SwitchRow("Pulse on notifications", Engine.notifOn.value) { Engine.notifOn.value = it }
            GlassButton("Grant notification access") { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        GlassCard {
            Label("Phone events")
            SwitchRow("Call ring (own color per contact)", Extras.callOn.value) { Extras.callOn.value = it }
            SwitchRow("Battery alerts", Extras.battOn.value) { Extras.battOn.value = it }
            GlassButton("Show battery level") { Extras.showBattery(ctx) }
        }
        GlassCard {
            Label("Minecraft sync")
            Body(Extras.mcStatus.value, 0.7f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Start", primary = true) { stopAll(ctx); c.active = ""; Extras.mcStart() }
                GlassButton("Stop") { Extras.mcStop() }
            }
        }
    }
}

@Composable
private fun SettingsTab(c: Ctl, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    TabColumn {
        GlassCard {
            Label("Connection")
            Body(Led.status.value, 0.85f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Connect", primary = true, onClick = onConnect)
                GlassButton("Disconnect") { stopAll(ctx); c.active = ""; Led.disconnect() }
            }
        }
        GlassCard {
            Label("LED strip")
            SliderRow("Pixel count", "${c.pix.roundToInt()}", c.pix, 1f..255f, step = 1f, onChange = { c.pix = it })
            GlassButton("Apply pixel count") { Led.setPixels(c.pix.roundToInt()) }
        }
        GlassCard {
            Label("Quick settings tile")
            Body("Swipe down, tap the pencil, and drag “LED Smart Tempo” into your tiles. Tap once to start, tap again to turn off and disconnect.", 0.7f)
        }
    }
}

@Composable
private fun GlassField(value: String, onChange: (String) -> Unit, hint: String) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(50)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
        cursorBrush = SolidColor(accent),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.08f))), shape)
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(hint, fontSize = 15.sp, color = Color.White.copy(alpha = 0.40f))
                inner()
            }
        }
    )
}
