package com.ledstudio.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Engine.init(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

fun perms(): Array<String> =
    (if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) + Manifest.permission.RECORD_AUDIO

@Composable
fun App() {
    val ctx = LocalContext.current
    var hue by remember { mutableStateOf(0f) }
    var hold by remember { mutableStateOf(500f) }
    var fade by remember { mutableStateOf(1000f) }
    var spd by remember { mutableStateOf(1f) }
    var sens by remember { mutableStateOf(1f) }
    var useCover by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<Step>() }
    val effects = remember { mutableStateListOf<Effect>().apply { addAll(Store.all(ctx)) } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Led.connect(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LED Studio", style = MaterialTheme.typography.headlineMedium)
        Text(Led.status.value)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { perm.launch(perms()) }) { Text("Connect") }
            OutlinedButton(onClick = { Engine.stop(); Led.color(0) }) { Text("Off") }
        }
        Text("Brightness")
        Slider(value = Led.bright.value, onValueChange = { Led.bright.value = it }, valueRange = 0.05f..1f)
        Text("Speed")
        Slider(value = spd, onValueChange = { spd = it; Engine.speed = it }, valueRange = 0.25f..4f)
        Text("Color")
        Slider(value = hue, onValueChange = { hue = it; Engine.stop(); Led.color(Led.hsv(it)) }, valueRange = 0f..360f)

        HorizontalDivider()
        Text("Music & cover", style = MaterialTheme.typography.titleMedium)
        Text("Mic sensitivity")
        Slider(value = sens, onValueChange = { sens = it }, valueRange = 0.2f..3f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useCover, onCheckedChange = { useCover = it }); Spacer(Modifier.width(8.dp)); Text("Breathe in cover color")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Engine.music(if (useCover) null else Led.hsv(hue), sens) }) { Text("Music breathing") }
            Button(onClick = { Engine.cover() }) { Text("Cover mesh") }
        }
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Grant notification access (for covers)") }

        HorizontalDivider()
        Text("Effects", style = MaterialTheme.typography.titleMedium)
        effects.toList().forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, Modifier.weight(1f))
                Button(onClick = { Engine.play(e) }) { Text("Play") }
                if (!e.builtin) TextButton(onClick = { effects.remove(e); Store.save(ctx, effects) }) { Text("Delete") }
            }
        }

        HorizontalDivider()
        Text("Build your own", style = MaterialTheme.typography.titleMedium)
        Text("Pick a color with the Color slider, set hold/fade, then Add step.")
        Text("Hold: ${hold.toInt()} ms")
        Slider(value = hold, onValueChange = { hold = it }, valueRange = 20f..3000f)
        Text("Fade: ${fade.toInt()} ms")
        Slider(value = fade, onValueChange = { fade = it }, valueRange = 0f..5000f)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { steps.forEach { Box(Modifier.size(26.dp).background(Color(it.c))) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { steps.add(Step(Led.hsv(hue), hold.toInt(), fade.toInt())) }) { Text("Add step") }
            OutlinedButton(onClick = { if (steps.isNotEmpty()) Engine.play(Effect("p", steps.toList())) }) { Text("Preview") }
            TextButton(onClick = { steps.clear() }) { Text("Clear") }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Effect name") }, singleLine = true)
        Button(onClick = {
            if (name.isNotBlank() && steps.isNotEmpty()) {
                effects.add(Effect(name.trim(), steps.toList())); Store.save(ctx, effects); name = ""; steps.clear()
            }
        }) { Text("Save effect") }
    }
}
