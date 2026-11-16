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
import com.wexpa.liquidglass.GlassTabBar as LiquidGlassTabBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
//  Liquid-glass design system (native Compose take on the OpenGlass UI look)
//  - cards use translucent frosted tint over the shared backdrop, with a lit rim
//    and a soft top highlight
//  - pill controls with spring motion, glass thumb on sliders, sliding glass pill in the tab bar
// ---------------------------------------------------------------------------------------------

val LocalAccent = compositionLocalOf { Color(0xFF8B7BFF) }

private val RimBrush = Brush.linearGradient(listOf(
    Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.30f)))

/** Neutral capsule glass used for moving slider thumbs, matching the navigation selector. */
internal fun DrawScope.drawLiquidGlassThumb(center: Offset, width: Float, height: Float, pressed: Boolean) {
    val w = width.coerceAtMost(size.width)
    val h = height.coerceAtMost(size.height)
    val left = center.x - w / 2f
    val top = center.y - h / 2f
    val shapeSize = Size(w, h)
    val radius = CornerRadius(h / 2f, h / 2f)
    drawRoundRect(Color.Black.copy(alpha = 0.22f), Offset(left, top + 1.5.dp.toPx()), shapeSize, radius)
    if (pressed) {
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
            Size(w + 4.dp.toPx(), h + 4.dp.toPx()), CornerRadius((h + 4.dp.toPx()) / 2f))
    }
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.20f), Color(0xFFCBD2E8).copy(alpha = 0.15f)),
            startY = top, endY = top + h
        ),
        Offset(left, top), shapeSize, radius
    )
    drawRoundRect(Color.White.copy(alpha = 0.72f), Offset(left, top), shapeSize, radius, style = Stroke(width = 1.dp.toPx()))
    drawRoundRect(
        Color.White.copy(alpha = 0.55f),
        Offset(left + w * 0.20f, top + h * 0.13f),
        Size(w * 0.44f, h * 0.10f),
        CornerRadius(h * 0.08f, h * 0.08f)
    )
}

@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/** A lightweight glass pane: translucent tint, lit rim, and top highlight. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(30.dp),
    strong: Boolean = false,
    pad: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val top = if (strong) 0.14f else 0.10f
    val bottom = if (strong) 0.06f else 0.035f
    Box(modifier.fillMaxWidth().clip(shape).border(1.dp, RimBrush, shape)) {
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom)))))
        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.62f, stiffness = 420f), label = "press")
    val bubble by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 260f), label = "glass-bubble")
    val shape = RoundedCornerShape(50)
    val fill = if (primary) Brush.verticalGradient(listOf(accent.copy(alpha = 0.66f), accent.copy(alpha = 0.24f)))
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.055f)))
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill)
            .border(1.dp, RimBrush, shape)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (bubble > 0.001f) {
                val center = Offset(size.width * (0.16f + 0.68f * bubble), size.height * 0.5f)
                val radius = maxOf(size.width, size.height) * 0.86f
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.42f * bubble), accent.copy(alpha = 0.20f * bubble), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }
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
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.88f)))
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
    val targetFraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val sliderFraction by animateFloatAsState(
        targetFraction,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 560f),
        label = "glass-slider-position"
    )
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
            val r = maxThumbPx / 2f
            val cx = r + sliderFraction * (size.width - 2f * r)
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
            drawLiquidGlassThumb(Offset(cx, cy), d, d * 0.78f, pressed = drag)
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
    val items = listOf("♪" to "Music", "✦" to "Scenes", "⚙" to "Settings")
    LiquidGlassTabBar(
        itemCount = items.size,
        selectedIndex = selected,
        onSelected = onSelect,
        modifier = modifier.fillMaxWidth().height(72.dp),
        motionEnabled = true
    ) { index, isSelected ->
        val (glyph, name) = items[index]
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(glyph, fontSize = 20.sp, color = if (isSelected) Color.White else Color.White.copy(alpha = 0.64f))
            Text(name, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.64f))
        }
    }
}
