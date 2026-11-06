package com.keeprolling.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 按钮按下反馈：**缩放 + 轻震 +（可选）圆环扩散**。
 *
 * ## 为什么要有这个文件
 * 项目里所有按钮都是自绘圆钮，`clickable` 显式传 `indication = null` 去掉了 Material 的
 * 方形水波纹（圆钮上出现方角很难看）。结果是：按下时**只有声音**，画面一动不动 ——
 * 手指"我按到了"这件事完全靠耳朵，静音场景下就成了"点了没反应"。
 *
 * 三种反馈各管一层，缺一层都会觉得钝：
 * ```
 *  ① 缩放 0.92   视觉：按钮被按进去一点（这层最重要，眼睛先信）
 *  ② 轻震         触觉：一次 10ms 级的 tick（关了声音也还有反馈）—— 实现见 `HapticTick.kt`
 *  ③ 圆环扩散     只给「开始 / 暂停仿真」一个高光动作，普通按钮不加（否则整屏都在放烟花）
 * ```
 *
 * ## 三个必须遵守的细节
 *  1. **缩放只能写在 `graphicsLayer` 里**。改 `size` / `padding` 会触发重新布局 ——
 *     底栏与左侧浮层的锚点都是从尺寸推出来的，一改就是整片界面重排（抖）。
 *  2. **`scale` 用 `State` 持有、只在绘制期读**（`graphicsLayer { }` / `drawBehind { }` 里），
 *     绝不在组合期 `by animateFloatAsState` —— 那会每帧重组按钮所在的整行。
 *  3. **`clickable` 必须传 `press.source`**。自己另建一个 `MutableInteractionSource`
 *     收不到任何按下事件，按钮会"看着有反馈代码、按下去毫无变化"。
 *
 * ## 圆环为什么能画出按钮边界
 * 圆环要扩到按钮的 1.36 倍才看得出"扩散"。Compose 的布局节点默认**不裁剪**绘制内容
 * （`drawBehind` 可以画到边界外），所以不用给容器加 padding。前提是祖先没有
 * `clipToBounds()` —— 底栏与左侧浮层都没有。
 */

/**
 * 全局「按下要不要震一下」的开关（默认开）。
 *
 * 由 [com.keeprolling.app.ui.MainScreen] 从设置注入（音频浮层里最右边那个「轻震」按钮）。
 * 用 `compositionLocalOf` 而不是 `staticCompositionLocalOf`：后者值一变就重组整棵子树，
 * 而这里只需要让"读过它"的按钮重组。
 */
val LocalHapticEnabled = compositionLocalOf { true }

/**
 * 一个按钮的按下反馈状态。
 *
 * 用"状态持有"的方式传出去，调用方在 `graphicsLayer` / `drawBehind` 里读值，
 * 不会把每帧的动画值带进组合期。
 */
class PressFeedback internal constructor(
    /** 传给 `clickable(interactionSource = …)`：**必须是同一个**，否则收不到按下事件 */
    val source: MutableInteractionSource,
    /** 当前缩放（0.92 ~ 1.0）。只在绘制期读 */
    val scale: State<Float>,
    /** 圆环进度 0~1；没开圆环时为 null。只在绘制期读 */
    val ring: Animatable<Float, AnimationVector1D>?,
)

/**
 * 记住一个按钮的按下反馈。
 *
 * @param ring 是否需要"圆环扩散"（只给「开始 / 暂停仿真」；第 57 轮去掉了「进入编辑」）
 */
@Composable
fun rememberPressFeedback(ring: Boolean = false): PressFeedback {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()

    // 注意：**不要**写成 `by` 直接取值 —— 那会让这个 composable 每帧重组
    val scale: State<Float> = animateFloatAsState(
        targetValue = if (pressed) PRESS_SCALE else 1f,
        // dampingRatio < 1 → 松手时轻微过冲一下，像真按钮弹回来
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )

    // ring 是每个调用点固定的常量，用 key 包一层避免"条件 remember"踩到槽位错位
    val ringProgress = remember(ring) { if (ring) Animatable(0f) else null }
    // 触觉走自己的实现（HapticTick.kt）：Compose 的 LocalHapticFeedback 不给传 flags，
    // 而且它在 API 26 上会把 TextHandleMove 映射成 -1（恒不震）。详见该文件头注释。
    val tick = rememberTickHaptics()
    // 开关随时可能被切（音频浮层里那个「轻震」）。用 rememberUpdatedState 让收集器读到**最新**值，
    // 而不是重启 LaunchedEffect —— 重启会把正在进行的收集打断，白白丢一次按下事件。
    val hapticNow = rememberUpdatedState(LocalHapticEnabled.current)

    LaunchedEffect(source) {
        var ringJob: Job? = null
        source.interactions.collect { interaction ->
            if (interaction !is PressInteraction.Press) return@collect
            // ① 触觉：按下的一瞬间就震，不等抬手（"即时感"全靠这一点）
            //    tick 内部自带三级兜底 + 一次失败日志，这里不需要再包 try/catch
            if (hapticNow.value) tick()
            // ② 圆环：每次按下都从 0 重放；用子协程跑动画，别把收集器挂住
            if (ringProgress != null) {
                ringJob?.cancel()
                ringJob = launch {
                    ringProgress.snapTo(0f)
                    ringProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(RING_MS, easing = LinearOutSlowInEasing),
                    )
                }
            }
        }
    }

    return PressFeedback(source = source, scale = scale, ring = ringProgress)
}

/**
 * 把按下反馈挂到按钮上：**缩放**（必做）+ 可选的**圆环**。
 *
 * 顺序要求：这一串必须放在 `.background(...)` **之前** ——
 * 圆环才会画在圆底"后面"（从按钮底下透出来），而不是盖住图标。
 *
 * @param ringColor 传 `Color.Unspecified` = 不要圆环
 */
fun Modifier.pressEffect(
    press: PressFeedback,
    ringColor: Color = Color.Unspecified,
): Modifier {
    val scaled = this.graphicsLayer {
        val s = press.scale.value
        scaleX = s
        scaleY = s
    }
    val ringAnim: Animatable<Float, AnimationVector1D> = press.ring
        ?: return scaled
    if (ringColor == Color.Unspecified) return scaled
    return scaled.drawBehind {
        val p = ringAnim.value
        // 0 = 刚按下（环贴着按钮边缘，大部分被圆底挡住）；1 = 扩到最大且全透明
        if (p <= 0f || p >= 1f) return@drawBehind
        val r0 = size.minDimension / 2f
        drawCircle(
            color = ringColor.copy(alpha = RING_ALPHA * (1f - p)),
            radius = r0 * (1f + RING_SPREAD * p),
            style = Stroke(width = RING_STROKE.toPx()),
        )
    }
}

/**
 * 对话框里的图标按钮（用来替代 Material3 的 `IconButton`）。
 *
 * 尺寸与图标大小跟 `IconButton` 一致（48dp / 24dp，透明底），区别只有两处：
 *  - 去掉 Material 的方形水波纹，换成**按下缩放 + 轻震**，与底栏、左侧浮层的手感统一；
 *  - 不带圆环（第 57 轮起"整个 App 只剩开始 / 暂停那一圈"，见 [rememberPressFeedback]）。
 */
@Composable
fun PressIconButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
) {
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(48.dp)
            .pressEffect(press)
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 按下时的缩放。0.92 是"看得出来但不夸张"的经验值（44dp → 缩 3.5dp） */
private const val PRESS_SCALE = 0.92f
/** 圆环扩散时长。260ms：比音效长一点，收尾时还在扩散，听着像有回声 */
private const val RING_MS = 260

/** 圆环最终半径 = 按钮半径 × (1 + 该值) */
private const val RING_SPREAD = 0.36f

/** 圆环起始透明度（乘以 1-p 后线性衰减到 0） */
private const val RING_ALPHA = 0.35f

/** 圆环线宽 */
private val RING_STROKE = 2.dp
