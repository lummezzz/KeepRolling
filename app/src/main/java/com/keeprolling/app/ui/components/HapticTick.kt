package com.keeprolling.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 一次「轻点」触觉反馈（按钮按下时用）。
 *
 * ## 为什么不能只用 `LocalHapticFeedback`
 * Compose 的 [androidx.compose.ui.hapticfeedback.HapticFeedback] 只是 `View.performHapticFeedback`
 * 的一层薄封装，有**两个致命限制**，正是「开关开着却不震」的根因：
 *
 * ```
 * ① 它不给传 flags —— 只能用「尊重系统触摸反馈总开关」的默认档
 * ② 它内部把 HapticFeedbackType 映射成 HapticFeedbackConstants，其中
 *    TextHandleMove → TEXT_HANDLE_MOVE 是 **API 27+** 的常量；
 *    本 App minSdk = 26，在 8.0 机器上 Compose 直接传 -1 → 恒不震，也不报错
 * ```
 * 而且 `View.performHapticFeedback()` **失败时只返回 false，不抛异常** —— 调用方
 * 写成 `runCatching { … }` 就什么都看不出来（这正是上一版的做法，见下）。
 *
 * ## 真正的根因：`performHapticFeedback` 需要 `VIBRATE` 权限
 * 它并不直连马达，而是
 * ```
 * View.performHapticFeedback
 *   → ViewRootImpl
 *     → WindowManagerService.performHapticFeedback   ← 这里查调用方的 VIBRATE 权限
 *       → 没有权限：直接 return false（**静默**，无异常、无日志）
 * ```
 * 本 App 是「纯本地模拟器」，清单里一个权限都没申请，所以这一路 100% 被拒。
 * 修复见 `AndroidManifest.xml` 的 `<uses-permission android:name="android.permission.VIBRATE" />`
 * （`VIBRATE` 是 **normal 权限**，安装即授予，不需要运行时弹窗）。
 *
 * ## 三道兜底（按实现率从高到低，成功一次就停）
 * ```
 * ① View.performHapticFeedback(<该档的系统常量>, 0)   系统调好幅度，最省事
 * ② Vibrator.vibrate(<该档的时长/振幅>)               ① 返回 false（关总开关/ROM 不实现该档）
 * ③ 什么都不做 + 打一条日志                            连马达都没有（模拟器 / 部分平板）
 * ```
 *
 * ### 两档强度见 [TickStrength]
 * 按钮按下 / 档位切换用 `VIRTUAL_KEY`（重、清脆），拖动中的连续反馈用 `CLOCK_TICK`（轻）。
 *
 * ### 为什么用 `VIRTUAL_KEY` 而不是 `TEXT_HANDLE_MOVE`
 * - `VIRTUAL_KEY`（API 1 起）就是**所有键盘按键用的那一档**，几乎 100% 机型都实现了，
 *   力度对 44dp 圆钮刚好；
 * - `TEXT_HANDLE_MOVE` 是「拖动文字光标」的语义，不少 ROM 把它做成 0 强度或干脆不实现，
 *   拿来当按钮 tick 会出现「有的机器完全没感觉」。
 * - 如果你觉得 `VIRTUAL_KEY` 偏重，把它换成 `TEXT_HANDLE_MOVE` 即可（记得配 API 27 守卫）。
 *
 * ### 关于 ② 绕过了系统「触摸反馈」总开关
 * 这是刻意的：App 自己有「轻震」开关，用户把它打开就是明确表示「我要震动」。
 * 如果严格尊重系统设置，那么系统总开关一关，本 App 的开关就变成了摆设 ——
 * 用户会像这次一样，看到开关是亮的、却什么感觉都没有。
 * 若更想尊重系统设置，把 `fireTick()` 里第 ② 段删掉即可（只保留 ① + 日志）。
 */

/** logcat tag：`adb logcat -s KeepRollingHaptic` */
private const val TAG = "KeepRollingHaptic"

/**
 * 触觉的两档强度。
 *
 * | 档 | 用在哪 | 系统常量 | 兜底时长 / 振幅 |
 * |---|---|---|---|
 * | [PRESS] | 档位切换、按钮按下 | `VIRTUAL_KEY`（键盘按键那一档，清脆） | 12ms / 150 |
 * | [DRAG] | 连续滑动（拖动中的"路面质感"） | `CLOCK_TICK`（时钟滴答那一档，很轻） | 6ms / 70 |
 *
 * ## 为什么用两个**系统常量**来区分强度，而不是只调振幅
 * ① 系统 haptic（`performHapticFeedback`）是**按机型调好的**，命中时根本走不到我们的
 *    `VibrationEffect`，所以在"最正常的那条路"上，振幅参数是**无效的** ——
 *    想区分强弱只能换常量。`VIRTUAL_KEY` 重、`CLOCK_TICK` 轻，这是系统自己定的语义。
 * ② 兜底（[Vibrator]）那条路才用得到 [durationMs] / [amplitude]，
 *    因此两档要在这两处**都**拉开差距，否则"轻/重"只在部分机型上成立。
 *
 * ## 为什么拖动不能用 `VIRTUAL_KEY`
 * 拖动会连续震几十次，`VIRTUAL_KEY` 的重度叠加起来像"来电"；
 * `CLOCK_TICK` 是系统给滑块 / 时钟指针用的那一档，连起来只留下一层细微的"沙沙"感。
 */
enum class TickStrength(
    internal val constant: Int,
    internal val durationMs: Long,
    internal val amplitude: Int,
) {
    /** 档位切换 / 按钮按下：一次清脆的"嗒" */
    PRESS(HapticFeedbackConstants.VIRTUAL_KEY, 12L, 150),

    /** 连续滑动：很轻、可以连着来。再短（<5ms）很多马达起振不出来 */
    DRAG(HapticFeedbackConstants.CLOCK_TICK, 6L, 70),
}

/** 只记一次：每按一次按钮都打日志会把 logcat 刷爆 */
private var tickFailureLogged = false

/**
 * 记住一个「按一下震一下」的触发器（按钮用，[TickStrength.PRESS]）。
 *
 * 返回的 lambda 可以安全地存进 `LaunchedEffect` 的收集器里 ——
 * 它不读任何 Compose 状态，只会去碰 `view` 和 `vibrator` 两个稳定引用。
 */
@Composable
fun rememberTickHaptics(): () -> Unit {
    val fire = rememberHaptics()
    // 包一层：把"能选档"的版本收敛成"只按一下"。remember 保证实例稳定，
    // 否则 PressFeedback 里 `LaunchedEffect(source)` 每次重组都会重启收集器，白丢一次按下事件。
    return remember(fire) { { fire(TickStrength.PRESS) } }
}

/**
 * 记住一个可以指定强度的触觉触发器（拖动时用，见 [DragHaptics]）。
 *
 * 返回的 lambda 只碰 `view` / `vibrator` 两个稳定引用，可以安全地塞进
 * `pointerInput {}` 里长期持有 —— 不会因为重组换了实例。
 */
@Composable
fun rememberHaptics(): (TickStrength) -> Unit {
    val view = LocalView.current
    val context = LocalContext.current
    // 这两个引用在按钮的整个生命周期里都不变，所以 remember 一次就够，
    // 不会因为重组生成新 lambda 而让收集器里的引用失效
    val vibrator = remember(context) { defaultVibrator(context) }
    return remember(view, vibrator) { { strength -> fireTick(view, vibrator, strength) } }
}

/**
 * 触发一次轻震。
 *
 * **绝不抛异常** —— 触觉是锦上添花，任何机型兼容问题都不该让点击失效。
 */
private fun fireTick(view: View, vibrator: Vibrator?, strength: TickStrength) {
    // ① 系统 haptic：幅度由机型/系统按该档的语义调好，命中就结束。
    //    第 2 个参数是 flags，0 = 尊重系统「触摸反馈」总开关。
    //    ⚠️ 没有 VIBRATE 权限时这里会**静默返回 false**（WMS 拒绝，不抛异常）。
    val systemOk = runCatching {
        view.performHapticFeedback(strength.constant, 0)
    }.getOrDefault(false)
    if (systemOk) return

    // ② 系统没震（总开关关着 / ROM 没实现这一档 / 缺权限）→ 自己震。
    //    `v == null ||` 必须写在这里，Kotlin 才会把下面的 v 智能转换成非空。
    val v = vibrator
    if (v == null || !runCatching { v.hasVibrator() }.getOrDefault(false)) {
        logTickFailure(null)
        return
    }
    runCatching {
        v.vibrate(VibrationEffect.createOneShot(strength.durationMs, strength.amplitude))
    }.onFailure { logTickFailure(it) }
}

/** 失败只报一次，免得连点按钮把 logcat 刷爆 */
private fun logTickFailure(error: Throwable?) {
    if (tickFailureLogged) return
    tickFailureLogged = true
    if (error == null) {
        Log.w(
            TAG,
            "轻震无法生效：设备没有振动马达（模拟器 / 部分平板），" +
                "或系统「触摸反馈」被关闭。此提示只打一次。"
        )
    } else {
        Log.w(TAG, "轻震无法生效（清单里缺 VIBRATE 权限？）。此提示只打一次。", error)
    }
}

/**
 * 取默认振动器。
 *
 * API 31 起推荐走 `VibratorManager.defaultVibrator`（多马达设备要明确取「默认那个」），
 * 老系统仍用 `VIBRATOR_SERVICE`。
 */
@Suppress("DEPRECATION")
private fun defaultVibrator(context: Context): Vibrator? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}.getOrNull()
