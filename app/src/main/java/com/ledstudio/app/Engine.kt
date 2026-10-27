package com.ledstudio.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.media.AudioRecord
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import kotlin.math.pow
import kotlin.math.sqrt

data class Step(val c: Int, val hold: Int, val fade: Int)
data class Effect(val name: String, val steps: List<Step>)

class CoverService : NotificationListenerService()

val PRESETS = listOf(
    Effect("Breathing", listOf(Step(0xFFFF3D00.toInt(), 300, 2000), Step(Color.BLACK, 300, 2000))),
    Effect("Rainbow", listOf(0f, 60f, 120f, 180f, 240f, 300f).map { Step(Led.hsv(it), 0, 1500) }),
    Effect("Strobe", listOf(Step(Color.WHITE, 70, 0), Step(Color.BLACK, 70, 0))),
    Effect("Police", listOf(Step(Color.RED, 150, 0), Step(Color.BLACK, 50, 0), Step(Color.BLUE, 150, 0), Step(Color.BLACK, 50, 0))),
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
    fun refresh(ctx: Context) {
        try {
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            val md = msm.getActiveSessions(ComponentName(ctx, CoverService::class.java)).firstOrNull()?.metadata ?: return
            val k = md.getString(MediaMetadata.METADATA_KEY_TITLE) + md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (k == key) return
            val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return
            key = k
            val sw = Palette.from(bmp).maximumColorCount(16).generate().swatches.sortedByDescending { it.population }.take(5)
            if (sw.isNotEmpty()) cols = sw.map { boost(it.rgb) }
        } catch (e: SecurityException) { }
    }
}

object Engine {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private lateinit var app: Context
    @Volatile var speed = 1f
    @Volatile var decay = 0.9f   // music: higher = slower, smoother falloff

    fun init(c: Context) { app = c.applicationContext }
    fun stop() { job?.cancel() }
    private fun run(block: suspend CoroutineScope.() -> Unit) { stop(); job = scope.launch(block = block) }

    fun play(e: Effect) = run {
        while (isActive) for (s in e.steps) {
            val f = (s.fade / speed).toLong()
            Led.fadeTo(s.c, f, true)
            delay(maxOf(20L, f + (s.hold / speed).toLong()))
        }
    }

    fun cover() = run {
        var i = 0
        while (isActive) {
            Cover.refresh(app)
            val cs = Cover.cols
            val f = (4000 / speed).toLong()
            Led.fadeTo(cs[i++ % cs.size], f, true)
            delay(f)
        }
    }

    // Music from the phone's own audio output (no mic). Bass-driven breathing, auto-gain.
    fun music(rec: AudioRecord, fixed: Int?, sens: Float) = run {
        val buf = ShortArray(1024)
        var lp = 0f; var env = 0f; var peak = 0.02f; var tick = 0
        var base = fixed ?: Cover.cols[0]; var baseT = base
        rec.startRecording()
        try {
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var s = 0f
                for (i in 0 until n) { val x = buf[i] / 32768f; lp += 0.03f * (x - lp); s += lp * lp }
                val e = sqrt(s / n)
                peak = maxOf(peak * 0.997f, e, 0.005f)
                val lv = ((e / peak).pow(2f) * sens).coerceIn(0f, 1f)
                env = if (lv > env) env + (lv - env) * 0.6f else env * decay
                if (fixed == null) {
                    if (tick++ % 40 == 0) { Cover.refresh(app); baseT = Cover.cols[0] }
                    base = ColorUtils.blendARGB(base, baseT, 0.04f)
                }
                Led.fadeTo(ColorUtils.blendARGB(Color.BLACK, base, 0.05f + 0.95f * env), 70)
            }
        } finally { rec.release() }
    }
}
