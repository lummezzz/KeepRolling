package com.keeprolling.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * **界面（UI 层）的昼夜配色表**（本轮需求 2）。
 *
 * 画布里的路面 / 标线 / 街景走的是 [SimPalette]；这里管的是**浮在画布之上的界面元素**：
 * 圆形图标按钮、左侧按钮列、信息卡、提示气泡、统计面板、弹窗卡片……
 * 两套表分开，是因为它们的"面子"需求不同：画布要的是光影质感，界面要的是可读的对比度。
 *
 * [night] 同样是 0~1 的连续值，和画布的 `nightT` 用同一个动画源 → 整屏是一起暗下来的，
 * 不会出现"路面黑了、按钮还是亮的白块"。
 *
 * 为什么用 CompositionLocal 而不是给每个组件加参数：这些颜色被十来个按钮、卡片共用，
 * 一路传参会把每个函数的签名都污染一遍；注入一个 Local 后，组件内部取一次即可。
 */
@Immutable
class UiPalette(night: Float) {

    private val t: Float = night.coerceIn(0f, 1f)

    private fun mix(day: Long, dark: Long): Color =
        if (t <= 0f) Color(day) else if (t >= 1f) Color(dark) else lerp(Color(day), Color(dark), t)

    /** 按钮 / 卡片的底面（白天纯白，夜间深蓝灰） */
    val panel: Color = mix(0xFFFFFFFF, 0xFF222A34)

    /** 容器面板（左侧按钮列那种半透明底板） */
    val panelSoft: Color = mix(0xFFFFFFFF, 0xFF191F27)

    /** 主文字 */
    val ink: Color = mix(0xFF2C2C2A, 0xFFE8EAF0)

    /** 次要文字 */
    val sub: Color = mix(0xFF5F5E5A, 0xFFB3BAC6)

    /** 更弱的辅助文字 */
    val muted: Color = mix(0xFF888780, 0xFF8B94A2)

    /** 分组分隔线 */
    val divider: Color = mix(0xFFD3D1C7, 0xFF39424F)

    /** 中性图标 */
    val iconNeutral: Color = mix(0xFF5F5E5A, 0xFFC3CAD6)

    /** 禁用态：底色 / 图标 */
    val disabledBg: Color = mix(0xFFEFEEE9, 0xFF2A3038)
    val disabledTint: Color = mix(0xFFC4C2BA, 0xFF59616D)

    /** 夜间用一圈弱描边代替阴影（深色底上阴影是看不见的） */
    val outline: Color = Color(0xFF9AA6B8).copy(alpha = 0.22f * t)

    val night: Float get() = t
}

/** 当前界面配色（由 `MainScreen` 按昼夜注入，默认白天） */
internal val LocalUiPalette = staticCompositionLocalOf { UiPalette(0f) }

/**
 * 交给 Material3 的配色（需求 2）：弹窗、底部设置面板、开关、滑块、Chip 全部跟着它走，
 * 不用逐个组件改色。
 *
 * 只覆盖必要项 —— 其余沿用 `darkColorScheme` / `lightColorScheme` 的默认值，
 * 这样 Material3 内部那些 `surfaceContainer*` 派生色仍然是成套的，不会出现"面板一半深一半浅"。
 */
internal fun appColorScheme(night: Boolean): ColorScheme = if (night) {
    darkColorScheme(
        primary = Color(0xFFAFA9EC),
        onPrimary = Color(0xFF1D1738),
        surface = Color(0xFF1B222C),
        onSurface = Color(0xFFE8EAF0),
        surfaceVariant = Color(0xFF2A323D),
        onSurfaceVariant = Color(0xFFB3BAC6),
        background = Color(0xFF0F1319),
        onBackground = Color(0xFFE8EAF0),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF534AB7),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF26215C),
        surfaceVariant = Color(0xFFEEEDFE),
        onSurfaceVariant = Color(0xFF5F5E5A),
        background = Color(0xFFFAF8F3),
    )
}
