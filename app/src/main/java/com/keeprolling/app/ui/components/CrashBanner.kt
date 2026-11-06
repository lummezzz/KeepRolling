package com.keeprolling.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.SimulatorViewModel

/**
 * 崩溃提示横幅（v2 改为**非遮挡**形式）：
 * 底部红色胶囊，不挡画面，用户能看清是哪个路口堵住了（画布上会有红色脉动圈）。
 * 点击横幅上的铅笔图标 → 回到编辑模式改造路网。
 */
@Composable
fun CrashBanner(vm: SimulatorViewModel, junctionLabel: String, modifier: Modifier = Modifier) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current
    // 夜间用深红底 + 浅红字：保留"危险"的语义色，但不会在深色画面上炸出一块粉白
    val bg = lerp(Color(0xFFFCEBEB), Color(0xFF3A2326), pal.night)
    val ink = lerp(Color(0xFFA32D2D), Color(0xFFF3A9A4), pal.night)
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .clickable(onClick = vm::backToEditAfterCrash),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = m.s(StrKey.CRASH_BANNER, junctionLabel),
            color = ink,
            style = MaterialTheme.typography.bodyMedium,
        )
        Icon(Icons.Filled.Edit, contentDescription = m.s(StrKey.DESC_BACK_EDIT), tint = ink)
    }
}
