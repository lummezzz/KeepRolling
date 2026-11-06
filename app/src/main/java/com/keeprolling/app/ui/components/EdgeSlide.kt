package com.keeprolling.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 元素"从哪条边弹出来 / 往哪条边缩回去"。
 *
 * 对应关系：
 *  - [START] 屏幕左侧：设置行、道路类型按钮等左侧按钮列；
 *  - [END] 屏幕右侧：右侧浮出的滑块 / 菜单（预留）；
 *  - [TOP] 屏幕上方：车辆信息框、顶部气泡、统计面板；
 *  - [BOTTOM] 屏幕底部：首页三按钮、编辑模式按钮、崩溃横幅。
 */
enum class SlideEdge { START, END, TOP, BOTTOM }

/**
 * 出现在 / 消失掉时那一点点淡入淡出（配合位移，边界不会"硬切"）。
 *
 * `internal` 而不是 private：底栏"换班"要按它来推算接力延时（见 `BottomBar.HANDOVER_DELAY_MS`），
 * 让它跟着这个时长自动走，别在两处各写一个魔法数字。
 */
internal const val EDGE_FADE_MS = 160

/** 弹簧阻尼：略微过冲再回弹。进退共用，保证来去"手感"一致 */
private const val DAMPING = 0.58f

/**
 * **成对的边缘弹入 / 弹出动画**。
 *
 * ## 为什么不用 `AnimatedVisibility` 了
 *
 * 早期版本用 `AnimatedVisibility(visibleState = …)` 包内容，进退参数完全对称，
 * 但实际观感是"退出顺、进入卡"。原因不在动画参数，而在**两个方向的成本根本不对称**：
 *
 *  - **退出**：内容早已组合、测量、绘制过。整段退场只是每帧改一次图层位移与透明度，
 *    纯 GPU 变换，几乎零成本 → 顺。
 *  - **进入**：`AnimatedVisibility` 在不可见时**根本不组合内容**，退场结束还会把内容**销毁**。
 *    于是每次进入的第一帧要一次性干完：新建整棵子树的组合节点、跑所有 `remember`、
 *    生成 Modifier 链、首次测量、首次绘制、新建 RenderNode。这 3~8ms 的重活正好压在
 *    **弹簧速度最快、人眼最敏感**的那一帧上 → 开头明显顿一下。
 *
 * 换句话说：**卡的不是动画，是动画第一帧上那次"首次组合"**。
 *
 * ## 现在的做法：先预热，再起跑
 *
 * 位移自己用 [Animatable] 驱动（参数与旧版逐字一致：`spring(0.58, Medium)` + `tween(160)`），
 * 于是可以把"进组合"和"开始动"拆成两步：
 *
 *  1. `present = true` —— 这一帧只做组合、测量、首次落位；此时 `slide = 0` / `fade = 0`，
 *     内容贴在屏幕外且完全透明，**既看不见也不会闪**；
 *  2. `withFrameNanos` 让过两帧 —— 重活已经在观众看不见的地方付完了（一帧只够"排上队"，
 *     两帧才能保证本轮重组已经跑完）；
 *  3. 启动弹簧与淡入 —— 此时每一帧只剩图层变换，和退场一样轻。
 *
 * ## 预热是"整组"的，错峰只错起跑（本轮修复）
 *
 * [index] 的错峰**以前是把"进组合"本身也一起推迟**：第 i 个元素等到 `i × staggerMs` 才
 * `present = true`，也就是它要在前一个元素**正在飞的过程中**现组合一遍 —— 一组 5 个按钮
 * 就是 5 次小卡顿，均匀撒在整段进场里，看起来像"一抖一抖地往上冒"。
 *
 * 现在 `present = true` 只等 [enterDelayMs] 这个"组级"的时刻，同组元素**一起**在看不见的
 * 状态下建好；`i × staggerMs` 只推迟**起跑**。这样一组元素只有一次（不可见的）建树开销，
 * 错峰的仍然是动画本身 → 逐个登场的手感不变，但每一次起跑都是纯图层变换。
 *
 * [exitStaggerMs] 单独控制退场是否排队：默认与进场同节奏（`= staggerMs`）；
 * 底栏那种"整组换班"的场景传 0，让旧的一组**整组同时**沉底（原因见 `BottomBar` 的注释）。
 *
 * [enterDelayMs] 用于"新旧两批元素交替"的场合（比如主界面 ↔ 编辑模式的底栏）：
 * 让新的一批等旧的一批彻底离开视野之后再上，避免两套按钮在屏幕同一处重叠。
 * **冷启动那次不吃这个延时**（没有"旧的一批"可等），否则首屏按钮会莫名迟到。
 *
 * @param visible 是否应当显示。false 时播完退场动画才会真正从组合里移除
 * @param edge    贴哪条屏幕边（同时决定进场/退场的位移方向）
 * @param distance 位移距离；默认 52dp —— 够看出"是从外面来的"，又不至于飞很远
 */
@Composable
fun EdgeSlideIn(
    visible: Boolean,
    edge: SlideEdge,
    modifier: Modifier = Modifier,
    index: Int = 0,
    distance: Dp = 52.dp,
    staggerMs: Long = 45L,
    exitStaggerMs: Long = staggerMs,
    enterDelayMs: Long = 0L,
    replayKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val travelPx = with(LocalDensity.current) { distance.toPx() }

    // 位移进度：0 = 完全贴在屏幕外，1 = 就位。用弹簧（会过冲再回弹）。
    val slide = remember { Animatable(0f) }
    // 透明度进度：单独用短补间，对应旧版的 fadeIn/fadeOut(tween(160))。
    // 两段并行 ≈ 旧的 `slide + fade` 复合过渡，观感完全一样。
    val fade = remember { Animatable(0f) }

    // 是否留在组合里（也就是"是否占位"）。
    // 初值跟随 visible：首次进入组合时如果本来就该显示，就直接以"看不见的贴边态"组合好，
    // 再让过两帧起跑 —— 所以冷启动那次进场也是"弹进来"而不是"凭空出现"。
    var present by remember { mutableStateOf(visible) }

    // "这是本元素的第一次进场吗"。冷启动时整棵界面刚搭起来，没有谁需要等，
    // 所以第一次进场不吃 [enterDelayMs]。
    // 用普通数组当可变持有器（不用 State）：这个标记只给协程看，写它不该触发重组。
    val coldStart = remember { BooleanArray(1) { true } }

    LaunchedEffect(visible, replayKey) {
        val firstRun = coldStart[0]
        coldStart[0] = false

        // ① 重播（本轮需求 5）：同一个槽位只是换了"对象"——比如从一个开放路口点到另一个
        //    开放路口，按钮的图标其实一模一样，不给点反馈就像没点中。这里先沿原路弹回去，
        //    再从边缘弹回来。
        //    ⚠️ 重播**不能**写 `present = false`：那会让父布局先塌一下再撑开（就是"合并框"
        //    那种抖动）。留在组合里只动图层，位置一点都不会变。
        if (visible && !firstRun && present && slide.value > 0.9f) {
            val w = index * exitStaggerMs
            if (w > 0L) delay(w)
            coroutineScope {
                launch { fade.animateTo(0f, tween(EDGE_FADE_MS)) }
                slide.animateTo(0f, spring(dampingRatio = DAMPING, stiffness = Spring.StiffnessMedium))
            }
        }

        if (visible) {
            // ② 预热：同组元素**立刻**进组合 + 测量 + 首次落位（透明、贴边，看不见）。
            //
            // ⚠️ 这一步必须在 `delay` **之前**（第 57 轮）。从前是"先等 140ms 再建树"，
            // 于是换班过程中有**两次**重组高峰：一次在点击那一帧（模式切换本身），
            // 一次在 140ms 后（建这 5~8 个按钮）。后一次正好落在旧组淡出到一半、
            // 新组即将起跑的位置上 —— 画面正动得最快的时候卡一下，最显眼。
            //
            // 现在把建树并进"点击那一帧"：反正那一帧本来就要重组（`uiMode` 变了、
            // 按钮组要换），一次重活合并成一帧摊掉；等 140ms 之后真正起跑时，
            // 每个元素都已经组合/测量完毕，每帧只剩图层变换 —— 和退场一样轻。
            present = true
            // ① 组级接力 + ③ 个人错峰：都只推迟**起跑**，不推迟建树（两段延时可以直接相加）
            val lead = if (firstRun) 0L else enterDelayMs + index * staggerMs
            if (lead > 0L) delay(lead)
            // ④ 把这一帧让过去，让上面的重活在动画开始前付完。
            //    为什么等**两**帧：第一帧负责把"内容已出现"这次重组真正跑完（组合 + 测量 + 落位），
            //    第二帧才是有保证的"就位之后"。只等一帧时，重组与动画的帧回调谁先谁后取决于
            //    帧时钟的唤醒顺序，等于把重活又塞回了动画第一帧。
            withFrameNanos { it }
            withFrameNanos { it }
            // ⑤ 起跑：位移与淡入并行
            coroutineScope {
                launch { fade.animateTo(1f, tween(EDGE_FADE_MS)) }
                slide.animateTo(1f, spring(dampingRatio = DAMPING, stiffness = Spring.StiffnessMedium))
            }
        } else {
            // 从来没显示过（还在预热前就又被隐藏）：没有可播的退场，直接结束
            if (!present) return@LaunchedEffect
            val wait = index * exitStaggerMs
            if (wait > 0L) delay(wait)
            coroutineScope {
                launch { fade.animateTo(0f, tween(EDGE_FADE_MS)) }
                slide.animateTo(0f, spring(dampingRatio = DAMPING, stiffness = Spring.StiffnessMedium))
            }
            // 退场播完才真正移出组合 → 位置随之释放（和旧版 AnimatedVisibility 一致）
            present = false
        }
    }

    if (present) {
        Box(
            // 位移/透明度都写在 graphicsLayer 里：只改图层，不触发重新布局。
            // 读数发生在图层更新阶段（不是组合阶段），所以每帧只做一次图层更新。
            modifier = modifier.graphicsLayer {
                val outside = (1f - slide.value) * travelPx
                when (edge) {
                    SlideEdge.START -> translationX = -outside
                    SlideEdge.END -> translationX = outside
                    SlideEdge.TOP -> translationY = -outside
                    SlideEdge.BOTTOM -> translationY = outside
                }
                alpha = fade.value
            },
        ) {
            content()
        }
    }
}

/**
 * 记住"最后一次非空的值"。
 *
 * 需要它的原因：退场动画期间组件仍在组合里，但驱动它的数据（聚焦车辆、提示文案）
 * 已经在同一时刻变成 null 了。直接渲染 null 就只剩一片空白在飞 —— 所以要留住上一帧
 * 的内容，让它**带着脸**缩回去。
 *
 * 写状态放在 [SideEffect] 里（组合完成后才提交），避免组合期写状态引起的额外重组；
 * 有值时直接返回新值，所以界面上永远显示的是当前内容，不会滞后一帧。
 */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { mutableStateOf(value) }
    SideEffect { if (value != null) holder.value = value }
    return value ?: holder.value
}
