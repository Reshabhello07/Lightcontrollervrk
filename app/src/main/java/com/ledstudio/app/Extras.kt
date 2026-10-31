package com.ledstudio.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.sin

object Extras {
    val callOn = mutableStateOf(true)
    val battOn = mutableStateOf(true)
    val mcStatus = mutableStateOf("Off")
    private val scope = CoroutineScope(Dispatchers.Default)
    private var ringJob: Job? = null
    private var mcJob: Job? = null
    private var registered = false

    // ---------- Incoming call ring: each contact gets their own colour ----------
    fun ringStart(name: String) {
        if (!callOn.value || ringJob?.isActive == true) return
        val c = Led.hsv((abs(name.hashCode()) % 360).toFloat())
        val dim = ColorUtils.blendARGB(Color.BLACK, c, 0.08f)
        val wasFx = Led.paused
        ringJob = scope.launch {
            try {
                while (isActive) {
                    Led.holdUntil = System.currentTimeMillis() + 900
                    Led.fadeTo(c, 280, true, true); delay(450)
                    Led.holdUntil = System.currentTimeMillis() + 900
                    Led.fadeTo(dim, 280, true, true); delay(450)
                }
            } finally {
                Led.holdUntil = 0
                if (wasFx) Led.effect(Engine.fx.value)
            }
        }
    }
    fun ringStop() { ringJob?.cancel(); ringJob = null }

    // ---------- Battery alerts ----------
    fun initPhone(ctx: Context) {
        if (registered) return
        registered = true
        val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED) }
        ctx.registerReceiver(object : BroadcastReceiver() {
            var last = -1
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_POWER_CONNECTED) { if (battOn.value) Led.pulse(0xFF00FF66.toInt()); return }
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                if (lvl < 0) return
                if (battOn.value && last >= 0) {
                    if (!plugged && lvl < last && (lvl == 15 || lvl == 10 || lvl == 5)) Led.pulse(Color.RED, 1500)
                    if (plugged && lvl == 100 && last < 100) Led.pulse(0xFF2288FF.toInt(), 1500)
                }
                last = lvl
            }
        }, f)
    }

    fun showBattery(ctx: Context) {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (l >= 0) Led.pulse(Led.hsv(l * 1.2f), 3000)   // red (empty) -> green (full)
    }

    // ---------- Minecraft sync (the game mod sends UDP to 127.0.0.1:4210) ----------
    private val KEYS = listOf(0L to 0xFFFF9A3C.toInt(), 1500L to 0xFFFFF0C8.toInt(), 11000L to 0xFFFFF0C8.toInt(),
        12500L to 0xFFFF5A1F.toInt(), 13800L to 0xFF141C5A.toInt(), 22500L to 0xFF141C5A.toInt(), 24000L to 0xFFFF9A3C.toInt())

    private fun sky(t: Long): Int {
        for (i in 1 until KEYS.size) if (t <= KEYS[i].first) {
            val a = KEYS[i - 1]; val b = KEYS[i]
            return ColorUtils.blendARGB(a.second, b.second, (t - a.first).toFloat() / (b.first - a.first))
        }
        return KEYS.last().second
    }

    fun mcStart() {
        Engine.stop()
        mcJob = scope.launch {
            var hp = 20f; var lastHp = 20f; var dim = "overworld"; var tm = -1L
            var lastPkt = 0L; var flash = 0L
            val sock = DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 4210)); soTimeout = 40
            }
            val buf = ByteArray(256); val pkt = DatagramPacket(buf, buf.size)
            mcStatus.value = "Waiting for Minecraft..."
            try {
                while (isActive) {
                    try {
                        pkt.length = buf.size
                        sock.receive(pkt)
                        val m = String(pkt.data, 0, pkt.length, Charsets.UTF_8).split(";")
                            .associate { val p = it.split("="); p[0] to p.getOrElse(1) { "" } }
                        hp = m["hp"]?.toFloatOrNull() ?: hp
                        dim = m["dim"] ?: dim
                        tm = m["t"]?.toLongOrNull() ?: tm
                        if (hp < lastHp - 0.01f) flash = System.currentTimeMillis()
                        lastHp = hp; lastPkt = System.currentTimeMillis()
                        mcStatus.value = "Connected to Minecraft"
                    } catch (e: SocketTimeoutException) { }
                    val now = System.currentTimeMillis()
                    if (lastPkt == 0L) continue
                    val d = dim.lowercase()
                    var c = when {
                        d.contains("nether") -> {
                            val f = ((sin(now / 300.0) + sin(now / 170.0) * 0.6 + 1.6) / 3.2).toFloat()
                            Color.HSVToColor(floatArrayOf(4f + 14f * f, 1f, 0.45f + 0.5f * f))
                        }
                        d.contains("end") -> Color.HSVToColor(floatArrayOf(275f + 20f * sin(now / 2000.0).toFloat(), 0.9f, 0.45f + 0.25f * sin(now / 1500.0).toFloat()))
                        tm >= 0 -> sky(tm % 24000)
                        else -> 0xFFFFF0C8.toInt()
                    }
                    if (hp in 0.1f..6f) {   // low health heartbeat
                        val ph = (now % 900) / 900f
                        val pulse = if (ph < 0.15f) 1 - ph / 0.15f else if (ph in 0.3f..0.42f) 0.6f * (1 - (ph - 0.3f) / 0.12f) else 0f
                        c = ColorUtils.blendARGB(c, Color.RED, (0.85f * pulse).coerceIn(0f, 1f))
                    }
                    if (hp <= 0f) c = ColorUtils.blendARGB(Color.BLACK, Color.RED, 0.3f)
                    val hit = now - flash < 220
                    if (now - lastPkt > 3000) mcStatus.value = "Game paused or closed"
                    Led.fadeTo(if (hit) Color.RED else c, if (hit) 40 else 90)
                }
            } finally { sock.close(); mcStatus.value = "Off" }
        }
    }
    fun mcStop() { mcJob?.cancel(); mcJob = null }
}
