package com.keeprolling.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 音频相关的 7 个手绘矢量图标。
 *
 * 为什么自绘而不用 `Icons.Filled.VolumeUp` / `MusicNote`：和 [NightModeIcon] 一个理由 ——
 * 项目里所有非常用图标都是手绘的，**不依赖图标库版本**（改名 / 拆包会直接编译失败）。
 * 一条 path 永远不会过期。
 *
 * ## 七个图标的分工（必须一眼分清，它们在屏幕上挨着）
 *  - [AudioMasterIcon]：**调音台**（三条推子）→ "这里是音频总开关"，管音乐 + 音效两路；
 *  - [PrevTrackIcon] / [NextTrackIcon]：**|◀ 与 ▶|** → 换上一首 / 下一首；
 *  - [MusicNoteIcon]：**双联音符** → 只管音乐；
 *  - [SpeakerIcon]：**喇叭 + 声波** → 只管音效；
 *  - [HapticIcon]：**手机 + 两侧震动弧** → 只管"按下轻震"。
 *
 * 如果总开关也用喇叭，它和它展开出来的"音效喇叭"就长得一模一样，
 * 用户会以为点了同一个东西两次。同理，震动**不能**画成三条竖线（那是调音台）。
 */

/**
 * 「音频」主按钮图标：三条竖向推子 + 各自的旋钮，即调音台（equalizer）。
 *
 * 结构：3 条 x = 6.4 / 12.0 / 17.6 的竖线（贯穿 3.2~20.8）+ 3 个半径 2.8 的实心旋钮，
 * 旋钮高度刻意错开（8.6 / 15.4 / 6.4），一眼就是"可调"而不是"电线"。
 */
val AudioMasterIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AudioMaster",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 三条轨道
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(6.4f, 3.2f); lineTo(6.4f, 20.8f)
            moveTo(12.0f, 3.2f); lineTo(12.0f, 20.8f)
            moveTo(17.6f, 3.2f); lineTo(17.6f, 20.8f)
        }
        // 三个旋钮（90° 四段弧拼整圆，与 SunIcon 同样的稳妥画法）
        path(fill = SolidColor(Color.Black)) {
            circlePath(6.4f, 8.6f, 2.8f)
            circlePath(12.0f, 15.4f, 2.8f)
            circlePath(17.6f, 6.4f, 2.8f)
        }
    }.build()
}

/**
 * 「全部静音」时的主按钮图标：调音台 + 一条 45° 斜杠。
 *
 * 斜杠从左下划到右上（与 [HideUiIcon] 的习惯一致）；画在最上层，
 * 所以它会盖住旋钮 —— 这正是"整个关掉"该有的样子。
 */
val AudioMutedIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AudioMuted",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(6.4f, 3.2f); lineTo(6.4f, 20.8f)
            moveTo(12.0f, 3.2f); lineTo(12.0f, 20.8f)
            moveTo(17.6f, 3.2f); lineTo(17.6f, 20.8f)
        }
        path(fill = SolidColor(Color.Black)) {
            circlePath(6.4f, 8.6f, 2.8f)
            circlePath(12.0f, 15.4f, 2.8f)
            circlePath(17.6f, 6.4f, 2.8f)
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.1f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(3.6f, 20.4f)
            lineTo(20.4f, 3.6f)
        }
    }.build()
}

/**
 * 「音乐」开关图标：双联八分音符（两个符头 + 两根符干 + 一条横梁）。
 *
 * 为什么是双联而不是单个音符：单音符在小尺寸下只剩"一个圆点加一根棍"，
 * 容易被当成"气球"或"图钉"；加了横梁之后形状独特，20dp 也能认出来。
 */
val MusicNoteIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MusicNote",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 符干 + 横梁（一笔画完，避免两条线在接缝处露出缺口）
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(8.6f, 15.6f)      // 左符干底
            lineTo(8.6f, 6.6f)       // 上到横梁左端
            lineTo(18.4f, 4.8f)      // 横梁（略斜，像真的谱面）
            lineTo(18.4f, 13.8f)     // 右符干底
        }
        // 两个符头
        path(fill = SolidColor(Color.Black)) {
            circlePath(5.9f, 16.5f, 2.9f)
            circlePath(15.7f, 14.7f, 2.9f)
        }
    }.build()
}

/**
 * 「上一首」图标：`|◀` —— 左边一根竖条（挡板）+ 一个指向左的实心三角。
 *
 * 与 [NextTrackIcon] 严格镜像（x 坐标关于 12 对称、y 完全一致），
 * 两个并排时视觉重量才是平衡的。
 *
 * 三角用实心而不是描边：20dp 下描边三角只剩"一个圈套一个圈"，认不出方向。
 */
val PrevTrackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PrevTrack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 挡板
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(5.4f, 6.2f)
            lineTo(5.4f, 17.8f)
        }
        // 指向左的三角
        path(fill = SolidColor(Color.Black)) {
            moveTo(18.6f, 5.8f)
            lineTo(18.6f, 18.2f)
            lineTo(8.0f, 12.0f)
            close()
        }
    }.build()
}

/**
 * 「下一首」图标：`▶|` —— [PrevTrackIcon] 的左右镜像。
 */
val NextTrackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NextTrack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 挡板
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(18.6f, 6.2f)
            lineTo(18.6f, 17.8f)
        }
        // 指向右的三角
        path(fill = SolidColor(Color.Black)) {
            moveTo(5.4f, 5.8f)
            lineTo(5.4f, 18.2f)
            lineTo(16.0f, 12.0f)
            close()
        }
    }.build()
}

/**
 * 「音效」开关图标：喇叭 + 两道声波。
 *
 * 喇叭用**梯形 + 矩形**围成（斜边在右侧，指向上/下张开），实心；
 * 声波用两条同心二次曲线，从近到远、只画到 15.0 / 17.6 的弧度，
 * 太满会糊成一团黑。
 */
val SpeakerIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Speaker",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 箱体 + 喇叭口（一条闭合路径）
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.4f, 9.4f)
            lineTo(7.8f, 9.4f)
            lineTo(12.6f, 5.0f)
            lineTo(12.6f, 19.0f)
            lineTo(7.8f, 14.6f)
            lineTo(3.4f, 14.6f)
            close()
        }
        // 两道声波
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(15.0f, 9.2f)
            quadTo(17.2f, 12.0f, 15.0f, 14.8f)
            moveTo(17.6f, 6.8f)
            quadTo(21.4f, 12.0f, 17.6f, 17.2f)
        }
    }.build()
}

/**
 * 「轻震」开关图标：手机 + 两侧震动弧。
 *
 * 手机用**一条闭合描边**画（四角各自 quadTo 收圆），两侧各一条向外鼓的二次曲线。
 * 为什么不用"三条竖线 + 上下箭头"那种经典震动图标：那个轮廓和 [AudioMasterIcon]
 * 的调音台几乎一样，而这两个按钮在屏幕上只隔一个「音效」。手机机身 + 外弧是唯一的
 * 既能表达"震"、又和推子不撞的选择。
 */
val HapticIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Haptic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 两侧震动弧（先画，被机身压住一点点正好）
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(5.4f, 9.4f)
            quadTo(4.0f, 12.0f, 5.4f, 14.6f)
            moveTo(18.6f, 9.4f)
            quadTo(20.0f, 12.0f, 18.6f, 14.6f)
        }
        // 手机机身（一条闭合描边）
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(9.4f, 3.4f)
            lineTo(14.6f, 3.4f)
            quadTo(16.2f, 3.4f, 16.2f, 5.0f)
            lineTo(16.2f, 19.0f)
            quadTo(16.2f, 20.6f, 14.6f, 20.6f)
            lineTo(9.4f, 20.6f)
            quadTo(7.8f, 20.6f, 7.8f, 19.0f)
            lineTo(7.8f, 5.0f)
            quadTo(7.8f, 3.4f, 9.4f, 3.4f)
            close()
        }
    }.build()
}

/**
 * 在 [androidx.compose.ui.graphics.vector.PathBuilder] 里画一个整圆。
 *
 * 用**四段 90° 弧**而不是两段半圆：半圆的弦长恰好等于直径，圆弧方程有**无穷多解**，
 * 不同渲染实现可能挑到不同的解（甚至退化成直线）。四段 90° 的弦长 = r·√2 < 2r，
 * 解唯一，画出来一定是圆。见 [SunIcon] 里同样的说明。
 */
private fun PathBuilder.circlePath(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx, y1 = cy + r)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx, y1 = cy - r)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
    close()
}
