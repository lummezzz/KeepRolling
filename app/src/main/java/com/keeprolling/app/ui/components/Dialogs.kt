package com.keeprolling.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.keeprolling.app.storage.SavedNetwork
import com.keeprolling.app.ui.LocalUiPalette
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.SimulatorViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * # 存档弹窗（第 58 轮重做）
 *
 * 三个界面共用一套"大卡片"骨架：
 * ```
 *  ┌──────────────────────────┐
 *  │  缩略图（贴边，铺满上部） │  ← 一点 padding 都不给
 *  ├──────────────────────────┤
 *  │  名称 / 时间 / 按钮        │
 *  │  最下方一行小字（日期或警告）│
 *  └──────────────────────────┘
 * ```
 * 尺寸按屏幕比例给（[SHEET_W] × [SHEET_H]），三个弹窗**完全一致** —— 需求里
 * "尺寸与保存对话框一致"就是靠它们共用 [BigDialog] 保证的，而不是各写一遍再对齐。
 *
 * ## 为什么要自己写 `Dialog` 的骨架
 *  - Material3 的 `AlertDialog` 宽度由平台默认值定（约 280~320dp），塞不下缩略图；
 *  - 需要"缩略图贴边"—— `AlertDialog` 自带 24dp 内边距，无论如何都贴不到边；
 *  - `usePlatformDefaultWidth = false` 之后窗口铺满，再用 [Box] 居中一张 96%×92% 的卡片。
 *
 * ## 键盘
 * `decorFitsSystemWindows = false` + `imePadding()`：保存弹窗的输入框在**下半部分**，
 * 键盘一弹就会盖住它。让系统不要替我们缩窗口，改由 `imePadding` 把整张卡片顶上去 ——
 * 缩略图是 `weight(1f)`，它自己会变矮，输入框和按钮永远留在键盘上方。
 */

/** 卡片占屏幕宽 / 高的比例：四周留一圈，看得出是"弹层"而不是新页面 */
private const val SHEET_W = 0.96f
private const val SHEET_H = 0.92f
private val SHEET_SHAPE = RoundedCornerShape(26.dp)

/** 深红：与「清空 / 删除」同一族，表示"这一步会丢掉东西" */
private val DANGER_INK = Color(0xFFA32D2D)

/** 存档面板里用的时间格式：精确到分钟就够了，缩略图卡片上放不下秒 */
private fun formatStamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * 三个存档弹窗的公共骨架：铺满窗口的透明层 + 居中的大圆角卡片。
 *
 * 内容按 [ColumnScope] 给出去，调用方一般这样排：
 * `缩略图(weight(1f))` → 信息区（固定高度）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BigDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalUiPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,   // 宽度不再受平台默认值限制
            // 让窗口不要自己"替我们"处理系统栏与键盘：内边距全部交给下面的
            // systemBarsPadding / imePadding，键盘弹起时整张卡片被顶上去，
            // 缩略图（weight 1f）自己变矮，输入框永远留在键盘上方。
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(SHEET_W).fillMaxHeight(SHEET_H),
                shape = SHEET_SHAPE,
                color = pal.panelSoft,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

// ==================== 保存 ====================

/**
 * 保存路网：**上部整块缩略图（贴边）+ 下部命名 + 最底部一行当前时间**。
 *
 * 缩略图在弹窗打开时**只算一次**（`remember`）：弹窗开着的时候路网不会被编辑
 * （编辑手势被弹窗的遮罩挡住了），没必要每次重组都重新采样几百条折线。
 */
@Composable
fun SaveNetworkDialog(vm: SimulatorViewModel, onDismiss: () -> Unit) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current

    // 弹窗打开的那一刻定死时间：底部那行"当前日期和时间"和默认名字用同一个时刻，
    // 不会出现"名字里是 10:59、底部写 11:00"这种错位。
    val nowMillis = remember { System.currentTimeMillis() }
    val stamp = remember(nowMillis) { formatStamp(nowMillis) }
    var name by remember { mutableStateOf(m.s(StrKey.SAVE_DEFAULT_NAME, stamp)) }
    val thumb = remember { buildNetworkThumb(vm.network) }

    BigDialog(onDismiss) {
        // ① 上部：缩略图，**贴着对话框左右上三边**（所以这里一个 padding 都不加）
        NetworkThumbnail(
            thumb = thumb,
            emptyLabel = m.s(StrKey.SAVE_EMPTY_THUMB),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        // ② 下部：名称 + 按钮 + 时间
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        ) {
            Text(
                text = m.s(StrKey.SAVE_TITLE),
                color = pal.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(m.s(StrKey.SAVE_NAME)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            // 叉在左、勾在右（与"取消在左、确认在右"的阅读顺序一致）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PressIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = m.s(StrKey.DESC_CANCEL),
                    tint = pal.muted,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(6.dp))
                PressIconButton(
                    icon = Icons.Filled.Check,
                    contentDescription = m.s(StrKey.DESC_SAVE),
                    tint = Color(0xFF1D9E75),
                    onClick = {
                        // 只有**真的存下去了**才关弹窗：名字为空 / 写盘失败时留在原地，
                        // 用户改一个字就能重试（旧实现无论成败都关，等于把输入白扔了）
                        if (vm.saveCurrent(name)) onDismiss()
                    },
                )
            }
            // ③ 最下方：一行小字，当前日期和时间
            Text(
                text = stamp,
                color = pal.muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

// ==================== 加载 ====================

/**
 * 加载路网：**海报墙**（每行两张，可上下滑）。
 *
 * 点某张海报 → 再叠一层 [LoadConfirmDialog]（尺寸与保存弹窗完全一致）。
 * 用两层 `Dialog` 而不是在同一张卡片里换内容，是为了"取消"能自然回到海报墙 ——
 * 看错了一张、退回来再挑，不用重新点一次加载按钮。
 */
@Composable
fun LoadNetworkDialog(vm: SimulatorViewModel, onDismiss: () -> Unit) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current
    // 读盘 + 反序列化只在打开时做一次（几十个存档也只是几十毫秒）。
    // 用 `mutableStateOf` 而不是普通的 `remember { }`：删掉一个之后列表要立刻刷新，
    // 而重读一遍硬盘纯属浪费 —— 内存里这份就是最新的。
    var saves by remember { mutableStateOf(vm.listSaved()) }
    var picked by remember { mutableStateOf<SavedNetwork?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedNetwork?>(null) }

    BigDialog(onDismiss) {
        // 顶部：标题（带数量）+ 关闭。海报墙自己不需要标题，但没有关闭按钮就没法退出。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = m.s(StrKey.LOAD_TITLE, saves.size),
                color = pal.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            PressIconButton(
                icon = Icons.Filled.Close,
                contentDescription = m.s(StrKey.DESC_CLOSE),
                tint = pal.muted,
                onClick = onDismiss,
            )
        }

        if (saves.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = m.s(StrKey.LOAD_EMPTY),
                    color = pal.muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(saves, key = { it.name }) { saved ->
                    PosterCard(
                        saved = saved,
                        onClick = { picked = saved },
                        onDelete = { pendingDelete = saved },
                    )
                }
            }
        }
    }

    // 第二层：点开某张海报后的确认面板。
    val sel = picked
    if (sel != null) {
        LoadConfirmDialog(
            vm = vm,
            saved = sel,
            onCancel = { picked = null },
            onLoaded = onDismiss,
        )
    }

    // 删除存档的二次确认。旧版加载列表里每行都有个垃圾桶；改成海报墙之后，
    // 删除缩成海报右上角一个小圆钮（不点它就不会误触），确认仍然走「破坏性操作」的
    // 文字按钮 —— 一按就永久删除、没有撤销，必须拦一下。
    val del = pendingDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(m.s(StrKey.DELETE_SAVE_TITLE)) },
            text = { Text(m.s(StrKey.DELETE_SAVE_BODY, del.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSaved(del.name)
                    saves = saves.filterNot { it.name == del.name }
                    pendingDelete = null
                }) {
                    Text(m.s(StrKey.DESC_DELETE), color = DANGER_INK)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(m.s(StrKey.DESC_CANCEL), color = pal.muted)
                }
            },
        )
    }
}

/**
 * 一张"海报"：缩略图 + 名称 + 保存时间，右上角挂一个删除小圆钮。
 *
 * 缩略图用 4:3 —— 路网大多是横着铺开的，太高会把左右两侧压扁留出大片空白；
 * 4:3 既够看形状，一行两张又刚好放得下。
 *
 * @param onDelete 点右上角垃圾桶。做成**独立的点击区域**而不是长按：
 *                 长按没有任何视觉提示，发现不了；圆钮在角落，误触点到的概率又很低。
 */
@Composable
private fun PosterCard(
    saved: SavedNetwork,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val pal = LocalUiPalette.current
    val press = rememberPressFeedback()
    // 每张海报各算各的：加载面板一屏六张，key 绑在"哪个存档"上，
    // 滚动回来时会命中 remember 的缓存，不会重新采样
    val thumb = remember(saved.name, saved.savedAtMillis) { buildNetworkThumb(saved.network) }
    val stamp = remember(saved.savedAtMillis) { formatStamp(saved.savedAtMillis) }

    Column(
        modifier = Modifier
            // pressEffect 必须在 background 之前（见 PressFeedback.kt）：缩放走图层，不重排
            .pressEffect(press)
            .clip(RoundedCornerShape(16.dp))
            .background(pal.panel)
            .clickable(interactionSource = press.source, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
            NetworkThumbnail(thumb = thumb, modifier = Modifier.fillMaxSize())
            // 角标钮：这一层自己的 clickable 会吃掉按下事件，不会连带触发整张海报的点击
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                DeleteChip(onClick = onDelete)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(
                text = saved.name,
                color = pal.ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stamp,
                color = pal.muted,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 海报右上角的删除小圆钮：小、半透明黑底、白色垃圾桶 —— 看得见但不抢戏 */
@Composable
private fun DeleteChip(onClick: () -> Unit) {
    val press = rememberPressFeedback()
    Box(
        modifier = Modifier
            .size(26.dp)
            .pressEffect(press)
            .background(Color.Black.copy(alpha = 0.42f), CircleShape)
            .clickable(interactionSource = press.source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * 加载确认：**上部整块缩略图 + 中部名称与时间 + 最下方深红加粗警告**。
 *
 * 布局按需求的原话来排：
 * ```
 *  缩略图
 *  名称              [叉] [勾]
 *  2026-11-05 00:32
 *  加载会放弃目前正在使用的路网，确认吗      ← 深红加粗，最下方
 * ```
 * 按钮放在"名称 / 时间"这一行的右侧（而不是另起一行）：这样最后一行真的就是警告，
 * 同时按钮仍然在拇指够得着的中段，不会掉到屏幕最底下。
 */
@Composable
private fun LoadConfirmDialog(
    vm: SimulatorViewModel,
    saved: SavedNetwork,
    onCancel: () -> Unit,
    onLoaded: () -> Unit,
) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current
    val thumb = remember(saved.name, saved.savedAtMillis) { buildNetworkThumb(saved.network) }
    val stamp = remember(saved.savedAtMillis) { formatStamp(saved.savedAtMillis) }

    BigDialog(onCancel) {
        NetworkThumbnail(
            thumb = thumb,
            emptyLabel = m.s(StrKey.SAVE_EMPTY_THUMB),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = saved.name,
                        color = pal.ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stamp,
                        color = pal.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                PressIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = m.s(StrKey.DESC_CANCEL),
                    tint = pal.muted,
                    onClick = onCancel,
                )
                Spacer(Modifier.width(4.dp))
                PressIconButton(
                    icon = Icons.Filled.Check,
                    contentDescription = m.s(StrKey.DESC_LOAD),
                    tint = Color(0xFF1D9E75),
                    onClick = {
                        vm.loadNetwork(saved.name)
                        onLoaded()
                    },
                )
            }
            // 最下方：破坏性提示。深红 + 加粗 + 居中，扫一眼就知道"这一步不可逆"
            Text(
                text = m.s(StrKey.LOAD_WARN),
                color = DANGER_INK,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
            )
        }
    }
}

// ==================== 清空 ====================

/**
 * 清空路网的二次确认。
 * 破坏性操作，所以用**文字按钮**（"清空 / 取消"）而不是图标，避免误触。
 */
@Composable
fun ClearConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val m = LocalMessages.current
    val pal = LocalUiPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.s(StrKey.CLEAR_TITLE)) },
        text = { Text(m.s(StrKey.CLEAR_BODY)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(m.s(StrKey.CLEAR_CONFIRM), color = DANGER_INK) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(m.s(StrKey.DESC_CANCEL), color = pal.muted) }
        },
    )
}
