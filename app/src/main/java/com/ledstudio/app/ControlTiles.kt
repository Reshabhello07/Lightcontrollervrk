package com.ledstudio.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings shortcut that opens the required Android screen-capture consent before starting Smart Tempo. */
class SmartTempoTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateState()
    }

    override fun onClick() {
        super.onClick()
        if (LedService.running && LedService.style == 2) {
            stopAll(this, afterglow = false)
            stopService(Intent(this, LedService::class.java))
            updateState()
            return
        }

        val launch = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_SMART_TEMPO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }

    private fun updateState() {
        qsTile?.apply {
            icon = Icon.createWithResource(this@SmartTempoTileService, R.drawable.ic_tile_tempo)
            label = "Smart Tempo"
            state = if (LedService.running && LedService.style == 2) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}

/** Stops the LED effect, projection session, and Bluetooth connection. */
class DisconnectTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateState()
    }

    override fun onClick() {
        super.onClick()
        stopAll(this, afterglow = false)
        stopService(Intent(this, LedService::class.java))
        Led.disconnect()
        updateState()
    }

    private fun updateState() {
        qsTile?.apply {
            icon = Icon.createWithResource(this@DisconnectTileService, R.drawable.ic_tile_disconnect)
            label = "Disconnect fully"
            state = if (Led.status.value.startsWith("Connected")) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}

fun refreshQuickSettingsTiles(context: Context) {
    TileService.requestListeningState(context, ComponentName(context, SmartTempoTileService::class.java))
    TileService.requestListeningState(context, ComponentName(context, DisconnectTileService::class.java))
}
