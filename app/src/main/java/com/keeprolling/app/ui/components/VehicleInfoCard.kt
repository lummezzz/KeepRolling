package com.keeprolling.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keeprolling.app.model.FocusRender
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.SimulatorViewModel
import kotlin.math.roundToInt

private val CARD_ACCENT = Color(0xFF534AB7)

/**
 * **聚焦车辆的信息框**（本轮需求 4）：屏幕上方的一块白底卡片。
 *
 * | 行 | 内容 | 说明 |
 * |---|---|---|
 * | 实时速度 | `48 km/h` | 紫色高亮，最容易变化的一行 |
 * | 预设速度 | `60 km/h` | 这辆车的期望速度（由路口速度分布抽样） |
 * | 起始路口 | `路口1` | 生成时所在的路口 |
 * | 目标路口 | `路口3` | 当前目的地（改道后会跟着变） |
 *
 * 交互（全部对应需求原文）：
 *  - **点卡片本体** → [SimulatorViewModel.refocusVehicle]：重新缩放到合适比例并锁定跟随
 *    （用户之前拖开/缩走了，点一下就能回到这辆车身上）；
 *  - **点右上角的叉** → [SimulatorViewModel.clearFocus]：信息框消失，且不再锁定；
 *  - 卡片本身**不参与画布手势**：它是浮在画布之上的一层，点它不会穿透成"点空白处"。
 *
 * 数据源是 [FocusRender]，与车辆位置**同一帧**生成 —— 框里的读数与画面上那辆车
 * 永远是同一时刻的状态，不会出现"车已经停了、速度还写着 40"的错位。
 */
@Composable
fun VehicleInfoCard(
    vm: SimulatorViewModel,
    focus: FocusRender?,
    modifier: Modifier = Modifier,
) {
    if (focus == null) return                      // 车到站 / 被清空 → 整个框自动消失
    val m = LocalMessages.current
    val pal = LocalUiPalette.current

    val empty = m.s(StrKey.VEHICLE_INFO_EMPTY)
    val fromLabel = if (focus.originJunction > 0) vm.junctionLabel(focus.originJunction) else empty
    val toLabel = if (focus.targetJunction > 0) vm.junctionLabel(focus.targetJunction) else empty

    // 卡片形状**只定义一次**：背景用它，圆角不会变。
    //
    // ⚠️ 阴影已**整体移除**（本轮需求 1）。历史：
    //   ① 最早用 `Modifier.shadow` —— 卡片住在 EdgeSlideIn 里（父图层带 alpha 动画），
    //      硬件投影会按**矩形**栅格化，四个角露出直角尖角；
    //   ② 换成自绘 cardShadow 后仍然"看着有直角阴影"（多层外扩在浅色路面上边缘发脏）。
    //   这张卡本来就压在画面上、需要的是"和路面区分开"，而不是"浮起来"——
    //   靠 0.97 不透明度的面板色 + 圆角已经足够，索性把阴影彻底去掉，最干净。
    val shape = remember { RoundedCornerShape(14.dp) }
    Column(
        modifier = modifier
            .width(206.dp)
            .background(pal.panel.copy(alpha = 0.97f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = vm::refocusVehicle,
            )
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // ---- 标题行：左边是标题，右边是叉 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = m.s(StrKey.VEHICLE_INFO_TITLE),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = pal.muted,
            )
            Spacer(Modifier.weight(1f))
            // 叉：只是一块 26dp 的点击区域，视觉上是 15dp 的小图标，不抢戏
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = m.s(StrKey.VEHICLE_INFO_CLOSE),
                tint = pal.muted,
                modifier = Modifier
                    .size(26.dp)
                    // 先铺 26dp 的点击区域，再用内边距把图标收小 → 好点中、不显眼
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = vm::clearFocus,
                    )
                    .padding(5.dp),
            )
        }

        InfoLine(
            label = m.s(StrKey.VEHICLE_INFO_SPEED),
            value = "${focus.speedKmh.roundToInt()} km/h",
            valueColor = CARD_ACCENT,
            labelColor = pal.muted,
        )
        InfoLine(
            label = m.s(StrKey.VEHICLE_INFO_DESIRED),
            value = "${focus.desiredKmh.roundToInt()} km/h",
            valueColor = pal.ink,
            labelColor = pal.muted,
        )
        InfoLine(
            label = m.s(StrKey.VEHICLE_INFO_FROM),
            value = fromLabel,
            valueColor = pal.ink,
            labelColor = pal.muted,
        )
        InfoLine(
            label = m.s(StrKey.VEHICLE_INFO_TO),
            value = toLabel,
            valueColor = pal.ink,
            labelColor = pal.muted,
        )
    }
}

/** 一行"标签 …… 值"：标签在左（弱色、小），值靠右（主色、稍重），固定宽度卡片里对齐得很整齐 */
@Composable
private fun InfoLine(label: String, value: String, valueColor: Color, labelColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}
