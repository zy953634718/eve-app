package com.eve.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * 环形菜单（design/android-overlay.html §03 交互规范）。
 *
 *  - 8 项完整环形；贴边时自动收成朝屏幕内侧的 5 项扇区
 *  - 52px 圆 + 10.5px 标签；半径自适应 R = min(118dp, 30% 屏宽)
 *  - 弹入：45° 等距，逐项延迟 40ms，从 0.6R 外扩 + 淡入
 *  - 点空白 → 200ms 收回
 */
class RingMenuView(context: Context) : View(context) {

    /** 菜单项定义 */
    enum class Item(val title: String) {
        CHAT("聊聊天"), STORY("讲故事"), FLY("飞一圈"), SKIN("换皮肤"),
        TOMATO("番茄钟"), NAP("打盹"), SETTINGS("设置"), COLLAPSE("收起");

        /** 贴边扇区只保留这 5 项（design 屏 05） */
        val inEdgeSet: Boolean
            get() = this == CHAT || this == STORY || this == TOMATO || this == SETTINGS || this == COLLAPSE
    }

    enum class Mode { FULL, EDGE_RIGHT }   // EDGE_RIGHT：宠物贴左边缘，菜单朝右展开

    private val chipRadius = 26f            // 52px 圆
    private val labelSize = 11f
    private var ringRadius = 118f

    private var mode = Mode.FULL
    private var centerX = 0f
    private var centerY = 0f
    private var displayItems: List<Item> = emptyList()

    private var appearProgress = 1f
    private var dismissProgress = 0f
    private var selected: Item? = null
    private var animator: ValueAnimator? = null

    var onSelect: ((Item) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        setShadowLayer(dp(4f), 0f, dp(2f), 0xE6000000.toInt())
    }
    private val path = Path()
    private val rect = RectF()

    private val cyan = 0xFF3EC8FF.toInt()
    private val cyanDim = 0x4D3EC8FF

    init {
        labelPaint.textSize = dp(labelSize)
        iconPaint.color = cyan
    }

    /** 打开菜单。cx/cy 为宠物中心（屏幕坐标），radiusPx 由调用方按屏宽自适应算出 */
    fun show(cx: Float, cy: Float, radiusPx: Float, m: Mode) {
        centerX = cx
        centerY = cy
        ringRadius = radiusPx
        mode = m
        displayItems = Item.values().filter { m == Mode.FULL || it.inEdgeSet }
        selected = null
        dismissProgress = 0f
        appearProgress = 0f
        visibility = VISIBLE
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L + displayItems.size * 40L
            addUpdateListener {
                appearProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    /** 收回菜单（200ms 淡出） */
    fun dismiss(notify: Boolean = true) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            addUpdateListener {
                dismissProgress = it.animatedValue as Float
                invalidate()
                if (it.animatedFraction >= 1f) {
                    visibility = GONE
                    if (notify) onDismiss?.invoke()
                }
            }
            start()
        }
    }

    val isShowing: Boolean
        get() = visibility == VISIBLE && dismissProgress < 1f

    // ==================== 触摸 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isShowing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val hit = hitTest(event.x, event.y)
                if (hit != null) {
                    selected = hit
                    invalidate()
                    postDelayed({
                        dismiss()
                        onSelect?.invoke(hit)
                    }, 90)
                } else {
                    // 点空白 → 收回
                    dismiss()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Item? {
        val r = dp(chipRadius)
        displayItems.forEachIndexed { i, item ->
            val (ix, iy) = chipCenter(i)
            val dx = x - ix
            val dy = y - iy
            if (dx * dx + dy * dy <= (r + dp(6f)) * (r + dp(6f))) return item
        }
        return null
    }

    private fun chipCenter(index: Int): Pair<Float, Float> {
        val angleDeg = when (mode) {
            Mode.FULL -> -90f + index * (360f / displayItems.size)
            // 贴边：朝屏幕内侧的半扇区，5 项铺开 100°
            Mode.EDGE_RIGHT -> -50f + index * (100f / (displayItems.size - 1).coerceAtLeast(1))
        }
        val a = Math.toRadians(angleDeg.toDouble())
        return Pair(
            centerX + (ringRadius * Math.cos(a)).toFloat(),
            centerY + (ringRadius * Math.sin(a)).toFloat()
        )
    }

    // ==================== 绘制 ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val overall = (appearProgress * (1f - dismissProgress)).coerceIn(0f, 1f)
        if (overall <= 0f) return

        val r = dp(chipRadius)
        displayItems.forEachIndexed { i, item ->
            // 逐项延迟 40ms 弹出
            val startFraction = i * 40f / (220f + displayItems.size * 40f)
            val p = ((appearProgress - startFraction) / (1f - startFraction)).coerceIn(0f, 1f)
            if (p <= 0f) return@forEachIndexed

            val (fullX, fullY) = chipCenter(i)
            val scale = 0.6f + 0.4f * p
            val x = centerX + (fullX - centerX) * (0.6f + 0.4f * p)
            val y = centerY + (fullY - centerY) * (0.6f + 0.4f * p)
            val alpha = (255 * p * (1f - dismissProgress)).toInt().coerceIn(0, 255)
            val isSel = selected == item

            // 底盘
            chipPaint.color = withAlpha(0xF2142628.toInt(), alpha)
            chipPaint.setShadowLayer(dp(10f), 0f, dp(4f), withAlpha(0xB3000000.toInt(), alpha))
            canvas.drawCircle(x, y, r * scale * (if (isSel) 1.15f else 1f), chipPaint)
            chipPaint.clearShadowLayer()

            // 描边
            borderPaint.color = withAlpha(if (isSel) cyan else cyanDim, alpha)
            borderPaint.strokeWidth = dp(if (isSel) 1.6f else 1f)
            canvas.drawCircle(x, y, r * scale * (if (isSel) 1.15f else 1f), borderPaint)

            // 图标
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale * (if (isSel) 1.15f else 1f), scale * (if (isSel) 1.15f else 1f))
            iconPaint.color = withAlpha(cyan, alpha)
            drawIcon(canvas, item)
            canvas.restore()

            // 标签
            labelPaint.alpha = alpha
            canvas.drawText(item.title, x, y + r * (if (isSel) 1.15f else 1f) + dp(14f), labelPaint)
        }
    }

    private fun drawIcon(canvas: Canvas, item: Item) {
        val s = dp(11f)   // 图标半尺寸
        path.reset()
        when (item) {
            Item.CHAT -> {
                rect.set(-s, -s * 0.85f, s, s * 0.55f)
                canvas.drawRoundRect(rect, dp(4f), dp(4f), iconPaint)
                path.moveTo(-s * 0.25f, s * 0.55f)
                path.lineTo(-s * 0.45f, s * 0.95f)
                path.lineTo(s * 0.05f, s * 0.55f)
                canvas.drawPath(path, iconPaint)
            }
            Item.STORY -> {
                rect.set(-s, -s * 0.8f, 0f, s * 0.8f)
                canvas.drawRoundRect(rect, dp(2f), dp(2f), iconPaint)
                rect.set(0f, -s * 0.8f, s, s * 0.8f)
                canvas.drawRoundRect(rect, dp(2f), dp(2f), iconPaint)
                canvas.drawLine(0f, -s * 0.8f, 0f, s * 0.8f, iconPaint)
            }
            Item.FLY -> {
                rect.set(-s, -s, s, s)
                canvas.drawArc(rect, 40f, 280f, false, iconPaint)
                path.moveTo(s * 0.55f, -s * 0.72f)
                path.lineTo(s * 0.95f, -s * 0.62f)
                path.lineTo(s * 0.62f, -s * 0.28f)
                canvas.drawPath(path, iconPaint)
            }
            Item.SKIN -> {
                rect.set(-s * 0.9f, -s * 0.9f, s * 0.9f, s * 0.9f)
                canvas.drawArc(rect, 130f, 290f, false, iconPaint)
                canvas.drawCircle(s * 0.42f, -s * 0.34f, dp(2.2f), iconPaint)
                canvas.drawCircle(s * 0.06f, -s * 0.62f, dp(2.2f), iconPaint)
                canvas.drawCircle(-s * 0.5f, -s * 0.12f, dp(2.2f), iconPaint)
            }
            Item.TOMATO -> {
                canvas.drawCircle(0f, 0f, s * 0.92f, iconPaint)
                canvas.drawLine(0f, -s * 0.55f, 0f, 0f, iconPaint)
                canvas.drawLine(0f, 0f, s * 0.42f, 0f, iconPaint)
            }
            Item.NAP -> {
                rect.set(-s * 0.95f, -s * 0.95f, s * 0.5f, s * 0.5f)
                canvas.drawArc(rect, 90f, 200f, false, iconPaint)
                canvas.drawLine(s * 0.25f, s * 0.45f, s * 0.95f, s * 0.45f, iconPaint)
                canvas.drawLine(s * 0.55f, s * 0.72f, s * 0.95f, s * 0.72f, iconPaint)
            }
            Item.SETTINGS -> {
                canvas.drawCircle(0f, 0f, s * 0.36f, iconPaint)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    val x1 = (s * 0.62f * Math.cos(a)).toFloat()
                    val y1 = (s * 0.62f * Math.sin(a)).toFloat()
                    val x2 = (s * 0.95f * Math.cos(a)).toFloat()
                    val y2 = (s * 0.95f * Math.sin(a)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, iconPaint)
                }
            }
            Item.COLLAPSE -> {
                path.moveTo(-s * 0.8f, -s * 0.3f)
                path.lineTo(0f, s * 0.42f)
                path.lineTo(s * 0.8f, -s * 0.3f)
                canvas.drawPath(path, iconPaint)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
