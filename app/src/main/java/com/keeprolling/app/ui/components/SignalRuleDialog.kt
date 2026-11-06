package com.keeprolling.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.SimulatorViewModel

private val RULE_ACCENT = Color(0xFF534AB7)

/**
 * **红绿灯设置**对话框（本轮需求 1）。
 *
 * 入口：编辑模式里点中某个路口的红绿灯 → 左侧「删除」按钮**上方**新出现的设置按钮。
 * 里面只有一件事：给这个路口挑一条运行规则。
 *
 * | 规则 | 放行方式 | 每状态绿灯 |
 * |---|---|---|
 * | 一 | 一个方向"直行 + 左转"一起放行，两个方向交替 | 35 秒 |
 * | 二 | 直行 / 左转分开放行，四个状态轮转 | 20 秒 |
 * | 三 | 一次只放一个方向（上 / 下 / 左 / 右） | 20 秒 |
 *
 * 三种规则下**右转都无需等灯**（文案里已写明，避免用户以为漏配了右转相位）。
 *
 * 交互与「出入口设置」一致：改动只存在本地草稿 [picked]，点「勾」才写回 ViewModel，
 * 点「叉」/ 点空白处 / 返回键 = 放弃。
 */
@Composable
fun SignalRuleDialog(vm: SimulatorViewModel, nodeId: Long, onDismiss: () -> Unit) {
    val m = LocalMessages.current
    var picked by remember(nodeId) { mutableStateOf(vm.signalRuleOf(nodeId)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 需求 5：标题固定为「红绿灯设置」，不再拼接路口信息
        title = { Text(m.s(StrKey.SIGNAL_RULE_TITLE)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (rule in 1..3) {
                    RuleOption(
                        name = m.s(nameKeyOf(rule)),
                        desc = m.s(descKeyOf(rule)),
                        selected = picked == rule,
                        onClick = { picked = rule },
                    )
                }
            }
        },
        confirmButton = {
            // 与路口对话框同一套：缩放 + 轻震，不加圆环（全场只留开始 / 暂停那一圈）
            PressIconButton(
                icon = Icons.Filled.Check,
                contentDescription = m.s(StrKey.DESC_APPLY),
                tint = RULE_ACCENT,
                onClick = { vm.applySignalRule(nodeId, picked) },
            )
        },
        dismissButton = {
            PressIconButton(
                icon = Icons.Filled.Close,
                contentDescription = m.s(StrKey.DESC_DISCARD),
                tint = Color(0xFFA32D2D),
                onClick = onDismiss,
            )
        },
    )
}

/** 一条规则卡片：名字 + 状态说明；选中时底色变浅紫、边框变紫，右侧出现勾 */
@Composable
private fun RuleOption(
    name: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 需求 2：选项卡片也跟昼夜走（白天白底浅紫选中，夜间深底紫色描边）
    val pal = LocalUiPalette.current
    val bg = if (selected) lerp(pal.panel, RULE_ACCENT, 0.16f) else pal.panel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) RULE_ACCENT else pal.divider,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) RULE_ACCENT else pal.ink,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = pal.sub,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = RULE_ACCENT,
                modifier = Modifier.padding(start = 6.dp).size(18.dp),
            )
        }
    }
}

/** 规则序号 → 名字文案键 */
private fun nameKeyOf(rule: Int): StrKey = when (rule) {
    2 -> StrKey.SIGNAL_RULE_2_NAME
    3 -> StrKey.SIGNAL_RULE_3_NAME
    else -> StrKey.SIGNAL_RULE_1_NAME
}

/** 规则序号 → 状态说明文案键 */
private fun descKeyOf(rule: Int): StrKey = when (rule) {
    2 -> StrKey.SIGNAL_RULE_2_DESC
    3 -> StrKey.SIGNAL_RULE_3_DESC
    else -> StrKey.SIGNAL_RULE_1_DESC
}
