package com.keeprolling.app.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡片的**柔和投影**（本轮需求 2：信息框四角的阴影是直角，和卡片的圆角对不上）。
 *
 * ## 为什么不用 `Modifier.shadow`
 * `Modifier.shadow(elevation, shape)` 走的是图形层的**硬件投影**：它按节点的轮廓生成阴影，
 * 而我们的卡片都住在 [EdgeSlideIn] 里 —— 那个父图层带 `alpha` 动画。硬件投影在带透明度的
 * 图层里会被按**矩形**栅格化（投射层的边界），于是卡片是圆角的、影子却是直角的四方形，
 * 四个角上就露出四个小尖角。
 *
 * ## 现在的做法
 * 自己画：把卡片形状的轮廓**向外扩几圈**、每圈更淡一点，叠出"边缘柔化"的错觉。
 *  - 形状用的是**同一个** [shape]，所以圆角天然一致，不可能对不上；
 *  - 四层同心 + 递增膨胀 ≈ 一层高斯模糊（尺寸小的时候肉眼分不出差别）；
 *  - 只画在最外层（`drawBehind`），完全不参与布局，也不影响点击。
 *
 * @param shape 卡片本身的形状（**必须**和卡片的 background 用同一个，否则又会不一致）
 * @param dy    整体下移量：光从左上方来，影子略偏右下才自然
 */
fun Modifier.cardShadow(
    shape: Shape,
    dy: Dp = 2.5.dp,
    color: Color = Color(0xFF15181C),
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val rr = (outline as? Outline.Rounded)?.roundRect ?: return@drawBehind

    // 越往外越淡：四圈叠起来就是一层柔和的边缘
    val spread = floatArrayOf(0f, 2.0f, 4.4f, 7.2f)
    val alpha = floatArrayOf(0.090f, 0.052f, 0.028f, 0.014f)
    val dyPx = dy.toPx()

    translate(0f, dyPx) {
        for (i in spread.indices) {
            val s = spread[i]
            // 半径也跟着膨胀，否则外圈会出现"圆角比内圈更小"的错位感
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = rr.left - s,
                        top = rr.top - s,
                        right = rr.right + s,
                        bottom = rr.bottom + s,
                        topLeftCornerRadius = CornerRadius(rr.topLeftCornerRadius.x + s),
                        topRightCornerRadius = CornerRadius(rr.topRightCornerRadius.x + s),
                        bottomRightCornerRadius = CornerRadius(rr.bottomRightCornerRadius.x + s),
                        bottomLeftCornerRadius = CornerRadius(rr.bottomLeftCornerRadius.x + s),
                    ),
                )
            }
            drawPath(path, color.copy(alpha = alpha[i]))
        }
    }
}
