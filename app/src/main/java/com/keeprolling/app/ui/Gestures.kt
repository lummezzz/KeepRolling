package com.keeprolling.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import com.keeprolling.app.ui.components.DragHaptics
import com.keeprolling.app.viewmodel.SimulatorViewModel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val HOLD_MS = 400L           // 长按就绪时长
private const val TAP_MAX_MS = 750L        // 超过这个时长就不再算"轻点"（独立于 HOLD_MS，避免长按变短后点选变苛刻）
private const val TAP_SLOP_PX = 16f        // 单击容差
private const val DOUBLE_TAP_MS = 300L     // 双击间隔
/**
 * 缩放的**死区**：两指间距变化小于 0.2% 就当没变。
 *
 * 手指静止时原始坐标仍有 0.5~1px 抖动，撑在 300px 的两指间距上大约 0.3% —— 不设死区
 * 就会出现"手指没动、画面却在缓慢放大"。真正的捏合远大于这个量（不会因此变迟钝）。
 */
private const val ZOOM_DEADZONE = 0.002f

/**
 * 命中容差（屏幕像素，随缩放自适应换算成米）。
 *
 * **端点优先（需求 2）**：编辑模式端点半径 48px（明显大于道路的 30px），
 * 所以"大致点在端点附近"一定选中端点，不会误选整条道路；
 * 主界面（VIEW）只需要点名开放路口，半径保持 32px 更稳。
 */
private const val NODE_HIT_PX_EDIT = 48f   // 编辑模式：道路端点（开放路口 / 封闭口）
private const val NODE_HIT_PX_VIEW = 32f   // 主界面：开放路口
private const val ROAD_HIT_PX = 30f        // 命中整条道路
private const val JUNCTION_HIT_PX = 60f    // 命中十字 / 丁字路口（需求 6：比端点更大，路口好点得多）
private const val SIGNAL_HIT_PX = 26f      // 命中路口红绿灯小图标（需求 6）
private const val VEHICLE_HIT_PX = 26f     // 命中一辆车（需求 4：点车即聚焦跟随）
private const val EDGE_HANDLE_PX = 34f     // 命中「选中道路」两端的拖拽手柄（需求 2）

/** 双击检测用的跨手势状态 */
class TapTracker {
    var lastTapTime = 0L
    var lastTapPos: Offset = Offset.Zero

    fun isDoubleTap(pos: Offset): Boolean {
        val now = System.currentTimeMillis()
        val ok = now - lastTapTime < DOUBLE_TAP_MS && (pos - lastTapPos).getDistance() < 48f
        lastTapTime = if (ok) 0L else now
        lastTapPos = pos
        return ok
    }
}

private enum class Mode { UNDECIDED, DRAW, TRANSFORM, PAN, CURVATURE, ENDPOINT }

/**
 * 统一手势处理（写成一个函数，避免多个手势识别器互相抢事件）：
 *
 *  优先级从高到低：
 *   1. 选中道路两端的**拖拽手柄** → 拖动端点、移动整条道路（需求 2，仅编辑模式 + 选中道路时）
 *   2. 曲线**曲率手柄** → 拖动调整曲线曲率（仅编辑模式、选中曲线时）
 *   3. 单指**按住 0.4 秒**（编辑模式 + 已选直线/曲线工具）→ 进入画路：
 *      起终点显示同心圆，拖动时跟手预览，抬手生成最终道路
 *      画路途中若第二根手指落下 → 取消本次画路，转为缩放
 *   4. 双指 → 缩放 + 平移（以两指中点为中心；手指数变化时重新取基准，杜绝"手指一放上去画面就跳"）
 *   5. 单指拖动 → 平移画布
 *   6. 轻点 → 编辑模式下按 **红绿灯图标 → 道路端点 → 十字/丁字路口 → 道路** 的优先级命中
 *      （见 [NODE_HIT_PX_EDIT] / [JUNCTION_HIT_PX]）；主界面轻点开放路口 → 打开出入口设置；双击 → 视角复位
 *
 * **左侧菜单的收起（需求 4）**：手指一落上画布就先收起左侧菜单（道路工具 / 设置行）；
 * 若这次轻点的作用就是"收起菜单"，则不再额外改变选中对象。
 *
 * **触觉**：[drag] 由调用方创建（`rememberDragHaptics()`）。画路与拖端点时按"手指走过的米数"
 * 给细密的轻震，和档位类控件的"一档一响"共用同一条实现（见 `DragHaptics`），
 * 只是量化粒度不同。
 *
 * 说明：曾经尝试的"双指轻点撤销"已移除——只要第二根手指落下，模式就会切到 TRANSFORM（缩放），
 * 因此"停在 UNDECIDED 的双指轻点"永远走不到，等于没有实际效果；撤销改由底部常驻按钮负责。
 */
suspend fun PointerInputScope.keepRollingGestures(
    camera: CameraState,
    vm: SimulatorViewModel,
    tapTracker: TapTracker,
    drag: DragHaptics,
    onHoldReady: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = System.currentTimeMillis()
        var mode = Mode.UNDECIDED
        var moved = false
        var lastCentroid = down.position
        var lastSpread = 0f
        var lastWorld = camera.screenToWorld(down.position)
        var lastCount = 1
        // 手指数变化（1↔2↔3…）后的第一个事件只重新定基准、不产生位移，避免画面瞬移
        var transformFresh = false
        // 本次手势里"手指走过的世界距离"：画路 / 拖端点用它换算成触觉槽位（每 2 米一响）
        var dragTravel = 0.0
        // 新手势从干净状态开始计数（不清冷却时间戳，见 DragHaptics.reset 注释）
        drag.reset()

        // 点画布 → 收起左侧菜单（需求 4）；记住此前是否展开，供"轻点"分支决定是否跳过命中检测
        val menuWasOpen = vm.dismissLeftMenus()

        val downWorld = camera.screenToWorld(down.position)

        // ---- 1. 曲率手柄：按下即抓（它在弦外侧的空地上，不与端点/道路打架）----
        var pendingHandle: Long? = null
        if (vm.uiMode.value == SimulatorViewModel.UiMode.EDIT) {
            // 选中道路的端点手柄（需求 2）先**记为候选**，等手指真的移动了才进入拖拽：
            // 这样"轻点端点"仍然是点选（开放/封闭路口），只有"拖"才是移动道路。
            pendingHandle = vm.edgeHandleAt(downWorld, camera.pxToMeters(EDGE_HANDLE_PX))
            if (pendingHandle == null) {
                val tolM = camera.pxToMeters(22f)
                if (vm.hitCurvatureHandle(downWorld, tolM)) {
                    mode = Mode.CURVATURE
                    vm.beginCurvatureDrag()
                }
            }
        }

        var finished = false
        while (!finished) {
            // 静止按住时用超时等待：超时即表示"按住 0.4 秒不动"
            // 注意：按在"选中道路的端点手柄"上时不算画路候选（那是要拖拽移动道路，需求 2）
            val holdCandidate = mode == Mode.UNDECIDED && !moved && pendingHandle == null && vm.canDraw()
            val timeout = if (holdCandidate) {
                (HOLD_MS - (System.currentTimeMillis() - downTime)).coerceAtLeast(1L)
            } else {
                Long.MAX_VALUE
            }
            val event = withTimeoutOrNull(timeout) { awaitPointerEvent() }
            if (event == null) {
                // 超时 → 长按就绪，进入画路
                if (mode == Mode.UNDECIDED && !moved && pendingHandle == null && vm.canDraw()) {
                    lastWorld = camera.screenToWorld(down.position)
                    vm.beginDraw(lastWorld)
                    onHoldReady()
                    mode = Mode.DRAW
                }
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            val count = pressed.size
            // 本事件里**真的有手指挪动**了吗？触摸事件在"有人按下 / 抬起"时也会送达，
            // 那种事件里所有已按住的指针位移都是 0 —— 此时绝不能按"两指中点"去平移，
            // 否则第二根手指一落地，画面就会瞬移半个指距。
            // （用 position / previousPosition 自己比，别用 positionChanged()：那是内部实现，
            //   在部分 Compose 版本里不对外可见 → Unresolved reference。）
            val anyMoved = event.changes.any {
                it.pressed && (it.position.x != it.previousPosition.x ||
                    it.position.y != it.previousPosition.y)
            }

            // 手指数量发生变化 → 重新定基准（第二根手指落下 / 抬起时画面不跳）
            if (count != lastCount) {
                transformFresh = true
                // 单指形态遇到第二根手指，必须切到「双指」模式。
                // 旧代码只处理了 UNDECIDED / DRAW，于是"已经开始单指平移（PAN）再放下第二指"
                // 会留在 PAN 分支里，拿**两指中点**去减**单指位置** —— 一次瞬移半个指距。
                when (mode) {
                    Mode.DRAW -> {
                        // ⚠️ 只有**第二根手指落下**（count ≥ 2）才算"画到一半被打断"。
                        // 抬手时 count 会变成 0，那同样是"手指数变化"，但它是**正常收笔** ——
                        // 曾经这里无条件 cancelDraw() + 切到 TRANSFORM，于是
                        // `when (mode)` 最后的 `Mode.DRAW -> finishDraw(...)` 永远走不到：
                        // 预览跟手一切正常，一抬手就什么都没生成，而且不报错、不提示。
                        if (count >= 2) {
                            vm.cancelDraw()
                            mode = Mode.TRANSFORM
                            vm.onManualCameraMove()      // 手动缩放 / 拖动 → 解除"锁定跟随车辆"（需求 4）
                        }
                    }
                    Mode.UNDECIDED, Mode.PAN -> {
                        if (count >= 2) {
                            mode = Mode.TRANSFORM
                            vm.onManualCameraMove()
                        }
                    }
                    // ENDPOINT / CURVATURE 是"拖手柄"，第二指落下不改模式，但同样只重定基准
                    else -> Unit
                }
            }

            val centroid = if (count > 0) {
                var x = 0f
                var y = 0f
                for (c in pressed) {
                    x += c.position.x
                    y += c.position.y
                }
                Offset(x / count, y / count)
            } else {
                lastCentroid
            }
            val spread = if (count >= 2) (pressed[0].position - pressed[1].position).getDistance() else 0f

            when (mode) {
                Mode.CURVATURE -> {
                    // 手指数量刚变过的那一帧只重定基准，不产生拖拽量
                    lastWorld = camera.screenToWorld(centroid)
                    if (!transformFresh) vm.dragCurvature(lastWorld) else transformFresh = false
                    event.changes.forEach { it.consume() }
                }
                Mode.ENDPOINT -> {
                    // 拖拽选中道路的端点：实时移动节点，相接道路自动跟随（需求 2）
                    val next = camera.screenToWorld(centroid)
                    if (!transformFresh) {
                        dragTravel += next.distanceTo(lastWorld)
                        vm.dragEdgeEndpoint(next)
                        // 每"手指走过 2 米"一响（不是每帧一响）：慢拖时一步一响，快拖时被封顶在 ~31 次/秒
                        drag.flow((dragTravel * 0.5).toInt())
                    } else {
                        transformFresh = false
                    }
                    lastWorld = next
                    event.changes.forEach { it.consume() }
                }
                Mode.DRAW -> {
                    val next = camera.screenToWorld(centroid)
                    dragTravel += next.distanceTo(lastWorld)
                    lastWorld = next
                    vm.updateDraw(lastWorld)
                    // 画路是"笔尖在纸上走"：跟手给细密的轻震，走 2 米一响（与拖端点同一套换算）
                    drag.flow((dragTravel * 0.5).toInt())
                    transformFresh = false
                    event.changes.forEach { it.consume() }
                }
                Mode.TRANSFORM -> {
                    if (transformFresh) {
                        // 刚有手指按下 / 抬起：只把基准换到新的两指中点与间距，**不施加任何增量**
                        transformFresh = false
                    } else if (anyMoved) {
                        // 以两指中点为中心缩放（applyZoom 内部按指尖世界坐标做反向补偿）。
                        // 只在**间距真的变了**时才缩放：第二指"点一下"不改间距 → 不缩放。
                        if (spread > 1f && lastSpread > 1f) {
                            val ratio = spread / lastSpread
                            if (abs(ratio - 1f) > ZOOM_DEADZONE) camera.applyZoom(ratio, centroid)
                        }
                        camera.pan(centroid - lastCentroid)
                    }
                    event.changes.forEach { it.consume() }
                }
                Mode.PAN -> {
                    if (transformFresh) transformFresh = false
                    else if (anyMoved) camera.pan(centroid - lastCentroid)
                    event.changes.forEach { it.consume() }
                }
                Mode.UNDECIDED -> {
                    if (count == 1) {
                        val c = event.changes.firstOrNull { it.id == down.id }
                        if (c != null && (c.position - down.position).getDistance() > TAP_SLOP_PX) moved = true
                    }
                    if (moved) {
                        // 手指移动超过容差：命中端点手柄 → 拖拽移动道路；否则 → 平移画布
                        val h = pendingHandle
                        mode = if (h != null && vm.beginEdgeEndpointDrag(h, downWorld)) {
                            Mode.ENDPOINT
                        } else {
                            Mode.PAN
                        }
                        vm.onManualCameraMove()   // 手动拖动 → 解除锁定跟随（需求 4）
                    } else if (count == 1 && vm.canDraw() &&
                        System.currentTimeMillis() - downTime >= HOLD_MS
                    ) {
                        lastWorld = camera.screenToWorld(down.position)
                        vm.beginDraw(lastWorld)
                        onHoldReady()
                        mode = Mode.DRAW
                    }
                }
            }

            lastCentroid = centroid
            if (spread > 0f) lastSpread = spread
            lastCount = count
            if (event.changes.none { it.pressed }) finished = true
        }

        // ---- 手势结束 ----
        val duration = System.currentTimeMillis() - downTime
        when (mode) {
            Mode.DRAW -> vm.finishDraw(lastWorld)
            Mode.ENDPOINT -> vm.endEdgeEndpointDrag()
            Mode.CURVATURE -> vm.endCurvatureDrag()
            Mode.UNDECIDED -> {
                if (!moved && duration < TAP_MAX_MS) {
                    // 需求 4（去UI）：界面全隐时，单击**任意位置**的唯一含义就是"把 UI 唤回来"。
                    // 这一支必须放在最前面 —— 此时不该有任何命中检测（点到车也不锁定）。
                    if (vm.hideUi.value) {
                        vm.setHideUi(false)
                    } else if (tapTracker.isDoubleTap(down.position)) {
                        camera.resetToNetwork(vm.network, size)
                    } else if (menuWasOpen) {
                        // 这一次轻点的作用就是"收起左侧菜单"（需求 4）——不额外改变选中对象
                    } else {
                        val world = camera.screenToWorld(down.position)
                        val edit = vm.uiMode.value == SimulatorViewModel.UiMode.EDIT
                        // 优先级 00（需求 4）：非编辑模式下先看有没有点在**车辆**上 → 聚焦跟随。
                        // 车只有运行时才有，编辑模式不参与，避免挡住"点路口/点道路"。
                        val hitVehicle = !edit && vm.focusVehicleAt(world, camera.pxToMeters(VEHICLE_HIT_PX))
                        if (hitVehicle) {
                            // 已聚焦：相机缩放 + 锁定跟随由画布监听 focusEpoch 完成
                        } else {
                            // 优先级 0：编辑模式先看有没有点在**路口红绿灯小图标**上（需求 6）
                            val signalNode = if (edit) {
                                vm.hitSignalMarker(
                                    world,
                                    SimulatorViewModel.SIGNAL_GAP_M,
                                    camera.pxToMeters(SIGNAL_HIT_PX),
                                )
                            } else {
                                null
                            }
                            if (signalNode != null) {
                                vm.selectSignalNode(signalNode)
                            } else {
                                // 优先级 1：端点（更大容差） → 优先级 2：路口 → 优先级 3：道路。
                                // 容差按屏幕像素换算成米，因此缩放多大都点得准。
                                vm.onTap(
                                    world,
                                    camera.pxToMeters(if (edit) NODE_HIT_PX_EDIT else NODE_HIT_PX_VIEW),
                                    camera.pxToMeters(ROAD_HIT_PX),
                                    camera.pxToMeters(JUNCTION_HIT_PX),
                                )
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}

