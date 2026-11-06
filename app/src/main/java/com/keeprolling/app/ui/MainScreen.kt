package com.keeprolling.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.keeprolling.app.ui.components.BOTTOM_BAR_HEIGHT
import com.keeprolling.app.ui.components.BottomBar
import com.keeprolling.app.ui.components.ClearConfirmDialog
import com.keeprolling.app.ui.components.ContextButtons
import com.keeprolling.app.ui.components.CrashBanner
import com.keeprolling.app.ui.components.EdgeSlideIn
import com.keeprolling.app.ui.components.JunctionConfigDialog
import com.keeprolling.app.ui.components.LoadNetworkDialog
import com.keeprolling.app.ui.components.LocalHapticEnabled
import com.keeprolling.app.ui.components.SaveNetworkDialog
import com.keeprolling.app.ui.components.SettingsSheet
import com.keeprolling.app.ui.components.SignalRuleDialog
import com.keeprolling.app.ui.components.SlideEdge
import com.keeprolling.app.ui.components.VehicleInfoCard
import com.keeprolling.app.ui.components.cardShadow
import com.keeprolling.app.ui.components.rememberLastNonNull
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.Messages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.SimulatorViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val WARM_WHITE = Color(0xFFFAF8F3)   // 主界面：温润白
private val LIGHT_GRAY = Color(0xFFF1EFE8)   // 编辑模式：浅灰
private val NIGHT_VIEW_BG = Color(0xFF0F1319)   // 主界面的夜色
private val NIGHT_EDIT_BG = Color(0xFF161B22)   // 编辑模式的夜色（比主界面略亮一档：编辑要看清线）

/**
 * 左侧按钮组与底部按钮之间留"恰好一个按钮高度"的间距。
 * 左侧按钮尺寸 44dp；底栏外边距 26dp，按钮最高 64dp（编辑模式的红色勾）。
 */
private val LEFT_BUTTON_H = 44.dp
private val BOTTOM_BAR_MARGIN = 26.dp

/** 界面整体淡出 / 渐显的时长（去UI）。比按钮弹入慢一点，形成"先散场、后亮起"的层次 */
private const val UI_FADE_MS = 380

/**
 * 主界面（竖屏全屏沉浸）：
 *  顶部：无标题栏；左上角操作提示 / 车辆信息卡、右上角统计面板（可开关）
 *  左侧：上下文图标按钮组（**贴屏幕左边缘最底部、竖向排列**，与底部按钮间距一个按钮高）
 *  中部：全屏画布（背景随模式切换：温润白 / 浅灰）
 *  底部：无背景条的图标按钮区（开始·暂停 / 编辑 / 设置；编辑模式为 道路 / 清空 / 撤销 / 重做 / 勾）
 *
 * 本轮改动（进入动画卡顿修复）：
 *  - **痛点**：按钮"弹入"开头会顿一下，"弹回"却很顺 —— 因为 `AnimatedVisibility` 在不可见时
 *    不组合内容、退场后还会销毁，进入的第一帧要现组合 + 现测量 + 现建图层，而退出只剩图层变换。
 *    现在 [EdgeSlideIn] 自己驱动动画，并**先预热一帧再起跑**，把重活挪出动画帧；
 *  - **放大器**：主界面根部原来订阅了 `vm.frame`（16ms 一次），整个界面层被拖着 60Hz 重组。
 *    现在帧订阅下沉到 [FocusCard] / [CrashBannerHost] / [StatsOverlay] 各自订阅，
 *    动画帧的预算不再被无关重组吃掉。
 */
@Composable
fun MainScreen(vm: SimulatorViewModel = viewModel()) {
    // ⚠️ 这里**故意不订阅 `vm.uiMode`**（第 57 轮）。
    // 它只在两处用到：根容器的底色、以及各个按钮组自己的显示与否（那些组件内部各自订阅）。
    // 从前它在根部读，于是"进入 / 退出编辑模式"会把整棵界面树重组一遍 —— 包括画布。
    // 画布不可跳过（参数里有普通 class），一重组十层就全部重录，900 栋楼 + 1600 棵树重来一遍，
    // 于是按钮弹出的那几百毫秒里明显顿一下。现在底色下沉到 [StageBackdrop]，
    // 画布也用 [CanvasHost] 做了一层稳定边界，换模式只重录"底色 + 编辑标记"两层。
    val simState by vm.simState.collectAsState()
    // ⚠️ 这里**故意不订阅 `vm.frame`**。
    // 它每 16ms 换一次；一旦在根部读它，整层界面（含每一个 EdgeSlideIn 与所有按钮）
    // 都会跟着 60Hz 重组，把动画帧的预算吃干净 —— 而"进入"方向本来就比"退出"多一笔
    // 首帧组合开销，于是只有进入会肉眼可见地卡。帧数据由 FocusCard / StatsOverlay 各自订阅。
    val junctionDialogNodeId by vm.junctionDialogNodeId.collectAsState()
    val signalRuleDialogNodeId by vm.signalRuleDialogNodeId.collectAsState()
    val showSettingsSheet by vm.showSettingsSheet.collectAsState()
    val showSaveDialog by vm.showSaveDialog.collectAsState()
    val showLoadDialog by vm.showLoadDialog.collectAsState()
    val showClearConfirm by vm.showClearConfirm.collectAsState()
    val speedDialOpen by vm.speedDialOpen.collectAsState()
    val rerouteMenuOpen by vm.rerouteMenuOpen.collectAsState()
    val dayMenuOpen by vm.dayMenuOpen.collectAsState()
    // 语言二选一菜单（与昼夜菜单同一套开关）：展开时要铺一层"点任意处收起"的透明层
    val langMenuOpen by vm.langMenuOpen.collectAsState()
    // 音频菜单（本轮新增）：点「音频」在它右侧展开「音乐 · 音效」两个开关。
    // 和昼夜 / 语言一样需要那层"点任意处收起"的透明层。
    val audioMenuOpen by vm.audioMenuOpen.collectAsState()
    val lang by vm.lang.collectAsState()
    // 触觉反馈开关（音频浮层里的「轻震」）：注入 CompositionLocal，供**所有按钮**读取。
    // 用独立的 StateFlow 而不是 settings 字段，是为了让"改这一项"只通知按钮，不牵动整屏。
    val hapticOn by vm.hapticEnabled.collectAsState()

    // 根容器的底色也跟着昼夜走（画布本身会盖满屏幕，但刘海/系统栏边缘
    // 那几像素露出来的就是它 —— 夜里留一条白边会非常出戏）。
    //
    // 需求 1 之后这里有三个来源，别混：
    //  · dayMode    —— 白天 / 夜间 / 自动；
    //  · targetNight—— 界面层用的"夜晚程度"（手动是 0/1，自动由仿真时间推算、约 8Hz 推送）；
    //  · nightT     —— 真正拿来上色的值：手动模式下补间 340ms（整屏一起暗下来），
    //                  自动模式下本来就是连续的，再套一层补间只会让天色滞后。
    val dayMode by vm.dayMode.collectAsState()
    val targetNight by vm.dayNight.collectAsState()
    val blendedNight by animateFloatAsState(
        targetValue = targetNight,
        animationSpec = tween(durationMillis = 340),
        label = "rootNight",
    )
    val nightT = if (dayMode == DayMode.AUTO) targetNight else blendedNight
    // 界面配色 / Material3 配色。用 remember 缓存，避免每帧重建配色对象。
    // 自动模式下 nightT 约 8Hz 变一次，直接当 remember 的 key 等于每秒重建 8 次配色对象、
    // 并把整层界面重组 8 次。这里按 1/24 量化：天色变化本来就看不出 4% 的差别，
    // 而 key 一量化，晚上那半个循环里实际上只会重建十几次。
    val nightKey = (nightT * 24f).roundToInt()
    val uiPalette = remember(nightKey) { UiPalette(nightKey / 24f) }
    // Material3 只在"跨过一半"时换整套配色（弹窗、滑块、开关都跟着它）：
    // 连续插值一整套 colorScheme 不现实，而夜里停在 0.45 附近正好是黄昏
    val schemeDark = nightT > 0.45f
    val scheme = remember(schemeDark) { appColorScheme(schemeDark) }

    // 去UI —— 一个 0~1 的可见度驱动全部界面元素渐隐 / 渐显
    val hideUi by vm.hideUi.collectAsState()
    // ⚠️ 不写 `by`（第 57 轮）：这个值在 380ms 的补间里每帧都变，在组合期读一次 = 整层界面
    // 每帧重组一次。它只以 `State` 的形式往下走：
    //   · 界面层的透明度交给 `graphicsLayer`（绘制期读，零重组）；
    //   · 「还要不要留在组合里」用 `derivedStateOf` 摘成一个布尔 —— 只在跨过阈值时变一次；
    //   · 画布里的界面标记由 `MarkerLayer` 在绘制期自己读。
    val uiAlpha = animateFloatAsState(
        targetValue = if (hideUi) 0f else 1f,
        animationSpec = tween(durationMillis = UI_FADE_MS),
        label = "uiAlpha",
    )
    // 真正"看不见"之后就把整层界面移出组合：否则会留下一堆**看不见但点得着**的按钮。
    // `remember(uiAlpha)` 把 State 实例当 key：万一哪天动画被重建，这个派生值也跟着重建，
    // 不会抱着一个已经没人更新的旧 State 读到"永远可见"。
    val uiVisible by remember(uiAlpha) { derivedStateOf { uiAlpha.value > 0.01f } }

    val camera = remember { CameraState() }
    val toastMsg by vm.toast.collectAsState()

    // 轻提示：卡片文字，显示在屏幕正上方，短暂停留后自动消失
    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            delay(2200)
            vm.clearToast()
        }
    }

    // 当前聚焦车辆的快照、崩溃横幅的文案：两者都依赖每帧刷新的 `vm.frame`，
    // 但**只在各自的宿主里**（[FocusCard] / [CrashBannerHost]）订阅，避免把整层界面拖进 60Hz 重组。
    val toastShown = rememberLastNonNull(toastMsg)

    // 画布的**稳定参数边界**：`vm` / `camera` 都是普通 class（编译器视为不稳定），
    // 直接用它们当参数会让 `SimCanvas` 永远不可跳过 —— 界面侧任何一次重组（轻提示、展开菜单、
    // 拖动滑块）都会顺带把画布的十层全部重录。包成 @Stable 的壳之后，只要壳和 `uiAlpha` 没变，
    // 画布就整段跳过；画布自己的数据流（collectAsState）照常工作。详见 [CanvasHost] 的注释。
    val canvasHost = remember(vm, camera) { CanvasHost(vm, camera) }

    MaterialTheme(
        colorScheme = scheme,
        // 只换配色：字体与形状沿用外层主题，避免把排版一起重置掉
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        // 按当前语言把文案表注入整棵子树 + 注入昼夜界面配色 + 注入"按下要不要震"
        CompositionLocalProvider(
            LocalMessages provides Messages.of(lang),
            LocalUiPalette provides uiPalette,
            LocalHapticEnabled provides hapticOn,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 根底色（温润白 / 浅灰，随昼夜变深）：单独一层、自己订阅 `uiMode`。
                // 它只在画布之外的那几像素（刘海、系统栏边缘）露出来，但必须存在 ——
                // 夜里留一条白边会非常出戏。
                StageBackdrop(vm, nightT)

                // 画布始终可见；它内部那些"属于界面"的标记（路口圆圈、编号、相位卡）
                // 自己按 uiAlpha 淡出，所以这里不用套 alpha。
                SimCanvas(canvasHost, Modifier.fillMaxSize(), uiAlpha)

                if (uiVisible) {
                    // 所有界面元素都在这一层里：统一透明度 + 统一移除时机。
                    // 透明度写进 graphicsLayer（等价于原来的 `Modifier.alpha`，但读数发生在
                    // 绘制期）—— 淡出那 380ms 里组合阶段一次都不用重跑。
                    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha.value }) {

                        // ==================== 顶部信息区 ====================
                        // 位置关系（自外向内，**由布局保证互不相交**）：
                        //
                        //   ┌──────────────────────────────────────────┐
                        //   │ 左槽：操作提示 / 车辆信息卡     右槽：统计 │  ← 同一行，左右分槽
                        //   ├──────────────────────────────────────────┤
                        //   │            轻提示气泡（居中，接在上一行下面）│  ← 永远在信息区**下方**
                        //   └──────────────────────────────────────────┘
                        //
                        // 三个要点：
                        //  ① 左右两槽是同一个 Row 的两个孩子：左槽 `weight(1f)` 吃掉剩余空间，
                        //     右槽按内容自身宽度靠右 → 统计面板再宽也只会挤压左槽，不会盖上去；
                        //  ② 提示气泡是这个 Column 的**下一个孩子**，天然排在前一行下方 →
                        //     无论信息区多高（有没有信息卡、开没开统计），它都不会压上去；
                        //  ③ 提示与信息卡在左槽里上下堆叠（`spacedBy`），两者同时出现也只是往下让位。
                        //
                        // 以前是三个各自 `align(不同角落)` 的独立元素，位置靠手写的 top = 72/172dp 去躲，
                        // 一旦统计面板多出一行（开启自主规划会多「累计换路」）就会撞上气泡。
                        val topPad = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.displayCutout)
                            .padding(start = 16.dp, end = 16.dp, top = 20.dp)

                        Column(
                            modifier = topPad,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                // 行高变化（信息卡进出、统计面板开关）做一次补间，
                                // 下面的气泡跟着平滑位移，不会"跳一下"
                                modifier = Modifier.fillMaxWidth().animateContentSize(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // 左上角操作提示（从左侧弹入 / 收回左侧）
                                    HintText(vm)
                                    // 聚焦车辆的信息框（从上方落下 / 缩回上方）。
                                    // 车到站消失时 `focus` 立刻变 null，宿主内部用 rememberLastNonNull
                                    // 留住最后一帧读数，让卡片"带着内容"缩回去。
                                    FocusCard(vm)
                                }
                                // 右上角统计面板（运行时间 + 车辆数）
                                StatsOverlay(vm)
                            }

                            // 轻提示气泡：所有轻提示统一显示在这里（信息区下方居中）
                            EdgeSlideIn(
                                visible = toastMsg != null,
                                edge = SlideEdge.TOP,
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                // 需求 2 的同类问题：气泡也是圆角 + 住在 alpha 图层里，
                                // 用 Modifier.shadow 会在四个角露出直角尖角。改用自绘 cardShadow。
                                val toastShape = remember { RoundedCornerShape(20.dp) }
                                Text(
                                    text = toastShown ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = uiPalette.ink,
                                    modifier = Modifier
                                        .cardShadow(toastShape)
                                        .background(uiPalette.panel, toastShape)
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }

                        // 崩溃横幅（非遮挡：不挡画面，能看清是哪个路口堵住了）—— 从下方升起、往下方收回
                        CrashBannerHost(
                            vm = vm,
                            visible = simState == SimulatorViewModel.SimState.CRASHED,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 104.dp),
                        )

                        // 底部图标按钮区（无背景条）—— 按钮依次从底部升起 / 依次沉回底部
                        BottomBar(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp))

                        // 倍速滑块 / 自主规划菜单展开时：铺一层透明层，**点屏幕任意位置即确认/收起**。
                        // 放在其它 UI 之上、左侧按钮组之下——点按钮仍走按钮自己的逻辑，点别处则收起浮层。
                        if (speedDialOpen || rerouteMenuOpen || dayMenuOpen || langMenuOpen ||
                            audioMenuOpen
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        vm.closeSpeedDial()     // 收起倍速滑块（并提示一次最终倍率）
                                        vm.closeRerouteMenu()
                                        vm.closeDayMenu()       // 收起昼夜三选一
                                        vm.closeLangMenu()      // 收起语言二选一
                                        vm.closeAudioMenu()     // 收起音乐 / 音效两个开关
                                    },
                            )
                        }

                        // 左侧竖向上下文按钮组：贴屏幕左边缘最底部、竖向排列（放在最上层，保证浮层展开时仍可点）。
                        // 用 **BottomStart（底对齐）** 锚定：底部留白 = 底栏舞台高度 + 恰好一个按钮高度。
                        // ⚠️ 高度取底栏那个**常量**（[BOTTOM_BAR_HEIGHT]），不要按模式写 60/64：
                        // 以前这里跟着模式在 60↔64 之间切换，等于在换班的那一瞬间把这整列按钮
                        // 往上/往下**瞬移** 4dp —— 底栏在动、左边这一列却在跳，看起来就是"抖了一下"。
                        // 空列表时整列自己滑回屏幕左侧并从组合里移除（判断在组件内部）
                        ContextButtons(
                            vm,
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 14.dp, bottom = BOTTOM_BAR_MARGIN + BOTTOM_BAR_HEIGHT + LEFT_BUTTON_H),
                        )
                    }
                }
            }

            // ==================== 弹层 ====================
            if (junctionDialogNodeId != null) {
                JunctionConfigDialog(vm, junctionDialogNodeId!!) { vm.closeJunctionDialog() }
            }
            // 红绿灯设置（三选一的运行规则）
            if (signalRuleDialogNodeId != null) {
                SignalRuleDialog(vm, signalRuleDialogNodeId!!) { vm.closeSignalRuleDialog() }
            }
            if (showSettingsSheet) SettingsSheet(vm) { vm.closeSettingsSheet() }
            if (showSaveDialog) SaveNetworkDialog(vm) { vm.closeDialogs() }
            if (showLoadDialog) LoadNetworkDialog(vm) { vm.closeDialogs() }
            if (showClearConfirm) {
                ClearConfirmDialog(onConfirm = { vm.confirmClear() }, onDismiss = { vm.cancelClear() })
            }
        }
    }
}

/**
 * 根容器的底色（第 57 轮从 `MainScreen` 根部搬下来）。
 *
 * ## 为什么要单独一个 composable
 * 它的取值同时依赖 `uiMode`（白 / 浅灰）和 `nightT`（昼夜补间），而这两样在根部读的代价
 * 极不对称：`nightT` 每帧变一次（手动切换昼夜时补间 340ms），`uiMode` 只变一次 ——
 * 但**只要在根部读，两者都会让整棵界面树重组**，包括那张十层画布。
 *
 * 搬到这里之后：
 *  - `uiMode` 只重组这一个全屏矩形（画布里的底色层自己另有一份，见 `BackdropLayer`）；
 *  - `nightT` 每帧变也只重组它自己，画布靠 `CanvasHost` 整段跳过。
 *
 * 这个 Box 铺满全屏且在 `SimCanvas` **之下**：画布自己会画一层底色盖住它，
 * 它只负责"画布之外那几像素"（刘海、窄屏留白），以及画布首帧还没画出来时的那一下。
 */
@Composable
private fun StageBackdrop(vm: SimulatorViewModel, nightT: Float) {
    val uiMode by vm.uiMode.collectAsState()
    val bg = if (uiMode == SimulatorViewModel.UiMode.EDIT) {
        lerp(LIGHT_GRAY, NIGHT_EDIT_BG, nightT)
    } else {
        lerp(WARM_WHITE, NIGHT_VIEW_BG, nightT)
    }
    Box(Modifier.fillMaxSize().background(bg))
}

/**
 * 左上角"聚焦车辆信息卡"的宿主：**帧订阅只发生在这里**。
 *
 * 为什么单独拆一个宿主：`vm.frame` 每 16ms 换一次。以前在主界面根部 `collectAsState`，
 * 整个界面层（画布标记、底栏、左侧按钮列、所有 EdgeSlideIn）都会被拖着 60Hz 重组一次。
 * 一旦叠上"进入动画首帧要现组合整棵子树"的开销，那一帧就撑爆了预算 → 只有进入会卡。
 *
 * 这里用 `derivedStateOf` 把"有没有聚焦车辆"从每帧变化的帧对象里摘出来：
 * 未聚焦时它恒为 null，宿主**完全不会**因为帧刷新而重组；聚焦时才会跟着刷新读数（本来就该刷）。
 */
@Composable
private fun FocusCard(vm: SimulatorViewModel) {
    val frameState = vm.frame.collectAsState()
    val focused by remember(frameState) { derivedStateOf { frameState.value?.focus } }
    // 退场动画期间数据已经是 null 了，靠它留住上一帧的读数，让卡片"带着内容"缩回去
    val shown = rememberLastNonNull(focused)

    // 从上方落下 / 缩回上方
    EdgeSlideIn(visible = focused != null, edge = SlideEdge.TOP) {
        VehicleInfoCard(vm, shown)
    }
}

/**
 * 崩溃横幅的宿主：同样只在本地订阅帧。
 *
 * 文案取"第一个被堵住的路口名"，是一个 `String?` —— 用 `derivedStateOf` 之后
 * 只有路口变了才会重组（而不是每帧重组），同时退场时靠 rememberLastNonNull 留住最后一句。
 */
@Composable
private fun CrashBannerHost(
    vm: SimulatorViewModel,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val frameState = vm.frame.collectAsState()
    val fallback = LocalMessages.current.s(StrKey.LABEL_JUNCTION_UNNUMBERED)
    // ⚠️ `blockedJunctions` 本身是非空 Map，但整条链因为 `frame?` 而变成可空类型，
    // 所以 `keys` 之后仍然是 `Set<Long>?` —— 用 `orEmpty()` 落地最稳。
    val label by remember(frameState) {
        derivedStateOf {
            frameState.value?.stats?.blockedJunctions.orEmpty().keys
                .firstOrNull()?.let { vm.junctionLabel(it) }
        }
    }
    val shown = rememberLastNonNull(label)

    // 从下方升起、往下方收回
    EdgeSlideIn(visible = visible, edge = SlideEdge.BOTTOM, modifier = modifier) {
        CrashBanner(vm, shown ?: fallback)
    }
}

/** 右上角统计面板：运行时间 / 车辆数（+ 累计换路 / 累计变道） */
@Composable
private fun StatsOverlay(vm: SimulatorViewModel) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current
    val show by vm.showStats.collectAsState()
    val frame by vm.frame.collectAsState()
    // 设置变化（比如开/关自主规划）要让「累计换路」那一行及时出现/收起：
    // 以前靠根部每帧重组顺带刷新，现在帧订阅下沉了，这里必须自己盯版本号。
    val configVersion by vm.configVersion.collectAsState()

    // 帧率只在面板展开时采样：采样循环本身会**持续请求下一帧**（withFrameNanos 的特性），
    // 面板一收起就停 —— 否则为了显示一个数字，App 会永远满帧唤醒。
    val fps = rememberFps(show)

    // ---------- 面板尺寸恒定 ----------
    // 旧实现的宽度 = "最宽那一行的自然宽度"，于是**每一秒都在变**：
    //   · 时间 00:09 → 00:10：数字字形宽度不同，框的左边缘抖一下；
    //   · 车辆数 9 → 10、帧率 60 → 120：多一位数字，框直接宽出一截；
    //   · 关掉自主规划时「累计换路」整行消失，框又窄一截。
    // 面板靠右对齐，宽度一变就是**左边缘横跳**，非常显眼。
    //
    // 现在改成：拿"位数最多"的内容把每一行**量一遍**，取最大宽度钉死。
    // 数值怎么变都不会再改宽度；顺带把每行限制成单行，高度也就固定了。
    // 用测量的方式而不是写死 dp：切语言（中/英）、改系统字号后依然贴合。
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val inkStyle = MaterialTheme.typography.labelLarge
    val subStyle = MaterialTheme.typography.labelMedium
    val mutedStyle = MaterialTheme.typography.labelSmall
    val panelWidth = remember(m, measurer, density, inkStyle, subStyle, mutedStyle) {
        fun widthOf(style: TextStyle, text: String): Float =
            measurer.measure(text, style).size.width.toFloat()
        // 探针取 4 位数 / 99:59 / 120fps —— 都远超实际可能出现的最大值
        val widest = maxOf(
            widthOf(inkStyle, m.s(StrKey.STATS_RUNTIME, "99:59")),
            widthOf(subStyle, m.s(StrKey.STATS_VEHICLES, 8888)),
            widthOf(subStyle, m.s(StrKey.STATS_ARRIVED, 8888)),
            widthOf(mutedStyle, m.s(StrKey.STATS_REROUTES, 8888)),
            widthOf(mutedStyle, m.s(StrKey.STATS_LANECHANGES, 8888)),
            widthOf(mutedStyle, m.s(StrKey.STATS_FPS, 120)),
        )
        // 左右内边距各 12dp（见下面的 padding），另留 4dp 吸收字体度量差异
        with(density) { widest.toDp() } + 28.dp
    }

    // 从上方落下、往上方收回
    EdgeSlideIn(visible = show, edge = SlideEdge.TOP, index = 1) {
        Column(
            modifier = Modifier
                .width(panelWidth)
                .background(pal.panelSoft.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            @Suppress("UNUSED_EXPRESSION") configVersion
            val stats = frame?.stats
            Text(
                m.s(StrKey.STATS_RUNTIME, formatClock(frame?.simTime ?: 0.0)),
                style = inkStyle,
                color = pal.ink,
                maxLines = 1,
            )
            Text(
                m.s(StrKey.STATS_VEHICLES, stats?.active ?: 0),
                style = subStyle,
                color = pal.sub,
                maxLines = 1,
            )
            // 「到达车辆数」：累计已抵达目标路口的车辆总数（需求 3）。
            // 直接用 `SimStats.arrived`（= 模拟器里的 `totalArrived`），它在车辆抵达的
            // **那一帧**就 +1，不用等淡出播完，所以和画面同步。
            Text(
                m.s(StrKey.STATS_ARRIVED, stats?.arrived ?: 0),
                style = subStyle,
                color = pal.sub,
                maxLines = 1,
            )
            // 「累计换路」（被堵住而自主更换路线的总次数），仅在开启自主规划时显示。
            // 面板宽度已按"这一行存在"量过，所以它出现/收起只影响高度、不影响宽度。
            if (vm.settings.autoReroute) {
                Text(
                    m.s(StrKey.STATS_REROUTES, stats?.reroutes ?: 0),
                    style = mutedStyle,
                    color = pal.muted,
                    maxLines = 1,
                )
            }
            // 「累计变道」（多车道道路上车辆变道的总次数）
            Text(
                m.s(StrKey.STATS_LANECHANGES, stats?.laneChanges ?: 0),
                style = mutedStyle,
                color = pal.muted,
                maxLines = 1,
            )
            // 实时帧率：拖动路网 / 缩放到很远时可以看它有没有掉
            Text(
                m.s(StrKey.STATS_FPS, fps),
                style = mutedStyle,
                color = pal.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * 实时帧率（帧/秒）。[enabled] 为 false 时返回 0，且不会申请任何帧。
 *
 * 为什么用 `withFrameNanos` 的时间戳，而不是"数一秒内画了几帧"：
 * 后者在 60Hz 屏上永远显示 60 —— 卡掉两帧只是统计窗口里少两帧，读数还在 58 左右晃，
 * 看不出"这一下卡了"。这里算的是**两次绘制之间的真实间隔**的平均值，
 * 一次 33ms 的卡顿会把 0.5 秒窗口的读数直接压到 45~50，肉眼可见。
 *
 * 采样窗口取 0.5 秒：再短数字会乱跳，再长就反映不出拖动中那种瞬时顿挫。
 */
@Composable
private fun rememberFps(enabled: Boolean): Int {
    var fps by remember { mutableStateOf(0) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        var frames = 0
        var windowStart = 0L
        while (true) {
            val t = withFrameNanos { it }
            if (windowStart == 0L) {
                windowStart = t
                continue
            }
            frames++
            val dt = (t - windowStart) / 1_000_000_000.0
            if (dt >= 0.5) {
                fps = (frames / dt).roundToInt()
                frames = 0
                windowStart = t
            }
        }
    }
    return fps
}

/** 秒 → mm:ss */
private fun formatClock(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

/** 左上角操作提示（仅在需要时出现，保持画面干净）——从左侧弹入、收回左侧 */
@Composable
private fun HintText(vm: SimulatorViewModel) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current
    val uiMode by vm.uiMode.collectAsState()
    val drawTool by vm.drawTool.collectAsState()
    val selection by vm.selection.collectAsState()
    // 「有没有路」取自路网对象本身（不是 State），必须在路网/配置变化时重新判断：
    // 以前靠根部每帧重组顺带刷新，现在帧订阅已下沉，这里自己盯版本号。
    // 用"稳定版"：拖动端点时路的条数不会变，没必要跟着每帧重组。
    val networkVersion by vm.networkVersionSettled.collectAsState()
    val configVersion by vm.configVersion.collectAsState()
    val hasRoads = vm.network.edges.isNotEmpty()

    val hint = when {
        uiMode == SimulatorViewModel.UiMode.EDIT && !hasRoads ->
            m.s(StrKey.HINT_EDIT_NO_ROADS)
        uiMode == SimulatorViewModel.UiMode.EDIT && drawTool.isRoad ->
            m.s(StrKey.HINT_EDIT_DRAWING)
        uiMode == SimulatorViewModel.UiMode.EDIT && selection == SimulatorViewModel.Selection.ROAD ->
            m.s(StrKey.HINT_EDIT_ROAD_SELECTED)
        uiMode == SimulatorViewModel.UiMode.EDIT &&
            selection == SimulatorViewModel.Selection.SIGNAL ->
            m.s(StrKey.HINT_EDIT_SIGNAL)
        uiMode == SimulatorViewModel.UiMode.EDIT &&
            selection == SimulatorViewModel.Selection.NO_SIGNAL ->
            m.s(StrKey.HINT_EDIT_NO_SIGNAL)
        uiMode == SimulatorViewModel.UiMode.EDIT ->
            m.s(StrKey.HINT_EDIT_DEFAULT)
        !hasRoads -> m.s(StrKey.HINT_VIEW_NO_ROADS)
        else -> null
    }
    @Suppress("UNUSED_EXPRESSION") networkVersion
    @Suppress("UNUSED_EXPRESSION") configVersion
    // 提示随状态整句替换：退场时把最后一句留住，否则会看到"空胶囊"往回缩
    val shown = rememberLastNonNull(hint)

    EdgeSlideIn(visible = hint != null, edge = SlideEdge.START) {
        Text(
            text = shown ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = pal.muted,
            modifier = Modifier
                .background(pal.panelSoft.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
