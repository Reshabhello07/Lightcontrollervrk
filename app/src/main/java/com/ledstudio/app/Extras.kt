package com.ledstudio.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import androidx.compose.runtime.mutableStateOf

object Extras {
    val battOn = mutableStateOf(true)
    private var registered = false

    // Battery alerts: green on plug-in, red at 15/10/5%, blue when full
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
}
