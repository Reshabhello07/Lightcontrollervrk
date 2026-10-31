package com.ledstudio.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// Quick Settings tile for Smart tempo.
//  tap (off) -> connects to the strip and turns Smart tempo on
//  tap (on)  -> turns everything off and disconnects from the strip
class SmartTempoTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (isOn()) {
            stopAll(applicationContext)
            Led.disconnect()
            refresh(false)
            return
        }
        openHelper()
    }

    @Suppress("DEPRECATION")
    private fun openHelper() {
        val i = Intent(this, TileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("style", 2)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(i)
        }
    }

    private fun isOn() = LedService.running && LedService.style == 2

    private fun refresh(on: Boolean = isOn()) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "LED Smart Tempo"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }
}

object Tiles {
    /** Asks the system to refresh the tile (no-op if the tile isn't added). */
    fun poke(ctx: Context) {
        try { TileService.requestListeningState(ctx, ComponentName(ctx, SmartTempoTileService::class.java)) } catch (e: Exception) { }
    }
}
