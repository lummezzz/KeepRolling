package com.keeprolling.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.keeprolling.app.audio.Sfx
import com.keeprolling.app.model.SimSettings
import com.keeprolling.app.ui.DayMode
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.ui.i18n.Lang
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.viewmodel.SimulatorViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val PURPLE = Color(0xFF534AB7)

/** 「自动规划已启用」的绿：与倍速 1.5x+、开放路口同一族语义色，表示"这个功能在干活" */
private val REROUTE_ON = Color(0xFF1D9E75)

/** 不可用按钮的图标透明度：淡但要能看清形状（再淡就变成"这里什么都没有"） */
private const val DISABLED_ALPHA = 0.35f

/**
 * 左侧"道路工具"菜单的排列顺序（从上到下固定为）：
 * **直线 · 直线×2 · 直线×3 · 曲线 · 曲线×2 · 曲线×3**。
 * 车道数由图标本身表达（1/2/3 根斜线 或 1/2/3 段同心弧），不再用角标。
 */
private val ROAD_TOOL_ORDER = listOf(
    SimulatorViewModel.DrawTool.LINE,
    SimulatorViewModel.DrawTool.LINE2,
    SimulatorViewModel.DrawTool.LINE3,
    SimulatorViewModel.DrawTool.CURVE,
    SimulatorViewModel.DrawTool.CURVE2,
    SimulatorViewModel.DrawTool.CURVE3,
)

/**
 * 昼夜三选一的排列顺序（本轮需求 1）：白天 → 夜间 → 自动循环。
 *
 * 这个 list 同时决定了**展开菜单里从左到右**的次序，以及三个按钮的错峰序号
 * （用 `m.ordinal`）—— 所以"太阳在最左、AUTO 在最右"是顺序的必然结果，
 * 不需要另外写位置。
 */
private val DAY_MODES = listOf(DayMode.DAY, DayMode.NIGHT, DayMode.AUTO)

/**
 * 语言二选一的排列顺序（本轮需求 2）：中文在左、English 在右。
 *
 * 与 [DAY_MODES] 同一个套路：list 的顺序就是展开后从左到右的顺序，
 * `l.ordinal` 同时当错峰序号用（0 先弹、1 后弹，与进出场对应）。
 */
private val LANG_ORDER = listOf(Lang.ZH, Lang.EN)

/**
 * 左侧浮出的**竖向**按钮组，分成**两枚独立胶囊**：
 *  - 主列：编辑模式点「道路」→ 直线 / 曲线；点「设置」→ 倍速 / 统计 / 自主规划 /
 *    昼夜 / 去UI / 保存 / 加载 / 语言；
 *  - 选择组：点选道路 → 改车道 / 删除；点选断头路 → 开放；点选开放路口 → 封闭 / 设置 /
 *    红绿灯增删。**选中了谁，这一整枚胶囊（底框 + 按钮）就作为一个整体弹入弹出**
 *    （本轮需求 2）—— 三类元素全部走同一段代码，所以动画规则完全一致。
 *
 * ⚠️ 这里**不再用 `if (条件) { 按钮 }`**。条件是假的时候组件会立刻消失，
 * 根本没机会播退场动画。改成永远声明、把条件交给 [EdgeSlideIn] 的 `visible`：
 * 条件变假 → [EdgeSlideIn] 让内容多留一会儿、沿原路滑回去，再真正移出组合。
 *
 * 按钮从左往右的弹出次序由声明顺序决定（`slideIdx` 自增），进场与退场用的是同一个序号，
 * 所以"先出来的先回去"，两段动画完全对应。
 */
@Composable
fun ContextButtons(vm: SimulatorViewModel, modifier: Modifier = Modifier) {
    val uiMode by vm.uiMode.collectAsState()
    val selection by vm.selection.collectAsState()
    val drawTool by vm.drawTool.collectAsState()
    val showRoadSubmenu by vm.showRoadSubmenu.collectAsState()
    val showSettingsRow by vm.showSettingsRow.collectAsState()
    val showStats by vm.showStats.collectAsState()
    val speedDialOpen by vm.speedDialOpen.collectAsState()
    val rerouteMenuOpen by vm.rerouteMenuOpen.collectAsState()
    val configVersion by vm.configVersion.collectAsState()
    // 用"稳定版"版本号：拖动端点时路网每帧都在动，但左侧按钮的内容并不会变，
    // 跟着每帧重组纯属白烧 —— 拖动结束才刷新（见 SimulatorViewModel.networkVersionSettled）
    val networkVersion by vm.networkVersionSettled.collectAsState()
    val lang by vm.lang.collectAsState()
    // 昼夜菜单（本轮需求 1）：按钮显示哪个图标、菜单里哪个高亮，都靠它
    val dayMode by vm.dayMode.collectAsState()
    val dayMenuOpen by vm.dayMenuOpen.collectAsState()
    // 语言菜单（本轮需求 2）：与昼夜菜单同一套开关逻辑
    val langMenuOpen by vm.langMenuOpen.collectAsState()
    // 音频菜单（本轮新增）：点「音频」→ 右侧展开「音乐 · 音效 · 轻震」三个开关
    val audioMenuOpen by vm.audioMenuOpen.collectAsState()
    // 两个开关的当前值。它们存在 `vm.settings` 里（要持久化），不是 StateFlow ——
    // 靠上面订阅的 `configVersion` 驱动重组：toggleMusic/toggleSfx 都会 +1。
    val musicOn = vm.settings.musicEnabled
    val sfxOn = vm.settings.sfxEnabled
    // 触觉反馈（本轮新增）：第三个开关，排在「音效」右边
    val hapticOn = vm.settings.hapticEnabled
    // ⚠️ 选择目标 id 必须**订阅**，不能读 `.value`：
    // 从一个开放路口点到另一个开放路口时 `selection` 一直是 OPEN（StateFlow 去重，不会发新值），
    // 只有 id 变了 —— 不订阅的话整个组件根本不重组，按钮自然"没反应"（需求 5 说的就是它）。
    val selectedNodeId by vm.selectedNodeId.collectAsState()
    val selectedRoadId by vm.selectedRoadId.collectAsState()

    val pal = LocalUiPalette.current
    // 自主规划那排圆钮要按当前语言取短标签（关 / 灵敏 / 标准 / 保守 ⇄ Off / Hi / Mid / Lo）
    val m = LocalMessages.current

    // 浮层相对按钮左侧的横向偏移（按钮宽 44dp + 8dp 间隙）
    val density = LocalDensity.current
    val popupOffsetX = with(density) { 52.dp.roundToPx() }

    /**
     * 「一排圆钮」类浮层（昼夜 / 语言 / 音频）的横向偏移：44dp 按钮 + **20dp 间隙** = 64dp。
     *
     * 为什么要比倍速滑块 / 自主规划卡片（52dp）多留 12dp：
     * 那两类浮层本身是一条横线 / 一张卡片，贴着按钮看也还是一个整体；
     * 而这一排是**几个独立的圆钮**，贴太近会和左侧那一列按钮在视觉上连成一串，
     * 让人以为它们是同一列的一部分（"这两个圆钮怎么横着长了"）。
     * 拉开一点，它们就明确是"从这个按钮展开出来的一排"。
     *
     * 数值统一取 [ROW_POPUP_OFFSET]：语言与音频已经抽成独立组件（在文件末尾），
     * 它们内部自己算偏移，两处必须是同一个数 —— 否则挪动按钮时浮层会错位几像素。
     */
    val rowPopupOffsetX = with(density) { ROW_POPUP_OFFSET.roundToPx() }

    val editMode = uiMode == SimulatorViewModel.UiMode.EDIT
    val viewMode = uiMode == SimulatorViewModel.UiMode.VIEW
    val showRoadTools = editMode && showRoadSubmenu
    val showSettings = viewMode && showSettingsRow

    // ==================== 选择类（本轮需求 1）====================
    // 点任一元素（道路 / 路口 / 红绿灯）时，它对应的那几个按钮**连同外面那圈白色胶囊底框**
    // 要作为一个整体弹入 / 弹出；换元素时先整枚弹出去、再整枚弹进来。
    //
    // 三类元素全部走 [SelectionCapsule] 这一段代码 —— 动画规则天生完全一致，
    // 不存在"这一类比那一类多一个按钮就错位"的可能。
    //
    // 这里只负责把"当前选中的是谁、它有哪些按钮"整理成一份 [SelView] 快照。
    val selView = if (editMode) selViewOf(vm, selection, selectedNodeId, selectedRoadId) else null

    // 主列（道路工具 + 设置行）有没有内容。选择组已经独立，不再算进来。
    val hasMainContent = showRoadTools || showSettings

    // 主列按钮的错峰序号：**每组从 0 重新数**，每声明一个 +1。
    //  - 必须按组重置：如果全局累加，VIEW 模式的「设置行」会接在十几档之后才开始弹；
    //  - 组件内序号稳定：同一个按钮每次进退都用同一档延时，动效不会错位。
    var slideIdx = 0

    // 外层只负责**竖向堆叠 + 贴底**：上面是主列胶囊，下面是选择组胶囊，
    // 两枚胶囊各自独立弹入弹出（需求 2 要的就是这个）。
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Suppress("UNUSED_EXPRESSION") configVersion
        @Suppress("UNUSED_EXPRESSION") networkVersion

        // ==================== 主列：道路工具 + 设置行 ====================
        EdgeSlideIn(
            visible = hasMainContent,
            edge = SlideEdge.START,
            distance = 56.dp,
        ) {
            Column(
                modifier = Modifier
                    .background(pal.panelSoft.copy(alpha = 0.92f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---- 画路工具：直线 / 直线×2 / 直线×3 ｜ 曲线 / 曲线×2 / 曲线×3 ----
                // 点「道路」展开；点**任一工具后立即全部收起**（见 setDrawTool）。
                // 第 3 个与第 4 个按钮之间加大间距，并用一条横线把「3 直线」与「3 曲线」分隔开。
                for (tool in ROAD_TOOL_ORDER.take(3)) {
                    RoadToolButton(vm, tool, drawTool == tool, showRoadTools, slideIdx++)
                }
                EdgeSlideIn(showRoadTools, SlideEdge.START, index = slideIdx++) { LeftGroupDivider() }
                for (tool in ROAD_TOOL_ORDER.drop(3)) {
                    RoadToolButton(vm, tool, drawTool == tool, showRoadTools, slideIdx++)
                }

                slideIdx = 0   // 新的一组：设置行错峰从 0 重新开始

                // ---- 设置行：倍速 / 统计 / 自主规划 / 夜间 / 去UI ‖ 语言 / 音频 / 保存 / 加载 ----
                val speed = vm.settings.speedMultiplier

                // 倍速：点按钮 → 右侧浮出「横线 + 圆形滑块」；**松手即完成设定并使滑块渐隐消失**。
                // 按钮底色随所选倍率变化：0.5x 黄 · 1x 面板色 · 1.5/2/3x 绿 · 5x 红。
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) {
                    Box {
                        val (btnBg, btnTint) = speedButtonColors(speed, pal.panel)
                        SmallIconButton(
                            icon = Icons.Filled.Speed,
                            tint = btnTint,
                            bg = btnBg,
                        ) { vm.toggleSpeedDial() }
                        if (speedDialOpen) {
                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(x = popupOffsetX, y = 0),
                            ) {
                                val scope = rememberCoroutineScope()
                                val alpha = remember { Animatable(1f) }
                                LaunchedEffect(Unit) { alpha.snapTo(1f) }
                                SpeedRail(
                                    current = speed,
                                    trackColor = pal.divider,
                                    knobColor = pal.panel,
                                    modifier = Modifier.alpha(alpha.value),
                                    onPick = { vm.setSpeedMultiplier(it, announce = false) },
                                    onRelease = {
                                        // 松手 → 渐隐 → 收起滑块（并由 closeSpeedDial 气泡提示最终倍率）
                                        scope.launch {
                                            alpha.animateTo(0f, tween(180))
                                            vm.closeSpeedDial()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // 统计开关（右上角 运行时间 + 车辆数）
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) {
                    SmallIconButton(
                        icon = Icons.Filled.BarChart,
                        tint = if (showStats) Color.White else null,
                        bg = if (showStats) PURPLE else null,
                    ) { vm.toggleStats() }
                }

                // 自主规划：点按钮 → 右侧展开**一排四个圆钮**：关 / 灵敏 / 标准 / 保守。
                //
                // 交互与昼夜、语言、音频**完全同一套**：同一份 EdgeSlideIn 逐个滑入、
                // 同一时间只开一个浮层、选中即收起。改之前这里是一张 224dp 的卡片，
                // 立即出现/立即消失、条目也没有按下反馈 —— 和整列圆钮的手感是两回事。
                //
                // 主按钮配色分两层：展开中 = 紫（与其它"我正开着"的按钮同义）；
                // 已启用但未展开 = 绿（沿用原来"自动规划开着"的提示）；都没开 = 面板色。
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) {
                    Box {
                        SmallIconButton(
                            icon = Icons.AutoMirrored.Filled.AltRoute,
                            tint = if (rerouteMenuOpen || vm.settings.autoReroute) Color.White else null,
                            bg = when {
                                rerouteMenuOpen -> PURPLE
                                vm.settings.autoReroute -> REROUTE_ON
                                else -> null
                            },
                        ) { vm.toggleRerouteMenu() }
                        if (rerouteMenuOpen) {
                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(x = rowPopupOffsetX, y = 0),
                            ) {
                                // 与昼夜 / 语言 / 音频同样的结构：外层 Box 各固定 44dp 占位，
                                // Popup 窗口尺寸因此恒定，不会跟着入场动画一帧帧地改大小（那会闪）。
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SimulatorViewModel.REROUTE_PRESETS.forEachIndexed { i, p ->
                                        Box(Modifier.size(44.dp)) {
                                            EdgeSlideIn(
                                                visible = rerouteMenuOpen,
                                                edge = SlideEdge.START,
                                                index = i,
                                                distance = 24.dp,
                                            ) {
                                                RerouteSlot(
                                                    label = m.s(p.shortKey),
                                                    selected = p.matches(vm.settings),
                                                    // 选完自动收起由 applyReroutePreset 负责
                                                    // （和 setDayMode / setLang 一样在 VM 里收）
                                                    onClick = { vm.applyReroutePreset(p) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 昼夜模式（紧接「自主规划」之后，本轮需求 1）：
                // 点一下在按钮**右侧**展开三个按钮 —— 白天（太阳）/ 夜间（月亮）/ 自动循环（AUTO）。
                // 按钮本身显示"当前模式"的图标与配色，所以不展开也能一眼看出现在是什么天。
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) {
                    Box {
                        DaySlot(mode = dayMode, selected = dayMenuOpen) { vm.toggleDayMenu() }
                        if (dayMenuOpen) {
                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(x = rowPopupOffsetX, y = 0),
                            ) {
                                // 三个槽位**固定占位**（外层 Box 各 44dp）：Popup 的窗口尺寸
                                // 因此恒定不变，不会跟着入场动画一帧一帧地改大小（那会闪）。
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (m in DAY_MODES) {
                                        Box(Modifier.size(44.dp)) {
                                            EdgeSlideIn(
                                                visible = dayMenuOpen,
                                                edge = SlideEdge.START,
                                                index = m.ordinal,
                                                distance = 24.dp,
                                            ) {
                                                DaySlot(
                                                    mode = m,
                                                    selected = dayMode == m,
                                                    onClick = { vm.setDayMode(m) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // **去UI**：紧接「夜间模式」之后。
                // 点一下 → 所有按钮 / 文字 / 标记一起淡出，只剩画面；
                // 此时仍可单指拖动、双指缩放，单击屏幕任意位置重新唤回界面。
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) {
                    SmallIconButton(icon = HideUiIcon) { vm.toggleHideUi() }
                }

                // 把「倍速·统计·自主规划·夜间·去UI」与下面那组「语言·音频·保存·加载」
                // 用横线分隔
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) { LeftGroupDivider() }

                // ---- 语言 · 音频 · 保存 · 加载 ----
                // 第 58 轮需求 1：**语言与音频搬到保存的上方**。
                // 理由是使用频率：语言基本"设一次就不动"，音频（尤其是切歌 / 静音）
                // 却是边看边调的，藏在保存下面等于每次都要多点两下才摸到。
                // 现在从上到下依次是：语言 → 音频 → 保存 → 加载，越常用越靠上。
                LangSettingSlot(
                    vm = vm,
                    lang = lang,
                    open = langMenuOpen,
                    visible = showSettings,
                    index = slideIdx++,
                )
                AudioSettingSlot(
                    vm = vm,
                    musicOn = musicOn,
                    sfxOn = sfxOn,
                    hapticOn = hapticOn,
                    open = audioMenuOpen,
                    visible = showSettings,
                    index = slideIdx++,
                )
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) {
                    SmallIconButton(icon = Icons.Filled.Save, tint = Color(0xFF185FA5)) {
                        vm.openSaveDialog()
                    }
                }
                EdgeSlideIn(showSettings, SlideEdge.START, index = slideIdx++) {
                    SmallIconButton(icon = Icons.Filled.FolderOpen, tint = Color(0xFF854F0B)) {
                        vm.openLoadDialog()
                    }
                }
            }
        }

        // ==================== 选择组：整枚胶囊（底框 + 按钮）一起弹 ====================
        // 需求 1 的全部动画都在 [SelectionCapsule] 里，这里只把"选中的是谁"传进去。
        SelectionCapsule(view = selView, vm = vm, distance = 56.dp)
    }
}

// ==================== 选中元素后浮出的那枚胶囊 ====================

/**
 * 「选中了谁、这枚胶囊里该有哪几个按钮」的一份**快照**。
 *
 * 为什么要快照而不是每次都现算：换元素时旧的那枚要先整队滑出屏幕，滑出的这 200 多毫秒里
 * 它画的必须还是**旧内容**。如果它直接去读"当前选中的是谁"，那么旧胶囊一边往外飞、
 * 一边把按钮换成了新的 —— 那正是用户说的"闪烁 / 两套按钮重叠"。
 *
 * [kind] + [targetId] 是**身份**：它们变了才算"换元素"（要播退出→进入）。
 * [curved] / [lanes] / [hasSignal] 只是同一目标上的**数据**（改车道数、加红绿灯），
 * 变了就原地换按钮的图标与置灰状态，不该重播整段动画。
 */
private data class SelView(
    val kind: SimulatorViewModel.Selection,
    val targetId: Long,
    val curved: Boolean,
    val lanes: Int,
    val hasSignalBtn: Boolean,
    val hasSignal: Boolean,
)

/** 把 VM 里的选择状态整理成一份 [SelView]；没有选中任何元素时返回 null */
private fun selViewOf(
    vm: SimulatorViewModel,
    sel: SimulatorViewModel.Selection,
    nodeId: Long?,
    roadId: Long?,
): SelView? {
    if (sel == SimulatorViewModel.Selection.NONE) return null
    if (sel == SimulatorViewModel.Selection.ROAD) {
        return SelView(
            kind = sel,
            targetId = roadId ?: -1L,
            curved = vm.selectedRoadIsCurve(),
            lanes = vm.selectedRoadLanes(),
            hasSignalBtn = false,
            hasSignal = false,
        )
    }
    // 开放路口若是十字 / 丁字（度 ≥ 3），还能增删该路口的红绿灯。
    // 用 takeIf 一次问出「可放灯的那个路口」，下面两个字段都从它推：
    // 若写成 `canSignal && nodeId != null && …`，K2 的隐含条件已经让 `nodeId != null`
    // 恒为真（`canSignal` 是稳定 val，其真值蕴含 nodeId 非空），编译器会报"条件永远为 true"。
    val signalNode = nodeId?.takeIf {
        sel == SimulatorViewModel.Selection.OPEN && vm.network.degree(it) >= 3
    }
    return SelView(
        kind = sel,
        targetId = nodeId ?: -1L,
        curved = false,
        lanes = 0,
        hasSignalBtn = signalNode != null,
        hasSignal = signalNode != null && vm.hasSignal(signalNode),
    )
}

/** 与 [EdgeSlideIn] 完全一致的回弹手感，两处进退观感必须相同 */
private const val CAPSULE_DAMPING = 0.58f

/**
 * 选中元素后浮出的**整枚胶囊**（白色底框 + 全部按钮）。需求 1 的动画全在这里。
 *
 * ## 三条规则，三类元素完全一致
 *  1. **出现**：整枚（底框 + 按钮一起）从屏幕左侧弹入；
 *  2. **换元素**（同类型换目标、或换另一类元素）：先把旧的一整枚弹回屏幕左侧，
 *     **再**把新的一整枚弹进来；
 *  3. **取消选择**（点空白处）：整枚弹回屏幕左侧。
 *
 * ## 为什么不用 [EdgeSlideIn] 的 replayKey
 * 那套做的是"原地弹回再弹入"，内容在同一帧就换掉了 —— 旧按钮还在飞、新按钮已经站在
 * 屏幕上，视觉上就是一次闪。这里把"换内容"这个动作**夹在两次动画之间**：
 * 退出播完（`slide = 0` 且 `fade = 0`，完全在屏幕外且透明）才替换 [shown]，
 * 所以屏幕上永远只有一套按钮，也不可能出现"胶囊先撑高再缩回"。
 *
 * ## 快速连点也不会乱
 * 每条动画都用 [Animatable]，而 [LaunchedEffect] 在新目标到来时会取消上一条协程 ——
 * 于是新的一段从**当前位置**接着走，不会跳帧。
 */
@Composable
private fun SelectionCapsule(
    view: SelView?,
    vm: SimulatorViewModel,
    distance: Dp,
) {
    val pal = LocalUiPalette.current
    val travelPx = with(LocalDensity.current) { distance.toPx() }

    // 0 = 完全贴在屏幕左侧外面，1 = 就位。进退共用同一组参数，来去手感一致。
    val slide = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }

    // "此刻实际画在屏幕上的是哪一份内容"。只在不可见的瞬间被替换（见类注释）。
    var shown by remember { mutableStateOf<SelView?>(null) }

    // 身份：变了才是"换元素"。同目标改车道数/加红绿灯不在这里面。
    val identity: Any? = view?.let { it.kind to it.targetId }

    // 同一目标内的数据刷新（改车道数、增删红绿灯）：
    // 直接原地换内容，不重播动画 —— 否则每点一次「变双车道」，整枚胶囊都会闪一下飞回去。
    // 写在 SideEffect 里而不是组合期，避免"组合过程中写状态"引起额外重组。
    SideEffect {
        val s = shown
        if (view != null && s != null &&
            s.kind == view.kind && s.targetId == view.targetId && s != view
        ) {
            shown = view
        }
    }

    LaunchedEffect(identity) {
        val target = view

        // ---------- 规则 3：取消选择 → 整枚弹出 ----------
        if (target == null) {
            if (shown == null) return@LaunchedEffect   // 本来就不在场，没有可播的退场
            vm.sound.play(Sfx.CAPSULE_OUT)
            coroutineScope {
                launch { fade.animateTo(0f, tween(EDGE_FADE_MS)) }
                slide.animateTo(0f, spring(dampingRatio = CAPSULE_DAMPING, stiffness = Spring.StiffnessMedium))
            }
            shown = null
            return@LaunchedEffect
        }

        // ---------- 规则 2：换元素 → 先把旧的整枚弹回去 ----------
        // 首次出现（shown == null）时没有"旧的"，直接进。
        if (shown != null) {
            vm.sound.play(Sfx.CAPSULE_OUT)
            coroutineScope {
                launch { fade.animateTo(0f, tween(EDGE_FADE_MS)) }
                slide.animateTo(0f, spring(dampingRatio = CAPSULE_DAMPING, stiffness = Spring.StiffnessMedium))
            }
        }

        // 此刻 slide = 0、fade = 0：完全在屏幕外且全透明，换内容谁也看不见。
        // 这也是"位置一致"的来源 —— 胶囊是**贴底**的，换内容只改它的上沿。
        shown = target

        // 预热两帧（与 [EdgeSlideIn] 同一套理由）：把首次组合 / 测量的开销
        // 埋在这两帧看不见的时间里，动画第一帧就只剩图层变换。
        withFrameNanos { it }
        withFrameNanos { it }

        // ---------- 规则 1：整枚弹入 ----------
        // 放在预热之后：此刻内容已经组合/测量完毕，这一声正好压在真正开始滑动的第一帧上
        vm.sound.play(Sfx.CAPSULE_IN)
        coroutineScope {
            launch { fade.animateTo(1f, tween(EDGE_FADE_MS)) }
            slide.animateTo(1f, spring(dampingRatio = CAPSULE_DAMPING, stiffness = Spring.StiffnessMedium))
        }
    }

    val content = shown
    if (content != null) {
        Box(
            // 位移 / 透明度走 graphicsLayer：每帧只改图层，不触发重新布局。
            modifier = Modifier.graphicsLayer {
                translationX = -(1f - slide.value) * travelPx
                alpha = fade.value
            },
        ) {
            Column(
                modifier = Modifier
                    .background(pal.panelSoft.copy(alpha = 0.92f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionSlots(vm, content)
            }
        }
    }
}

/**
 * 胶囊里的三个**固定槽位**，槽 0 贴底、槽 1、槽 2 依次往上。
 *
 * ⚠️ 为什么坚持按槽位排：
 *  - 以前每种选择各是一组、按声明顺序自上而下排，而整列是**底对齐**的 ——
 *    于是"开放"落在最下，"封闭"因为下面还跟着一个「设置」被顶上去一格，
 *    两者位置对不上（点完一个封闭路口再点另一个，按钮会往上跳一格）；
 *  - 更糟的是换选另一类元素时两套按钮会**同时在场**（旧的还在退场动画里），
 *    胶囊背景瞬间变成三行高再缩回一行 —— 看起来就是"先弹出一个大框，再突然变小"。
 * 现在一枚胶囊里最多三个槽位、每个槽位最多一个按钮，换类型只是换内容。
 */
@Composable
private fun SelectionSlots(vm: SimulatorViewModel, v: SelView) {
    val roadSel = v.kind == SimulatorViewModel.Selection.ROAD
    val deadSel = v.kind == SimulatorViewModel.Selection.DEAD_END
    val openSel = v.kind == SimulatorViewModel.Selection.OPEN
    val sigSel = v.kind == SimulatorViewModel.Selection.SIGNAL
    val noSigSel = v.kind == SimulatorViewModel.Selection.NO_SIGNAL

    // ---- 槽 2（最上）：删除道路 / 红绿灯增删 ----
    if (roadSel || v.hasSignalBtn) {
        if (roadSel) {
            SmallIconButton(icon = Icons.Filled.Delete, tint = Color(0xFFA32D2D)) {
                vm.deleteSelectedRoad()
            }
        } else if (v.hasSignal) {
            SmallIconButton(icon = Icons.Filled.Close, tint = Color(0xFFA32D2D)) {
                vm.removeSignalAtSelected()
            }
        } else {
            SmallIconButton(icon = Icons.Filled.Add, tint = Color(0xFF0F6E56)) {
                vm.addSignalAtSelected()
            }
        }
    }

    // ---- 槽 1：变双车道 / 出入口设置 / 红绿灯设置 ----
    if (roadSel || openSel || sigSel) {
        if (roadSel) {
            SmallIconButton(
                icon = roadIconFor(v.curved, 2),
                enabled = v.lanes != 2,
                tint = Color(0xFF534AB7),
            ) { vm.setSelectedRoadLanes(2) }
        } else if (openSel) {
            SmallIconButton(icon = Icons.Filled.Tune, tint = Color(0xFF534AB7)) {
                val id = v.targetId
                if (id > 0L) vm.openJunctionDialog(id)
            }
        } else {
            SmallIconButton(icon = Icons.Filled.Tune, tint = Color(0xFF534AB7)) {
                val id = v.targetId
                if (id > 0L) vm.openSignalRuleDialog(id)
            }
        }
    }

    // ---- 槽 0（贴底 / 主操作）：变三车道 · 开放 · 封闭 · 删灯 · 加灯 ----
    if (roadSel) {
        SmallIconButton(
            icon = roadIconFor(v.curved, 3),
            enabled = v.lanes != 3,
            tint = Color(0xFF534AB7),
        ) { vm.setSelectedRoadLanes(3) }
    } else if (deadSel) {
        SmallIconButton(icon = Icons.Filled.LockOpen, tint = Color(0xFF0F6E56)) {
            vm.openSelectedNode()
        }
    } else if (openSel) {
        SmallIconButton(icon = Icons.Filled.Lock, tint = Color(0xFFA32D2D)) {
            vm.closeSelectedNode()
        }
    } else if (sigSel) {
        SmallIconButton(icon = Icons.Filled.Close, tint = Color(0xFFA32D2D)) {
            vm.removeSignalAtSelected()
        }
    } else if (noSigSel) {
        SmallIconButton(icon = Icons.Filled.Add, tint = Color(0xFF0F6E56)) {
            vm.addSignalAtSelected()
        }
    }
}

// ==================== 复用小组件 ====================

/**
 * 左侧列表里「两个分组之间」的分隔件：
 * 一条细横线 + 上下留白，把两段按钮的间距**略微拉大**，形成"前组 / 后组"的视觉区隔。
 */
@Composable
private fun LeftGroupDivider() {
    val pal = LocalUiPalette.current
    Box(
        modifier = Modifier
            .padding(vertical = 3.dp)
            .width(26.dp)
            .height(1.5.dp)
            .background(pal.divider, RoundedCornerShape(1.dp)),
    )
}

/** 单个道路工具按钮（直线 / 曲线 × 单/双/三车道），点一下即选中该路型（选中后菜单会立即收起） */
@Composable
private fun RoadToolButton(
    vm: SimulatorViewModel,
    tool: SimulatorViewModel.DrawTool,
    selected: Boolean,
    visible: Boolean,
    index: Int,
) {
    EdgeSlideIn(visible, SlideEdge.START, index = index) {
        SmallIconButton(
            icon = roadIconFor(tool.curve, tool.lanes),
            tint = if (selected) Color.White else null,
            bg = if (selected) PURPLE else null,
        ) {
            vm.setDrawTool(if (selected) SimulatorViewModel.DrawTool.NONE else tool)
        }
    }
}

/**
 * 音频菜单里的一个**开关圆钮**（音乐 / 音效）。
 *
 * 与 [DaySlot] / [LangSlot] 同一套视觉：44dp 圆钮、**开着 = 紫底白图标**、
 * 关着 = 面板色 + 中性图标色。
 *
 * 为什么用"填色"而不是"打个叉"表示关闭：项目里所有"当前项"的表达都是紫底
 * （统计面板、自主规划、选中的道路工具…），这里保持一致 —— 一眼就知道哪个是开的，
 * 不需要额外学一套符号。
 *
 * @param on 开 = 紫底；关 = 面板色
 */
@Composable
private fun AudioToggleSlot(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressEffect(press)
            .background(if (on) PURPLE else pal.panel, CircleShape)
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (on) Color.White else pal.iconNeutral,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 「上一首 / 下一首」这种**一次性动作**的圆钮。
 *
 * 与 [AudioToggleSlot] 的区别：动作钮**没有"开/关"状态**，所以永远是中性面板色，
 * 只有可用 / 不可用两态。这个区分很重要 —— 如果它也用紫底表示"开"，
 * 用户会以为"上一首"是个可以一直按着的开关。
 *
 * @param enabled 音乐关掉时置灰。不是为了好看：那时按下去什么都不会发生，
 *                置灰是唯一能让用户明白"为什么没反应"的办法。
 */
@Composable
private fun AudioStepSlot(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressEffect(press)
            .background(pal.panel, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = pal.iconNeutral.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 一个昼夜模式按钮（本轮需求 1）：**白天 = 太阳 · 夜间 = 月亮 · 自动循环 = "AUTO" 字样**。
 *
 * 三种模式共用这一个 composable，尺寸固定 44dp —— 展开后三个按钮才能排成整齐的一排，
 * 也才不会因为"图标换文字"导致宽度跳动。
 *
 * @param selected 是否是当前模式（紫底白字，与项目里其它"当前项"的表示完全一致）
 */
@Composable
private fun DaySlot(mode: DayMode, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressEffect(press)
            .background(if (selected) PURPLE else pal.panel, CircleShape)
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (selected) Color.White else pal.iconNeutral
        if (mode == DayMode.AUTO) {
            // 需求原文就是"按钮样式为 AUTO"，所以这里不用图标而是直接写字。
            // 24dp 画布塞 4 个字母会糊成一团，文字反而更清楚。
            Text(
                text = "AUTO",
                color = tint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
        } else {
            Icon(
                imageVector = if (mode == DayMode.DAY) SunIcon else NightModeIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 一个语言按钮（本轮需求 2）：**中文 = 汉字「中」 · English = 字母「En」**。
 *
 * 交互与 [DaySlot] 完全一致：44dp 圆钮、选中填紫色、点一下即生效。
 * 用文字而不是图标，是因为"中 / En"这两个字形本身就是最清楚的标识 ——
 * 任何"翻译"类图标都表达不出"当前是哪种语言"。
 */
@Composable
private fun LangSlot(lang: Lang, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressEffect(press)
            .background(if (selected) PURPLE else pal.panel, CircleShape)
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (lang == Lang.ZH) "中" else "En",
            color = if (selected) Color.White else pal.iconNeutral,
            // 「中」笔画多、可以大一点；「En」两个字母，稍小一点才不会顶到圆边
            fontSize = if (lang == Lang.ZH) 17.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * 倍速按钮的配色：按当前倍率返回 (底色, 图标色)。
 *  0.5x → 黄；1x → 面板色（相当于无背景）；1.5 / 2 / 3x → 绿；5x → 红。
 *
 * @param panel 当前昼夜的面板色（白天白、夜间深蓝灰），1x 时用它
 */
private fun speedButtonColors(m: Double, panel: Color): Pair<Color, Color> = when {
    m < 1.0 -> Color(0xFFE0A32E) to Color.White     // 0.5x 黄
    m == 1.0 -> panel to Color(0xFF185FA5)          // 1x 无背景
    m < 5.0 -> Color(0xFF1D9E75) to Color.White     // 1.5 / 2 / 3x 绿
    else -> Color(0xFFE24B4A) to Color.White        // 5x 红
}

// ==================== 倍速横向滑块 ====================

/** 倍速档位的显示文本：整数去掉小数点（1x 而不是 1.0x） */
private fun speedLabelOf(m: Double): String =
    if (m == m.toInt().toDouble()) "${m.toInt()}x" else "${m}x"

/**
 * 倍速滑块（横向）：**一条横线 + 一个普通圆形滑块**（不是放大镜、没有选择框、没有底部文字）。
 *  - 圆形滑块可左右拖动，经过即吸附到最近档位；
 *  - 圆内实时显示当前倍率；
 *  - 拖动过程**实时生效**（静默改设置，不弹气泡，避免刷屏）；
 *  - **松手即完成设定**：抬手时回调 [onRelease]，由外层把滑块渐隐收起。
 *
 * @param trackColor 未选中段的横线颜色（随昼夜变）
 * @param knobColor  滑块底色（随昼夜变）
 */
@Composable
private fun SpeedRail(
    current: Double,
    trackColor: Color,
    knobColor: Color,
    modifier: Modifier = Modifier,
    onPick: (Double) -> Unit,
    onRelease: () -> Unit,
) {
    val choices = SimSettings.SPEED_MULTIPLIERS
    var widthPx by remember { mutableFloatStateOf(1f) }
    val textMeasurer = rememberTextMeasurer()
    val accent = Color(0xFF185FA5)
    val releaseState = rememberUpdatedState(onRelease)
    // pointerInput 只在 choices 变化时重启，闭包里读不到最新的 current —— 必须用 State 包一层
    val currentState = rememberUpdatedState(current)
    val density = LocalDensity.current
    val padX = with(density) { 20.dp.toPx() }
    // 倍速是**离散步进**（0.5/1/2/3/4/5x 六档）→ 一档一响，不是连续震
    val drag = rememberDragHaptics()

    // 绘制与手势命中必须共用同一坐标系：轨道两端各有 20dp 留白。
    fun xAt(index: Int, width: Float): Float {
        val span = (width - 2f * padX).coerceAtLeast(1f)
        return padX + span * (index.toFloat() / (choices.size - 1))
    }

    Canvas(
        modifier = modifier
            .width(196.dp)
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(choices) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val n = choices.size
                    fun indexAt(x: Float): Int {
                        val span = (widthPx - 2f * padX).coerceAtLeast(1f)
                        val t = ((x - padX) / span).coerceIn(0f, 1f)
                        return (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    }
                    var last = indexAt(down.position.x)
                    // 新手势：清掉"上次震到哪一档"的记忆，按下即响第一声
                    drag.reset()
                    onPick(choices[last])
                    // 只有按下点真的落在**别的档**上才震一次：点在当前档上等于没调整，
                    // 那时只有渐隐收起，不该有震动
                    val idxAtDown = choices.indexOfFirst { abs(it - currentState.value) < 1e-6 }
                    if (last != idxAtDown) drag.step(last)
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        val idx = indexAt(change.position.x)
                        if (idx != last) {
                            last = idx
                            onPick(choices[idx])
                            // 跨一档震一下；快速划过整条轨道时由 DragHaptics 封顶在 ~22 次/秒
                            drag.step(idx)
                        }
                        change.consume()
                    }
                    // 松手 → 通知外层渐隐收起
                    if (released) releaseState.value()
                }
            },
    ) {
        val left = padX
        val right = size.width - padX
        val railY = size.height / 2f
        val curIdx = choices.indexOfFirst { abs(it - current) < 1e-6 }.let { if (it < 0) 1 else it }
        fun xOf(i: Int): Float = xAt(i, size.width)

        // 横线：未选段（浅色）+ 已选段（蓝）
        drawLine(
            trackColor, Offset(left, railY), Offset(right, railY),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
        )
        drawLine(
            accent, Offset(left, railY), Offset(xOf(curIdx), railY),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
        )

        // 普通圆形滑块：面板色底 + 蓝描边，圆内显示当前倍率
        val cx = xOf(curIdx)
        val r = 15.dp.toPx()
        drawCircle(knobColor, radius = r, center = Offset(cx, railY))
        drawCircle(accent, radius = r, center = Offset(cx, railY), style = Stroke(width = 2.5.dp.toPx()))
        val valueLayout = textMeasurer.measure(
            speedLabelOf(choices[curIdx]),
            TextStyle(fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold),
        )
        drawText(
            textLayoutResult = valueLayout,
            topLeft = Offset(cx - valueLayout.size.width / 2f, railY - valueLayout.size.height / 2f),
        )
    }
}

// ==================== 自主规划预设槽位 ====================

/**
 * 一个"自主规划预设"圆钮（关 / 灵敏 / 标准 / 保守）。
 *
 * 结构与 [DaySlot]、[LangSlot] 完全一致 —— 44dp 圆钮、选中填紫、按压有缩放 + 轻震。
 * 差别只在"脸上写什么"：这三个预设的差别是**程度**而不是种类，画不出三个一眼能分辨的图标，
 * 所以直接写字（和语言按钮的「中」/「En」是同一路数）。
 *
 * @param label 短标签，1~2 个汉字或 2~3 个英文字母。完整说明在选中后的 toast 里给。
 */
@Composable
private fun RerouteSlot(label: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressEffect(press)
            .background(if (selected) PURPLE else pal.panel, CircleShape)
            .clickable(
                interactionSource = press.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else pal.iconNeutral,
            // 按字数分档，保证既不顶到圆边、也不至于小得看不清：
            // 1 个汉字（关）可以大；2 个汉字（灵敏）收一档；3 个字母（Mid）再小一点
            fontSize = when {
                label.length <= 1 -> 17.sp
                label.length == 2 -> 12.sp
                else -> 10.sp
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ==================== 设置行里的两个"展开式"按钮（第 58 轮提出） ====================

/**
 * 「语言」按钮 + 右侧展开的两个圆钮（中 / En）。
 *
 * 第 58 轮从 [ContextButtons] 里整段搬出来，只做了一件事：**让顺序可以随手调整**。
 * 这两个块原来各 50~150 行塞在函数中间，想换个顺序就得在几百行里做剪切粘贴，
 * 极易漏掉一个闭合括号；抽成组件之后，"谁在谁上面"就是一行调用的先后。
 *
 * @param open    浮层是否展开（`vm.langMenuOpen`）
 * @param visible 主按钮是否在场（设置行展开时才有）
 * @param index   错峰序号，决定它第几个弹出来
 */
@Composable
private fun LangSettingSlot(
    vm: SimulatorViewModel,
    lang: Lang,
    open: Boolean,
    visible: Boolean,
    index: Int,
) {
    val rowOffsetX = with(LocalDensity.current) { ROW_POPUP_OFFSET.toPx().roundToInt() }
    EdgeSlideIn(visible, SlideEdge.START, index = index) {
        Box {
            LangSlot(lang = lang, selected = open) { vm.toggleLangMenu() }
            if (open) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(x = rowOffsetX, y = 0),
                ) {
                    // 每个槽位**固定 44dp 占位**：Popup 窗口尺寸因此恒定，
                    // 不会跟着入场动画一帧帧地改大小（那会闪）。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (l in LANG_ORDER) {
                            Box(Modifier.size(44.dp)) {
                                EdgeSlideIn(
                                    visible = open,
                                    edge = SlideEdge.START,
                                    index = l.ordinal,
                                    distance = 24.dp,
                                ) {
                                    LangSlot(
                                        lang = l,
                                        selected = lang == l,
                                        onClick = { vm.setLang(l) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「音频」按钮 + 右侧展开的一排圆钮：
 * `|◀ 上一首` · `▶| 下一首` · `♪ 音乐` · `🔊 音效` · `轻震`。
 *
 * ## 为什么音乐 / 音效是两个开关而不是一个总开关
 * 音乐是氛围、音效是反馈，两种需求完全独立（想安静看车流的人会关音乐但留着音效；
 * 录屏的人可能反过来）。一个总开关逼用户二选一。
 *
 * ## 顺序上的两个讲究
 *  - 切歌钮放最左（离主按钮最近）：它们是"音乐的延伸"，和音符开关算一组；
 *    音乐关掉时置灰不可点 —— 那时按下去什么都不会发生，置灰是唯一能让用户
 *    明白"为什么没反应"的办法。
 *  - 「轻震」放最右（离主按钮最远）：它和音效同属"反馈"，所以紧挨着音效；
 *    又因为它是最容易让人反感的一项（有人就是讨厌手机震），放最外侧最不容易误触。
 *
 * ## 主按钮的脸
 * 两个开关都关掉时换成带斜杠的调音台 —— 不用展开也能一眼看出"这个 App 现在哑了"。
 */
@Composable
private fun AudioSettingSlot(
    vm: SimulatorViewModel,
    musicOn: Boolean,
    sfxOn: Boolean,
    hapticOn: Boolean,
    open: Boolean,
    visible: Boolean,
    index: Int,
) {
    val rowOffsetX = with(LocalDensity.current) { ROW_POPUP_OFFSET.toPx().roundToInt() }
    EdgeSlideIn(visible, SlideEdge.START, index = index) {
        Box {
            SmallIconButton(
                icon = if (!musicOn && !sfxOn) AudioMutedIcon else AudioMasterIcon,
                tint = if (open) Color.White else null,
                bg = if (open) PURPLE else null,
            ) { vm.toggleAudioMenu() }
            if (open) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(x = rowOffsetX, y = 0),
                ) {
                    // 5 × 44 + 4 × 8 + 4（分组空隙）= 256dp，起点偏移 64dp → 右边缘 320dp，
                    // 360dp 窄屏上仍有 40dp 余量。
                    // 切歌与开关之间额外空 4dp（连同 spacedBy 的 8dp 实际 20dp），
                    // 让"换歌"和"开关"在视觉上分成两组。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(44.dp)) {
                            EdgeSlideIn(
                                visible = open, edge = SlideEdge.START, index = 0, distance = 24.dp,
                            ) {
                                AudioStepSlot(
                                    icon = PrevTrackIcon,
                                    enabled = musicOn,
                                    onClick = { vm.prevBgm() },
                                )
                            }
                        }
                        Box(Modifier.size(44.dp)) {
                            EdgeSlideIn(
                                visible = open, edge = SlideEdge.START, index = 1, distance = 24.dp,
                            ) {
                                AudioStepSlot(
                                    icon = NextTrackIcon,
                                    enabled = musicOn,
                                    onClick = { vm.nextBgm() },
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(44.dp)) {
                            EdgeSlideIn(
                                visible = open, edge = SlideEdge.START, index = 2, distance = 24.dp,
                            ) {
                                AudioToggleSlot(
                                    icon = MusicNoteIcon,
                                    on = musicOn,
                                    onClick = { vm.toggleMusic() },
                                )
                            }
                        }
                        Box(Modifier.size(44.dp)) {
                            EdgeSlideIn(
                                visible = open, edge = SlideEdge.START, index = 3, distance = 24.dp,
                            ) {
                                AudioToggleSlot(
                                    icon = SpeakerIcon,
                                    on = sfxOn,
                                    onClick = { vm.toggleSfx() },
                                )
                            }
                        }
                        Box(Modifier.size(44.dp)) {
                            EdgeSlideIn(
                                visible = open, edge = SlideEdge.START, index = 4, distance = 24.dp,
                            ) {
                                AudioToggleSlot(
                                    icon = HapticIcon,
                                    on = hapticOn,
                                    onClick = { vm.toggleHaptic() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 「一排圆钮」类浮层（语言 / 音频 / 昼夜 / 自主规划）相对按钮左侧的横向偏移：44dp 按钮 + 20dp 间隙 */
private val ROW_POPUP_OFFSET = 64.dp
