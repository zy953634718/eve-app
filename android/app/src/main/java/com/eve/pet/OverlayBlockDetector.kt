package com.eve.pet

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * 被屏蔽检测 —— M1 文档 §3 实验 E 延伸 + design 屏 06 降级态。
 *
 * 原理：Android 12+ 银行/支付类 App 会屏蔽第三方悬浮窗。被屏蔽时窗口不可见，
 * 但进程仍在跑。判定方式：
 *   亮屏状态下连续 N 秒收不到绘制帧回调 → 判定「被屏蔽」
 *   恢复：窗口 getGlobalVisibleRect 重新可见 → 解除
 *
 * 命中后由 Service 把窗口 alpha 降到 0.22、暂停解码（省电），并弹一次说明。
 */
class OverlayBlockDetector(
    private val view: View,
    private val checkIntervalMs: Long = 1000L,
    private val thresholdMs: Long = 4000L,
    private val onBlockedChanged: (Boolean) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastFrameNs = 0L
    @Volatile
    private var blocked = false
    private var running = false
    private val rect = Rect()

    /** 由渲染层每帧调用 */
    fun markFrame() {
        lastFrameNs = System.nanoTime()
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNs = System.nanoTime()
        handler.postDelayed(tick, checkIntervalMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (!blocked) {
                if (now - lastFrameNs > thresholdMs * 1_000_000L) {
                    setBlocked(true)
                }
            } else {
                // 恢复判定：窗口重新可见
                if (view.getGlobalVisibleRect(rect) && rect.width() > 0) {
                    setBlocked(false)
                }
            }
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    private fun setBlocked(v: Boolean) {
        blocked = v
        // 恢复时重置打点，避免立刻又被判为屏蔽
        lastFrameNs = System.nanoTime()
        onBlockedChanged(v)
    }

    val isBlocked: Boolean
        get() = blocked

    fun reset() {
        lastFrameNs = System.nanoTime()
    }
}
