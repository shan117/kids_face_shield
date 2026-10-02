package com.shantanu.shield.util

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, file-based health heartbeat for diagnosing the multi-day child-phone freeze
 * (Realme suppresses our logcat). Writes one CSV line every [INTERVAL_MS] to filesDir/diag.log on a
 * dedicated IO coroutine — it never runs on the main thread, so it can't add to the very
 * main-thread saturation it's trying to measure. Zero behavior change to enforcement.
 *
 * Columns:
 *  time        wall-clock (the LAST line's time marks the freeze moment)
 *  up_s        process uptime seconds
 *  heapMB/maxMB Java heap used / max (rising trend = Java leak)
 *  natMB       native heap allocated (rising trend = native/graphics leak)
 *  thr         live thread count (rising = coroutine/thread leak)
 *  ovLive      overlays added − removed (should hover 0–1; climbing = window/Context leak)
 *  ovAdd/ovRem cumulative overlay add/remove counts
 *  poll        enforcement-loop iteration count (flat between lines = loop stalled/dead)
 *  pollAge_ms  ms since the last loop iteration (large = main-thread/loop stall)
 *  mainStall_ms measured main-thread dispatch latency (large = main thread saturated)
 *
 * Pull it (debug build):
 *   adb -s <serial> shell "run-as com.appsecure.shield cat files/diag.log" > diag.log
 */
object Diag {
    private const val INTERVAL_MS = 30_000L
    private const val MAX_BYTES = 512 * 1024L   // rotate to diag.log.1 past this

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val started = AtomicInteger(0)
    private val startUptime = SystemClock.elapsedRealtime()

    private val overlayAdds = AtomicInteger(0)
    private val overlayRemoves = AtomicInteger(0)
    private val pollCount = AtomicLong(0)
    @Volatile private var lastPollUptime = SystemClock.elapsedRealtime()
    @Volatile private var lastMainStallMs = -1L
    @Volatile private var logDir: File? = null

    fun start(context: Context) {
        if (started.getAndSet(1) == 1) return  // idempotent
        val appContext = context.applicationContext
        val file = File(appContext.filesDir, "diag.log")
        logDir = appContext.filesDir
        val mainHandler = Handler(Looper.getMainLooper())
        scope.launch {
            runCatching {
                if (!file.exists() || file.length() == 0L) {
                    file.appendText("=== diag start ${iso(System.currentTimeMillis())} ===\n")
                    file.appendText("time,up_s,heapMB,maxMB,natMB,thr,ovLive,ovAdd,ovRem,poll,pollAge_ms,mainStall_ms\n")
                }
            }
            while (isActive) {
                // Probe main-thread latency: enqueue a token, let it record its own dispatch delay.
                val postAt = SystemClock.uptimeMillis()
                mainHandler.post { lastMainStallMs = SystemClock.uptimeMillis() - postAt }

                runCatching {
                    val rt = Runtime.getRuntime()
                    val heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                    val maxMB = rt.maxMemory() / (1024 * 1024)
                    val natMB = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                    val now = SystemClock.elapsedRealtime()
                    val line = buildString {
                        append(iso(System.currentTimeMillis())); append(',')
                        append((now - startUptime) / 1000); append(',')
                        append(heapMB); append(',')
                        append(maxMB); append(',')
                        append(natMB); append(',')
                        append(Thread.activeCount()); append(',')
                        append(overlayAdds.get() - overlayRemoves.get()); append(',')
                        append(overlayAdds.get()); append(',')
                        append(overlayRemoves.get()); append(',')
                        append(pollCount.get()); append(',')
                        append(now - lastPollUptime); append(',')
                        append(lastMainStallMs)
                        append('\n')
                    }
                    if (file.length() > MAX_BYTES) {
                        runCatching { file.copyTo(File(file.parentFile, "diag.log.1"), overwrite = true) }
                        file.writeText("=== rotated ${iso(System.currentTimeMillis())} ===\n")
                    }
                    file.appendText(line)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    /**
     * Append one timestamped event line, for diagnosing a feature on a phone whose logcat is suppressed
     * (Realme and some Xiaomi builds hide our tags entirely). Never throws; no-op until [start] has run,
     * because without filesDir there is nowhere to put it.
     */
    fun event(tag: String, message: String) {
        val dir = logDir ?: return
        scope.launch {
            runCatching {
                val line = "${iso(System.currentTimeMillis())},EVENT,$tag,$message"
                File(dir, "diag.log").appendText(line + System.lineSeparator())
            }
        }
    }

    /** Call once per enforcement-loop iteration (cheap: two atomics). */
    fun onPoll() {
        pollCount.incrementAndGet()
        lastPollUptime = SystemClock.elapsedRealtime()
    }

    fun onOverlayAdded() { overlayAdds.incrementAndGet() }
    fun onOverlayRemoved() { overlayRemoves.incrementAndGet() }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private fun iso(ms: Long): String = fmt.format(Date(ms))
}
