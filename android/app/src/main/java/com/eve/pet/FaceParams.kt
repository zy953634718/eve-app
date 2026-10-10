package com.eve.pet

/**
 * 12 个心情的面部参数表 —— 来源：design/led-face.html「12 个心情定义」。
 *
 * 落地原则（同设计稿 §04）：
 *  面部用 Canvas 实时绘制，不导位图、不播序列帧；每个心情 = 一组参数，
 *  切换心情即补间这些参数，形状自动形变。
 */
enum class EyeMode { RING, ARC, STAR, SOLID, LINE, CROSS }
enum class MouthMode { ARC, O, WAVE, LINE }

/** 眼睛参数（单眼） */
data class EyeSpec(
    val mode: EyeMode,
    val w: Float = 1f,        // 横向缩放
    val h: Float = 1f,        // 纵向缩放
    val arcDir: Int = 1,      // 1 = 上弧(困)；-1 = 下弧(笑)
    val arcK: Float = 1f,     // 弧线曲率
    val yOff: Float = 0f,     // 垂直偏移（设计 px）
    val tilt: Float = 0f      // 倾斜角度（度）
)

/** 嘴巴参数 */
data class MouthSpec(
    val mode: MouthMode,
    val w: Float = 1f,
    val h: Float = 0f,
    val curve: Float = 0f,    // 正值上扬 = 笑，负值下垂 = 难过
    val invert: Boolean = false
)

/** 一个完整心情 */
data class Emotion(
    val key: String,
    val label: String,
    val desc: String,
    val color: Long,
    val eye: EyeSpec,
    val mouth: MouthSpec,
    val eyeL: EyeSpec? = null,   // 左右眼差异化（好奇 / 难过 / 思考）
    val eyeR: EyeSpec? = null
) {
    fun left(): EyeSpec = eyeL ?: eye
    fun right(): EyeSpec = eyeR ?: eye
}

object Emotions {

    private const val BLUE = 0xFF3EC8FF
    private const val CYAN_LIGHT = 0xFF7FE0FF
    private const val BLUE_DEEP = 0xFF1B6FE8
    private const val AMBER = 0xFFFFB12E

    val IDLE = Emotion(
        "idle", "待机", "默认状态 · 12fps 呼吸", BLUE,
        EyeSpec(EyeMode.RING, 1f, 1f),
        MouthSpec(MouthMode.ARC, 0.95f, curve = 13f)
    )
    val HAPPY = Emotion(
        "happy", "开心", "完成任务 / 被抚摸", BLUE,
        EyeSpec(EyeMode.ARC, 0.95f, 0.8f, arcDir = -1, arcK = 1.05f),
        MouthSpec(MouthMode.ARC, 1.1f, curve = 30f)
    )
    val EXCITED = Emotion(
        "excited", "兴奋", "抽到奖品 / 升级", CYAN_LIGHT,
        EyeSpec(EyeMode.STAR, 1.05f, 1.05f),
        MouthSpec(MouthMode.ARC, 1.2f, h = 16f, curve = 18f)
    )
    val CURIOUS = Emotion(
        "curious", "好奇", "听到新词 / 被点一下", BLUE,
        EyeSpec(EyeMode.SOLID, 1f, 1f),
        MouthSpec(MouthMode.O, 0.62f, h = 7f),
        eyeL = EyeSpec(EyeMode.SOLID, 1.2f, 1.2f),
        eyeR = EyeSpec(EyeMode.SOLID, 0.82f, 0.82f)
    )
    val SURPRISED = Emotion(
        "surprised", "惊讶", "突发提示 / 首次唤醒", CYAN_LIGHT,
        EyeSpec(EyeMode.SOLID, 1.28f, 1.28f),
        MouthSpec(MouthMode.O, 0.85f, h = 16f)
    )
    val THINKING = Emotion(
        "thinking", "思考", "大模型推理中", BLUE,
        EyeSpec(EyeMode.SOLID, 0.92f, 0.72f, yOff = -5f, tilt = -9f),
        MouthSpec(MouthMode.WAVE, 0.85f)
    )
    val SLEEPY = Emotion(
        "sleepy", "困倦", "夜间 / 低电量", BLUE_DEEP,
        EyeSpec(EyeMode.ARC, 1f, 0.5f, arcDir = 1, arcK = 0.9f),
        MouthSpec(MouthMode.O, 0.5f, h = 9f)
    )
    val SLEEPING = Emotion(
        "sleeping", "睡觉", "息屏 · 0fps", BLUE_DEEP,
        EyeSpec(EyeMode.LINE, 1f, 0f),
        MouthSpec(MouthMode.LINE, 0.5f)
    )
    val SAD = Emotion(
        "sad", "难过", "连续失败 / 被冷落", BLUE_DEEP,
        EyeSpec(EyeMode.ARC, 1f, 1f),
        MouthSpec(MouthMode.ARC, 0.85f, curve = -15f),
        eyeL = EyeSpec(EyeMode.ARC, 0.9f, 0.6f, arcDir = 1, arcK = 0.8f, tilt = 15f),
        eyeR = EyeSpec(EyeMode.ARC, 0.9f, 0.6f, arcDir = 1, arcK = 0.8f, tilt = -15f)
    )
    val LISTENING = Emotion(
        "listening", "聆听", "语音输入中", BLUE,
        EyeSpec(EyeMode.RING, 1.05f, 1.05f),
        MouthSpec(MouthMode.LINE, 0.6f)
    )
    val TALKING = Emotion(
        "talking", "说话", "TTS 播报 · 眼嘴联动", BLUE,
        EyeSpec(EyeMode.RING, 1f, 1f),
        MouthSpec(MouthMode.ARC, 1.05f, curve = 12f)
    )
    val ERROR = Emotion(
        "error", "出错", "网络异常 / 权限被拒", AMBER,
        EyeSpec(EyeMode.CROSS, 0.95f, 0.95f),
        MouthSpec(MouthMode.WAVE, 0.9f, invert = true)
    )

    val ALL: List<Emotion> = listOf(
        IDLE, HAPPY, EXCITED, CURIOUS, SURPRISED, THINKING,
        SLEEPY, SLEEPING, SAD, LISTENING, TALKING, ERROR
    )

    fun byKey(key: String): Emotion = ALL.firstOrNull { it.key == key } ?: IDLE
}
