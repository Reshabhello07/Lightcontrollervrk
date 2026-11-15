package com.ledstudio.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

data class Step(val c: Int, val hold: Int, val fade: Int)
data class Effect(val name: String, val steps: List<Step>)
data class VibeFrame(val color: Int, val beat: Boolean)

val NOTIF_COLORS = mapOf(
    "com.whatsapp" to 0xFF25D366.toInt(), "com.whatsapp.w4b" to 0xFF25D366.toInt(),
    "com.discord" to 0xFF5865F2.toInt(), "com.instagram.android" to 0xFFE1306C.toInt(),
    "org.telegram.messenger" to 0xFF2AABEE.toInt(), "com.google.android.gm" to 0xFFEA4335.toInt(),
    "com.google.android.apps.messaging" to 0xFF1A73E8.toInt(), "com.snapchat.android" to 0xFFFFFC00.toInt()
)

class CoverService : NotificationListenerService() {
    override fun onNotificationPosted(s: StatusBarNotification) {
        val n = s.notification
        if (s.packageName == packageName) return
        if (n.category == Notification.CATEGORY_CALL) {
            val type = n.extras.getInt("android.callType", 0)   // 1 incoming, 2 ongoing
            if (type == 1 || (type == 0 && !s.isOngoing)) Extras.ringStart(n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "?")
            else Extras.ringStop()
            return
        }
        val media = n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        if (!Engine.notifOn.value || s.isOngoing || media) return
        Led.pulse(NOTIF_COLORS[s.packageName] ?: Color.WHITE)
    }
    override fun onNotificationRemoved(s: StatusBarNotification) {
        if (s.notification.category == Notification.CATEGORY_CALL) Extras.ringStop()
    }
}

val PRESETS = listOf(
    Effect("Breathing", listOf(Step(0xFFFF3D00.toInt(), 300, 2000), Step(Color.BLACK, 300, 2000))),
    Effect("Rainbow", listOf(0f, 60f, 120f, 180f, 240f, 300f).map { Step(Led.hsv(it), 0, 1500) }),
    Effect("Fire", listOf(Step(0xFFFF2200.toInt(), 100, 400), Step(0xFFFF8800.toInt(), 100, 400), Step(0xFFFFCC00.toInt(), 50, 300))),
    Effect("Sunset", listOf(Step(0xFFFF5E00.toInt(), 1500, 3500), Step(0xFFFF0066.toInt(), 1500, 3500), Step(0xFF5500FF.toInt(), 1500, 3500)))
)

object Cover {
    var cols: List<Int> = listOf(Color.WHITE)
    private var key = ""
    private fun boost(c: Int): Int {
        val h = FloatArray(3); Color.colorToHSV(c, h); h[1] = maxOf(h[1], 0.6f); h[2] = 1f
        return Color.HSVToColor(h)
    }
    /** Samples the cover on a 16x16 grid: returns (fingerprint, share of clearly coloured pixels). */
    private fun scan(bmp: Bitmap): Pair<Int, Float>? = try {
        val hsv = FloatArray(3); var h = 17; var lit = 0; var vivid = 0
        for (gy in 0 until 16) for (gx in 0 until 16) {
            val px = bmp.getPixel((gx * bmp.width + bmp.width / 2) / 16, (gy * bmp.height + bmp.height / 2) / 16)
            h = h * 31 + (px and 0x00F8F8F8)
            Color.colorToHSV(px, hsv)
            if (hsv[2] > 0.2f) { lit++; if (hsv[1] > 0.4f) vivid++ }   // near-black pixels have meaningless hue
        }
        Pair(h, if (lit == 0) 0f else vivid.toFloat() / lit)
    } catch (e: Exception) { null }

    /** Reads the current song's cover. Black / white / grey covers give plain white light. Returns true when new colours were read. */
    fun refresh(ctx: Context): Boolean {
        try {
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            val md = msm.getActiveSessions(ComponentName(ctx, CoverService::class.java)).firstOrNull()?.metadata ?: return false
            val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return false
            val sc = scan(bmp)
            val k = md.getString(MediaMetadata.METADATA_KEY_TITLE) + "|" + md.getString(MediaMetadata.METADATA_KEY_ARTIST) + "|" + (sc?.first ?: 0)
            if (k == key) return false
            key = k
            if (sc != null && sc.second < 0.2f) { cols = listOf(Color.WHITE); return true }
            val all = try { Palette.from(bmp).maximumColorCount(24).generate().swatches } catch (e: Exception) { emptyList() }
            val colorful = all.filter { it.hsl[1] >= 0.35f && it.hsl[2] in 0.15f..0.9f }.sortedByDescending { it.population }
            cols = if (colorful.isEmpty()) listOf(Color.WHITE) else colorful.take(5).map { boost(it.rgb) }
            return true
        } catch (e: SecurityException) { }
        return false
    }
}

object Engine {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private lateinit var app: Context
    @Volatile var speed = 1f
    @Volatile var decay = 0.9f
    val notifOn = mutableStateOf(true)
    val bpm = mutableStateOf(0)
    val fx = mutableStateOf(1)
    val vibeRecording = mutableStateOf(false)
    val vibeHasSaved = mutableStateOf(false)
    val vibeSampleCount = mutableStateOf(0)
    val glitchFreeze = mutableStateOf(false)
    private val capturedVibe = mutableListOf<VibeFrame>()
    private val savedVibe = mutableStateListOf<VibeFrame>()
    private val musicMemory = mutableListOf<Int>()
    private var vibeLoaded = false
    @Volatile private var activeMode = ""
    private var afterglowJob: Job? = null

    fun init(c: Context) {
        app = c.applicationContext
        Extras.initPhone(app)
        if (!vibeLoaded) {
            vibeLoaded = true
            try {
                val json = JSONArray(app.getSharedPreferences("vibe", 0).getString("frames", "[]") ?: "[]")
                for (i in 0 until json.length()) {
                    val frame = json.getJSONObject(i)
                    savedVibe.add(VibeFrame(frame.getInt("color"), frame.optBoolean("beat")))
                }
                vibeHasSaved.value = savedVibe.isNotEmpty()
                vibeSampleCount.value = savedVibe.size
            } catch (_: Exception) { savedVibe.clear() }
        }
    }

    fun startVibeCapture() {
        synchronized(capturedVibe) { capturedVibe.clear() }
        vibeSampleCount.value = 0
        vibeRecording.value = true
    }

    fun finishVibeCapture() {
        vibeRecording.value = false
        val frames = synchronized(capturedVibe) { capturedVibe.toList() }
        if (frames.isEmpty()) return
        savedVibe.clear(); savedVibe.addAll(frames)
        vibeSampleCount.value = frames.size
        vibeHasSaved.value = true
        val json = JSONArray()
        frames.forEach { json.put(JSONObject().put("color", it.color).put("beat", it.beat)) }
        if (vibeLoaded) app.getSharedPreferences("vibe", 0).edit().putString("frames", json.toString()).apply()
    }

    fun playVibe() {
        val frames = savedVibe.toList()
        if (frames.isEmpty()) return
        run("vibe") {
            for (frame in frames) {
                if (!isActive) break
                Led.fadeTo(frame.color, if (frame.beat) 24 else 110, smooth = !frame.beat, force = true)
                delay(if (frame.beat) 95 else 120)
            }
            Led.fadeTo(Color.BLACK, 1800, smooth = true, force = true)
        }
    }

    fun stop(afterglow: Boolean = true) {
        val previousMode = activeMode
        activeMode = ""
        if (previousMode == "music" && vibeRecording.value) finishVibeCapture()
        job?.cancel(); job = null
        bpm.value = 0
        Extras.mcStop()
        if (!afterglow) afterglowJob?.cancel()
        else if (previousMode == "music" && afterglowJob?.isActive != true) startAfterglow()
    }

    private fun startAfterglow() {
        if (afterglowJob?.isActive == true) return
        val colors = synchronized(musicMemory) { musicMemory.toList() }.ifEmpty { listOf(Led.cur) }
        afterglowJob = scope.launch {
            val started = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - started
                if (elapsed >= 60_000L) break
                val progress = elapsed / 60_000f
                val color = colors[((elapsed / 3200L) % colors.size).toInt()]
                val strength = (1f - progress).coerceIn(0f, 1f).pow(1.5f)
                Led.fadeTo(ColorUtils.blendARGB(Color.BLACK, color, strength), 1500, smooth = true, force = true)
                delay(1200)
            }
            Led.fadeTo(Color.BLACK, 4000, smooth = true, force = true)
        }
    }

    private fun run(mode: String, block: suspend CoroutineScope.() -> Unit) {
        stop(afterglow = false)
        activeMode = mode
        job = scope.launch(block = block)
    }

    private fun hsvC(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v.coerceIn(0f, 1f)))
    private fun rot(c: Int, d: Float): Int { val h = FloatArray(3); Color.colorToHSV(c, h); h[0] = (h[0] + d) % 360f; return Color.HSVToColor(h) }

    fun play(e: Effect) = run("preset") {
        while (isActive) for (s in e.steps) {
            val f = (s.fade / speed).toLong()
            Led.fadeTo(s.c, f, true)
            delay(maxOf(20L, f + (s.hold / speed).toLong()))
        }
    }

    fun cover() = run("cover") {
        var i = 0
        while (isActive) {
            Cover.refresh(app)
            val cs = Cover.cols
            val f = (4000 / speed).toLong()
            Led.fadeTo(cs[i++ % cs.size], f, true)
            delay(f)
        }
    }

    fun cycle(from: Int, to: Int, sec: Int) = run("cycle") {
        var i = from
        while (isActive) { fx.value = i; Led.effect(i); delay(sec * 1000L); i = if (i >= to) from else i + 1 }
    }

    // ---- Cinematic scenes ----
    private fun scene(f: (Float) -> Int) = run("scene") {
        val t0 = System.nanoTime()
        while (isActive) { Led.fadeTo(f((System.nanoTime() - t0) / 1e9f), 70); delay(40) }
    }

    fun scene(name: String) {
        when (name) {
            "thunder" -> { var next = 4f; var fl = -1f
                scene { t ->
                    if (t > next) { fl = t; next = t + 3 + Random.nextFloat() * 7 }
                    val d = t - fl
                    if (fl >= 0 && d < 0.35f && ((d * 1000).toInt() / 70) % 2 == 0) Color.WHITE
                    else hsvC(225f, 0.9f, 0.25f + 0.1f * sin(t)) } }
            "candle" -> { var v = 0.8f
                scene { if (Random.nextFloat() < 0.35f) v = 0.45f + 0.55f * Random.nextFloat(); hsvC(22f, 1f, v) } }
            "aurora" -> scene { t -> hsvC(150f + 110f * (0.5f + 0.5f * sin(t / 7f)), 0.85f, 0.55f + 0.35f * sin(t / 3.3f)) }
            "ocean" -> scene { t -> hsvC(195f + 15f * sin(t / 9f), 0.9f, 0.45f + 0.45f * (0.5f + 0.5f * sin(t * 1.2f))) }
            "lava" -> { var v = 0.6f
                scene { if (Random.nextFloat() < 0.08f) v = 0.35f + 0.65f * Random.nextFloat(); hsvC(4f + 14f * v, 1f, v) } }
        }
    }

    // ---- Screen sync: weighted average of colourful pixels ----
    fun screen(r: ImageReader) = run("screen") {
        try {
            while (isActive) {
                val img = r.acquireLatestImage()
                if (img == null) { delay(30); continue }
                var sampledColor: Int? = null
                try {
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    var redSum = 0L; var greenSum = 0L; var blueSum = 0L; var weightSum = 0L
                    var y = 0
                    while (y < img.height) {
                        var x = 0
                        while (x < img.width) {
                            val offset = y * rowStride + x * 4
                            val red = buffer.get(offset).toInt() and 255
                            val green = buffer.get(offset + 1).toInt() and 255
                            val blue = buffer.get(offset + 2).toInt() and 255
                            val weight = 1 + maxOf(red, green, blue) - minOf(red, green, blue)
                            redSum += red * weight; greenSum += green * weight; blueSum += blue * weight; weightSum += weight
                            x += 3
                        }
                        y += 3
                    }
                    if (weightSum > 0) sampledColor = Color.rgb((redSum / weightSum).toInt(), (greenSum / weightSum).toInt(), (blueSum / weightSum).toInt())
                } finally { img.close() }
                sampledColor?.let { Led.fadeTo(it, 140) }
                delay(40)
            }
        } finally { r.close() }
    }
    // ---- Music from phone audio. style 0 breathing, 1 spectrum, 2 smart tempo ----
    fun music(rec: AudioRecord, fixed: Int?, sens: Float, style: Int) = run("music") {
        val buf = ShortArray(1024)
        var l1 = 0f; var l2 = 0f
        val pk = floatArrayOf(.02f, .02f, .02f); val lv = FloatArray(3); val env = FloatArray(3)
        var tick = 0; var ci = 0; var prev = 0f; var lastBeat = 0L; var decayK = decay
        val ibi = ArrayList<Long>()
        var phase = 0; var quietSince = 0L; var buildStart = 0L; var dropAt = 0L; var lastDrop = 0L
        var pal = if (fixed == null) Cover.cols else listOf(fixed, rot(fixed, 120f), rot(fixed, 240f))
        // breathing (style 0): same logic as the original LedStudio breathing
        val hist = FloatArray(43); var hi = 0; var prevE = 0f; var bLast = 0L; val bIbi = ArrayList<Long>()
        var breath = 0.12f; var lo = 0f; var hiV = 0f; var inited = false; var base = pal[0]
        synchronized(musicMemory) { musicMemory.clear() }
        var lastMemoryAt = 0L; var lastVibeAt = 0L; var lastAudibleAt = System.currentTimeMillis()
        var silentAfterglow = false; var glitchPrevious = 0f; var lastGlitchBeat = 0L
        var freezeUntil = 0L; var burstUntil = 0L; var burstColor = Color.WHITE
        rec.startRecording()
        try {
            while (isActive) {
                val n = withContext(Dispatchers.IO) { rec.read(buf, 0, buf.size) }
                if (n <= 0) continue
                if (style != 2) decayK = decay
                var sb = 0f; var sm = 0f; var sh = 0f
                for (i in 0 until n) {
                    val x = buf[i] / 32768f
                    l1 += 0.03f * (x - l1); l2 += 0.25f * (x - l2)
                    val m = l2 - l1; val h = x - l2
                    sb += l1 * l1; sm += m * m; sh += h * h
                }
                val e = floatArrayOf(sqrt(sb / n), sqrt(sm / n), sqrt(sh / n))
                for (k in 0..2) {
                    pk[k] = maxOf(pk[k] * 0.997f, e[k], 0.003f)
                    lv[k] = ((e[k] / pk[k]).pow(2f) * sens).coerceIn(0f, 1f)
                    env[k] = if (lv[k] > env[k]) env[k] + (lv[k] - env[k]) * 0.6f else env[k] * decayK
                }
                if (fixed == null && tick++ % 40 == 0) { Cover.refresh(app); pal = Cover.cols }
                val now = System.currentTimeMillis()
                val audible = (e.maxOrNull() ?: 0f) > 0.0035f
                if (audible) {
                    lastAudibleAt = now
                    if (silentAfterglow) { afterglowJob?.cancel(); silentAfterglow = false }
                    if (now - lastMemoryAt >= 850) {
                        lastMemoryAt = now
                        val color = Led.cur
                        synchronized(musicMemory) {
                            val last = musicMemory.lastOrNull()
                            val distance = if (last == null) 255 else kotlin.math.abs(Color.red(color) - Color.red(last)) + kotlin.math.abs(Color.green(color) - Color.green(last)) + kotlin.math.abs(Color.blue(color) - Color.blue(last))
                            if (last == null || distance > 70) { musicMemory.add(color); if (musicMemory.size > 18) musicMemory.removeAt(0) }
                        }
                    }
                } else if (!silentAfterglow && musicMemory.isNotEmpty() && now - lastAudibleAt > 2500) {
                    silentAfterglow = true
                    startAfterglow()
                }
                val beatHit = lv[0] > 0.55f && glitchPrevious <= 0.55f && now - lastGlitchBeat > 280
                glitchPrevious = lv[0]
                if (beatHit) {
                    lastGlitchBeat = now
                    if (glitchFreeze.value) {
                        val frozen = Led.cur
                        burstColor = ColorUtils.blendARGB(frozen, Color.WHITE, 0.78f)
                        freezeUntil = now + 75
                        burstUntil = now + 145
                        Led.fadeTo(frozen, 75, smooth = true, force = true)
                    }
                }
                if (vibeRecording.value && audible && now - lastVibeAt >= 120) {
                    lastVibeAt = now
                    val count = synchronized(capturedVibe) {
                        if (capturedVibe.size < 300) capturedVibe.add(VibeFrame(Led.cur, beatHit))
                        capturedVibe.size
                    }
                    if (count % 5 == 0 || count >= 300) vibeSampleCount.value = count
                    if (count >= 300) finishVibeCapture()
                }
                if (style == 0) {
                    // comfortable breathing: follow the song's loudness against its own recent quiet/loud range
                    val ld = kotlin.math.ln(0.75f * e[0] + 0.25f * e[1] + 1e-4f)
                    if (!inited) { lo = ld - 1f; hiV = ld + 1f; inited = true }
                    lo = if (ld < lo) lo + (ld - lo) * 0.05f else lo + 0.0006f
                    hiV = if (ld > hiV) hiV + (ld - hiV) * 0.08f else hiV - 0.001f
                    val nrm = ((ld - lo) / (hiV - lo).coerceAtLeast(0.8f)).coerceIn(0f, 1f)
                    val goal = 0.08f + 0.84f * nrm
                    val sp = ((0.97f - decay) / 0.22f * 1.4f).coerceIn(0f, 1f)   // Smoothness slider: higher = slower breathing
                    breath += (goal - breath) * (if (goal > breath) 0.06f + 0.5f * sp else 0.02f + 0.45f * sp)
                    base = ColorUtils.blendARGB(base, pal[0], 0.05f)
                    // tempo (BPM display): bass rising edges against a ~1 s adaptive threshold
                    hist[hi++ % hist.size] = e[0]
                    var avg = 0f; for (x in hist) avg += x
                    avg /= hist.size
                    var vr = 0f; for (x in hist) vr += (x - avg) * (x - avg)
                    val sd = sqrt(vr / hist.size)
                    if (e[0] > avg * 1.3f + 0.5f * sd && e[0] > 0.008f / sens && e[0] > prevE && now - bLast > 260) {
                        if (bLast > 0 && now - bLast < 1500) { bIbi.add(now - bLast); if (bIbi.size > 12) bIbi.removeAt(0) }
                        bLast = now
                        if (bIbi.size >= 4) {
                            var b = 60000f / bIbi.sorted()[bIbi.size / 2]
                            while (b < 70f) b *= 2f
                            while (b > 180f) b /= 2f
                            bpm.value = b.toInt()
                        }
                    }
                    prevE = e[0]
                }
                if (style == 2) {
                    if (lv[0] > 0.55f && prev <= 0.55f && now - lastBeat > 280) {
                        if (lastBeat > 0 && now - lastBeat < 1500) { ibi.add(now - lastBeat); if (ibi.size > 8) ibi.removeAt(0) }
                        lastBeat = now; ci++
                        if (ibi.size >= 3) {
                            var b = 60000f / ibi.sorted()[ibi.size / 2]
                            while (b < 70f) b *= 2; while (b > 180f) b /= 2
                            bpm.value = b.toInt()
                            decayK = 0.95f - 0.17f * ((b - 70f) / 110f).coerceIn(0f, 1f)
                        }
                    }
                    prev = lv[0]
                }
                if (style == 3) {   // beat drop: quiet build-up -> blast -> new colour
                    val air = lv[1] + lv[2]
                    when (phase) {
                        0 -> if (env[0] < 0.12f && air > 0.2f) {
                                if (quietSince == 0L) quietSince = now
                                if (now - quietSince > 1200 && now - lastDrop > 4000) { phase = 1; buildStart = now }
                            } else quietSince = 0L
                        1 -> if (lv[0] > 0.7f && now - buildStart > 600) { phase = 2; dropAt = now; lastDrop = now; ci++ }
                             else if (now - buildStart > 20000) phase = 0
                        2 -> if (now - dropAt > 180) { phase = 0; quietSince = 0L }
                    }
                }
                val out = when (style) {
                    1 -> {
                        var r = 0; var g = 0; var b = 0
                        for (k in 0..2) { val c = pal[k % pal.size]; val a = env[k]; r += (Color.red(c) * a).toInt(); g += (Color.green(c) * a).toInt(); b += (Color.blue(c) * a).toInt() }
                        Color.rgb(maxOf(r, 8).coerceAtMost(255), maxOf(g, 8).coerceAtMost(255), maxOf(b, 8).coerceAtMost(255))
                    }
                    2 -> ColorUtils.blendARGB(Color.BLACK, pal[ci % pal.size], 0.05f + 0.95f * env[0])
                    3 -> when (phase) {
                        1 -> ColorUtils.blendARGB(Color.BLACK, pal[(ci + 1) % pal.size], 0.02f + 0.2f * lv[2])
                        2 -> Color.WHITE
                        else -> ColorUtils.blendARGB(Color.BLACK, pal[ci % pal.size], 0.05f + 0.95f * env[0])
                    }
                    else -> ColorUtils.blendARGB(Color.BLACK, base, breath)
                }
                if (!silentAfterglow) {
                    when {
                        now < freezeUntil -> Unit
                        now < burstUntil -> Led.fadeTo(burstColor, 24, force = true)
                        else -> Led.fadeTo(out, when { style == 3 && phase == 1 -> 250L; style == 3 && phase == 2 -> 20L; style == 2 -> 50L; else -> 70L })
                    }
                }
            }
        } finally { rec.release() }
    }
}
