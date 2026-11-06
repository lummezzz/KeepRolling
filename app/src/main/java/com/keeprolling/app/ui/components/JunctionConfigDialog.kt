package com.keeprolling.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keeprolling.app.model.JunctionConfig
import com.keeprolling.app.model.SPEED_CURVE_MAX_KMH
import com.keeprolling.app.model.SPEED_CURVE_MIN_KMH
import com.keeprolling.app.model.SPEED_CURVE_POINTS
import com.keeprolling.app.model.SPEED_CURVE_STEP_KMH
import com.keeprolling.app.model.speedCurveValueAt
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.SimulatorViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

// 分组之间的间隔（与组内 6dp 形成明显对比，"有关联的近、无关的远"）
private val GROUP_GAP = 16.dp

/** 打开对话框时把配置里的速度曲线"落实"成 9 个点：没有 / 长度不对就回落到默认曲线（旧存档同样适用） */
private fun JunctionConfig.withResolvedCurve(): JunctionConfig =
    if (speedCurve.size == SPEED_CURVE_POINTS) this else copy(speedCurve = effectiveCurve())

/**
 * 出入口设置对话框：
 *  - 车辆生成：生成间隔（a 秒/辆，整数 1~10）· 三段式车型概率 · **自定义速度曲线**（9 个控制点，上下拖动）
 *  - 路口热度：1~10 整数，显示换算后的目的地概率
 *
 * 交互：
 *  - 所有控件只改**本地草稿副本** `draft`；点「勾」才一次性写回 ViewModel（保存并应用），
 *    点「叉」/ 点空白处 / 返回键 → 直接丢弃本次改动；
 *  - 文案走 [LocalMessages]（需求 5：中英切换）。
 */
@Composable
fun JunctionConfigDialog(vm: SimulatorViewModel, nodeId: Long, onDismiss: () -> Unit) {
    val m = LocalMessages.current
    // 需求 2：对话框内部的文字与小控件也要跟着昼夜变色
    val pal = LocalUiPalette.current
    // 进入对话框时取一份当前配置作为草稿；点「勾」前不动真实配置
    val stored = vm.junctionConfigs[nodeId] ?: return
    var draft by remember(nodeId) { mutableStateOf(stored.withResolvedCurve()) }

    // 四个控件各持一个触觉节拍器（**不能共用**：生成间隔与路口热度都是 1~10 的档位，
    // 共用一个实例会出现"从间隔 5 拖到 6 不震"——槽位撞车）。
    //  · 生成间隔 / 路口热度 = 离散步进 → step()，一档一响
    //  · 车型比例 / 速度曲线 = 连续滑动 → flow()，每跨 1% 一响，比档位更密更轻
    val spawnTick = rememberDragHaptics()
    val heatTick = rememberDragHaptics()
    val ratioTick = rememberDragHaptics()
    val curveTick = rememberDragHaptics()

    AlertDialog(
        onDismissRequest = onDismiss,   // 点外部 / 返回 = 丢弃
        title = {
            Text(m.s(StrKey.JUNCTION_TITLE, vm.junctionLabel(nodeId)))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ---------------- 车辆生成 ----------------
                SectionTitle(m.s(StrKey.SECTION_VEHICLE_SPAWN), Color(0xFF0F6E56))

                // 生成间隔：标签紧贴其滑动条
                Text(
                    m.s(StrKey.SPAWN_INTERVAL, draft.spawnIntervalSec),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = draft.spawnIntervalSec.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    onValueChange = { v ->
                        val n = v.roundToInt().coerceIn(1, 10)
                        draft = draft.copy(spawnIntervalSec = n)
                        // 整数秒就是"档"：跨一格响一下（同一格里微动不响）
                        spawnTick.step(n)
                    },
                )

                Spacer(Modifier.height(GROUP_GAP))   // 与「车型比例」组拉开

                Text(
                    m.s(StrKey.VEHICLE_RATIO),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ThreeSegmentSlider(
                    car = draft.carRatio,
                    bus = draft.busRatio,
                    truck = draft.truckRatio,
                    haptic = ratioTick,
                    onChange = { c, b, t ->
                        draft = draft.copy(carRatio = c, busRatio = b, truckRatio = t)
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        m.s(StrKey.RATIO_CAR, (draft.carRatio * 100).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF185FA5),
                    )
                    Text(
                        m.s(StrKey.RATIO_BUS, (draft.busRatio * 100).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF854F0B),
                    )
                    Text(
                        m.s(StrKey.RATIO_TRUCK, (draft.truckRatio * 100).roundToInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = pal.sub,
                    )
                }

                Spacer(Modifier.height(GROUP_GAP))   // 与「速度分布」组拉开

                // ---------------- 速度分布（自定义曲线，需求 1）----------------
                Text(
                    m.s(StrKey.SPEED_CURVE_TITLE),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SpeedCurveEditor(
                    curve = draft.speedCurve,
                    haptic = curveTick,
                    onChange = { newCurve -> draft = draft.copy(speedCurve = newCurve) },
                )
                Text(
                    m.s(StrKey.SPEED_CURVE_HINT),
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.muted,
                )

                Spacer(Modifier.height(GROUP_GAP))   // 与「路口热度」组拉开

                // ---------------- 路口热度 ----------------
                SectionTitle(m.s(StrKey.SECTION_HEAT), Color(0xFF993C1D))
                Text(
                    m.s(StrKey.HEAT_LABEL, draft.heat),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = draft.heat.toFloat(),
                    valueRange = 1f..10f,
                    steps = 8,
                    onValueChange = { v ->
                        val n = v.roundToInt().coerceIn(1, 10)
                        draft = draft.copy(heat = n)
                        // 同上：热度也是 1~10 的整数档
                        heatTick.step(n)
                    },
                )
                Text(
                    m.s(StrKey.HEAT_HINT),
                    style = MaterialTheme.typography.labelSmall,
                    color = pal.muted,
                )
                Text(
                    vm.junctionProbabilityText(nodeId, draft.heat),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF993C1D),
                )
            }
        },
        // 「勾」= 确认：如果这次是从「开放」按钮进来的，勾下去才真正开放路口，
        // 同时把这份草稿配置一起写回（需求 4：勾 = 开放 + 保留修改）。
        // 走 vm.confirmJunctionDialog 而不是直接 applyJunctionConfig —— 后者只管配置，
        // 不知道"这次还要顺便把路口开放了"。
        confirmButton = {
            // 「确认开放路口」：缩放 + 轻震，**不加圆环**（第 57 轮按需求收敛为
            // "整个 App 只留开始 / 暂停那一圈"）。本来它也播不完 ——
            // 确认之后对话框立刻收起，圆环扩到一半就被盖住了。
            // 用 PressIconButton 而不是 Material 的 IconButton —— 后者是方形水波纹，
            // 跟全项目"圆钮 + 缩放"的手感不统一（见 PressFeedback.kt）。
            PressIconButton(
                icon = Icons.Filled.Check,
                contentDescription = m.s(StrKey.DESC_APPLY),
                tint = Color(0xFF1D9E75),
                onClick = { vm.confirmJunctionDialog(nodeId, draft) },
            )
        },
        // 「叉」= 取消：丢弃本次改动并关闭；若是开放流程，路口**保持封闭**（需求 4）
        dismissButton = {
            PressIconButton(
                icon = Icons.Filled.Close,
                contentDescription = m.s(StrKey.DESC_DISCARD),
                tint = pal.muted,
                onClick = onDismiss,
            )
        },
    )
}

/** 分组小标题（带主题色），用于把"有关联的内容"归到一组 */
@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = color)
}

// ==================== 三段式双滑块 ====================

/**
 * 三段式双滑块：两个拖点把整条轨道切成 小型车 | 中型车 | 大型车 三段，段宽即概率。
 *
 * @param haptic 拖动触觉节拍器（由调用方持有，保证一个控件一个实例）。
 *               这里用 [DragHaptics.flow]：比例是连续量，量化到 1% 作为槽位，
 *               于是"每挪动 1% 轻震一下"——比档位更密，但慢拖时不会空震。
 */
@Composable
private fun ThreeSegmentSlider(
    car: Double,
    bus: Double,
    truck: Double,
    haptic: DragHaptics,
    onChange: (car: Double, bus: Double, truck: Double) -> Unit,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val pal = LocalUiPalette.current
    val current = rememberUpdatedState(Triple(car, bus, truck))
    val changeState = rememberUpdatedState(onChange)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    haptic.reset()          // 新手势：按下即从干净状态开始计槽位
                    val (c0, b0, _) = current.value
                    val t1 = c0
                    val t2 = c0 + b0
                    var draggingLeft = abs(down.position.x - t1 * widthPx) < abs(down.position.x - t2 * widthPx)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val (c, b, _) = current.value
                        val x = (change.position.x / widthPx).toDouble().coerceIn(0.0, 1.0)
                        if (draggingLeft) {
                            // 拖左点 = 挪动"小型车 | 中型车"的边界：右边界（中段终点）不动。
                            // ⚠️ coerceIn(min, max) 在 min > max 时会**抛异常**（不是静默返回端点），
                            // 而极端比例（如 0.97 / 0.02 / 0.01）下上界会小于 0.02。
                            // 所以先把上下界本身夹进合法区间，再夹取值。
                            val t2 = (c + b).coerceIn(0.07, 1.0)
                            val maxT1 = (t2 - 0.05).coerceAtLeast(0.02)
                            val nt1 = x.coerceIn(0.02, maxT1)
                            changeState.value(
                                nt1,
                                (t2 - nt1).coerceAtLeast(0.0),
                                (1.0 - t2).coerceAtLeast(0.0),
                            )
                        } else {
                            // 拖右点 = 挪动"中型车 | 大型车"的边界：左边界不动
                            val minT2 = (c + 0.05).coerceAtMost(0.98)
                            val nt2 = x.coerceIn(minT2, 0.98)
                            changeState.value(
                                c,
                                (nt2 - c).coerceAtLeast(0.0),
                                (1.0 - nt2).coerceAtLeast(0.0),
                            )
                        }
                        // 触觉挂在**量化后的比例**上（1% 一格），而不是每帧：
                        // 慢拖 1% 一响，快拖被封顶在 ~31 次/秒，且完全不看指针事件的频率。
                        haptic.flow((x * 100).roundToInt())
                        change.consume()
                    }
                }
            }
    ) {
        val h = 14.dp.toPx()
        val top = (size.height - h) / 2f
        val x1 = (car * size.width).toFloat()
        val x2 = ((car + bus) * size.width).toFloat()
        val r = CornerRadius(h / 2f)
        // 段宽严格等于实际概率；不能为了好看强制最小宽度，否则色段会重叠并越过轨道末端。
        drawRoundRect(Color(0xFF378ADD), topLeft = Offset(0f, top), size = Size(x1, h), cornerRadius = r)
        drawRect(Color(0xFFEF9F27), topLeft = Offset(x1, top), size = Size((x2 - x1).coerceAtLeast(0f), h))
        drawRoundRect(
            pal.muted,
            topLeft = Offset(x2, top),
            size = Size((size.width - x2).coerceAtLeast(0f), h),
            cornerRadius = r,
        )
        drawThumb(x1, size.height / 2f, pal.panel)
        drawThumb(x2, size.height / 2f, pal.panel)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(cx: Float, cy: Float, fill: Color) {
    drawCircle(fill, radius = 13f, center = Offset(cx, cy))
    drawCircle(Color(0xFF534AB7), radius = 13f, center = Offset(cx, cy), style = Stroke(width = 2.5f))
}

// ==================== 自定义速度曲线（需求 1）====================

/**
 * 自定义速度曲线：横轴 40~120 km/h，**固定 9 个控制点**（间隔 10），每个点可上下拖动改变概率，
 * 相邻点之间用 **Catmull-Rom 样条**平滑连接。
 *
 *  - 单指按住某个控制点 → 上下拖动调整它的高度（横坐标锁定，只改纵坐标）；
 *  - 与采样共用 [speedCurveValueAt] → 图上看到的分布就是车辆速度的分布。
 *
 * @param haptic 拖动触觉节拍器。高度是连续量，量化到 **1%（纵向百分位）** 作为槽位：
 *               手指每上/下挪动 1% 轻震一下（画布高 160dp，1% 约 4~5px，跟手很自然）。
 */
@Composable
private fun SpeedCurveEditor(
    curve: List<Double>,
    haptic: DragHaptics,
    onChange: (List<Double>) -> Unit,
) {
    val n = SPEED_CURVE_POINTS
    var widthPx by remember { mutableFloatStateOf(1f) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    val pal = LocalUiPalette.current
    val current = rememberUpdatedState(curve)
    val changeState = rememberUpdatedState(onChange)
    val textMeasurer = rememberTextMeasurer()

    // 留白按 dp 换算后交给 Canvas（刻度文字是 sp；纯裸像素在不同屏幕密度下会被裁掉）
    val density = LocalDensity.current
    val padL = with(density) { 10.dp.toPx() }
    val padR = padL
    val padTop = with(density) { 10.dp.toPx() }
    val padBottom = with(density) { 22.dp.toPx() }

    /** 第 i 个控制点的横坐标（绘图与命中共用） */
    fun xOf(i: Int, w: Float): Float =
        padL + (w - padL - padR) * (i.toFloat() / (n - 1))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .onSizeChanged {
                widthPx = it.width.toFloat().coerceAtLeast(1f)
                heightPx = it.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    haptic.reset()          // 新手势：按下即从干净状态开始计槽位
                    val hgt = heightPx
                    val w = widthPx
                    // 选中离按下点最近的控制点，之后**只拖这一个点**（横坐标锁定）
                    var idx = 0
                    var bestDx = Float.MAX_VALUE
                    for (i in 0 until n) {
                        val dx = abs(down.position.x - xOf(i, w))
                        if (dx < bestDx) { bestDx = dx; idx = i }
                    }
                    fun heightAt(y: Float): Double {
                        val plotH = (hgt - padTop - padBottom).coerceAtLeast(1f)
                        return (((hgt - padBottom) - y) / plotH).toDouble().coerceIn(0.0, 1.0)
                    }
                    // 立即生效一次（点哪改哪）
                    run {
                        val list = current.value.toMutableList()
                        if (idx in list.indices) {
                            val h = heightAt(down.position.y)
                            list[idx] = h
                            changeState.value(list)
                            haptic.flow((h * 100).roundToInt())
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val list = current.value.toMutableList()
                        if (idx in list.indices) {
                            val h = heightAt(change.position.y)
                            list[idx] = h
                            changeState.value(list)
                            // 同一条规则：量化 1% 一格 + 32ms 冷却
                            haptic.flow((h * 100).roundToInt())
                        }
                        change.consume()
                    }
                }
            }
    ) {
        val plotH = (size.height - padTop - padBottom).coerceAtLeast(1f)
        val axisY = padTop + plotH

        // 纵向网格线（每个控制点一条，帮助对齐）
        for (i in 0 until n) {
            val x = xOf(i, size.width)
            drawLine(pal.divider.copy(alpha = 0.55f), Offset(x, padTop), Offset(x, axisY), strokeWidth = 1f)
        }
        // 基线
        drawLine(pal.divider, Offset(padL, axisY), Offset(size.width - padR, axisY), strokeWidth = 1.2f)

        // 曲线（Catmull-Rom 平滑插值）与填充
        val path = Path()
        val fill = Path()
        val steps = 160
        var started = false
        for (i in 0..steps) {
            val ratio = i.toFloat() / steps
            val kmh = SPEED_CURVE_MIN_KMH + ratio * (SPEED_CURVE_MAX_KMH - SPEED_CURVE_MIN_KMH)
            val v = speedCurveValueAt(curve, kmh).toFloat()
            val x = padL + (size.width - padL - padR) * ratio
            val y = padTop + plotH * (1f - v)
            if (!started) {
                path.moveTo(x, y)
                fill.moveTo(x, axisY)
                fill.lineTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width - padR, axisY)
        fill.close()
        drawPath(fill, Color(0xFF534AB7), alpha = 0.12f)
        drawPath(path, Color(0xFF534AB7), style = Stroke(width = 2.2f))

        // 9 个控制点（可拖动）
        for (i in 0 until n) {
            val h = (curve.getOrNull(i) ?: 0.0).coerceIn(0.0, 1.0)
            val c = Offset(xOf(i, size.width), padTop + plotH * (1f - h.toFloat()))
            drawCircle(pal.panel, radius = 6.5f, center = c)
            drawCircle(Color(0xFF1D9E75), radius = 6.5f, center = c, style = Stroke(width = 2.2f))
        }

        // 横轴：**9 个控制点全部标注具体速度值**（40 / 50 / … / 120），各自居中于对应的点下方
        val tickStyle = TextStyle(fontSize = 8.5.sp, color = pal.muted)
        for (i in 0 until n) {
            val kmh = (SPEED_CURVE_MIN_KMH + i * SPEED_CURVE_STEP_KMH).roundToInt()
            val label = textMeasurer.measure(kmh.toString(), tickStyle)
            val cx = xOf(i, size.width) - label.size.width / 2f
            // 夹在画布内，首尾两个数字不会被切掉
            val clampedX = cx.coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
            drawText(label, topLeft = Offset(clampedX, axisY + 3f))
        }
        // 单位放到左上角，避免和正中间的 "80" 打架
        val unit = textMeasurer.measure("km/h", tickStyle)
        drawText(unit, topLeft = Offset(padL, 0f))
    }
}
