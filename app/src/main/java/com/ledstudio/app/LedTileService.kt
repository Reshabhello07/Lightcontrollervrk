package com.ledstudio.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// Quick Settings tiles: tap to start / stop a music-synced light from the notification panel.
abstract class MusicTile(private val style: Int, private val name: String) : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val mine = LedService.running && LedService.style == style
        if (LedService.running) stopAll(applicationContext)   // stop whatever is running (the other tile's mode, or this one)
        if (mine) { refresh(); return }
        val i = Intent(this, TileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("style", style)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, style, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = LedService.running && LedService.style == style
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = name
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }
}

class LedTileService : MusicTile(0, "LED Breathing")
class SmartTempoTileService : MusicTile(2, "LED Smart Tempo")

object Tiles {
    /** Asks the system to refresh both tiles (no-op for tiles that aren't added). */
    fun poke(ctx: Context) {
        for (c in listOf(LedTileService::class.java, SmartTempoTileService::class.java))
            try { TileService.requestListeningState(ctx, ComponentName(ctx, c)) } catch (e: Exception) { }
    }
}
