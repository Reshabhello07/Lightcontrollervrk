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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.roundToInt

/** All UI state that the tabs share. */
class Ctl(ctx: Context) {
    var hue by mutableStateOf(0f)
    var spd by mutableStateOf(1f)
    var sens by mutableStateOf(1f)
    var smooth by mutableStateOf(0.9f)
    var useCover by mutableStateOf(true)
    var pix by mutableStateOf(ctx.getSharedPreferences("led", 0).getInt("pixels", 200).toFloat())
    var fxName by mutableStateOf("")
    var active by mutableStateOf("")          // which music mode button is lit
    var pending = "music"
    var style = 0
    val favs = mutableStateMapOf<Int, String>().apply { putAll(loadFavs(ctx)) }
    var go: (String, Int) -> Unit = { _, _ -> }
    fun playFx(ctx: Context, id: Int) { stopAll(ctx, afterglow = false); active = ""; Engine.fx.value = id; Led.effect(id) }
}

@Composable
fun App(initialTab: Int = 0, autoStartSmartTempo: Boolean = false) {
    val ctx = LocalContext.current
    val c = remember { Ctl(ctx) }
    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 3)) }
    var previousTab by remember { mutableIntStateOf(tab) }
    var displayedTab by remember { mutableIntStateOf(tab) }
    val pageAlpha = remember { Animatable(1f) }
    val pageOffset = remember { Animatable(0f) }
    val pageBlur = remember { Animatable(0f) }
    val morph = remember { Animatable(1f) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    var navOrigin by remember { mutableStateOf(Offset.Zero) }
    var navSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(tab) {
        if (previousTab != tab) {
            val direction = if (tab > previousTab) 1f else -1f
            previousTab = tab
            morph.snapTo(0f)
            launch { morph.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
            coroutineScope {
                launch { pageBlur.animateTo(12f, tween(90, easing = FastOutSlowInEasing)) }
                pageAlpha.animateTo(0f, tween(90, easing = FastOutSlowInEasing))
            }
            displayedTab = tab
            pageOffset.snapTo(direction * 0.12f)
            pageAlpha.snapTo(0f)
            coroutineScope {
                launch { pageAlpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
                launch { pageOffset.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
                launch { pageBlur.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
            }
        }
    }

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
    LaunchedEffect(autoStartSmartTempo) {
        if (autoStartSmartTempo) {
            c.active = "m2"
            c.go("music", 2)
        }
    }

    val accent = Color(0xFF8B7BFF)
    CompositionLocalProvider(LocalAccent provides accent) {
        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = tab, animationSpec = tween(360), label = "tab-wallpaper") { page ->
                val (wallpaper, scrim) = when (page) {
                    0 -> R.drawable.led1 to 0.58f
                    1 -> R.drawable.led2 to 0.34f
                    2 -> R.drawable.led3 to 0.78f
                    else -> R.drawable.led4 to 0.78f
                }
                val wallpaperPainter = painterResource(wallpaper)
                Box(Modifier.fillMaxSize()) {
                    Image(
                        painter = wallpaperPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Image(
                        painter = wallpaperPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = 1.16f
                            scaleY = 1.16f
                            alpha = 0.36f
                        }.blur(42.dp)
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
                }
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 14.dp)) {
                Header(c, onConnect = { perm.launch(perms()) })
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).onGloballyPositioned { pageOrigin = it.positionInRoot(); pageSize = it.size }.blur(pageBlur.value.dp).graphicsLayer {
                    alpha = pageAlpha.value
                    translationX = pageOffset.value * size.width
                    scaleX = 0.985f + (1f - pageAlpha.value) * 0.015f
                    scaleY = 0.985f + (1f - pageAlpha.value) * 0.015f
                }) {
                    when (displayedTab) {
                        0 -> MusicTab(c)
                        1 -> ScenesTab(c)
                        2 -> SyncTab(c)
                        else -> SettingsTab(c, onConnect = { perm.launch(perms()) })
                    }
                }
                GlassTabBar(tab, { tab = it }, Modifier.navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp).onGloballyPositioned { navOrigin = it.positionInRoot(); navSize = it.size })
            }
            Canvas(Modifier.fillMaxSize()) {
                val progress = morph.value
                if (progress < 1f && navSize.width > 0 && pageSize.height > 0) {
                    val itemWidth = navSize.width / 4f
                    val start = Offset(navOrigin.x + itemWidth * (tab + 0.5f), navOrigin.y + 5.dp.toPx())
                    val end = Offset(pageOrigin.x + 30.dp.toPx(), pageOrigin.y + 24.dp.toPx())
                    val lift = (start.y - end.y).coerceAtLeast(1f)
                    val c1 = Offset(start.x, start.y - lift * 0.42f)
                    val c2 = Offset(end.x, end.y + lift * 0.32f)
                    fun point(t: Float): Offset {
                        val u = 1f - t
                        return Offset(
                            u * u * u * start.x + 3f * u * u * t * c1.x + 3f * u * t * t * c2.x + t * t * t * end.x,
                            u * u * u * start.y + 3f * u * u * t * c1.y + 3f * u * t * t * c2.y + t * t * t * end.y
                        )
                    }
                    val path = Path()
                    val segments = (28 * progress).toInt().coerceAtLeast(1)
                    for (i in 0..segments) {
                        val p = point(progress * i / segments)
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    val alpha = (1f - progress).coerceIn(0f, 1f)
                    drawPath(path, Brush.linearGradient(listOf(accent.copy(alpha = 0f), Color.White.copy(alpha = alpha), accent.copy(alpha = alpha)), start = start, end = end), style = Stroke(width = 3.dp.toPx()))
                    val bead = point(progress)
                    drawCircle(accent.copy(alpha = alpha * 0.24f), radius = 20.dp.toPx(), center = bead)
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = alpha), accent.copy(alpha = alpha * 0.75f), Color.Transparent), center = bead, radius = 11.dp.toPx()), radius = 11.dp.toPx(), center = bead)
                }
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
            GlassButton("Off") { stopAll(ctx, afterglow = false); c.active = ""; Led.fadeTo(android.graphics.Color.BLACK, 400, true) }
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
    TabColumn {
        GlassCard {
            Label("Music · phone audio, no mic")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Spectrum", primary = c.active == "m1") { c.active = "m1"; c.go("music", 1) }
                GlassButton("Smart tempo", primary = c.active == "m2") { c.active = "m2"; c.go("music", 2) }
            }
            if (Engine.bpm.value > 0) Body("♪  ${Engine.bpm.value} BPM", 0.7f)
        }
    }
}
@Composable
private fun ScenesTab(c: Ctl) {
    val ctx = LocalContext.current
    TabColumn {
        GlassCard(strong = true) {
            Label("Color Reactor")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${c.hue.roundToInt()}°", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Body("Drag the outer ring to turn the color. Pull from the center to raise or lower brightness.", 0.65f)
                    Body("Flick to send a glassy ripple.", 0.5f)
                }
                ColorReactor(c, Modifier.size(176.dp))
            }
        }
        GlassCard {
            Label("Color")
            SliderRow("Hue", "${c.hue.roundToInt()}°", c.hue, 0f..360f, rainbow = true,
                onChange = { c.hue = it; Led.fadeTo(Led.hsv(it), 60) }, onFinish = { stopAll(ctx, afterglow = false); c.active = ""; Led.fadeTo(Led.hsv(c.hue), 120) })
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
            PRESETS.filterNot { it.name == "Breathing" }.forEach { e ->
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
                GlassButton("Disconnect") { stopAll(ctx, afterglow = false); c.active = ""; Led.disconnect() }
            }
        }
        GlassCard {
            Label("LED strip")
            SliderRow("Pixel count", "${c.pix.roundToInt()}", c.pix, 1f..255f, step = 1f, onChange = { c.pix = it }, onFinish = { savePixelCount(ctx, c.pix.roundToInt()) })
            GlassButton("Apply pixel count") { savePixelCount(ctx, c.pix.roundToInt()); Led.setPixels(c.pix.roundToInt()) }
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
            Box(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.08f))), shape)
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(hint, fontSize = 15.sp, color = Color.White.copy(alpha = 0.40f))
                inner()
            }
        }
    )
}

@Composable
private fun ColorReactor(c: Ctl, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ripple = remember { Animatable(1f) }
    Box(modifier.pointerInput(c) {
        var brightnessGesture = false
        detectDragGestures(
            onDragStart = { down ->
                val center = Offset(size.width / 2f, size.height / 2f)
                brightnessGesture = (down - center).getDistance() < minOf(size.width, size.height) * 0.24f
                stopAll(ctx, afterglow = false)
                c.active = ""
            },
            onDragEnd = {
                scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(520)) }
            }
        ) { change, _ ->
            change.consume()
            if (brightnessGesture) {
                Led.bright.value = (1f - change.position.y / size.height).coerceIn(0.05f, 1f)
            } else {
                val center = Offset(size.width / 2f, size.height / 2f)
                val angle = atan2(change.position.y - center.y, change.position.x - center.x) * 180f / PI.toFloat()
                c.hue = (angle + 360f) % 360f
                Led.fadeTo(Led.hsv(c.hue), 45, force = true)
            }
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val selected = Color(Led.hsv(c.hue))
            val wheel = listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)
            drawCircle(Brush.sweepGradient(wheel, center), radius = s * 0.45f, center = center, style = Stroke(width = 13.dp.toPx()))
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.92f), selected, Color.Black.copy(alpha = 0.78f)), center = center, radius = s * 0.35f),
                radius = s * 0.34f,
                center = center
            )
            if (ripple.value < 1f) {
                drawCircle(selected.copy(alpha = 1f - ripple.value), radius = s * (0.26f + 0.16f * ripple.value), center = center, style = Stroke(width = 2.dp.toPx()))
            }
            drawCircle(Color.White.copy(alpha = 0.52f), radius = s * 0.045f, center = Offset(center.x - s * 0.12f, center.y - s * 0.14f))
        }
        Text("${(Led.bright.value * 100).roundToInt()}%", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
