package com.eve.pet

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.io.File

/**
 * 硬解能力探测 —— M1 文档 §3 实验 A（H4）。
 *
 * 结论决定编码选型：VP9 with Alpha 若在目标机型上拿不到硬件解码器，
 * 需切 HEVC with Alpha；再不行回退动画 WebP。
 */
object HardwareDecoderProbe {

    data class DecoderInfo(
        val label: String,
        val mime: String,
        val codecName: String,
        val hardware: Boolean,
        val maxInstances: Int,
        val lowLatency: Boolean
    )

    private val TARGETS = listOf(
        "VP9" to "video/x-vnd.on2.vp9",
        "HEVC" to "video/hevc",
        "H.264" to "video/avc"
    )

    fun probe(mime: String, w: Int, h: Int): Pair<String, Boolean> {
        return try {
            val fmt = MediaFormat.createVideoFormat(mime, w, h)
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = list.findDecoderForFormat(fmt) ?: return "NONE" to false
            val info = MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos.firstOrNull { it.name == name }
            val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info?.isHardwareAccelerated == true
            } else {
                name.startsWith("OMX.") || name.startsWith("c2.")
            }
            name to hw
        } catch (e: Exception) {
            "ERROR(${e.message})" to false
        }
    }

    /** 目标机型上的三种编码探测结果 */
    fun probeAll(w: Int = 480, h: Int = 480): List<DecoderInfo> {
        return TARGETS.map { (label, mime) ->
            val (name, hw) = probe(mime, w, h)
            val info = try {
                MediaCodecList(MediaCodecList.ALL_CODECS)
                    .codecInfos.firstOrNull { it.name == name }
            } catch (e: Exception) {
                null
            }
            val maxInstances = info?.let {
                try {
                    it.getCapabilitiesForType(mime).maxSupportedInstances
                } catch (e: Exception) {
                    -1
                }
            } ?: -1
            DecoderInfo(label, mime, name, hw, maxInstances, info?.isSoftwareOnly == false)
        }
    }

    /** 机型摘要：M1 报告要记录到数据表里 */
    fun deviceSummary(): String = buildString {
        appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.VERSION.INCREMENTAL}")
        appendLine("芯片: ${Build.HARDWARE} / ${Build.SOC_MODEL}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    /** 探测报告落盘：Android/data/com.eve.pet/files/m1/decoder-probe-<ts>.txt */
    fun writeReport(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "m1").apply { mkdirs() }
        val file = File(dir, "decoder-probe-${System.currentTimeMillis()}.txt")
        val sb = StringBuilder()
        sb.appendLine("=== 伊娃 M1 实验 A · 硬解能力探测 ===")
        sb.appendLine("时间: ${java.util.Date()}")
        sb.appendLine(deviceSummary())
        sb.appendLine("--- 解码器探测（480x480）---")
        probeAll().forEach { d ->
            sb.appendLine("${d.label}: ${d.codecName} | hardware=${d.hardware} | maxInstances=${d.maxInstances}")
        }
        sb.appendLine()
        sb.appendLine("判定：目标机型 VP9 hardware=true 则编码选型成立（H4 Go）")
        file.writeText(sb.toString())
        return file
    }
}
