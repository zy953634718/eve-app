package com.eve.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager

/**
 * 悬浮窗前台服务：伊娃的宿主。
 *
 * 窗口结构：
 *  - 窗口 A（常驻）：PetContainerView，220dp 方形，只包宠物本体 + 气泡
 *  - 窗口 B（按需）：RingMenuView 全屏，菜单打开时挂载，点空白/选中即卸载
 *
 * 状态机 → 渲染：FrameRateController 的 fps 喂给身体层（媒体帧），面部层独立刷新
 * 降级：被银行/支付类 App 屏蔽 → 淡出至 22% + 暂停解码（design 屏 06）
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "eve_pet_forever"
        private const val NOTIFY_ID = 1001
        const val ACTION_RESTORE = "com.eve.pet.action.RESTORE"
        private const val PET_WINDOW_DP = 220f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var frameController: FrameRateController
    private lateinit var logger: ExperimentLogger

    private var petWindow: PetContainerView? = null
    private var menuWindow: RingMenuView? = null
    private var petParams: WindowManager.LayoutParams? = null
    private var blockDetector: OverlayBlockDetector? = null

    private var napping = false
    private var blockedNotified = false
    private var edgeHidden = false          // 贴边半隐藏（只露 40%）

    private val petWindowSizePx: Int by lazy {
        (PET_WINDOW_DP * resources.displayMetrics.density).toInt()
    }

    /** 熄屏/亮屏/电量 —— 实验 C1 与低电模式的信号源 */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    frameController.onScreenOff()
                    petWindow?.setEmotion(Emotions.SLEEPING)
                }
                Intent.ACTION_SCREEN_ON -> frameController.onScreenOn()
                Intent.ACTION_USER_PRESENT -> {
                    frameController.onUserPresent()
                    petWindow?.setEmotion(Emotions.IDLE)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (level >= 0 && scale > 0) {
                        val pct = level * 100 / scale
                        frameController.onBatteryChanged(pct, plugged)
                        if (pct < 20 && !plugged) petWindow?.setEmotion(Emotions.SLEEPY)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithType()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        logger = ExperimentLogger(this)

        // 注意：FrameRateController 构造时会立刻回调一次，此处不可引用 frameController 自身（未初始化）
        frameController = FrameRateController { fps ->
            petWindow?.setFps(fps)
            logger.fpsTarget = fps
            logger.stateLabel = if (fps == 0) "SLEEP" else if (fps == 8) "NAP" else if (fps >= 30) "INTERACT" else "IDLE"
        }

        showPet()
        logger.start("metrics").also { logger.writeHeader() }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // targetSdk 34：必须显式声明导出行为
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE) restorePet()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        blockDetector?.stop()
        removeMenu()
        removePet()
        logger.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ==================== 窗口 A：宠物 ====================

    private fun showPet() {
        if (petWindow != null) return

        val view = PetContainerView(this)
        petWindow = view

        val params = WindowManager.LayoutParams(
            petWindowSizePx, petWindowSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW() * 0.62f).toInt()
            y = (screenH() * 0.55f).toInt()
        }
        petParams = params

        // ---- 手势接线 ----
        view.onSingleTap = {
            if (edgeHidden) {
                slideOutFromEdge()
            } else {
                view.blink()
                view.setEmotion(Emotions.HAPPY)
                view.showBubble(greetings.random())
                view.postDelayed({ view.setEmotion(Emotions.IDLE) }, 1200)
            }
        }
        view.onDoubleTap = {
            if (edgeHidden) {
                slideOutFromEdge()
            } else {
                view.setEmotion(Emotions.LISTENING)
                view.showBubble("我在听…")
                view.postDelayed({ view.setEmotion(Emotions.IDLE) }, 2600)
            }
        }
        view.onOpenMenu = { openMenu() }
        view.onDrag = { dx, dy -> movePetBy(dx, dy) }
        view.onDragEnd = { snapToEdge() }
        view.onFrame = { blockDetector?.markFrame() }

        blockDetector = OverlayBlockDetector(view) { blocked -> onBlockedChanged(blocked) }
            .also { it.start() }

        windowManager.addView(view, params)
    }

    private fun removePet() {
        petWindow?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* 未附加 */ }
            it.release()
        }
        petWindow = null
        petParams = null
        blockDetector?.stop()
        blockDetector = null
    }

    private fun restorePet() {
        if (petWindow == null) {
            showPet()
            petWindow?.showBubble("我回来啦")
        }
    }

    private fun movePetBy(dx: Float, dy: Float) {
        val p = petParams ?: return
        val (minY, maxY) = petYBounds()
        p.x = (p.x + dx).toInt().coerceIn(-petWindowSizePx / 2, screenW() - petWindowSizePx / 2)
        p.y = (p.y + dy).toInt().coerceIn(minY, maxY)
        petWindow?.let {
            try {
                windowManager.updateViewLayout(it, p)
            } catch (e: Exception) { /* ignore */ }
        }
        edgeHidden = false
    }

    /** 松手吸附最近边缘（20% 屏宽阈值内），并避开状态栏与导航区 */
    private fun snapToEdge() {
        val p = petParams ?: return
        val w = screenW()
        val threshold = w * 0.2f
        val center = p.x + petWindowSizePx / 2f
        when {
            center < threshold -> {
                p.x = -(petWindowSizePx * 0.6f).toInt()   // 半隐藏：只露 40%
                edgeHidden = true
            }
            center > w - threshold -> {
                p.x = (w - petWindowSizePx * 0.4f).toInt()
                edgeHidden = true
            }
            else -> edgeHidden = false
        }
        val (minY, maxY) = petYBounds()
        p.y = p.y.coerceIn(minY, maxY)
        petWindow?.let {
            try {
                windowManager.updateViewLayout(it, p)
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun slideOutFromEdge() {
        val p = petParams ?: return
        p.x = (petWindowSizePx * 0.1f).toInt()
        edgeHidden = false
        petWindow?.let {
            try {
                windowManager.updateViewLayout(it, p)
            } catch (e: Exception) { /* ignore */ }
            it.showBubble("出来啦")
        }
    }

    // ==================== 窗口 B：环形菜单 ====================

    private fun openMenu() {
        if (menuWindow != null || petWindow == null) return
        val menu = RingMenuView(this)
        menuWindow = menu

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // 不抢焦点；打开期间接收触摸以支持「点空白收起」
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        // 半径自适应：R = min(118dp, 30% 屏宽)（design 规范）
        val density = resources.displayMetrics.density
        val radius = minOf(118f * density, screenW() * 0.30f)
        val p = petParams
        val cx = (p?.x ?: 0) + petWindowSizePx / 2f
        val cy = (p?.y ?: 0) + petWindowSizePx * 0.60f
        val mode = if (cx < screenW() * 0.35f) RingMenuView.Mode.EDGE_RIGHT else RingMenuView.Mode.FULL

        menu.onSelect = { item -> onMenuItemSelected(item) }
        menu.onDismiss = { removeMenu() }

        windowManager.addView(menu, params)
        menu.show(cx, cy, radius, mode)
        frameController.onInteract()   // 菜单打开 = 交互态 30fps
    }

    private fun removeMenu() {
        menuWindow?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* 未附加 */ }
        }
        menuWindow = null
        if (frameController.state == FrameRateController.State.INTERACT) {
            frameController.onUserPresent()
        }
    }

    private fun onMenuItemSelected(item: RingMenuView.Item) {
        val view = petWindow ?: return
        when (item) {
            RingMenuView.Item.CHAT -> talk(view, "在呢，想聊点什么？", 2600)
            RingMenuView.Item.STORY -> talk(view, "从前有个小机器人…", 2600)
            RingMenuView.Item.FLY -> {
                view.setEmotion(Emotions.EXCITED)
                view.showBubble("起飞！")
                flyAround()
                view.postDelayed({ view.setEmotion(Emotions.IDLE) }, 2000)
            }
            RingMenuView.Item.SKIN -> {
                view.setEmotion(Emotions.CURIOUS)
                view.showBubble("皮肤商城 M2 上线")
                view.postDelayed({ view.setEmotion(Emotions.IDLE) }, 2200)
            }
            RingMenuView.Item.TOMATO -> {
                view.setEmotion(Emotions.THINKING)
                view.showBubble("番茄钟 25:00 开始")
                view.postDelayed({ view.setEmotion(Emotions.IDLE) }, 2600)
            }
            RingMenuView.Item.NAP -> toggleNap()
            RingMenuView.Item.SETTINGS -> openMainActivity()
            RingMenuView.Item.COLLAPSE -> collapse()
        }
    }

    /** 说话姿态 + 气泡：眼睛与嘴巴共享包络（face.talking） */
    private fun talk(view: PetContainerView, text: String, durationMs: Long) {
        view.setEmotion(Emotions.TALKING)
        view.setTalking(true)
        view.showBubble(text)
        view.postDelayed({
            view.setTalking(false)
            view.setEmotion(Emotions.IDLE)
        }, durationMs)
    }

    /** 菜单项「飞一圈」：绕当前位置画椭圆轨迹（顺带验证贴边吸附） */
    private fun flyAround() {
        val p = petParams ?: return
        val view = petWindow ?: return
        val baseX = p.x
        val baseY = p.y
        val rx = screenW() * 0.22f
        val ry = screenH() * 0.06f
        val animator = android.animation.ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1600L
            addUpdateListener { va ->
                val a = (va.animatedValue as Float).toDouble()
                p.x = (baseX + rx * Math.sin(a)).toInt()
                p.y = (baseY - ry * (1 - Math.cos(a))).toInt().coerceAtLeast(0)
                try {
                    windowManager.updateViewLayout(view, p)
                } catch (e: Exception) { /* 视图可能已移除 */ }
            }
            start()
        }
        view.postDelayed({
            animator.cancel()
            p.x = baseX
            p.y = baseY
            try {
                windowManager.updateViewLayout(view, p)
            } catch (e: Exception) { /* ignore */ }
            snapToEdge()
        }, 1650)
    }

    private fun toggleNap() {
        val view = petWindow ?: return
        napping = !napping
        if (napping) {
            view.setEmotion(Emotions.SLEEPY)
            view.showBubble("我眯一会儿…")
            view.setFps(8)                      // 打盹档：8fps（对应 H1 不达标时的降帧路径）
            logger.fpsTarget = 8
            logger.stateLabel = "NAP"
        } else {
            view.setEmotion(Emotions.IDLE)
            view.showBubble("睡醒啦")
            frameController.onUserPresent()     // 回 12fps
        }
    }

    private fun collapse() {
        removeMenu()
        removePet()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification(collapsedHint = true))
    }

    private fun openMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    // ==================== 被屏蔽降级（design 屏 06） ====================

    private fun onBlockedChanged(blocked: Boolean) {
        logger.blocked = blocked
        val p = petParams ?: return
        val view = petWindow ?: return
        if (blocked) {
            p.alpha = 0.22f                 // 淡出至 22%
            view.setFps(0)                  // 暂停解码省电
            view.setEmotion(Emotions.SLEEPING)
            if (!blockedNotified) {
                blockedNotified = true
                view.showBubble("当前应用不支持悬浮")   // 首次说明（M1 用气泡代替 Toast）
            }
        } else {
            p.alpha = 1f
            frameController.onUserPresent() // 退出后自动恢复
            view.setEmotion(Emotions.IDLE)
        }
        try {
            windowManager.updateViewLayout(view, p)
        } catch (e: Exception) { /* ignore */ }
    }

    // ==================== 前台服务与通知 ====================

    private fun startForegroundWithType() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "伊娃常驻", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = buildNotification(collapsedHint = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun buildNotification(collapsedHint: Boolean): Notification {
        val restoreIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(if (collapsedHint) "伊娃已收起" else "伊娃在你桌面上")
            .setContentText(if (collapsedHint) "点此唤回" else "长按 1.2 秒唤起环形菜单")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(restoreIntent)
            .build()
    }

    // ==================== 屏幕尺寸工具 ====================

    private fun screenW(): Int = resources.displayMetrics.widthPixels
    private fun screenH(): Int = resources.displayMetrics.heightPixels

    /** 宠物可活动纵向范围：避开状态栏与手势导航区 */
    private fun petYBounds(): Pair<Int, Int> {
        val statusBar = resId("status_bar_height")
        val navBar = resId("navigation_bar_height")
        val minY = statusBar
        val maxY = (screenH() - navBar - petWindowSizePx).coerceAtLeast(minY)
        return minY to maxY
    }

    private fun resId(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}

private val greetings = listOf(
    "你好呀～", "今天也要加油哦", "我在呢", "摸摸头", "嘿，别忘了我", "工作顺利吗？"
)
