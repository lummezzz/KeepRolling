package com.keeprolling.app.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * # 拖动触觉：两档强度 + 两道闸
 *
 * 拖动类交互的触觉有两个天然相反的诉求：
 * **既想"跟着手一路有反馈"，又不能让手指高速划过时震成一团**。
 * 所以这里不做"每次回调震一下"（那必然堆积），而是让**值**来决定震不震：
 *
 * ```
 * 指针事件（可能 120~240 次/秒）→ 换算成一个"槽位"(slot) → 槽位没变就不震
 *                              → 槽位变了、且离上次超过冷却时间 → 震一下
 * ```
 *
 * ## 两条调用线
 *
 * | 方法 | 适用 | 槽位怎么取 | 强度 | 冷却 |
 * |---|---|---|---|---|
 * | [step] | **离散步进**：倍速档位、生成间隔、路口热度 | 档位序号 / 整数秒 / 热量值 | [TickStrength.PRESS] | 45ms |
 * | [flow] | **连续滑动**：车型比例、速度曲线、画路、拖端点 | 把连续量量化成的整数（%/2m…） | [TickStrength.DRAG] | 32ms |
 *
 * ### 为什么用"槽位"而不是"距离累加器"
 * 累加器（每滑 10px 加一次）在**缩放级别变化时手感会变**（同样一屏米数不同），
 * 而且从"跳着拖"切到"慢慢拖"时要额外重置；槽位直接挂在**业务值**上 ——
 * 值没变就一定不震，值变了一定震（只要过了冷却）。这也是"档位"和"连续"能用同一套代码的原因：
 * *区别只在量化粒度，不在机制*。
 *
 * ### 冷却窗口内发生的变化怎么办：**丢掉，不补**
 * 常见错误是"攒着等窗口结束再补一响"，结果手指停下之后还在震（回声），
 * 快速划过反而在松手瞬间连爆几下。这里的做法是**只记录真正震过的那次**，
 * 窗口内被跳过的槽位不记账 —— 于是：
 *  - 手指快速划过 → 稳定在 22 次/秒（PRESS）或 31 次/秒（DRAG）的上限；
 *  - 手指停下 → 立刻安静；
 *  - 手指慢慢挪 → 每跨一个槽位响一次，不会被 45/32ms 吃掉（人手动不了那么快）。
 *
 * ### 槽位只在"真的要震"时才更新
 * 如果冷却窗口内就更新槽位，那么"快速划过 10 个档位"只会响 1 声，且此后再也不响 ——
 * 用户会以为震动坏了。这里留 `slot` 不动，等窗口一过就用**当前**槽位补上，语义始终是
 * "现在这个档位我还没震过"。
 *
 * ## 开关
 * 每次真的触发前读一次 `LocalHapticEnabled`（音频浮层里的「轻震」）。
 * 用 [rememberUpdatedState] 包一层读到**最新**值 —— 否则用户关掉之后，
 * 已经创建的 ticker 还会一直震到手势结束。
 *
 * ## 不要跨控件共用一个实例
 * 槽位空间是**每个实例各自一份**：给"生成间隔"和"路口热度"共用同一个 ticker，
 * 两边都是 1~10 的档位，会出现"从间隔 5 拖到 6 不震"的鬼现象。每个控件 `remember` 一个。
 */
class DragHaptics internal constructor(
    private val isEnabled: () -> Boolean,
    private val fire: (TickStrength) -> Unit,
) {
    /** 上次真正震过的步进槽位（`Int.MIN_VALUE` = 还没有） */
    private var stepSlot = Int.MIN_VALUE
    private var stepAtMs = 0L

    /** 上次真正震过的滑动槽位 */
    private var flowSlot = Int.MIN_VALUE
    private var flowAtMs = 0L

    /**
     * 离散步进：**跨过一档**才响。
     *
     * @param slot 当前档位（任意单调取值的整数：档位序号、整数秒、热量值…）
     */
    fun step(slot: Int) {
        if (slot == stepSlot) return
        val now = SystemClock.uptimeMillis()
        if (now - stepAtMs < STEP_MIN_GAP_MS) return
        stepSlot = slot
        stepAtMs = now
        emit(TickStrength.PRESS)
    }

    /**
     * 连续滑动：**跨过一个量化格**才响，且比 [step] 更密、更轻。
     *
     * @param slot 把连续量量化后的整数。粒度就是"多慢才不震"：
     *   比例用「百分数」、画路/拖端点用「米数 / 2」。
     */
    fun flow(slot: Int) {
        if (slot == flowSlot) return
        val now = SystemClock.uptimeMillis()
        if (now - flowAtMs < FLOW_MIN_GAP_MS) return
        flowSlot = slot
        flowAtMs = now
        emit(TickStrength.DRAG)
    }

    /**
     * 新手势开始时清掉槽位（**不清时间戳**）。
     *
     * 只清槽位是为了让"上一次拖到第 3 档，这一次又从第 3 档开始"也能响一声
     * （用户重新按下去，本来就该有反馈）；不清时间戳是为了不让两次手势之间的
     * 冷却被重置成 0 —— 否则"快速点两下"会连震，反而比不重置更吵。
     */
    fun reset() {
        stepSlot = Int.MIN_VALUE
        flowSlot = Int.MIN_VALUE
    }

    private fun emit(strength: TickStrength) {
        // 开关关着也照样推进槽位（见类注释）：关掉期间"这个档位已经过去了"，
        // 重新打开时不该补一响。
        if (isEnabled()) fire(strength)
    }

    private companion object {
        /**
         * 步进冷却 45ms（≈22 次/秒）。
         *
         * 再短（<30ms）快速划过整条倍速轨道会连成一段"嗡嗡"，听感上像卡住了；
         * 再长（>70ms）又会让"一档一响"的对应关系变模糊。
         */
        const val STEP_MIN_GAP_MS = 45L

        /**
         * 滑动冷却 32ms（≈31 次/秒）。
         *
         * 马达的起振/停止本身有 5~15ms 的延迟，32ms 的间隔在体感上刚好接成
         * "连续的细沙"而不是"一顿一顿"。低于 25ms 就只剩一片糊。
         */
        const val FLOW_MIN_GAP_MS = 32L
    }
}

/**
 * 取一个拖动触觉节拍器（每个控件一个实例，见 [DragHaptics] 类注释）。
 *
 * 实例在整个 Composable 生命周期内稳定，可以安全地被 `pointerInput {}` 长期持有。
 */
@Composable
fun rememberDragHaptics(): DragHaptics {
    val fire = rememberHaptics()
    // 每次重组都会刷新这个 State；ticker 内部通过 lambda 读 `.value`，
    // 于是"关掉轻震"能立刻作用到**已经创建**的 ticker 上。
    val enabled = rememberUpdatedState(LocalHapticEnabled.current)
    return remember(fire) { DragHaptics({ enabled.value }, fire) }
}
