package com.keeprolling.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.viewmodel.SimulatorViewModel

private val TEAL = Color(0xFF1D9E75)
private val AMBER = Color(0xFFBA7517)
private val PURPLE = Color(0xFF534AB7)
private val DANGER = Color(0xFFE24B4A)

/**
 * 底栏"舞台"的固定高度 = 最高那颗按钮（编辑模式退出编辑的红色勾 64dp）。
 *
 * ⚠️ 这是本轮"交接时抖一下"的**直接原因**：两组按钮高度不一样（主界面 60dp、
 * 编辑模式 50dp + 64dp），以前这个 Box 没有固定高度，高度就由"当前有谁在场"决定 ——
 * 那颗 64dp 的勾一进组合，舞台就从 60 跳到 64，底栏整体（以及左边那列按钮的锚点）
 * 跟着瞬间位移 4dp；反过来退出编辑时，勾一走，舞台再掉回 60，又跳一下。
 * 把高度钉死，布局在整段交接里**完全不变**，动画才可能是纯图层变换。
 */
internal val BOTTOM_BAR_HEIGHT = 64.dp

/**
 * 交接的节奏（本轮重排）。
 *
 * 一次换班拆成两拍：
 *   ① 旧的那组**整组同时**沉底 —— 淡出 [EDGE_FADE_MS] ms 后彻底看不见；
 *   ② 新的那组在 [HANDOVER_DELAY_MS] 时**预热**（进组合、测量、落位，但完全透明且贴在
 *      屏幕外），再晚两帧起跑，然后按 0/1/2… 逐个登场。
 *
 * 为什么退场不排队（[BAR_EXIT_STAGGER_MS] = 0）：
 * 排队退场会把总时长拖到"最后一颗"（`4 × 45ms + 160ms ≈ 340ms`），而新的那组不能等这么久，
 * 否则点一下要愣小半秒；可要是不等，两套圆就在屏幕底部同一片区域重叠 —— 旧的在半透明地
 * 往下沉、新的从同一条边往上冒，看着发虚、闪烁。换班场景里"整组一起退"既短又整齐，
 * 把错峰留给**进场**，衔接就干净了。
 *
 * 为什么接力差 20ms 而不是"等对方彻底消失"：
 * 旧组 alpha 归零在 160ms；新组预热 140ms、起跑约 173ms（预热后要等两帧）。于是画面
 * **既不空拍、也不会两套圆同框**。这个值直接由淡出时长推出来，以后调淡出不用再改第二处。
 */
private const val BAR_EXIT_STAGGER_MS = 0L
private const val HANDOVER_DELAY_MS: Long = EDGE_FADE_MS - 20L

/**
 * 底部交互区：**没有背景条**，只有悬浮圆形图标按钮。
 *
 *  主界面：开始/暂停 · 编辑 · 设置（居中一排）
 *  编辑模式：左侧 [道路][清空][撤销][重做] + 右下角 [红色勾·退出编辑]，
 *      同处一行、中心在同一水平线上；退出按钮放大一圈、图标为勾、底色红。
 *
 * 每个按钮都是"从底部升起 / 沉回底部"的一对动画（[EdgeSlideIn]）：
 * **进场**按 0/1/2… 的序号逐个弹出；**退场**整组同时沉底（不排队，见上面的节奏说明）。
 *
 * ⚠️ 两组按钮放在同一个 [Box] 里（而不是 `if/else` 二选一）：这样切换模式时，
 * 退场的那组还能留在组合里播完动画 —— 用 `if/else` 的话条件一假就整组瞬间蒸发，
 * 只剩新的一组自己弹进来，进出不对称。
 *
 * ⚠️ 这个 Box 的**高度是常量**（[BOTTOM_BAR_HEIGHT]），宽度由编辑模式那行 `fillMaxWidth`
 * 撑满、也是常量。也就是说：换班整个过程里，底栏的**布局尺寸一次都不变**，
 * 只有子项在图层上做位移/淡入淡出。布局一变就会带着整片界面重排，那才是"抖动"的来源。
 */
@Composable
fun BottomBar(vm: SimulatorViewModel, modifier: Modifier = Modifier) {
    val uiMode by vm.uiMode.collectAsState()
    val simState by vm.simState.collectAsState()
    val drawTool by vm.drawTool.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()

    val viewMode = uiMode == SimulatorViewModel.UiMode.VIEW

    // contentAlignment = Center：主界面那组比编辑模式窄，Box 被编辑模式那行撑满宽度时，
    // 窄的那组仍然居中，不会突然贴到左边去。
    // height(BOTTOM_BAR_HEIGHT)：**钉死高度** —— 两组互换时布局不许变（原因见该常量注释）。
    // fillMaxWidth：宽度也钉死。以前宽度由"当前在场的最宽子项"决定 —— 编辑行是 `fillMaxWidth`、
    // 主界面那行是 ~216dp，于是编辑行一退场（`present = false`）盒子就从屏宽塌回 216dp，
    // 居中基准一变，还在飞的那几个按钮会一起横跳几个像素。两个方向都钉死，换班才是纯图层动画。
    Box(
        modifier = modifier.fillMaxWidth().height(BOTTOM_BAR_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        // ---- 主界面：居中三按钮 ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val running = simState == SimulatorViewModel.SimState.RUNNING
            EdgeSlideIn(
                visible = viewMode, edge = SlideEdge.BOTTOM,
                index = 0, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
            ) {
                CircleIconButton(
                    icon = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    size = 60.dp,
                    bg = if (running) AMBER else TEAL,
                    tint = Color.White,
                    // 高光动作（唯一的一个）：开始 / 暂停仿真 —— 整场交互里最重要的一下
                    ring = true,
                ) { vm.toggleSimulation() }
            }
            EdgeSlideIn(
                visible = viewMode, edge = SlideEdge.BOTTOM,
                index = 1, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
            ) {
                // 这里**不加圆环**（第 57 轮按需求改）：整场交互只保留「开始 / 暂停」那一个
                // 圆环。进入编辑会连带整屏换一套按钮（一场"换班"演出），再叠一圈扩散，
                // 视觉上就抢了按钮本身的风头；而开始/暂停是"世界开始动"的那一下，值得高光。
                CircleIconButton(icon = Icons.Filled.Edit) { vm.enterEditMode() }
            }
            EdgeSlideIn(
                visible = viewMode, edge = SlideEdge.BOTTOM,
                index = 2, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
            ) {
                CircleIconButton(icon = Icons.Filled.Settings) { vm.toggleSettingsRow() }
            }
        }

        // ---- 编辑模式：左 [道路][清空][撤销][重做] —— 右 [红色勾·退出编辑]（同一水平线）----
        // 撤销 / 重做**常驻显示**：没有可撤销（可重做）的操作时呈灰色、不可点击。
        // 错峰序号从 0 重新数：这一行是**独立的一组**，接着主界面那组往下数的话，
        // 第一个按钮还要多等 3 档（135ms）才露面。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 多车道用「多根线 / 多段弧」图标直接表达车道数，**不加 x2/x3 角标**。
                // 底部「道路」按钮显示当前选中的路型；未选任何路型时显示通用的「修路」图标。
                val roadIcon = if (drawTool == SimulatorViewModel.DrawTool.NONE) {
                    Icons.Filled.AddRoad
                } else {
                    roadIconFor(drawTool.curve, drawTool.lanes)
                }
                EdgeSlideIn(
                    visible = !viewMode, edge = SlideEdge.BOTTOM,
                    index = 0, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
                ) {
                    CircleIconButton(
                        icon = roadIcon,
                        size = 50.dp,
                        bg = if (drawTool == SimulatorViewModel.DrawTool.NONE) null else PURPLE,
                        tint = if (drawTool == SimulatorViewModel.DrawTool.NONE) null else Color.White,
                    ) { vm.toggleRoadSubmenu() }
                }
                // 清空：删除画布上所有道路与路口设置（点了会先弹确认框）
                EdgeSlideIn(
                    visible = !viewMode, edge = SlideEdge.BOTTOM,
                    index = 1, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
                ) {
                    CircleIconButton(
                        icon = Icons.Filled.DeleteSweep,
                        size = 50.dp,
                        tint = Color(0xFFA32D2D),
                    ) { vm.requestClear() }
                }
                // 撤销（没有可撤销步骤时灰色、不可点）
                EdgeSlideIn(
                    visible = !viewMode, edge = SlideEdge.BOTTOM,
                    index = 2, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
                ) {
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.Undo,
                        size = 50.dp,
                        enabled = canUndo,
                    ) { vm.undo() }
                }
                // 重做 / 取消撤回（没有可重做步骤时灰色、不可点）
                EdgeSlideIn(
                    visible = !viewMode, edge = SlideEdge.BOTTOM,
                    index = 3, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
                ) {
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.Redo,
                        size = 50.dp,
                        tint = if (canRedo) Color(0xFF185FA5) else null,
                        enabled = canRedo,
                    ) { vm.redo() }
                }
            }

            // 退出编辑：红色圆底 + 白色**勾**（底色/尺寸保持不变）
            EdgeSlideIn(
                visible = !viewMode, edge = SlideEdge.BOTTOM,
                index = 4, enterDelayMs = HANDOVER_DELAY_MS, exitStaggerMs = BAR_EXIT_STAGGER_MS,
            ) {
                CircleIconButton(
                    icon = Icons.Filled.Check,
                    size = 64.dp,
                    bg = DANGER,
                    tint = Color.White,
                ) { vm.exitEditMode() }
            }
        }
    }
}

/**
 * 圆形图标按钮（白色底 + 细边），主按钮用实心色。
 *
 * 注意：`clickable` 显式传入 `indication = null`，去掉默认的水波纹（点击时那一块
 * **渐显渐隐的方形背景**）——按钮本身是圆的，而水波纹会被矩形边界裁剪成方形，不好看。
 * 取而代之的是 [rememberPressFeedback]：按下缩到 0.92 + 一次轻震，松手弹回。
 *
 * @param bg    null = 用当前昼夜配色里的"面板色"（白天白 / 夜间深灰蓝）；
 *              传了颜色就按传的来（实心主按钮）。
 * @param enabled 为 false 时呈**灰色不可点击**状态（用于「撤销 / 重做」常驻显示时的禁用态）。
 * @param ring  是否加"按下扩散圆环"。只给**开始 / 暂停仿真**这一个高光动作
 *              （第 57 轮按需求去掉了「进入编辑」那一圈），普通按钮加上就变成整屏放烟花。
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    size: Dp = 52.dp,
    bg: Color? = null,
    tint: Color? = null,
    enabled: Boolean = true,
    ring: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback(ring)
    val realBg = if (!enabled) pal.disabledBg else (bg ?: pal.panel)
    val realTint = if (!enabled) pal.disabledTint else (tint ?: pal.iconNeutral)
    Box(
        modifier = modifier
            .size(size)
            // 必须在 background 之前：圆环才画在圆底"后面"
            .pressEffect(press, if (enabled) (bg ?: PURPLE) else Color.Unspecified)
            .background(realBg, CircleShape)
            .clickable(
                enabled = enabled,
                // ⚠️ 必须是 press.source，新建一个就收不到按下事件
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = realTint,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/**
 * 小号的上下文按钮（左侧浮出列使用）；同样去掉方形水波纹。
 *
 * 按下反馈与 [CircleIconButton] 一致（缩放 0.92 + 轻震），但**不加圆环**：
 * 这一列按钮是密集排列的，全都扩散会互相压住。
 *
 * @param enabled 为 false 时呈**灰色不可点击**（用于「已经是该车道数」的按钮——避免重复点击）。
 */
@Composable
fun SmallIconButton(
    icon: ImageVector,
    tint: Color? = null,
    bg: Color? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback()
    val realBg = if (!enabled) pal.disabledBg else (bg ?: pal.panel)
    val realTint = if (!enabled) pal.disabledTint else (tint ?: pal.iconNeutral)
    Box(
        modifier = modifier
            .size(44.dp)
            .pressEffect(press)
            .background(realBg, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = realTint, modifier = Modifier.size(20.dp))
    }
}
