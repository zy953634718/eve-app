package com.eve.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.Date

/**
 * M1 实验数据记录器 —— 把待机 CPU / 帧率 / 内存 / 状态采样成 CSV 落盘。
 *
 * 输出目录：Android/data/com.eve.pet/files/m1/
 *   metrics-<ts>.csv
 * 字段：elapsed_s, cpu_percent, fps_target, fps_state, rss_mb, blocked, note
 *
 * 采样 CPU 的方式与 M1 文档命令行口径一致（/proc/<pid>/stat 的 utime+stime 差分），
 * 便于和 adb 侧数据交叉验证。
 */
class ExperimentLogger(private val ctx: Context) {

    companion object {
        private const val SAMPLE_INTERVAL_MS = 5000L
        private const val CLK_TCK = 100f          // Linux 用户态 HZ，Android 恒为 100
    }

    private val handler = Handler(Looper.getMainLooper())
    private var file: File? = null
    private var startNs = 0L
    private var lastCpuTicks = 0L
    private var lastSampleNs = 0L
    private var sampleCount = 0
    private var running = false

    @Volatile
    var fpsTarget: Int = 12
    @Volatile
    var stateLabel: String = "IDLE"
    @Volatile
    var blocked: Boolean = false
    @Volatile
    var emotionKey: String = "idle"

    /** 开始一次记录，返回 CSV 文件路径 */
    fun start(prefix: String = "metrics"): File {
        val dir = File(ctx.getExternalFilesDir(null), "m1").apply { mkdirs() }
        val f = File(dir, "$prefix-${System.currentTimeMillis()}.csv")
        f.writeText("elapsed_s,cpu_percent,fps_target,fps_state,rss_mb,blocked,emotion\n")
        file = f
        startNs = System.nanoTime()
        lastSampleNs = startNs
        lastCpuTicks = readCpuTicks()
        sampleCount = 0
        running = true
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
        return f
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    val outputDir: File
        get() = File(ctx.getExternalFilesDir(null), "m1")

    private val sampler = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dtSec = (now - lastSampleNs) / 1_000_000_000.0
            val ticks = readCpuTicks()
            val dTicks = (ticks - lastCpuTicks).coerceAtLeast(0)
            val cpuPercent = if (dtSec > 0) (dTicks / (CLK_TCK * dtSec)) * 100.0 else 0.0
            lastCpuTicks = ticks
            lastSampleNs = now
            sampleCount++
            val elapsed = (now - startNs) / 1_000_000_000.0
            val line = String.format(
                java.util.Locale.US,
                "%.1f,%.2f,%d,%s,%.1f,%s,%s\n",
                elapsed, cpuPercent, fpsTarget, stateLabel, rssMb(), blocked.toString(), emotionKey
            )
            try {
                file?.appendText(line)
            } catch (e: Exception) {
                // 磁盘不可写时静默（不影响主流程）
            }
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    /** /proc/self/stat 的 utime+stime（单位 tick） */
    private fun readCpuTicks(): Long = try {
        val stat = File("/proc/self/stat").readText()
        // 进程名的括号内可能含空格，从最后一个 ')' 之后开始切分
        val tail = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        // 切分后 index 0 = state，utime 为 index 11，stime 为 index 12
        (tail[11].toLong() + tail[12].toLong())
    } catch (e: Exception) {
        0L
    }

    private fun rssMb(): Double = try {
        val statm = File("/proc/self/statm").readText().split(' ')
        val pages = statm[1].toLong()
        pages * 4096.0 / 1024.0 / 1024.0
    } catch (e: Exception) {
        0.0
    }

    fun summary(): String {
        val f = file ?: return "未开始记录"
        return "已采样 $sampleCount 条 → ${f.absolutePath}"
    }

    /** 便捷：报告头信息（机型 + 时间），用于和 CSV 一起归档 */
    fun writeHeader(): File? {
        file?.let { f ->
            f.appendText("# ${Date()} | ${HardwareDecoderProbe.deviceSummary().replace("\n", " | ")}\n")
            return f
        }
        return null
    }
}
