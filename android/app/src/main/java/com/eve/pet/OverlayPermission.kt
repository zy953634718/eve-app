package com.eve.pet

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * 悬浮窗权限工具 —— M1 文档 §4.1。
 *
 * 关键点：国产 ROM（MIUI/HyperOS、ColorOS、OriginOS、MagicOS）上
 * Settings.canDrawOverlays 可能误报已授权，必须用 AppOpsManager 二次校验。
 * 这是实验 D 保活矩阵的前置条件之一。
 */
object OverlayPermission {

    fun check(ctx: Context): Boolean {
        // 第一道：系统 API
        if (!Settings.canDrawOverlays(ctx)) return false
        // 第二道：AppOps —— 国产 ROM 上 canDrawOverlays 可能误报已授权
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val op = AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(op, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun request(ctx: Context) {
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
        // 注意：Android 11+ 该设置页会忽略 package URI，直接落到总列表，属预期行为
    }
}
