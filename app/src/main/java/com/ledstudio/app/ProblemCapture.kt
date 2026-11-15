package com.ledstudio.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** Captures frame timing and process health locally, then lets the user share the report. */
object ProblemCapture {
    private const val CAPTURE_MS = 20_000L
    val status = mutableStateOf("Ready to capture a 20-second performance report")

    private data class Sample(
        val total: Long,
        val layout: Long,
        val draw: Long,
        val sync: Long,
        val command: Long,
        val swap: Long,
        val gpu: Long,
        val unknown: Long
    )

    private data class ActiveCapture(
        val activity: Activity,
        val startedWallMs: Long,
        val startedCpuMs: Long,
        val thread: HandlerThread,
        val samples: MutableList<Sample>,
        val listener: Window.OnFrameMetricsAvailableListener,
        val finish: Runnable
    )

    private val main = Handler(android.os.Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var active: ActiveCapture? = null

    private fun findActivity(context: Context): Activity? {
        var current: Context = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    fun start(context: Context) {
        val activity = findActivity(context) ?: run {
            status.value = "Could not start capture from this screen"
            return
        }
        if (active != null) {
            status.value = "Capture is already running — reproduce the lag now"
            return
        }

        val thread = HandlerThread("LedStudio-FrameMetrics").apply { start() }
        val samples = mutableListOf<Sample>()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val sample = Sample(
                total = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                layout = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                draw = metrics.getMetric(FrameMetrics.DRAW_DURATION),
                sync = metrics.getMetric(FrameMetrics.SYNC_DURATION),
                command = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                swap = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                gpu = metrics.getMetric(FrameMetrics.GPU_DURATION),
                unknown = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
            )
            synchronized(samples) { if (samples.size < 20_000) samples.add(sample) }
        }
        val startedWall = System.currentTimeMillis()
        val startedCpu = Process.getElapsedCpuTime()
        lateinit var capture: ActiveCapture
        val finish = Runnable { finish(capture) }
        capture = ActiveCapture(activity, startedWall, startedCpu, thread, samples, listener, finish)
        active = capture
        activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        status.value = "Capturing for 20 seconds — reproduce the lag now"
        main.postDelayed(finish, CAPTURE_MS)
    }

    /** Stop a pending capture if its Activity is destroyed, so no Window is retained. */
    fun activityDestroyed(activity: Activity) {
        val capture = active ?: return
        if (capture.activity === activity) {
            main.removeCallbacks(capture.finish)
            runCatching { activity.window.removeOnFrameMetricsAvailableListener(capture.listener) }
            capture.thread.quitSafely()
            active = null
            status.value = "Capture stopped because the app screen closed. Start another capture and keep LED Studio open."
        }
    }

    private fun finish(capture: ActiveCapture) {
        if (active !== capture) return
        active = null
        runCatching { capture.activity.window.removeOnFrameMetricsAvailableListener(capture.listener) }
        capture.thread.quitSafely()
        status.value = "Preparing performance report…"
        val samples = synchronized(capture.samples) { capture.samples.toList() }
        val wallMs = (System.currentTimeMillis() - capture.startedWallMs).coerceAtLeast(1)
        val cpuMs = (Process.getElapsedCpuTime() - capture.startedCpuMs).coerceAtLeast(0)
        val appContext = capture.activity.applicationContext
        val refreshRate = runCatching { capture.activity.windowManager.defaultDisplay.refreshRate }.getOrDefault(0f)
        val screen = runCatching {
            val metrics = appContext.resources.displayMetrics
            "${metrics.widthPixels}x${metrics.heightPixels} px, density ${metrics.densityDpi} dpi"
        }.getOrDefault("unknown")
        val mode = "${LedService.style}"
        val connection = Led.status.value

        ioScope.launch {
            try {
                val report = makeReport(appContext, samples, wallMs, cpuMs, refreshRate, screen, mode, connection)
                val dir = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "ledstudio-performance-$stamp.txt").apply { writeText(report) }
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
                withContext(Dispatchers.Main) {
                    status.value = "Report ready — choose where to share it"
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "LED Studio performance report")
                        putExtra(Intent.EXTRA_TEXT, "LED Studio performance capture. Attach this report in the chat for analysis.")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(appContext.contentResolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    appContext.startActivity(Intent.createChooser(send, "Share performance report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status.value = "Could not create report: ${e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun makeReport(
        context: Context,
        samples: List<Sample>,
        wallMs: Long,
        cpuMs: Long,
        refreshRate: Float,
        screen: String,
        mode: String,
        connection: String
    ): String {
        val valid = samples.filter { it.total > 0 }
        fun ms(ns: Long) = "%.2f ms".format(Locale.US, ns / 1_000_000.0)
        fun percentile(values: List<Long>, fraction: Double): Long {
            if (values.isEmpty()) return 0L
            val index = ((values.size - 1) * fraction.coerceIn(0.0, 1.0)).roundToLong().toInt().coerceIn(0, values.size - 1)
            return values.sorted()[index]
        }
        fun component(label: String, values: List<Long>) = "$label p95: ${ms(percentile(values, 0.95))}"
        val totals = valid.map { it.total }
        val frameBudgetNs = if (refreshRate > 0f) (1_000_000_000f / refreshRate).toLong() else 16_666_667L
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val cpuPercentOfOneCore = cpuMs * 100.0 / wallMs
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { "${context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus}" }.getOrDefault("unknown")
        } else "not available"
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())

        return buildString {
            appendLine("LED Studio performance report")
            appendLine("Captured: $timestamp")
            appendLine("Duration: ${wallMs / 1000.0}s")
            appendLine("This file contains local performance/device metrics only; it is not sent automatically.")
            appendLine()
            appendLine("DEVICE")
            appendLine("Manufacturer/model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Display: $screen, reported refresh rate ${"%.1f".format(Locale.US, refreshRate)} Hz")
            appendLine("Available CPU cores: ${runtime.availableProcessors()}")
            appendLine("Thermal status: $thermal")
            appendLine()
            appendLine("APP STATE")
            appendLine("LED connection: $connection")
            appendLine("Active LED service style: $mode")
            appendLine("Process CPU time: ${cpuMs} ms (${"%.1f".format(Locale.US, cpuPercentOfOneCore)}% of one core over capture)")
            appendLine("App memory PSS: ${memory.totalPss} KB")
            appendLine("Java heap: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024} KB used / ${runtime.maxMemory() / 1024} KB max")
            appendLine()
            appendLine("FRAME TIMINGS")
            appendLine("Frames captured: ${valid.size}")
            appendLine("Frame budget used for counts: ${ms(frameBudgetNs)} (${if (refreshRate > 0f) "based on reported refresh rate" else "fallback estimate"})")
            appendLine("Frames over 8.33 ms: ${totals.count { it > 8_333_333L }} (${percent(totals.count { it > 8_333_333L }, totals.size)})")
            appendLine("Frames over 16.67 ms: ${totals.count { it > 16_666_667L }} (${percent(totals.count { it > 16_666_667L }, totals.size)})")
            appendLine("Frames over 33.33 ms: ${totals.count { it > 33_333_333L }} (${percent(totals.count { it > 33_333_333L }, totals.size)})")
            appendLine("Average total frame: ${ms(if (totals.isEmpty()) 0 else totals.average().toLong())}")
            appendLine("p50 total frame: ${ms(percentile(totals, 0.50))}")
            appendLine("p90 total frame: ${ms(percentile(totals, 0.90))}")
            appendLine("p95 total frame: ${ms(percentile(totals, 0.95))}")
            appendLine("Maximum total frame: ${ms(totals.maxOrNull() ?: 0)}")
            appendLine(component("Layout/measure", valid.map { it.layout }))
            appendLine(component("Draw", valid.map { it.draw }))
            appendLine(component("Sync", valid.map { it.sync }))
            appendLine(component("Command issue", valid.map { it.command }))
            appendLine(component("Buffer swap", valid.map { it.swap }))
            appendLine(component("GPU", valid.map { it.gpu }))
            val gpuP95 = percentile(valid.map { it.gpu }, 0.95)
            val uiP95 = percentile(valid.map { it.layout + it.draw }, 0.95)
            val assessment = when {
                valid.isEmpty() -> "No frame samples were collected; keep the app visible throughout capture."
                gpuP95 > frameBudgetNs -> "GPU rendering may be a major bottleneck (GPU p95 exceeds the selected frame budget)."
                uiP95 > frameBudgetNs -> "UI layout/drawing may be a major bottleneck (layout + draw p95 exceeds the selected frame budget)."
                totals.count { it > frameBudgetNs } > totals.size / 5 -> "Many frames missed the selected budget, but reported GPU and UI work are below it; scheduling, glass compositing, or system load may be involved."
                else -> "Most sampled frames met the selected budget; reproduce the lag during the full capture and try again if needed."
            }
            appendLine("Automatic assessment: $assessment")
            appendLine(component("Unknown delay", valid.map { it.unknown }))
            appendLine()
            appendLine("Frame timing values are collected from Android FrameMetrics. They indicate where time was spent, but do not by themselves prove a single root cause.")
        }
    }

    private fun percent(count: Int, total: Int): String = if (total == 0) "n/a" else "%.1f%%".format(Locale.US, count * 100.0 / total)
}
