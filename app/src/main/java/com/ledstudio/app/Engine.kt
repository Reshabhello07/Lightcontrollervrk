package com.ledstudio.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.media.*
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class Step(val c: Int, val hold: Int, val fade: Int)
data class Effect(val name: String, val steps: List<Step>, val builtin: Boolean = false)

class CoverService : NotificationListenerService()

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

object Store {
    val presets = listOf(
        Effect("Breathing", listOf(Step(0xFFFF3D00.toInt(), 200, 1500), Step(Color.BLACK, 200, 1500)), true),
        Effect("Rainbow", listOf(0f, 60f, 120f, 180f, 240f, 300f).map { Step(Led.hsv(it), 0, 1000) }, true),
        Effect("Strobe", listOf(Step(Color.WHITE, 70, 0), Step(Color.BLACK, 70, 0)), true),
        Effect("Police", listOf(Step(Color.RED, 150, 0), Step(Color.BLACK, 50, 0), Step(Color.BLUE, 150, 0), Step(Color.BLACK, 50, 0)), true),
        Effect("Fire", listOf(Step(0xFFFF2200.toInt(), 100, 300), Step(0xFFFF8800.toInt(), 100, 300), Step(0xFFFFCC00.toInt(), 50, 200)), true),
        Effect("Sunset", listOf(Step(0xFFFF5E00.toInt(), 1500, 3000), Step(0xFFFF0066.toInt(), 1500, 3000), Step(0xFF5500FF.toInt(), 1500, 3000)), true)
    )
    private fun p(c: Context) = c.getSharedPreferences("fx", Context.MODE_PRIVATE)
    fun all(c: Context): List<Effect> {
        val a = JSONArray(p(c).getString("e", "[]"))
        val user = (0 until a.length()).map {
            val o = a.getJSONObject(it); val s = o.getJSONArray("s")
            Effect(o.getString("n"), (0 until s.length()).map { i ->
                val t = s.getJSONArray(i); Step(t.getInt(0), t.getInt(1), t.getInt(2))
            })
        }
        return presets + user
    }
    fun save(c: Context, list: List<Effect>) {
        val a = JSONArray()
        list.filter { !it.builtin }.forEach { e ->
            a.put(JSONObject().put("n", e.name).put("s", JSONArray(e.steps.map { JSONArray(listOf(it.c, it.hold, it.fade)) })))
        }
        p(c).edit().putString("e", a.toString()).apply()
    }
}

object Engine {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private lateinit var app: Context
    @Volatile var speed = 1f

    fun init(c: Context) { app = c.applicationContext }
    fun stop() { job?.cancel() }
    private fun run(block: suspend CoroutineScope.() -> Unit) { stop(); job = scope.launch(block = block) }

    private suspend fun fade(a: Int, b: Int, ms: Long) {
        val n = ms / 50
        if (n <= 0) { Led.color(b); return }
        for (i in 1..n) { Led.color(ColorUtils.blendARGB(a, b, i / n.toFloat())); delay(50) }
    }

    fun play(e: Effect) = run {
        var p = e.steps.last().c
        while (isActive) for (s in e.steps) {
            fade(p, s.c, (s.fade / speed).toLong())
            delay(maxOf(20L, (s.hold / speed).toLong()))
            p = s.c
        }
    }

    // Album-cover colors, slowly cross-faded
    fun cover() = run {
        var p = Color.BLACK; var i = 0
        while (isActive) {
            Cover.refresh(app)
            val cs = Cover.cols
            val c = cs[i++ % cs.size]
            fade(p, c, (3000 / speed).toLong()); p = c
        }
    }

    // Music breathing via phone mic. fixed=null -> use cover color
    @SuppressLint("MissingPermission")
    fun music(fixed: Int?, sens: Float) = run {
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 8192)
        val buf = ShortArray(800)
        var env = 0f; var tick = 0
        var base = fixed ?: Cover.cols[0]
        rec.startRecording()
        try {
            while (isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var s = 0.0
                for (i in 0 until n) s += buf[i] * buf[i].toDouble()
                val lv = (sqrt(s / n) / 32768.0 * sens * 10).toFloat().coerceIn(0f, 1f)
                env = if (lv > env) lv else env * 0.88f + lv * 0.12f
                if (fixed == null && tick++ % 40 == 0) { Cover.refresh(app); base = Cover.cols[0] }
                Led.color(ColorUtils.blendARGB(Color.BLACK, base, 0.06f + 0.94f * env))
            }
        } finally { rec.release() }
    }
}
