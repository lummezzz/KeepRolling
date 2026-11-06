package com.keeprolling.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keeprolling.app.model.SimSettings
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.SimulatorViewModel
import kotlin.math.roundToInt

/**
 * 设置面板：
 * 倍速、反应时间、崩溃阈值、网格吸附开关、**自主规划（拥堵自动改道）**、实时统计、重置模拟、清空画布。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: SimulatorViewModel, onDismiss: () -> Unit) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current
    val configVersion by vm.configVersion.collectAsState()
    val frame by vm.frame.collectAsState()

    // 每个滑块一个触觉节拍器（实例不能共用：槽位空间是各自的，见 DragHaptics 类注释）。
    // 这里的五个都是**连续滑块**（`Slider` 没给 `steps`），所以统一走 flow()：
    // 量化粒度取"标签上显示的那个数字"（0.1 秒 / 1 秒 / 1%），跨一格轻震一下。
    val reactionTick = rememberDragHaptics()
    val crashTick = rememberDragHaptics()
    val windowTick = rememberDragHaptics()
    val ratioTick = rememberDragHaptics()
    val cooldownTick = rememberDragHaptics()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            @Suppress("UNUSED_EXPRESSION") configVersion

            Text(m.s(StrKey.SETTINGS_TITLE), style = MaterialTheme.typography.titleMedium)

            // 倍速（0.5 ~ 5x）
            Text(m.s(StrKey.SETTING_SPEED), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in SimSettings.SPEED_MULTIPLIERS) {
                    val label = if (m == m.toInt().toDouble()) "${m.toInt()}x" else "${m}x"
                    AssistChip(
                        onClick = { vm.updateSettings { speedMultiplier = m } },
                        label = { Text(label) },
                    )
                }
            }

            // 反应时间
            Text(
                m.s(StrKey.SETTING_REACTION, "%.1f".format(vm.settings.reactionTimeSec)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = vm.settings.reactionTimeSec.toFloat(),
                valueRange = 0.4f..2.0f,
                onValueChange = { v ->
                    vm.updateSettings { reactionTimeSec = v.toDouble() }
                    // 标签显示到 0.1 秒 → 每跨过 0.1 秒轻震一下
                    reactionTick.flow((v * 10f).roundToInt())
                },
            )

            // 崩溃阈值
            Text(
                m.s(StrKey.SETTING_CRASH, vm.settings.crashBlockSeconds.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = vm.settings.crashBlockSeconds.toFloat(),
                valueRange = 5f..30f,
                onValueChange = { v ->
                    vm.updateSettings { crashBlockSeconds = v.toDouble() }
                    // 标签取整显示 → 每跨过 1 秒轻震一下
                    crashTick.flow(v.roundToInt())
                },
            )

            // 网格吸附
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.s(StrKey.SETTING_GRID_SNAP), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = vm.settings.gridSnap,
                    onCheckedChange = { v -> vm.updateSettings { gridSnap = v } },
                )
            }

            // 红绿灯（本轮新增）：十字 / 丁字路口自动配时，车辆按灯通行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.s(StrKey.SETTING_LIGHTS), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = vm.settings.trafficLightsEnabled,
                    onCheckedChange = { v -> vm.updateSettings { trafficLightsEnabled = v } },
                )
            }
            Text(
                m.s(StrKey.SETTING_LIGHTS_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = pal.muted,
            )

            HorizontalDivider()

            // ---------- 自主规划 ----------
            Text(m.s(StrKey.SECTION_REROUTE), style = MaterialTheme.typography.titleSmall)
            Text(
                m.s(StrKey.REROUTE_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = pal.muted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.s(StrKey.REROUTE_ENABLE), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = vm.settings.autoReroute,
                    onCheckedChange = { v -> vm.updateSettings { autoReroute = v } },
                )
            }

            Text(
                m.s(StrKey.REROUTE_WINDOW, "%.0f".format(vm.settings.rerouteWindowSec)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = vm.settings.rerouteWindowSec.toFloat(),
                valueRange = 2f..10f,
                onValueChange = { v ->
                    vm.updateSettings { rerouteWindowSec = v.toDouble() }
                    windowTick.flow(v.roundToInt())
                },
                enabled = vm.settings.autoReroute,
            )

            Text(
                m.s(StrKey.REROUTE_THRESHOLD, (vm.settings.rerouteSpeedRatio * 100).roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = vm.settings.rerouteSpeedRatio.toFloat(),
                valueRange = 0.1f..0.8f,
                onValueChange = { v ->
                    vm.updateSettings { rerouteSpeedRatio = v.toDouble() }
                    // 标签显示百分数 → 每跨 1% 轻震一下
                    ratioTick.flow((v * 100f).roundToInt())
                },
                enabled = vm.settings.autoReroute,
            )

            Text(
                m.s(StrKey.REROUTE_COOLDOWN, "%.0f".format(vm.settings.rerouteCooldownSec)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = vm.settings.rerouteCooldownSec.toFloat(),
                valueRange = 3f..30f,
                onValueChange = { v ->
                    vm.updateSettings { rerouteCooldownSec = v.toDouble() }
                    cooldownTick.flow(v.roundToInt())
                },
                enabled = vm.settings.autoReroute,
            )

            HorizontalDivider()

            // 实时统计（不占用主界面空间）
            Text(m.s(StrKey.SECTION_STATS), style = MaterialTheme.typography.titleSmall)
            val stats = frame?.stats
            Text(
                if (stats == null) m.s(StrKey.STATS_NONE)
                else m.s(
                    StrKey.STATS_BODY,
                    stats.active, stats.avgSpeedKmh.roundToInt(),
                    stats.spawned, stats.arrived, stats.reroutes, stats.laneChanges,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = pal.sub,
            )

            HorizontalDivider()

            // 危险/重置操作（图标 + 文字，方便识别）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { vm.resetSimulationFromButton() },
                    label = { Text(m.s(StrKey.ACTION_RESET)) },
                    leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                )
                AssistChip(
                    onClick = { vm.requestClear() },
                    label = { Text(m.s(StrKey.ACTION_CLEAR)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
