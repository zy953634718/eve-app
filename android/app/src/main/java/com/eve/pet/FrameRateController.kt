package com.eve.pet

import android.util.Log

/**
 * 降帧状态机 —— 功耗控制核心（M1 文档 §4.4）。
 *
 * M1 对应实验：
 *  - 实验 B（H1）：IDLE 12fps 是待机 CPU 达标档位，目标 < 1%
 *  - 实验 C1（H2a）：SLEEP(0) 必须真正暂停解码与渲染，
 *    这是息屏 8h 耗电 < 0.5% 能过的唯一原因
 *  - 低电量档为产品体验预留，M1 只验证不调优
 */
class FrameRateController(private val onFpsChanged: (Int) -> Unit) {

    enum class State(val fps: Int) {
        SLEEP(0), IDLE(12), INTERACT(30), LOW_BATTERY(5)
    }

    var state: State = State.IDLE
        private set

    init {
        // 初始即通知一次，保证 PetView 与状态机同步
        onFpsChanged(state.fps)
    }

    /** 熄屏 —— 实验 C1 的生死线 */
    fun onScreenOff() {
        update(State.SLEEP)
    }

    /** 亮屏（未解锁也恢复待机渲染） */
    fun onScreenOn() {
        if (state == State.SLEEP) update(State.IDLE)
    }

    /** 解锁，回到交互态观察 */
    fun onUserPresent() {
        update(State.IDLE)
    }

    /** 预留：后续接入宠物本体触摸交互时升 30fps */
    fun onInteract() {
        update(State.INTERACT)
    }

    /** 电量广播：level < 20 且未充电 → 降 5fps 省电档 */
    fun onBatteryChanged(levelPercent: Int, charging: Boolean) {
        if (levelPercent < 20 && !charging) {
            update(State.LOW_BATTERY)
        } else if (state == State.LOW_BATTERY) {
            update(State.IDLE)
        }
    }

    private fun update(next: State) {
        if (next != state) {
            state = next
            Log.i("FrameRateController", "state -> $next (${next.fps} fps)")
            onFpsChanged(next.fps)
        }
    }
}
