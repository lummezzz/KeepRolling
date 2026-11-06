package com.keeprolling.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义「昼夜」图标（本轮需求 2）。
 *
 * 为什么自绘而不是用 `Icons.Filled.DarkMode`：项目里已经有一整套手绘矢量图标
 * （见 [RoadLineIcon] 等同文件），自绘可以保证**不依赖图标库版本**——
 * 万一某个版本改名或多包冲突，编译期就炸了；而一条 path 永远不会过期。
 *
 * 形状：一弯新月 —— 外弧是左侧的大半圆（半径 9，以竖弦为直径），
 * 内弧用更大的半径（12）向**同一侧**鼓，两弧之间就夹出月牙。
 * 两个 arcTo 的 sweep 方向相反，才能围出一块面积（同向会退化成一个"弓形"）。
 */
val NightModeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "NightMode",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.4f, 3.0f)
            // 外弧：上 → 向下绕左侧回到下端点。
            // 半径 9.05 略大于弦长的一半（9.0）——刻意避开"半径恰好等于半弦"这种退化情形，
            // 让这条弧有唯一解，不会因不同渲染实现而偏向另一侧。
            arcTo(
                horizontalEllipseRadius = 9.05f,
                verticalEllipseRadius = 9.05f,
                theta = 0f,
                isMoreThanHalf = false,
                isPositiveArc = false,
                x1 = 12.4f,
                y1 = 21.0f,
            )
            // 内弧：下 → 向上绕回起点，半径更大 → 与外侧弧之间留下月牙
            arcTo(
                horizontalEllipseRadius = 12.0f,
                verticalEllipseRadius = 12.0f,
                theta = 0f,
                isMoreThanHalf = false,
                isPositiveArc = true,
                x1 = 12.4f,
                y1 = 3.0f,
            )
            close()
        }
    }.build()
}

/**
 * 「白天」图标（本轮需求 1）：实心太阳 —— 一个圆盘 + 8 条放射光线。
 *
 * 和 [NightModeIcon] 在形状上刻意做成一对反义：那边是**缺了一角的月牙**，
 * 这边是**饱满的圆 + 四散的光**。20dp 下也能一眼分清，不靠颜色。
 *
 * 光线画成 8 段短线（正交 4 条 + 对角线 4 条），线段内端半径 6.8、外端 9.6，
 * 与半径 4.6 的圆盘之间留一圈空隙 —— 贴太近会糊成一个毛球。
 */
val SunIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Sun",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 圆盘：用**四段 90° 弧**拼整圆。
        // 为什么不两段半圆：半圆的弦长恰好等于直径（2r），此时圆弧方程有**无穷多解**，
        // 不同渲染实现可能pick到不同的解（甚至画成一条直线）。四段 90° 的弦长 r·√2 < 2r，
        // 解唯一，任何实现画出来都是同一个圆。
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.6f, 12.0f)     // 右
            arcTo(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12.0f, y1 = 16.6f)
            arcTo(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7.4f, y1 = 12.0f)
            arcTo(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12.0f, y1 = 7.4f)
            arcTo(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16.6f, y1 = 12.0f)
            close()
        }
        // 8 条光线：上 / 下 / 左 / 右 + 四个对角
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12.0f, 2.4f); lineTo(12.0f, 5.2f)      // 上
            moveTo(12.0f, 18.8f); lineTo(12.0f, 21.6f)    // 下
            moveTo(2.4f, 12.0f); lineTo(5.2f, 12.0f)      // 左
            moveTo(18.8f, 12.0f); lineTo(21.6f, 12.0f)    // 右
            moveTo(5.21f, 5.21f); lineTo(7.19f, 7.19f)    // 左上
            moveTo(16.81f, 7.19f); lineTo(18.79f, 5.21f)  // 右上
            moveTo(5.21f, 18.79f); lineTo(7.19f, 16.81f)  // 左下
            moveTo(16.81f, 16.81f); lineTo(18.79f, 18.79f) // 右下
        }
    }.build()
}

/**
 * 「去UI」图标（本轮需求 4）：一只被斜线划掉的眼睛。
 *
 * 语义最直白：把看得见的"界面"从画面上抹掉，只留下画面本身。
 * 和 [NightModeIcon] 一样是手绘矢量，不依赖图标库版本。
 *
 * 结构：杏仁形眼眶（两段二次贝塞尔）+ 实心瞳孔 + 一条 45° 斜线。
 */
val HideUiIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "HideUi",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 眼眶：上、下两条对称的二次曲线
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(2.6f, 12.0f)
            quadTo(12.0f, 4.6f, 21.4f, 12.0f)
            quadTo(12.0f, 19.4f, 2.6f, 12.0f)
            close()
        }
        // 瞳孔（半径 3.05 略大于半弦 3，避开圆弧方程的退化情形）
        path(fill = SolidColor(Color.Black)) {
            moveTo(15.05f, 12.0f)
            arcTo(
                horizontalEllipseRadius = 3.05f,
                verticalEllipseRadius = 3.05f,
                theta = 0f,
                isMoreThanHalf = true,
                isPositiveArc = false,
                x1 = 8.95f,
                y1 = 12.0f,
            )
            arcTo(
                horizontalEllipseRadius = 3.05f,
                verticalEllipseRadius = 3.05f,
                theta = 0f,
                isMoreThanHalf = true,
                isPositiveArc = false,
                x1 = 15.05f,
                y1 = 12.0f,
            )
            close()
        }
        // 斜线：从左下划到右上（与"隐藏"图标一致的视觉惯例）
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.1f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4.2f, 19.8f)
            lineTo(19.8f, 4.2f)
        }
    }.build()
}
