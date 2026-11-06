package com.keeprolling.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义「道路工具」图标（需求 4）：
 *  - [RoadLineIcon] / [RoadLine2Icon] / [RoadLine3Icon]：**1 / 2 / 3 根倾斜线段** = 直线 单/双/三 车道
 *  - [RoadCurveIcon] / [RoadCurve2Icon] / [RoadCurve3Icon]：**1 / 2 / 3 段四分之一圆弧** = 曲线 单/双/三 车道
 *
 * 设计：
 *  - 多车道的"根数"直接表达车道数，**不再用右下角 x2/x3 角标**（需求 4：图标本身自解释）。
 *  - 直线多车道 = 把基准斜线段沿**法线方向**平移复制（平行斜线）；
 *    曲线多车道 = 以同一圆心做**同心圆弧**（半径依次增减）→ 天然平行。
 *  - 颜色统一用黑色描边，渲染时由 `Icon` 的 tint 覆盖，因此图标跟随按钮配色。
 */
private const val STROKE_W = 2.4f        // 单车道：稍粗，清晰
private const val STROKE_W_MULTI = 2.0f  // 多车道：稍细，避免挤在一起糊成一片

// 基准直线：左下 → 右上；法线方向 = (1,1)/√2
private const val LINE_X0 = 5.5
private const val LINE_Y0 = 18.5
private const val LINE_X1 = 18.5
private const val LINE_Y1 = 5.5
private const val NORMAL_X = 0.70710678   // 1/√2
private const val NORMAL_Y = 0.70710678

// 基准圆弧：圆心在右下角 (18.5,18.5)，半径 13（从 (5.5,18.5) 拱到 (18.5,5.5)）
private const val ARC_CX = 18.5
private const val ARC_CY = 18.5
private const val ARC_R = 13.0

/** 直线道路：左下 → 右上 的一段倾斜线段 */
val RoadLineIcon: ImageVector by lazy { lineIcon("RoadLine", listOf(0.0), STROKE_W) }

/** 双车道直线：两根平行斜线段 */
val RoadLine2Icon: ImageVector by lazy { lineIcon("RoadLine2", listOf(-3.0, 3.0), STROKE_W_MULTI) }

/** 三车道直线：三根平行斜线段 */
val RoadLine3Icon: ImageVector by lazy { lineIcon("RoadLine3", listOf(-3.8, 0.0, 3.8), STROKE_W_MULTI) }

/**
 * 曲线道路：从下左到上右、朝"左上"拱起的一段四分之一圆弧。
 *
 * 用三次贝塞尔逼近 1/4 圆：控制点 = 端点沿切线外移 0.5523 × 半径。
 * （标准圆弧→贝塞尔近似系数，画出来和真圆弧几乎无差。）
 */
val RoadCurveIcon: ImageVector by lazy { curveIcon("RoadCurve", listOf(ARC_R), STROKE_W) }

/** 双车道曲线：两段同心四分之一圆弧 */
val RoadCurve2Icon: ImageVector by lazy { curveIcon("RoadCurve2", listOf(9.8, 16.2), STROKE_W_MULTI) }

/** 三车道曲线：三段同心四分之一圆弧 */
val RoadCurve3Icon: ImageVector by lazy { curveIcon("RoadCurve3", listOf(8.4, 13.0, 17.6), STROKE_W_MULTI) }

/**
 * 按 [DrawTool] 的 (曲线?, 车道数) 取对应图标。
 * UI 里两处（底部「道路」按钮、左侧工具列表）共用，保证图标口径一致。
 */
fun roadIconFor(curve: Boolean, lanes: Int): ImageVector = when {
    !curve && lanes <= 1 -> RoadLineIcon
    !curve && lanes == 2 -> RoadLine2Icon
    !curve -> RoadLine3Icon
    lanes <= 1 -> RoadCurveIcon
    lanes == 2 -> RoadCurve2Icon
    else -> RoadCurve3Icon
}

/** 直线图标：把基准斜线段沿法线方向按 [offsets] 平移复制（0 = 基准线） */
private fun lineIcon(name: String, offsets: List<Double>, strokeW: Float): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeW,
            strokeLineCap = StrokeCap.Round,
        ) {
            for (o in offsets) {
                val dx = o * NORMAL_X
                val dy = o * NORMAL_Y
                moveTo((LINE_X0 + dx).toFloat(), (LINE_Y0 + dy).toFloat())
                lineTo((LINE_X1 + dx).toFloat(), (LINE_Y1 + dy).toFloat())
            }
        }
    }.build()

/** 曲线图标：以同一圆心画 [radii] 指定的多段同心四分之一圆弧 */
private fun curveIcon(name: String, radii: List<Double>, strokeW: Float): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeW,
            strokeLineCap = StrokeCap.Round,
        ) {
            for (r in radii) {
                val k = 0.55228475 * r   // 圆弧→贝塞尔近似系数
                moveTo((ARC_CX - r).toFloat(), ARC_CY.toFloat())
                curveTo(
                    (ARC_CX - r).toFloat(), (ARC_CY - k).toFloat(),
                    (ARC_CX - k).toFloat(), (ARC_CY - r).toFloat(),
                    ARC_CX.toFloat(), (ARC_CY - r).toFloat(),
                )
            }
        }
    }.build()
