package com.eve.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.animation.ValueAnimator

/**
 * 宠物容器：身体层 + 面部层 + 长按进度环 + 对白气泡，并统一处理手势。
 *
 * 手势规范（design/android-overlay.html §03）：
 *  - 长按 1.2s：环形进度从 12 点顺时针走满，宠物放大 112→128px、辉光增强；走满弹菜单
 *  - 松手过早：进度环 200ms 回退归零，不弹菜单
 *  - 单击：打个招呼（眼睛闪 + 头顶短对白 2.6s 淡出）
 *  - 双击：语音对话（跳过菜单，进入聆听态）
 *  - 拖动：跟手；松手由 Service 做贴边吸附
 */
class PetContainerView(context: Context) : FrameLayout(context) {

    companion object {
        private const val LONG_PRESS_MS = 1200f
        private const val BUBBLE_MS = 2600L
    }

    val body = PetView(context)
    val face = FaceView(context)

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onOpenMenu: (() -> Unit)? = null
    var onDrag: ((dtx: Float, dty: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    /** 每帧绘制打点（被屏蔽检测用） */
    var onFrame: (() -> Unit)? = null

    private var petSizePx = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // 长按进度
    private var pressing = false
    private var pressStartNs = 0L
    private var pressProgress = 0f
    private var ringAnimating = false
    private var ringRetractStartNs = 0L
    private var ringRetractFrom = 0f
    private var retractAnimator: ValueAnimator? = null
    private var longPressFired = false

    // 点击判定
    private var lastTapNs = 0L
    private var dragged = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    // 气泡
    private var bubbleText: String? = null
    private var bubbleAlpha = 0f
    private var bubbleShowAtNs = 0L

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF3EC8FF.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3f)
    }
    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x333EC8FF
        strokeWidth = dp(3f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        textSize = dp(11f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ringAnimating) return
            ringProgressTick(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // 窗口透明背景，仅宠物本体可点
        setWillNotDraw(false)
        body.setPetSize(0f)
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(face, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.onFrame = { onFrame?.invoke() }
        face.onFrame = { onFrame?.invoke() }

        setOnTouchListener { _, event -> onTouch(event) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val minDim = minOf(w, h).toFloat()
        petSizePx = minDim * 0.51f          // 宠物本体约占窗口一半（对应设计 112px）
        body.setPetSize(petSizePx)
        // 宠物中心略低于窗口中心，给头顶气泡留位置
        face.setAnchor(w / 2f, h * 0.60f, petSizePx)
    }

    // ==================== 状态透传 ====================

    fun setFps(fps: Int) {
        body.setTargetFps(fps)
    }

    fun setEmotion(e: Emotion) = face.setEmotion(e)
    fun setTalking(on: Boolean) = face.setTalking(on)
    fun blink() = face.blink()

    fun showBubble(text: String) {
        bubbleText = text
        bubbleShowAtNs = System.nanoTime()
        invalidate()
        handler.postDelayed({ invalidate() }, BUBBLE_MS + 100)
    }

    // ==================== 手势 ====================

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragged = false
                longPressFired = false
                pressing = true
                pressStartNs = System.nanoTime()
                pressProgress = 0f
                ringRetractFrom = 0f
                retractAnimator?.cancel()
                ringAnimating = true
                Choreographer.getInstance().postFrameCallback(frameCallback)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragged && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragged = true
                    cancelPress()               // 拖动优先，取消长按
                }
                if (dragged) {
                    onDrag?.invoke(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) {
                    onDragEnd?.invoke()
                } else if (longPressFired || pressProgress >= 0.999f) {
                    // 长按走满 → 菜单
                    cancelPress(animate = false)
                    pressProgress = 0f
                    invalidate()
                    onOpenMenu?.invoke()
                } else {
                    cancelPress()               // 松手过早：200ms 回退
                    val now = System.nanoTime()
                    if (now - lastTapNs < 300_000_000L) {
                        lastTapNs = 0L
                        onDoubleTap?.invoke()
                    } else {
                        lastTapNs = now
                        onSingleTap?.invoke()
                    }
                }
                pressing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPress()
                pressing = false
                return true
            }
        }
        return false
    }

    private fun ringProgressTick(ns: Long) {
        if (pressing) {
            val elapsedMs = (ns - pressStartNs) / 1_000_000f
            pressProgress = (elapsedMs / LONG_PRESS_MS).coerceIn(0f, 1f)
            // 宠物放大 112→128（×1.143）
            val s = 1f + 0.143f * pressProgress
            body.scaleX = s; body.scaleY = s
            face.scaleX = s; face.scaleY = s
            if (pressProgress >= 1f && !longPressFired) {
                longPressFired = true
                ringAnimating = false   // 交给 UP 处理弹菜单
                invalidate()
                return
            }
        } else if (ringRetractFrom > 0f) {
            // 回退动画
            val p = ((ns - ringRetractStartNs) / 200_000_000f).coerceIn(0f, 1f)
            pressProgress = ringRetractFrom * (1f - p)
            if (p >= 1f) {
                ringAnimating = false
                pressProgress = 0f
            }
        } else {
            ringAnimating = false
        }
        invalidate()
    }

    private fun cancelPress(animate: Boolean = true) {
        if (pressing && animate && pressProgress > 0.01f) {
            ringRetractFrom = pressProgress
            ringRetractStartNs = System.nanoTime()
            // 保持环动画继续跑回退
        } else {
            ringAnimating = false
            pressProgress = 0f
            invalidate()
        }
        pressing = false
        val s = 1f
        body.scaleX = s; body.scaleY = s
        face.scaleX = s; face.scaleY = s
        invalidate()
    }

    // ==================== 绘制（进度环 + 气泡，叠在最上层） ====================

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.60f
        val r = petSizePx * 0.62f

        // 长按进度环
        if (pressProgress > 0.001f) {
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawOval(rect, ringBgPaint)
            canvas.drawArc(rect, -90f, 360f * pressProgress, false, ringPaint)
        }

        // 对白气泡
        val text = bubbleText
        if (text != null) {
            val elapsedNs = System.nanoTime() - bubbleShowAtNs
            val p = elapsedNs / (BUBBLE_MS * 1_000_000f)
            if (p >= 1f) {
                bubbleText = null
            } else {
                // 出现快、末段淡出
                bubbleAlpha = when {
                    p < 0.08f -> p / 0.08f
                    p > 0.82f -> (1f - p) / 0.18f
                    else -> 1f
                }
                val textW = bubbleTextPaint.measureText(text)
                val padH = dp(11f)
                val padV = dp(7f)
                val bw = textW + padH * 2
                val bh = dp(11f) * 1.9f + padV
                val bx = cx
                val by = cy - r - dp(16f)
                rect.set(bx - bw / 2f, by - bh, bx + bw / 2f, by)
                bubblePaint.color = withAlpha(0xF2081620.toInt(), (228 * bubbleAlpha).toInt())
                canvas.drawRoundRect(rect, bh / 2f, bh / 2f, bubblePaint)
                bubblePaint.color = withAlpha(0xF23EC8FF.toInt(), (120 * bubbleAlpha).toInt())
                bubblePaint.style = Paint.Style.STROKE
                bubblePaint.strokeWidth = dp(1f)
                canvas.drawRoundRect(rect, bh / 2f, bh / 2f, bubblePaint)
                bubblePaint.style = Paint.Style.FILL
                bubbleTextPaint.alpha = (255 * bubbleAlpha).toInt().coerceIn(0, 255)
                val baseline = by - bh / 2f + bubbleTextPaint.textSize * 0.36f
                canvas.drawText(text, bx, baseline, bubbleTextPaint)
                invalidate()   // 维持淡出动画
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun release() {
        handler.removeCallbacksAndMessages(null)
        retractAnimator?.cancel()
        body.release()
        face.release()
    }
}
