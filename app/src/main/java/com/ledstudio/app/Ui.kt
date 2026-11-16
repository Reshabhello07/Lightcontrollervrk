@file:OptIn(ExperimentalLayoutApi::class)

package com.ledstudio.app

import android.Manifest
import android.app.Activity
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ShireFontFamily = FontFamily(Font(R.font.clarendon_regular))
private val SuperBouncerFontFamily = FontFamily(Font(R.font.super_bouncer))

/** All UI state that the tabs share. */
class Ctl(ctx: Context) {
    var hue by mutableStateOf(0f)
    var spd by mutableStateOf(1f)
    var sens by mutableStateOf(1f)
    var smooth by mutableStateOf(0.9f)
    var useCover by mutableStateOf(true)
    var pix by mutableStateOf(ctx.getSharedPreferences("led", 0).getInt("pixels", 200).toFloat())
    var fxName by mutableStateOf("")
    var selectedScene by mutableStateOf("")
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
                launch { pageBlur.animateTo(if (tab == 1) 0f else 7f, tween(90, easing = FastOutSlowInEasing)) }
                pageAlpha.animateTo(0f, tween(90, easing = FastOutSlowInEasing))
            }
            displayedTab = tab
            pageOffset.snapTo(direction * 0.12f)
            pageAlpha.snapTo(0f)
            pageBlur.snapTo(0f)
            coroutineScope {
                launch { pageAlpha.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
                launch { pageOffset.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
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
    val mainFont = SuperBouncerFontFamily
    CompositionLocalProvider(
        LocalAccent provides accent,
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = mainFont)
    ) {
        Box(Modifier.fillMaxSize()) {
            Crossfade(targetState = displayedTab, animationSpec = tween(300), label = "tab-wallpaper") { page ->
                val (wallpaper, scrim) = when (page) {
                    0 -> R.drawable.led1 to 0.58f
                    1 -> R.drawable.led2 to 0.34f
                    2 -> R.drawable.led3 to 0.78f
                    else -> R.drawable.led4 to 0.78f
                }
                val wallpaperContext = LocalContext.current.applicationContext
                val wallpaperBitmap by produceState<ImageBitmap?>(null, wallpaper) {
                    value = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeResource(wallpaperContext.resources, wallpaper)?.asImageBitmap()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    wallpaperBitmap?.let { bitmap ->
                        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (page != 1) {
                            Image(
                                bitmap, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().graphicsLayer {
                                    scaleX = 1.16f
                                    scaleY = 1.16f
                                    alpha = 0.30f
                                }.blur(28.dp)
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
                }
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 14.dp)) {
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = ShireFontFamily)) {
                    Header(c, onConnect = { perm.launch(perms()) })
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).onGloballyPositioned { pageOrigin = it.positionInRoot(); pageSize = it.size }.blur(pageBlur.value.dp).graphicsLayer {
                    alpha = pageAlpha.value
                    translationX = pageOffset.value * size.width
                    scaleX = 0.985f + (1f - pageAlpha.value) * 0.015f
                    scaleY = 0.985f + (1f - pageAlpha.value) * 0.015f
                }) {
                    when (displayedTab) {
                        0 -> MusicTab(c)
                        1 -> CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = ShireFontFamily)) { ScenesTab(c) }
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
                Text("Shire", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
private data class SceneChoice(val title: String, val image: Int, val effect: String)

private val sceneChoices = listOf(
    SceneChoice("Aurora", R.drawable.scene_aurora, "aurora"),
    SceneChoice("Crimson", R.drawable.scene_crimson, "crimson"),
    SceneChoice("Warm Ambience", R.drawable.scene_warm, "warm")
)

@Composable
private fun ScenesTab(c: Ctl) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Label("Choose a scene")
        Row(Modifier.fillMaxWidth().height(476.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VerticalGlassControl(
                label = "COLOR", value = c.hue, range = 0f..360f, hueControl = true,
                valueLabel = "${c.hue.roundToInt()}°", modifier = Modifier.width(50.dp).fillMaxHeight(),
                onValueChange = { c.hue = it; Led.fadeTo(Led.hsv(it), 60, force = true) },
                onFinish = {
                    stopAll(ctx, afterglow = false)
                    c.active = ""
                    Led.fadeTo(Led.hsv(c.hue), 120)
                }
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sceneChoices, key = { it.title }) { scene ->
                    SceneImageCard(scene, c.selectedScene == scene.title) {
                        c.selectedScene = scene.title
                        stopAll(ctx)
                        c.active = ""
                        Engine.scene(scene.effect)
                    }
                }
            }
            VerticalGlassControl(
                label = "BRIGHT", value = Led.bright.value, range = 0.05f..1f, hueControl = false,
                valueLabel = "${(Led.bright.value * 100).roundToInt()}%", modifier = Modifier.width(50.dp).fillMaxHeight(),
                onValueChange = { Led.bright.value = it }
            )
        }
        GlassCard(pad = 14.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("Effect speed")
                Spacer(Modifier.weight(1f))
                Body("${c.spd.roundToInt()}×", 0.8f)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f, 2f, 3f, 4f).forEach { speed ->
                    GlassButton("${speed.toInt()}×", modifier = Modifier.weight(1f), primary = c.spd == speed) {
                        c.spd = speed
                        Engine.speed = speed
                    }
                }
            }
        }
        GlassCard {
            Label("More scenes")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Thunderstorm" to "thunder", "Ocean" to "ocean").forEach { (name, effect) ->
                    GlassButton(name) {
                        c.selectedScene = name
                        stopAll(ctx)
                        c.active = ""
                        Engine.scene(effect)
                    }
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
            c.favs.toSortedMap().forEach { (fid, name) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Body("$name  (#$fid)", 0.9f)
                    Spacer(Modifier.weight(1f))
                    GlassButton("Play") { c.playFx(ctx, fid) }
                }
            }
        }
        GlassCard {
            Label("Effect presets")
            PRESETS.filterNot { it.name == "Breathing" }.forEach { effect ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Body(effect.name, 0.9f)
                    Spacer(Modifier.weight(1f))
                    GlassButton("Play") { stopAll(ctx); c.active = ""; Engine.play(effect) }
                }
            }
        }
    }
}

@Composable
private fun SceneImageCard(scene: SceneChoice, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val imageContext = LocalContext.current.applicationContext
    val sceneBitmap by produceState<ImageBitmap?>(null, scene.image) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeResource(imageContext.resources, scene.image)?.asImageBitmap()
        }
    }
    Box(
        Modifier.fillMaxWidth().height(152.dp).clip(shape)
            .border(1.dp, if (selected) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.26f), shape)
            .clickable(onClick = onClick)
    ) {
        if (sceneBitmap != null) {
            Image(sceneBitmap!!, scene.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF111218)))
        }
        Box(
            Modifier.fillMaxWidth().height(72.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.76f))))
        )
        Text(scene.title, Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 14.dp),
            fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Box(
            Modifier.align(Alignment.TopEnd).padding(12.dp).size(28.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f)).border(1.dp, Color.White.copy(alpha = 0.82f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(if (selected) 16.dp else 8.dp).clip(CircleShape)
                .background(if (selected) Color(0xFFBCAEFF) else Color.White.copy(alpha = 0.75f)))
        }
    }
}

@Composable
private fun VerticalGlassControl(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hueControl: Boolean,
    valueLabel: String,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
    onFinish: () -> Unit = {}
) {
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onFinish)
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val railBrush = if (hueControl) {
        Brush.verticalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.92f), Color(0xFF777985), Color(0xFF171820)))
    }
    Column(
        modifier.clip(RoundedCornerShape(25.dp))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.055f))))
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(25.dp))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 8.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.82f), maxLines = 1)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)
                .pointerInput(range) {
                    fun update(y: Float) {
                        val f = (1f - y / size.height).coerceIn(0f, 1f)
                        change(range.start + f * (range.endInclusive - range.start))
                    }
                    detectTapGestures { update(it.y); finish() }
                }
                .pointerInput(range) {
                    fun update(y: Float) {
                        val f = (1f - y / size.height).coerceIn(0f, 1f)
                        change(range.start + f * (range.endInclusive - range.start))
                    }
                    detectDragGestures(onDragStart = { update(it.y) }, onDragEnd = { finish() }) { input, _ ->
                        input.consume()
                        update(input.position.y)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val railWidth = 8.dp.toPx()
                val top = 8.dp.toPx()
                val railHeight = (size.height - top * 2).coerceAtLeast(1f)
                val x = size.width / 2f
                drawRoundRect(
                    railBrush,
                    topLeft = Offset(x - railWidth / 2f, top),
                    size = androidx.compose.ui.geometry.Size(railWidth, railHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(railWidth / 2f)
                )
                val y = top + (1f - fraction) * railHeight
                drawCircle(Color.White.copy(alpha = 0.20f), radius = 15.dp.toPx(), center = Offset(x, y))
                drawCircle(Color.White.copy(alpha = 0.96f), radius = 10.dp.toPx(), center = Offset(x, y))
                val thumb = if (hueControl) Color(android.graphics.Color.HSVToColor(floatArrayOf(value, 1f, 1f))) else Color(0xFFE7E5EF)
                drawCircle(thumb, radius = 7.dp.toPx(), center = Offset(x, y))
            }
        }
        Text(valueLabel, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.84f), maxLines = 1)
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
