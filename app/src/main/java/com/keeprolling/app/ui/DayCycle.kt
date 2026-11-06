package com.keeprolling.app.ui

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * **昼夜的三种驱动方式**（本轮需求 1）。
 *
 *  - [DAY]：手动固定白天；
 *  - [NIGHT]：手动固定夜间；
 *  - [AUTO]：跟随**仿真时间**自动推进（白天 → 黄昏 → 夜 → 黎明 → 白天 循环）。
 */
enum class DayMode { DAY, NIGHT, AUTO }

/**
 * 某一时刻的**光照状态**（本轮需求 1）。
 *
 * 这是整个渲染层唯一的"太阳"来源：路面配色、路灯、车灯用 [night]；
 * 建筑 / 树 / 车辆的影子用 [shadowDx] [shadowDy] [shadowLen] [shadowAlpha]；
 * 清晨黄昏那层暖色调用 [warmth]。
 *
 * 为什么把"影子往哪边倒、倒多长"也算在光照里，而不是让每个绘制函数自己写死：
 * 影子的方向与长度必须**全场景一致**（同一条街上的楼、树、车不可能各倒各的），
 * 一旦分散到三处，改一处就会露馅。这里算一次，三处都读同一份。
 */
@Immutable
data class DayLight(
    /** 0 = 大白天，1 = 全黑 */
    val night: Float,
    /** 影子方向的单位向量（屏幕坐标系，y 向下） */
    val shadowDx: Float,
    val shadowDy: Float,
    /** 影子长度的**倍数**（1 = 常规长度；清晨黄昏会拉长到 2 倍以上） */
    val shadowLen: Float,
    /** 影子浓度的倍数：夜里几乎看不见影子 */
    val shadowAlpha: Float,
    /** 0~1，清晨 / 黄昏最浓；用来在整屏叠一层极淡的暖色 */
    val warmth: Float,
)

/**
 * **昼夜循环**（本轮需求 1）：把"仿真时间"换算成光照。
 *
 * ## 为什么绑仿真时间，而不是手机的真实时间
 * 绑真实时间的话，用户晚上打开永远是黑的、中午打开永远是亮的 —— **看不到变化**，
 * 这个功能就等于不存在。绑仿真时间则：
 *  - 一个昼夜 = [DAY_LENGTH_SEC] 秒仿真时间，**倍速会一起加速**（5x 时 19 秒过完一天）；
 *  - 仿真暂停时 `simTime` 冻结，天色也停住 —— "时间停了，天色却还在变"才是违和的。
 *
 * ## 相位与太阳
 * [phaseAt] 给出 0~1 的相位（0.25 = 06:00 日出，0.5 = 12:00 正午，0.75 = 18:00 日落）。
 * 太阳高度用 `sin(π·s)`（s 是"日出到日落"归一化后的位置）：
 * 白天为正、夜里为负、日出日落恰好为 0 —— 一条连续、无跳变的曲线，
 * 所以天色是"渐渐暗下去"的，不存在"某一帧突然全黑"。
 *
 * 影子方向 θ = `π·(1-s) - 0.6`：日出时影子朝左、正午朝下偏右（`cos/sin` 约 0.56/0.83，
 * 与项目原本写死的光源方向一致，白天看起来和以前一模一样）、日落时朝右 —— 一整天转半圈。
 */
object DayCycle {

    /** 一个完整昼夜 = 多少**仿真秒**。96s ≈ 白天 55s + 夜里 41s（含两段黄昏黎明） */
    const val DAY_LENGTH_SEC = 96.0

    /**
     * `simTime = 0` 时对应的相位：0.30 ≈ 07:12。
     * 取这个值是为了"刚打开 app 就是白天"，而不是一上来就黑灯瞎火。
     */
    const val START_PHASE = 0.30f

    /** 手动「白天」用的相位（正午，影子方向与项目原来的固定光源一致） */
    const val DAY_PHASE = 0.5f

    /** 手动「夜间」用的相位（深夜，`night` = 1） */
    const val NIGHT_PHASE = 0.92f

    /** 当前仿真时刻 → 相位 0~1 */
    fun phaseAt(simTime: Double): Float {
        val p = (simTime / DAY_LENGTH_SEC).toFloat() + START_PHASE
        return p - floor(p)
    }

    /** 相位 → 光照状态 */
    fun lightAt(phase: Float): DayLight {
        val p = phase - floor(phase)
        // s：0 = 日出，0.5 = 正午，1 = 日落；夜里落在 (1, 2) 或 (-0.5, 0)
        val s = (p - 0.25f) / 0.5f
        // 太阳高度：白天为正、夜里为负，且处处连续（这就是天色不会跳变的原因）
        val elev = sin(PI * s).toFloat()
        // 多暗。两个阈值决定"黄昏有多长"：太阳略低于地平线就已经很黑了
        val night = 1f - smoothstep(-0.45f, 0.30f, elev)

        // ---- 影子方向：一整天转半圈 ----
        val sDay = s.coerceIn(0f, 1f)
        val theta = (PI * (1.0 - sDay) - 0.6).toFloat()
        val dx = cos(theta)
        val dy = sin(theta)

        // ---- 影子长度：太阳越低影子越长；夜里再收回去（没有太阳，别拖一条长尾巴） ----
        val e = elev.coerceIn(0f, 1f)
        val rawLen = (1.35f / (0.55f + 0.95f * e)).coerceIn(0.55f, 2.45f)
        val len = rawLen * (1f - 0.70f * night)

        // ---- 浓度：夜里几乎看不见影子 ----
        val alpha = (1f - 0.80f * night).coerceIn(0.15f, 1f)

        // ---- 暖色：只有贴近日出日落那一小段（|高度| < 0.32）才有 ----
        val warmth = ((0.32f - abs(elev)) / 0.32f).coerceIn(0f, 1f) * (1f - 0.5f * night)

        return DayLight(night, dx, dy, len, alpha, warmth)
    }

    /** 手动「白天」的光照（正午） */
    val DAY_LIGHT: DayLight = lightAt(DAY_PHASE)

    /** 手动「夜间」的光照 */
    val NIGHT_LIGHT: DayLight = lightAt(NIGHT_PHASE)

    /**
     * 手动模式下，用"暗不暗"的当前值重算光照。
     *
     * 手动切换白天↔夜间时，`night` 要 340ms 补间着过去（整片街区一起暗下来），
     * 但影子方向本来就该跟着模式**立刻**定下来 —— 让影子也慢慢转半圈会很怪。
     *
     * 所以这里：**方向取 [base]（立即定型），其余属性在固定的昼夜端点之间插值**。
     * 不能继续乘 [base] 的长度或浓度：[base] 自身可能已经含夜间衰减，重复计算会让
     * 影子突然缩短、变淡，甚至在切换结束时再衰减一次。
     */
    fun withNight(base: DayLight, night: Float): DayLight {
        val n = night.coerceIn(0f, 1f)
        return base.copy(
            night = n,
            shadowLen = DAY_LIGHT.shadowLen + (NIGHT_LIGHT.shadowLen - DAY_LIGHT.shadowLen) * n,
            shadowAlpha = DAY_LIGHT.shadowAlpha + (NIGHT_LIGHT.shadowAlpha - DAY_LIGHT.shadowAlpha) * n,
            warmth = DAY_LIGHT.warmth + (NIGHT_LIGHT.warmth - DAY_LIGHT.warmth) * n,
        )
    }

    /** 平滑阶跃：x ≤ e0 返回 0，x ≥ e1 返回 1，中间是 S 形曲线（一阶导连续，没有折角） */
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
