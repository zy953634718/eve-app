package com.eve.pet

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 控制台页：权限引导（首个转化漏斗）+ M1 实验工具。
 *
 * 权限三步（design §03）：悬浮窗 → 自启动 → 电池白名单
 * 实验工具：硬解探测（实验 A）、数据记录路径（实验 B/C/D 采样导出）
 */
class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var probeResult: TextView
    private lateinit var metricsPath: TextView
    private lateinit var primaryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (22 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name) + " · M1 验证控制台"
            textSize = 22f
            gravity = Gravity.START
        }
        val subtitle = TextView(this).apply {
            text = "桌面常驻宠物 · 技术预研 Demo"
            textSize = 13f
            alpha = 0.65f
            setPadding(0, (6 * density).toInt(), 0, 0)
        }

        val permTitle = section("权限引导（三步，缺一不可）")
        overlayStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
        }
        batteryStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, (8 * density).toInt(), 0, (6 * density).toInt())
        }
        val overlayBtn = smallButton("① 去开启悬浮窗权限") { OverlayPermission.request(this) }
        val autostartBtn = smallButton("② 去开启自启动（国产 ROM 必做）") { openAppDetails() }
        val batteryBtn = smallButton("③ 加入电池优化白名单") { requestIgnoreBatteryOptimizations() }

        primaryButton = Button(this).apply {
            textSize = 15f
            setOnClickListener { toggleService() }
        }

        val probeTitle = section("实验 A · 硬解能力探测（H4）")
        probeResult = TextView(this).apply {
            textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        val probeBtn = smallButton("重新探测并导出报告") { runProbe() }

        val metricsTitle = section("实验 B/C/D · 数据记录")
        metricsPath = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(permTitle)
        root.addView(overlayStatus)
        root.addView(overlayBtn)
        root.addView(autostartBtn)
        root.addView(batteryStatus)
        root.addView(batteryBtn)
        root.addView(primaryButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (18 * density).toInt() })
        root.addView(probeTitle)
        root.addView(probeResult)
        root.addView(probeBtn)
        root.addView(metricsTitle)
        root.addView(metricsPath)

        setContentView(ScrollView(this).apply { addView(root) })

        // Android 13+ 通知权限（常驻通知需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // ==================== UI 刷新 ====================

    private fun refreshUi() {
        val overlayOk = OverlayPermission.check(this)
        overlayStatus.text = if (overlayOk) "① 悬浮窗权限：已就绪 ✅" else "① 悬浮窗权限：未授权 ❌"

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryStatus.text = if (ignoring) {
            "③ 电池优化白名单：已加入 ✅"
        } else {
            "③ 电池优化白名单：未加入 ❌\n（不加入，锁屏几分钟即可能被杀）"
        }

        primaryButton.text = if (overlayOk) "启动悬浮窗服务" else "去授权悬浮窗"
        metricsPath.text = buildString {
            appendLine("采样 CSV 输出目录：")
            appendLine(ExperimentLogger(this@MainActivity).outputDir.absolutePath)
            appendLine()
            appendLine("adb 拉取：")
            appendLine("adb pull /sdcard/Android/data/com.eve.pet/files/m1/ ./m1")
            appendLine()
            append("日志开关（荣耀默认吞三方日志）：*#*#2846579#*#* → 后台设置 → AP LOG")
        }
    }

    // ==================== 动作 ====================

    private fun toggleService() {
        if (!OverlayPermission.check(this)) {
            OverlayPermission.request(this)
            return
        }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "伊娃已到桌面，长按 1.2 秒试试菜单", Toast.LENGTH_SHORT).show()
    }

    /** 自启动引导：跳应用详情页（各 ROM 自启动入口不同，统一落到详情页） */
    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "请手动进入系统设置开启自启动", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            @Suppress("BatteryLife")
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 实验 A：探测 VP9 / HEVC / H.264 硬解能力并导出报告 */
    private fun runProbe() {
        val infos = HardwareDecoderProbe.probeAll()
        val report = HardwareDecoderProbe.writeReport(this)
        probeResult.text = buildString {
            appendLine(HardwareDecoderProbe.deviceSummary())
            infos.forEach { d ->
                val mark = if (d.hardware) "✅ 硬解" else "❌ 软解/无"
                appendLine("${d.label}: $mark")
                appendLine("  ${d.codecName}")
            }
            appendLine()
            appendLine("报告已导出：")
            append(report.absolutePath)
        }
    }

    // ==================== 小工具 ====================

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (26 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        setOnClickListener { onClick() }
    }
}
