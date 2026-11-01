package com.ledstudio.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
//  Liquid-glass design system (native Compose take on the OpenGlass UI look)
//  - every card blurs the *backdrop* behind it (Android 12+), adds a frosted tint,
//    a lit rim and a soft top highlight
//  - pill controls with spring motion, glass thumb on sliders, sliding glass pill in the tab bar
// ---------------------------------------------------------------------------------------------

val LocalAccent = compositionLocalOf { Color(0xFF8B7BFF) }
val LocalRoot = compositionLocalOf { IntSize(1080, 2400) }

fun shiftHue(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + deg + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** The wallpaper behind all glass: deep gradient, colour blobs that follow the LED colour, bokeh rings and a strip of LEDs. */
fun DrawScope.drawBackdrop(sz: Size, accent: Color) {
    val w = sz.width; val h = sz.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0C16), Color(0xFF05060B)), startY = 0f, endY = h), size = sz)
    val a2 = shiftHue(accent, 70f); val a3 = shiftHue(accent, -70f)
    fun blob(c: Color, cx: Float, cy: Float, r: Float, al: Float) {
        val center = Offset(cx * w, cy * h)
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = al), Color.Transparent), center = center, radius = r * w), radius = r * w, center = center)
    }
    blob(accent, 0.18f, 0.10f, 0.85f, 0.60f)
    blob(a2, 0.98f, 0.36f, 0.70f, 0.48f)
    blob(a3, 0.05f, 0.66f, 0.75f, 0.42f)
    blob(accent, 0.80f, 0.97f, 0.70f, 0.38f)
    // bokeh: sharp shapes for the glass to soften
    val rings = listOf(
        floatArrayOf(0.15f, 0.30f, 0.09f), floatArrayOf(0.82f, 0.15f, 0.06f), floatArrayOf(0.60f, 0.48f, 0.12f),
        floatArrayOf(0.25f, 0.62f, 0.07f), floatArrayOf(0.90f, 0.70f, 0.10f), floatArrayOf(0.45f, 0.86f, 0.08f),
        floatArrayOf(0.08f, 0.92f, 0.05f), floatArrayOf(0.70f, 0.28f, 0.04f))
    rings.forEachIndexed { i, r ->
        val c = Offset(r[0] * w, r[1] * h)
        if (i % 2 == 0) drawCircle(Color.White.copy(alpha = 0.10f), radius = r[2] * w, center = c, style = Stroke(width = 2.dp.toPx()))
        else drawCircle(Color.White.copy(alpha = 0.07f), radius = r[2] * w, center = c)
    }
    // a strip of LEDs running diagonally across the screen
    val n = 34
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val x = (0.04f + 0.92f * t) * w
        val y = (0.90f - 0.30f * t + 0.03f * kotlin.math.sin(t * 9f)) * h
        val col = androidx.compose.ui.graphics.lerp(a3, a2, t)
        drawCircle(col.copy(alpha = 0.22f), radius = 14.dp.toPx(), center = Offset(x, y))
        drawCircle(col.copy(alpha = 0.95f), radius = 3.dp.toPx(), center = Offset(x, y))
    }
}

private val RimBrush = Brush.linearGradient(listOf(
    Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.30f)))

@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/** A pane of glass: blurred backdrop + frosted tint + lit rim + top highlight. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(30.dp),
    strong: Boolean = false,
    pad: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val root = LocalRoot.current
    val accent = LocalAccent.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val top = if (strong) 0.20f else 0.15f
    val bottom = if (strong) 0.09f else 0.05f
    Box(modifier
        .fillMaxWidth()
        .onGloballyPositioned { pos = it.positionInRoot() }
        .clip(shape)
        .border(1.dp, RimBrush, shape)
    ) {
        // the real "glass": the wallpaper behind this card, blurred (Android 12+)
        Canvas(Modifier.matchParentSize().blur(26.dp)) {
            translate(-pos.x, -pos.y) { drawBackdrop(Size(root.width.toFloat(), root.height.toFloat()), accent) }
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom)))))
        Box(Modifier.matchParentSize().background(accent.copy(alpha = 0.05f)))
        Box(Modifier.matchParentSize().drawBehind {
            drawRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), startY = 0f, endY = size.height * 0.45f),
                size = Size(size.width, size.height * 0.45f))
        })
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.5f, stiffness = 520f), label = "press")
    val shape = RoundedCornerShape(50)
    val fill = if (primary) Brush.verticalGradient(listOf(accent.copy(alpha = 0.80f), accent.copy(alpha = 0.38f)))
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.09f)))
    Box(modifier
        .graphicsLayer { scaleX = sc; scaleY = sc }
        .clip(shape)
        .background(fill)
        .border(1.dp, RimBrush, shape)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 420f), label = "sw")
    val track by animateColorAsState(if (checked) accent.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.20f), label = "swc")
    Box(Modifier
        .size(width = 54.dp, height = 32.dp)
        .clip(CircleShape)
        .background(track)
        .border(1.dp, RimBrush, CircleShape)
        .tap { onChange(!checked) }
        .padding(3.dp)) {
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.97f)))
    }
}

@Composable
fun GlassSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    rainbow: Boolean = false,
    onFinish: () -> Unit = {}
) {
    val accent = LocalAccent.current
    val dens = LocalDensity.current
    val changeU by rememberUpdatedState(onChange)
    val finishU by rememberUpdatedState(onFinish)
    var wPx by remember { mutableStateOf(1f) }
    var drag by remember { mutableStateOf(false) }
    val thumbDp by animateFloatAsState(if (drag) 38f else 28f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "thumb")
    val maxThumbPx = with(dens) { 38.dp.toPx() }
    fun setFromX(x: Float) {
        val usable = (wPx - maxThumbPx).coerceAtLeast(1f)
        val f = ((x - maxThumbPx / 2f) / usable).coerceIn(0f, 1f)
        var v = range.start + f * (range.endInclusive - range.start)
        if (step > 0f) v = (v / step).roundToInt() * step
        changeU(v.coerceIn(range.start, range.endInclusive))
    }
    Box(modifier
        .fillMaxWidth()
        .height(42.dp)
        .onSizeChanged { wPx = it.width.toFloat() }
        .pointerInput(range, step) { detectTapGestures { setFromX(it.x); finishU() } }
        .pointerInput(range, step) {
            detectDragGestures(
                onDragStart = { drag = true; setFromX(it.x) },
                onDragEnd = { drag = false; finishU() },
                onDragCancel = { drag = false }
            ) { change, _ -> change.consume(); setFromX(change.position.x) }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val r = maxThumbPx / 2f
            val cx = r + frac * (size.width - 2f * r)
            val cy = size.height / 2f
            val th = 8.dp.toPx()
            val corner = CornerRadius(th / 2f, th / 2f)
            val trackTop = cy - th / 2f
            if (rainbow) {
                val cols = List(7) { Color.hsv(it * 60f, 0.85f, 1f) }
                drawRoundRect(Brush.horizontalGradient(cols, startX = 0f, endX = size.width), Offset(0f, trackTop), Size(size.width, th), corner)
            } else {
                drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(0f, trackTop), Size(size.width, th), corner)
                drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent), startX = 0f, endX = cx.coerceAtLeast(1f)),
                    Offset(0f, trackTop), Size(cx.coerceAtLeast(0f), th), corner)
            }
            val d = thumbDp.dp.toPx()
            if (drag) drawCircle(accent.copy(alpha = 0.28f), radius = d / 2f + 7.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.Black.copy(alpha = 0.30f), radius = d / 2f + 1.5.dp.toPx(), center = Offset(cx, cy + 2.dp.toPx()))
            drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.99f), Color(0xFFD9DAEE)), startY = cy - d / 2f, endY = cy + d / 2f),
                radius = d / 2f, center = Offset(cx, cy))
            drawCircle(Color.White, radius = d / 2f, center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
        }
    }
}

@Composable
fun SliderRow(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, step: Float = 0f, rainbow: Boolean = false, onFinish: () -> Unit = {}
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 15.sp, color = Color.White.copy(alpha = 0.88f))
            Text(valueText, fontSize = 14.sp, color = Color.White.copy(alpha = 0.55f))
        }
        GlassSlider(value, onChange, range, step = step, rainbow = rainbow, onFinish = onFinish)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f))
        GlassSwitch(checked, onChange)
    }
}

@Composable
fun Label(t: String) =
    Text(t.uppercase(), fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.55f))

@Composable
fun Body(t: String, alpha: Float = 0.8f) = Text(t, fontSize = 15.sp, color = Color.White.copy(alpha = alpha))

@Composable
fun GlassTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf("♪" to "Music", "✦" to "Scenes", "⇄" to "Sync", "⚙" to "Settings")
    GlassCard(modifier, shape = RoundedCornerShape(38.dp), strong = true, pad = 6.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp)) {
            val w = maxWidth / items.size
            val x by animateDpAsState(w * selected, spring(dampingRatio = 0.72f, stiffness = 380f), label = "tab")
            val pill = RoundedCornerShape(32.dp)
            Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(2.dp).clip(pill)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.12f))))
                .border(1.dp, RimBrush, pill))
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, (glyph, name) ->
                    val on = i == selected
                    Column(Modifier.weight(1f).fillMaxHeight().tap { onSelect(i) },
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(glyph, fontSize = 20.sp, color = if (on) Color.White else Color.White.copy(alpha = 0.60f))
                        Text(name, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) Color.White else Color.White.copy(alpha = 0.60f))
                    }
                }
            }
        }
    }
}
