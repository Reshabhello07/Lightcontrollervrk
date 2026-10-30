package com.ledstudio.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class Step(val c: Int, val hold: Int, val fade: Int)
data class Effect(val name: String, val steps: List<Step>)

val NOTIF_COLORS = mapOf(
    "com.whatsapp" to 0xFF25D366.toInt(), "com.whatsapp.w4b" to 0xFF25D366.toInt(),
    "com.discord" to 0xFF5865F2.toInt(), "com.instagram.android" to 0xFFE1306C.toInt(),
    "org.telegram.messenger" to 0xFF2AABEE.toInt(), "com.google.android.gm" to 0xFFEA4335.toInt(),
    "com.google.android.apps.messaging" to 0xFF1A73E8.toInt(), "com.snapchat.android" to 0xFFFFFC00.toInt()
)

class CoverService : NotificationListenerService() {
    override fun onNotificationPosted(s: StatusBarNotification) {
        val n = s.notification
        val media = n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        if (!Engine.notifOn.value || s.isOngoing || media || s.packageName == packageName) return
        Led.pulse(NOTIF_COLORS[s.packageName] ?: Color.WHITE)
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
    /** Reads the current song's cover once. Returns true when a new song's colours were read. */
    fun refresh(ctx: Context): Boolean {
        try {
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            val md = msm.getActiveSessions(ComponentName(ctx, CoverService::class.java)).firstOrNull()?.metadata ?: return false
            val k = md.getString(MediaMetadata.METADATA_KEY_TITLE) + md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (k == key) return false
            val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return false
            key = k
            val all = Palette.from(bmp).maximumColorCount(24).generate().swatches
            val total = all.sumOf { it.population }.coerceAtLeast(1)
            val colorful = all.filter { it.hsl[1] >= 0.25f && it.hsl[2] in 0.12f..0.92f }.sortedByDescending { it.population }
            val share = colorful.sumOf { it.population }.toFloat() / total
            // black / white / grey covers -> plain white light
            cols = if (colorful.isEmpty() || share < 0.25f) listOf(Color.WHITE) else colorful.take(5).map { boost(it.rgb) }
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
    @Volatile var breathSpeed = 5f   // 1 (slow) .. 10 (fast), set from the slider
    val notifOn = mutableStateOf(true)
    val bpm = mutableStateOf(0)
    val fx = mutableStateOf(1)

    fun init(c: Context) { app = c.applicationContext; Extras.initPhone(app) }
    fun stop() { job?.cancel(); bpm.value = 0 }
    private fun run(block: suspend CoroutineScope.() -> Unit) { stop(); job = scope.launch(block = block) }
    private fun hsvC(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v.coerceIn(0f, 1f)))
    private fun rot(c: Int, d: Float): Int { val h = FloatArray(3); Color.colorToHSV(c, h); h[0] = (h[0] + d) % 360f; return Color.HSVToColor(h) }

    fun play(e: Effect) = run {
        while (isActive) for (s in e.steps) {
            val f = (s.fade / speed).toLong()
            Led.fadeTo(s.c, f, true)
            delay(maxOf(20L, f + (s.hold / speed).toLong()))
        }
    }

    fun cycle(from: Int, to: Int, sec: Int) = run {
        var i = from
        while (isActive) { fx.value = i; Led.effect(i); delay(sec * 1000L); i = if (i >= to) from else i + 1 }
    }

    // ---- Cinematic scenes ----
    private fun scene(f: (Float) -> Int) = run {
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
    fun screen(r: ImageReader) = run {
        try {
            while (isActive) {
                val img = r.acquireLatestImage()
                if (img == null) { delay(30); continue }
                val p = img.planes[0]; val b = p.buffer; val rs = p.rowStride
                var sr = 0L; var sg = 0L; var sb = 0L; var sw = 0L
                var y = 0
                while (y < img.height) {
                    var x = 0
                    while (x < img.width) {
                        val o = y * rs + x * 4
                        val R = b.get(o).toInt() and 255; val G = b.get(o + 1).toInt() and 255; val B = b.get(o + 2).toInt() and 255
                        val w = 1 + maxOf(R, G, B) - minOf(R, G, B)
                        sr += R * w; sg += G * w; sb += B * w; sw += w; x += 3
                    }
                    y += 3
                }
                img.close()
                if (sw > 0) Led.fadeTo(Color.rgb((sr / sw).toInt(), (sg / sw).toInt(), (sb / sw).toInt()), 140)
                delay(40)
            }
        } finally { r.close() }
    }

    // ---- Music from phone audio. style 0 breathing, 2 smart tempo, 3 beat drop ----
    // The cover colour is read once per song (about 2.5 s window); after that only brightness reacts to the beat.
    fun music(rec: AudioRecord, fixed: Int?, sens: Float, style: Int) = run {
        val buf = ShortArray(1024)
        var l1 = 0f; var l2 = 0f
        val pk = floatArrayOf(.02f, .02f, .02f); val lv = FloatArray(3)
        val hist = FloatArray(43); var hi = 0
        var breath = 0.12f; var lo = 0f; var hiV = 0f; var inited = false
        var pulse = 0f; var bassSm = 0f; var prevE = 0f; var lastBeat = 0L; var tick = 0
        val ibi = ArrayList<Long>(); var bpmDecay = -1f
        var ci = 0; var phase = 0; var quietSince = 0L; var buildStart = 0L; var dropAt = 0L; var lastDrop = 0L
        var pal = if (fixed == null) Cover.cols else listOf(fixed, rot(fixed, 120f), rot(fixed, 240f))
        var target = pal[0]; var base = target
        var learnUntil = System.currentTimeMillis() + 2500
        rec.startRecording()
        try {
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
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
                }
                bassSm = bassSm * 0.9f + lv[0] * 0.1f

                // comfortable breathing: follow the song's loudness against its own recent quiet/loud range
                val ld = kotlin.math.ln(0.75f * e[0] + 0.25f * e[1] + 1e-4f)
                if (!inited) { lo = ld - 1f; hiV = ld + 1f; inited = true }
                lo = if (ld < lo) lo + (ld - lo) * 0.05f else lo + 0.0006f
                hiV = if (ld > hiV) hiV + (ld - hiV) * 0.08f else hiV - 0.001f
                val nrm = ((ld - lo) / (hiV - lo).coerceAtLeast(0.8f)).coerceIn(0f, 1f)
                val goal = 0.08f + 0.84f * nrm
                val sp = (breathSpeed - 1f) / 9f
                breath += (goal - breath) * (if (goal > breath) 0.06f + 0.5f * sp else 0.02f + 0.45f * sp)
                val now = System.currentTimeMillis()

                // colour: read the cover once per song, then keep it fixed
                if (fixed == null && tick++ % (if (now < learnUntil) 10 else 40) == 0 && Cover.refresh(app)) {
                    learnUntil = now + 2500; pal = Cover.cols; ci = 0; target = pal[0]
                }
                base = ColorUtils.blendARGB(base, target, 0.05f)

                // beat detection: bass energy against a ~1 s adaptive threshold (mean + spread), rising edge only
                hist[hi++ % hist.size] = e[0]
                var avg = 0f; for (x in hist) avg += x
                avg /= hist.size
                var vr = 0f; for (x in hist) vr += (x - avg) * (x - avg)
                val sd = sqrt(vr / hist.size)
                if (e[0] > avg * 1.3f + 0.5f * sd && e[0] > 0.008f / sens && e[0] > prevE && now - lastBeat > 260) {
                    if (lastBeat > 0 && now - lastBeat < 1500) { ibi.add(now - lastBeat); if (ibi.size > 12) ibi.removeAt(0) }
                    lastBeat = now; if (e[0] > avg * 1.6f + sd) pulse = 0.6f
                    if (ibi.size >= 4) {
                        var b = 60000f / ibi.sorted()[ibi.size / 2]
                        while (b < 70f) b *= 2f
                        while (b > 180f) b /= 2f
                        bpm.value = b.toInt()
                        bpmDecay = 0.2f.pow(1f / (0.8f * (60f / b) / 0.0232f))   // fade to 20% in 0.8 beat
                    }
                }
                prevE = e[0]
                pulse *= if (bpmDecay < 0f) 0.9f else bpmDecay

                if (style == 3) {   // beat drop: quiet build-up -> white blast -> next cover colour
                    val air = lv[1] + lv[2]
                    if (phase == 0) {
                        if (bassSm < 0.12f && air > 0.2f) {
                            if (quietSince == 0L) quietSince = now
                            if (now - quietSince > 1200 && now - lastDrop > 4000) { phase = 1; buildStart = now }
                        } else { quietSince = 0L }
                    } else if (phase == 1) {
                        if (lv[0] > 0.7f && now - buildStart > 600) {
                            phase = 2; dropAt = now; lastDrop = now; ci++; target = pal[ci % pal.size]; pulse = 1f
                        } else if (now - buildStart > 20000) { phase = 0 }
                    } else if (now - dropAt > 180) { phase = 0; quietSince = 0L }
                }

                val level = if (style == 0) breath else (breath + 0.2f * pulse).coerceAtMost(0.95f)
                val out = when {
                    style == 3 && phase == 1 -> ColorUtils.blendARGB(Color.BLACK, base, 0.02f + 0.2f * lv[2])
                    style == 3 && phase == 2 -> Color.WHITE
                    else -> ColorUtils.blendARGB(Color.BLACK, base, level)
                }
                Led.fadeTo(out, when { style == 3 && phase == 1 -> 250L; style == 3 && phase == 2 -> 20L; else -> 80L })
            }
        } finally { rec.release() }
    }
}
