package com.eve.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.PowerManager
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宠物渲染视图：SurfaceView + MediaCodec 播放带 alpha 的 VP9 序列视频。
 *
 * M1 对应实验：
 *  - 实验 A（H4）：VP9 with Alpha 能否硬解，透明通道是否正确（黑底 = alpha 未解出）
 *  - 实验 B（H1）：12fps 待机 CPU < 1%
 *  - 实验 C1：息屏时 targetFps=0，必须真正停止解码与渲染
 *
 * 兜底策略：assets/idle_vp9_alpha.webm 不存在（美术素材未生成）或解码器创建失败时，
 * 回退为 Canvas 占位动画（呼吸发光圆形），保证没有素材也能跑通
 * 「悬浮窗 + 状态机 + 帧率控制」全链路 —— M1 文档 §7 允许用几何体代替真实素材测性能。
 */
class PetView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "PetView"
        private const val ASSET_NAME = "idle_vp9_alpha.webm"
        private const val DEFAULT_FPS = 12          // 实验 B：待机 12fps
        private const val PLACEHOLDER_SIZE = 480f   // 占位动画逻辑坐标系（M1 文档 §2.3 同尺寸）
    }

    private var decoderThread: Thread? = null
    private var codec: MediaCodec? = null
    private var extractor: MediaExtractor? = null

    /** true = MediaCodec 视频模式；false = Canvas 占位动画模式 */
    var isVideoMode = false
        private set

    /** 绘制一帧后的回调（供被屏蔽检测打点） */
    var onFrame: (() -> Unit)? = null

    /** 宠物本体目标尺寸（px），由容器按窗口尺寸设定；占位动画按此缩放 */
    private var petSizePx = 0f

    fun setPetSize(px: Float) {
        petSizePx = px
    }

    /** 当前目标帧率；0 表示完全暂停（实验 C1 的关键） */
    @Volatile
    var targetFps: Int = DEFAULT_FPS
        private set

    private val paused = AtomicBoolean(false)
    private val pauseLock = Object()

    // ---- 占位动画状态 ----
    private var placeholderRunning = false
    private var lastFrameNs = 0L
    private var phase = 0f

    /** vsync 循环是否已挂起（防重复 post） */
    @Volatile
    private var vsyncScheduled = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 心跳：仅在不活跃时以 250ms 低频轮询，等条件恢复后重新进入 vsync 循环。
     * 修复点：旧版 vsync 回调即使不绘制也每帧自我重挂（120Hz 空转烧 CPU），
     * 现在判定不活跃就彻底退出 vsync 循环，CPU 归零。
     */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!placeholderRunning) return
            val liveFps = effectiveFps()
            if (paused.get() || !screenActive() || liveFps <= 0) {
                mainHandler.postDelayed(this, 250)
                return
            }
            scheduleVsync()
        }
    }

    // 光晕预渲染成位图，每帧只做 blit + 缩放（软件 canvas 下渐变实时填充太贵）
    private val glowBitmap: Bitmap = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                180f, 180f, 180f,
                intArrayOf(0x667FD8FF, 0x223FA9FF, 0x003FA9FF),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(180f, 180f, 180f, p)
    }
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val clearPaint = Paint()

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /**
     * M1 调试开关：adb 远程帧率覆盖（实验 B/C1 的在线调节工具）。
     *   adb shell settings put global eve_pet_fps 0   → 完全停画（定位 CPU 归因）
     *   adb shell settings put global eve_pet_fps 30  → 30fps 上限档
     * 删除该行即恢复纯 targetFps 控制。每秒最多查一次，避免 IPC 开销。
     */
    private var liveFpsCache = targetFps.toFloat()
    private var liveFpsAtNs = 0L

    private fun effectiveFps(): Int {
        val now = System.nanoTime()
        if (liveFpsAtNs == 0L || now - liveFpsAtNs > 1_000_000_000L) {
            liveFpsAtNs = now
            liveFpsCache = Settings.Global.getFloat(
                context.contentResolver, "eve_pet_fps", targetFps.toFloat()
            )
        }
        return liveFpsCache.toInt()
    }

    /**
     * 实验 C1 双保险（不依赖广播）：
     *  1) PowerManager.isInteractive —— 标准 ROM 息屏为 false
     *  2) Display.state —— 荣耀/华为 AOD 息屏下 isInteractive 可能为 true，
     *     但 Display 会进入 STATE_DOZE，此信号在国产 ROM 上更可靠
     * 两个信号任一判定「屏幕没亮」就停止渲染。
     */
    private fun screenActive(): Boolean {
        if (!powerManager.isInteractive) return false
        val d = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return true
        return d.state == Display.STATE_ON
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            vsyncScheduled = false
            if (!placeholderRunning) return
            val liveFps = effectiveFps()
            // 不活跃 → 退出 vsync 循环，切 250ms 心跳等恢复
            if (paused.get() || !screenActive() || liveFps <= 0) {
                lastFrameNs = frameTimeNanos
                mainHandler.postDelayed(heartbeat, 250)
                return
            }
            drawPlaceholderFrame(frameTimeNanos, liveFps)
            scheduleVsync()
        }
    }

    private fun scheduleVsync() {
        if (!vsyncScheduled) {
            vsyncScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    init {
        holder.setFormat(PixelFormat.TRANSLUCENT)   // 悬浮窗透明通道
        holder.addCallback(this)
    }

    // ==================== 生命周期 ====================

    override fun surfaceCreated(holder: SurfaceHolder) {
        startVideoPlayback()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseAll()
    }

    /** Service 销毁时手动释放 */
    fun release() {
        releaseAll()
    }

    // ==================== 帧率控制（对接 FrameRateController） ====================

    /**
     * 状态机回调入口。
     * fps > 0：恢复/调整渲染节奏；fps <= 0：彻底暂停（实验 C1）。
     */
    fun setTargetFps(fps: Int) {
        targetFps = fps
        if (fps <= 0) {
            paused.set(true)
        } else {
            paused.set(false)
            synchronized(pauseLock) { pauseLock.notifyAll() }
        }
    }

    // ==================== 视频模式（MediaCodec） ====================

    private fun startVideoPlayback() {
        val ex = MediaExtractor()
        try {
            val afd = context.assets.openFd(ASSET_NAME)
            ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        } catch (e: Exception) {
            // 素材不存在 → 占位动画兜底
            ex.release()
            startPlaceholder()
            return
        }

        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                trackIdx = i
                format = f
                break
            }
        }
        if (trackIdx < 0 || format == null) {
            ex.release()
            startPlaceholder()
            return
        }
        ex.selectTrack(trackIdx)
        extractor = ex

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val c: MediaCodec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, holder.surface, null, 0)
                start()
            }
        } catch (e: Exception) {
            // 解码器不可用（如 VP9 alpha 不支持）→ 占位兜底，实验 A 会记录该机型
            ex.release()
            startPlaceholder()
            return
        }
        codec = c
        isVideoMode = true

        decoderThread = Thread({ decodeLoop(c, ex) }, "eve-decoder").apply {
            priority = Thread.MIN_PRIORITY   // 待机 CPU 优化
            start()
        }
    }

    private fun decodeLoop(codec: MediaCodec, extractor: MediaExtractor) {
        val bufferInfo = MediaCodec.BufferInfo()
        var lastPtsUs = 0L
        var inputDone = false

        while (!Thread.currentThread().isInterrupted) {
            // 实验 C1：息屏时在此阻塞，真正停止解码与渲染
            waitIfPaused()

            // 喂输入
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(ib, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // 取输出
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER ||
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                outIdx >= 0 -> {
                    // 按 pts 间隔节流：素材本身是 12fps，不空转烧 CPU（实验 B）
                    val pts = bufferInfo.presentationTimeUs
                    val deltaUs = (pts - lastPtsUs).coerceAtLeast(0)
                    lastPtsUs = pts
                    if (deltaUs > 0) {
                        try {
                            Thread.sleep(deltaUs / 1000, ((deltaUs % 1000) * 1000).toInt())
                        } catch (e: InterruptedException) {
                            return
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, true)
                    onFrame?.invoke()

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        // 循环播放
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        inputDone = false
                        lastPtsUs = 0
                    }
                }
            }
        }
    }

    private fun waitIfPaused() {
        while ((paused.get() || !screenActive()) && !Thread.currentThread().isInterrupted) {
            synchronized(pauseLock) {
                try {
                    pauseLock.wait(200)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    // ==================== 占位动画模式（Canvas 兜底） ====================

    private fun startPlaceholder() {
        isVideoMode = false
        placeholderRunning = true
        lastFrameNs = 0L
        scheduleVsync()
    }

    /** 节流绘制一帧呼吸光晕 —— 只测性能链路，不代表最终美术 */
    private fun drawPlaceholderFrame(frameTimeNanos: Long, liveFps: Int) {
        val intervalNs = 1_000_000_000L / liveFps.coerceAtLeast(1)
        if (lastFrameNs != 0L && frameTimeNanos - lastFrameNs < intervalNs) return
        lastFrameNs = frameTimeNanos

        val canvas = holder.lockCanvas() ?: return
        try {
            // 清成全透明（悬浮窗 alpha 正确性肉眼验证同实验 A）
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            phase += 0.09f
            val breath = 0.80f + 0.20f * kotlin.math.sin(phase)
            val cx = width / 2f
            val cy = height / 2f
            // 占位光晕外径 360 逻辑 px → 映射到宠物目标尺寸
            val scale = (if (petSizePx > 0f) petSizePx else PLACEHOLDER_SIZE) / 360f

            canvas.save()
            canvas.scale(breath * scale, breath * scale, cx, cy)
            canvas.drawBitmap(glowBitmap, cx - 180f, cy - 180f, blitPaint)
            canvas.drawCircle(cx, cy, 96f, corePaint)
            canvas.restore()
            onFrame?.invoke()
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "post placeholder frame failed: ${e.message}")
            }
        }
    }

    // ==================== 释放 ====================

    private fun releaseAll() {
        placeholderRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        decoderThread?.interrupt()
        decoderThread = null
        try {
            codec?.stop()
        } catch (e: Exception) { /* 已释放 */ }
        try {
            codec?.release()
        } catch (e: Exception) { /* 已释放 */ }
        codec = null
        try {
            extractor?.release()
        } catch (e: Exception) { /* 已释放 */ }
        extractor = null
        paused.set(false)
    }
}
