package com.eve.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 面部层：矢量实时绘制（design/led-face.html 落地）。
 *
 * 设计要点：
 *  - 与身体层解耦、独立刷新：静态表情时**0 帧**（不跑 vsync 循环）
 *  - 说话时眼睛与嘴巴共享同一音量包络（「眼嘴联动」）
 *  - 切换心情 = 补间参数，形状自动形变（220ms）
 */
class FaceView(context: Context) : View(context) {

    companion object {
        private const val TRANSITION_MS = 220L
        private const val BLINK_MS = 130L
    }

    private var emotion: Emotion = Emotions.IDLE
    private var fromEmotion: Emotion = Emotions.IDLE
    private var transitionStartNs = 0L

    /** 当前绘制的参数（补间结果） */
    private var curEyeL: EyeSpec = emotion.left()
    private var curEyeR: EyeSpec = emotion.right()
    private var curMouth: MouthSpec = emotion.mouth
    private var curColor: Int = emotion.color.toInt()

    /** 说话联动：音量包络 0..1 */
    @Volatile
    var talking: Boolean = false
        private set
    private var envelope = 0f

    // 眨眼
    private var blinkUntilNs = 0L
    private var nextBlinkNs = 0L
    private val blinkScale: Float
        get() {
            val now = System.nanoTime()
            if (now >= blinkUntilNs) return 1f
            val p = 1f - (blinkUntilNs - now) / (BLINK_MS * 1_000_000f)
            // 0→1→0 的快速闭眼曲线
            return 1f - (1f - abs(p * 2f - 1f)).coerceIn(0f, 1f) * 0.92f
        }

    /** 面部锚点（由容器按宠物尺寸设定） */
    private var anchorX = 0f
    private var anchorY = 0f
    private var unit = 1f          // 设计 px → 实际 px 的缩放系数

    private var running = false
    @Volatile
    private var vsyncScheduled = false
    private val handler = Handler(Looper.getMainLooper())

    /** 帧回调（供被屏蔽检测使用） */
    var onFrame: (() -> Unit)? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            vsyncScheduled = false
            if (!running) return
            tick(frameTimeNanos)
            val needMore = System.nanoTime() < transitionStartNs + TRANSITION_MS * 1_000_000L ||
                talking || System.nanoTime() < blinkUntilNs
            if (needMore) {
                scheduleVsync()
            } else {
                running = false
                invalidate()   // 收尾画最后一帧，然后彻底停表（静态表情 0 帧）
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        scheduleBlink()
    }

    fun setAnchor(cx: Float, cy: Float, petSizePx: Float) {
        anchorX = cx
        anchorY = cy
        unit = petSizePx / 112f      // 设计稿宠物 112px 为基准
        invalidate()
    }

    /** 切换心情：启动补间动画 */
    fun setEmotion(e: Emotion) {
        if (e.key == emotion.key && running) return
        fromEmotion = emotion
        emotion = e
        curColor = e.color.toInt()
        transitionStartNs = System.nanoTime()
        start()
    }

    fun currentEmotion(): Emotion = emotion

    fun setTalking(on: Boolean) {
        talking = on
        if (on) start()
    }

    /** 立即眨眼一次（单击打招呼用） */
    fun blink() {
        blinkUntilNs = System.nanoTime() + BLINK_MS * 1_000_000L
        start()
    }

    private fun scheduleBlink() {
        handler.postDelayed({
            blink()
            scheduleBlink()
        }, (3000..6000).random().toLong())
    }

    private fun start() {
        running = true
        scheduleVsync()
    }

    private fun scheduleVsync() {
        if (!vsyncScheduled) {
            vsyncScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun tick(ns: Long) {
        // 补间：from → to 数值插值
        val raw = ((ns - transitionStartNs) / (TRANSITION_MS * 1_000_000f)).coerceIn(0f, 1f)
        val p = raw * raw * (3 - 2 * raw)   // smoothstep
        curEyeL = lerpEye(fromEmotion.left(), emotion.left(), p)
        curEyeR = lerpEye(fromEmotion.right(), emotion.right(), p)
        curMouth = lerpMouth(fromEmotion.mouth, emotion.mouth, p)

        // 说话包络：多频叠加制造语音起伏（设计：唯一节奏源驱动眼+嘴）
        val t = ns / 1_000_000_000f
        envelope = if (talking) {
            (0.25f + 0.75f * abs(sin(t * 11f.toDouble()).toFloat()) *
                (0.6f + 0.4f * abs(sin(t * 3.7f.toDouble()).toFloat()))).coerceIn(0f, 1f)
        } else 0f

        onFrame?.invoke()
        invalidate()
    }

    private fun lerpEye(a: EyeSpec, b: EyeSpec, p: Float): EyeSpec = EyeSpec(
        mode = if (p < 0.5f) a.mode else b.mode,
        w = a.w + (b.w - a.w) * p,
        h = a.h + (b.h - a.h) * p,
        arcDir = b.arcDir,
        arcK = a.arcK + (b.arcK - a.arcK) * p,
        yOff = a.yOff + (b.yOff - a.yOff) * p,
        tilt = a.tilt + (b.tilt - a.tilt) * p
    )

    private fun lerpMouth(a: MouthSpec, b: MouthSpec, p: Float): MouthSpec = MouthSpec(
        mode = if (p < 0.5f) a.mode else b.mode,
        w = a.w + (b.w - a.w) * p,
        h = a.h + (b.h - a.h) * p,
        curve = a.curve + (b.curve - a.curve) * p,
        invert = b.invert
    )

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = unit
        if (u <= 0f) return

        // 说话时辉光增强：glow ×(1+e×0.45)（设计 §02）
        val glowScale = 1f + envelope * 0.45f
        val eyeY = anchorY - 8f * u
        val mouthY = anchorY + 20f * u
        val eyeDx = 26f * u
        val bs = blinkScale

        // 面部辉光
        glowPaint.color = withAlpha(curColor, (30 + 26 * envelope).toInt())
        canvas.drawCircle(anchorX, anchorY, 46f * u * glowScale, glowPaint)

        drawEye(canvas, curEyeL, anchorX - eyeDx, eyeY, u * glowScale, bs)
        drawEye(canvas, curEyeR, anchorX + eyeDx, eyeY, u * glowScale, bs)
        drawMouth(canvas, curMouth, anchorX, mouthY, u, envelope)
    }

    private fun drawEye(canvas: Canvas, spec: EyeSpec, cx: Float, cy: Float, u: Float, blink: Float) {
        val baseR = 14f * u
        val rx = baseR * spec.w
        val ry = baseR * spec.h * blink
        val color = curColor
        val strokeW = 3.6f * u

        canvas.save()
        canvas.translate(cx, cy + spec.yOff * u)
        if (spec.tilt != 0f) canvas.rotate(spec.tilt)

        when (spec.mode) {
            EyeMode.RING -> {
                strokePaint.color = color
                strokePaint.strokeWidth = strokeW
                strokePaint.setShadowLayer(6f * u, 0f, 0f, withAlpha(color, 120))
                canvas.drawOval(oval(-rx, -ry, rx, ry), strokePaint)
                strokePaint.clearShadowLayer()
            }
            EyeMode.SOLID -> {
                fillPaint.color = color
                fillPaint.setShadowLayer(8f * u, 0f, 0f, withAlpha(color, 130))
                canvas.drawOval(oval(-rx, -ry, rx, ry), fillPaint)
                fillPaint.clearShadowLayer()
            }
            EyeMode.ARC -> {
                // 上弧（困）= 弧口朝上；下弧（笑）= 弧口朝下
                strokePaint.color = color
                strokePaint.strokeWidth = strokeW
                val k = spec.arcK * 0.85f
                path.reset()
                path.moveTo(-rx, 0f)
                path.quadTo(0f, -spec.arcDir * ry * k, rx, 0f)
                canvas.drawPath(path, strokePaint)
            }
            EyeMode.LINE -> {
                strokePaint.color = color
                strokePaint.strokeWidth = strokeW
                canvas.drawLine(-rx, 0f, rx, 0f, strokePaint)
            }
            EyeMode.STAR -> {
                fillPaint.color = color
                val r1 = rx * 1.25f
                val r2 = rx * 0.34f
                path.reset()
                for (i in 0 until 8) {
                    val ang = Math.toRadians((i * 45 - 90).toDouble())
                    val r = if (i % 2 == 0) r1 else r2
                    val px = (r * Math.cos(ang)).toFloat()
                    val py = (r * Math.sin(ang)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            EyeMode.CROSS -> {
                strokePaint.color = color
                strokePaint.strokeWidth = strokeW * 0.9f
                val d = rx * 0.72f
                canvas.drawLine(-d, -d, d, d, strokePaint)
                canvas.drawLine(-d, d, d, -d, strokePaint)
            }
        }
        canvas.restore()
    }

    private fun drawMouth(canvas: Canvas, spec: MouthSpec, cx: Float, cy: Float, u: Float, env: Float) {
        val color = curColor
        val strokeW = 3.6f * u
        val w = 22f * u * spec.w
        val open = 1f + env * 1.5f     // 说话时张嘴幅度

        canvas.save()
        canvas.translate(cx, cy)
        when (spec.mode) {
            MouthMode.ARC -> {
                val depth = spec.curve * u * 0.35f
                if (spec.h > 0f) {
                    // 张开的笑口（兴奋）：填充月牙
                    fillPaint.color = withAlpha(color, 210)
                    path.reset()
                    path.moveTo(-w, 0f)
                    path.quadTo(0f, depth * open * 2f, w, 0f)
                    path.quadTo(0f, depth * open * 0.4f, -w, 0f)
                    path.close()
                    canvas.drawPath(path, fillPaint)
                } else {
                    strokePaint.color = color
                    strokePaint.strokeWidth = strokeW
                    strokePaint.setShadowLayer(5f * u, 0f, 0f, withAlpha(color, 110))
                    path.reset()
                    path.moveTo(-w, 0f)
                    path.quadTo(0f, depth * open, w, 0f)
                    canvas.drawPath(path, strokePaint)
                    strokePaint.clearShadowLayer()
                }
            }
            MouthMode.O -> {
                fillPaint.color = withAlpha(color, 220)
                val rr = (7f * u * spec.w) * (1f + env * 0.6f)
                val rh = (7f * u * (if (spec.h > 0f) spec.h / 7f else 1f)) * (1f + env * 0.9f)
                canvas.drawOval(oval(-rr, -rh, rr, rh), fillPaint)
            }
            MouthMode.WAVE -> {
                strokePaint.color = color
                strokePaint.strokeWidth = strokeW
                path.reset()
                val seg = w / 4f
                path.moveTo(-w, 0f)
                var x = -w
                var up = if (spec.invert) 1f else -1f
                while (x < w - 0.1f) {
                    path.quadTo(x + seg / 2f, up * 5f * u, x + seg, 0f)
                    x += seg
                    up = -up
                }
                canvas.drawPath(path, strokePaint)
            }
            MouthMode.LINE -> {
                strokePaint.color = color
                strokePaint.strokeWidth = strokeW
                canvas.drawLine(-w * 0.7f, 0f, w * 0.7f, 0f, strokePaint)
            }
        }
        canvas.restore()
    }

    private fun oval(l: Float, t: Float, r: Float, b: Float): RectF {
        rect.set(l, t, r, b)
        return rect
    }

    /** 释放（容器销毁时调用） */
    fun release() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}
