package com.keeprolling.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.keeprolling.app.model.VehicleKind
import kotlin.math.max

/**
 * 三种车型的**车身细节**（俯视视角）。
 *
 * 之前三种车只差长度和颜色，缩到常规视图下几乎分不出谁是谁。这里按车型分别画：
 *
 * | 车型 | 车长×车宽 | 画法 |
 * |---|---|---|
 * | 轿车（3.9 × 2.6） | 1.5 : 1 | 座舱玻璃框 + 实心车顶 + 四角灯 + 后视镜 |
 * | 中巴（5.85 × 2.6） | 2.25 : 1 | 方正车身 + 前后大挡风 + 每侧 4 块侧窗 + 车顶通风带 |
 * | 大货（7.8 × 2.6） | 3 : 1 | 前驾驶室 + 中间断开 + 后货箱（带棱线、尾门缝） |
 *
 * **坐标约定**：调用方已经 `rotate` 过了，所以这里全部用"局部坐标"——
 * `+x` 是车头方向，`+y` 是车身右侧；传入的 [center] 是车身几何中心。
 * 所有尺寸都从 `lengthPx / widthPx` 按比例算出来，所以任何缩放下比例都对。
 *
 * **性能**：细节按车宽开关。车宽不足 [DETAIL_MIN_WIDTH_PX] 像素时只画一个圆角矩形
 * （这时一块车窗只有 1 个像素，画了也是噪点，纯浪费）。整段没有任何对象分配，
 * 只有 `drawRoundRect` / `drawLine` 的直接调用。
 */
internal const val DETAIL_MIN_WIDTH_PX = 7f

/**
 * 画一辆车的车身。
 *
 * @param body 车身颜色（已含拥堵染色 + 夜间压暗 + 透明度）
 * @param alpha 车辆整体透明度（到站淡出时 < 1）
 * @param night 0 = 白天，1 = 夜晚：夜里车灯更亮、车窗更暗
 */
internal fun DrawScope.drawVehicleBody(
    kind: VehicleKind,
    center: Offset,
    lengthPx: Float,
    widthPx: Float,
    body: Color,
    alpha: Float,
    night: Float,
) {
    if (lengthPx <= 0f || widthPx <= 0f) return
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.02f) return
    val detail = widthPx >= DETAIL_MIN_WIDTH_PX
    val n = night.coerceIn(0f, 1f)

    when (kind) {
        VehicleKind.CAR -> drawCarBody(center, lengthPx, widthPx, body, a, n, detail)
        VehicleKind.BUS -> drawBusBody(center, lengthPx, widthPx, body, a, n, detail)
        VehicleKind.TRUCK -> drawTruckBody(center, lengthPx, widthPx, body, a, n, detail)
    }
}

// ==================== 车辆投影 ====================

/**
 * 车辆在地面上的**投影**（本轮需求 1：影子随时间转向、变长）。
 *
 * 车不高（1.5~1.9m），所以影子很短 —— 但它必须**和楼、树用同一份光源**：
 * 傍晚整条街的影子都朝东倒，唯独每辆车脚下光亮如新，一眼就看出是假的。
 *
 * 画法是"一块略微缩小的圆角矩形"：车在俯视下就是个圆角矩形，影子不需要仿真车顶细节。
 * 由调用方在画车身**之前**调用，于是车身压住影子根部，只露出周围一圈。
 *
 * @param offsetPx 影子中心相对车身中心的偏移（像素，已含方向与长度）
 * @param alpha    影子浓度（已含昼夜系数与车辆自身淡出透明度）
 */
internal fun DrawScope.drawVehicleShadow(
    center: Offset,
    lengthPx: Float,
    widthPx: Float,
    deg: Float,
    offsetPx: Offset,
    alpha: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.02f || lengthPx <= 0f || widthPx <= 0f) return
    val sc = Offset(center.x + offsetPx.x, center.y + offsetPx.y)
    // 略微缩小：影子不该和车身一样大，否则看起来像"垫了一块深色板"
    val l = lengthPx * 0.96f
    val w = widthPx * 0.92f
    rotate(deg, sc) {
        drawRoundRect(
            color = Color(0xFF20242A).copy(alpha = a),
            topLeft = Offset(sc.x - l / 2f, sc.y - w / 2f),
            size = Size(l, w),
            cornerRadius = CornerRadius(w * 0.45f),
        )
    }
}

// ==================== 轿车 ====================

/**
 * 轿车：3.9m 的小车，俯视看就是「机舱 — 座舱 — 后备厢」三段。
 *
 * 画法：车身圆角矩形 → 一层深色座舱玻璃 → 中间一块亮色车顶。
 * 玻璃露出的一圈就是前后挡风与侧窗，一眼能看出是轿车而不是一块砖。
 */
private fun DrawScope.drawCarBody(
    center: Offset,
    l: Float,
    w: Float,
    body: Color,
    a: Float,
    night: Float,
    detail: Boolean,
) {
    val cx = center.x
    val cy = center.y

    // ① 车身
    box(body, cx, cy, l, w, w * 0.34f)
    if (!detail) return

    val glass = glassOf(body).copy(alpha = 0.90f * a)
    val roof = roofOf(body).copy(alpha = 0.94f * a)

    // ② 座舱玻璃：比车身窄一圈，前后各留出约 1/4 车长给机舱与后备厢
    val cabLen = l * 0.52f
    val cabW = w * 0.84f
    val cabCx = cx - l * 0.02f
    box(glass, cabCx, cy, cabLen, cabW, w * 0.16f)

    // ③ 车顶：玻璃中间的实心顶板（前后各露出一段玻璃 = 前后挡风）
    box(roof, cabCx - l * 0.01f, cy, cabLen * 0.54f, cabW * 0.60f, w * 0.12f)

    // ④ 前后灯
    lamps(cx, cy, l, w, a, night)

    // ⑤ 后视镜：只有画得足够大才加（小图上会变成两个噪点）
    if (l >= 30f) {
        val mw = max(1.2f, l * 0.045f)
        val mh = max(0.9f, w * 0.085f)
        val mirror = edgeOf(body).copy(alpha = 0.80f * a)
        val mx = cx + l * 0.13f
        box(mirror, mx, cy - (w * 0.5f + mh * 0.42f), mw, mh, mh * 0.4f)
        box(mirror, mx, cy + (w * 0.5f + mh * 0.42f), mw, mh, mh * 0.4f)
    }
}

// ==================== 中巴 ====================

/**
 * 中巴：5.85m，比轿车长一半、车身更方（圆角更小）。
 *
 * 特征是**侧窗排** —— 每侧 4 块深色方窗 + 车顶一条浅色通风带，
 * 俯视看过去和轿车（只有一块大玻璃）区分度最高。
 */
private fun DrawScope.drawBusBody(
    center: Offset,
    l: Float,
    w: Float,
    body: Color,
    a: Float,
    night: Float,
    detail: Boolean,
) {
    val cx = center.x
    val cy = center.y

    // ① 车身：方正一点，符合公交 / 中巴的方盒子外形
    box(body, cx, cy, l, w, w * 0.20f)
    if (!detail) return

    val glass = glassOf(body).copy(alpha = 0.90f * a)

    // ② 前后挡风：横贯车头的整块玻璃
    box(glass, cx + l * 0.432f, cy, l * 0.072f, w * 0.78f, w * 0.09f)
    box(glass, cx - l * 0.442f, cy, l * 0.052f, w * 0.70f, w * 0.08f)

    // ③ 侧窗：每侧 4 块（间距按车长均分）
    val winLen = l * 0.112f
    val winH = w * 0.17f
    val rowY = w * 0.355f
    val startX = cx + l * 0.29f
    val step = l * 0.148f
    for (i in 0 until 4) {
        val x = startX - i * step
        box(glass, x, cy - rowY, winLen, winH, winH * 0.35f)
        box(glass, x, cy + rowY, winLen, winH, winH * 0.35f)
    }

    // ④ 车顶通风带：一条沿车长的浅色窄带，把"这是大巴"的顶视图特征点出来
    box(roofOf(body).copy(alpha = 0.50f * a), cx - l * 0.04f, cy, l * 0.44f, w * 0.30f, w * 0.13f)

    // ⑤ 前后灯
    lamps(cx, cy, l, w, a, night)
}

// ==================== 大货车 ====================

/**
 * 大货：7.8m，三段式——前驾驶室 + 中间断开 + 后货箱。
 *
 * 货箱刻意画成偏白灰（集装箱常见色），并加了几道横向棱线；
 * 驾驶室与货箱之间留一条缝，远看就是"车头拉着一节厢"，不会跟中巴混淆。
 */
private fun DrawScope.drawTruckBody(
    center: Offset,
    l: Float,
    w: Float,
    body: Color,
    a: Float,
    night: Float,
    detail: Boolean,
) {
    val cx = center.x
    val cy = center.y
    val edge = edgeOf(body)

    // ① 货箱：车身后 60%，偏白灰的厢体（比驾驶室更浅，形成明暗对比）
    val boxLen = l * 0.60f
    val boxCx = cx - l * 0.19f
    val cargoColor = lerp(body, Color(0xFFD8D5CC), 0.45f).copy(alpha = a)
    box(cargoColor, boxCx, cy, boxLen, w * 0.98f, w * 0.10f)
    if (detail) {
        // 厢体轮廓：让货箱与驾驶室在浅色路面上也能分开
        boxOutline(edge.copy(alpha = 0.26f * a), boxCx, cy, boxLen, w * 0.98f, w * 0.10f, 1f)
        // 横向棱线（集装箱的加强筋）
        val ribW = max(0.7f, w * 0.030f)
        val ribHalf = w * 0.44f
        val left = boxCx - boxLen * 0.5f + boxLen * 0.14f
        for (i in 0..3) {
            val x = left + i * (boxLen * 0.245f)
            drawLine(
                color = edge.copy(alpha = 0.24f * a),
                start = Offset(x, cy - ribHalf),
                end = Offset(x, cy + ribHalf),
                strokeWidth = ribW,
            )
        }
        // 尾门缝：车尾往前一点的一道短竖线，暗示"这是能打开的厢门"
        val seamX = boxCx - boxLen * 0.5f + boxLen * 0.07f
        drawLine(
            color = edge.copy(alpha = 0.30f * a),
            start = Offset(seamX, cy - ribHalf),
            end = Offset(seamX, cy + ribHalf),
            strokeWidth = ribW,
        )
    }

    // ② 驾驶室：前 31%，窄一点点（真实货车车头比车厢略窄）
    val cabLen = l * 0.31f
    val cabCx = cx + l * 0.325f
    box(body, cabCx, cy, cabLen, w * 0.94f, w * 0.16f)
    if (!detail) return

    // ③ 前挡风 + 两侧车窗
    val glass = glassOf(body).copy(alpha = 0.90f * a)
    box(glass, cabCx + cabLen * 0.28f, cy, cabLen * 0.30f, w * 0.72f, w * 0.08f)
    box(glass, cabCx - cabLen * 0.22f, cy - w * 0.30f, cabLen * 0.26f, w * 0.15f, w * 0.05f)
    box(glass, cabCx - cabLen * 0.22f, cy + w * 0.30f, cabLen * 0.26f, w * 0.15f, w * 0.05f)

    // ④ 前后灯
    lamps(cx, cy, l, w, a, night)
}

// ==================== 公用小工具 ====================

/** 局部坐标下的圆角矩形（[cx] / [cy] 是矩形中心） */
private fun DrawScope.box(color: Color, cx: Float, cy: Float, w: Float, h: Float, r: Float) {
    if (w <= 0.01f || h <= 0.01f || color.alpha <= 0f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - w * 0.5f, cy - h * 0.5f),
        size = Size(w, h),
        cornerRadius = CornerRadius(r.coerceAtLeast(0f)),
    )
}

/** 局部坐标下的圆角矩形**描边** */
private fun DrawScope.boxOutline(
    color: Color,
    cx: Float,
    cy: Float,
    w: Float,
    h: Float,
    r: Float,
    stroke: Float,
) {
    if (w <= 0.01f || h <= 0.01f || color.alpha <= 0f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - w * 0.5f, cy - h * 0.5f),
        size = Size(w, h),
        cornerRadius = CornerRadius(r.coerceAtLeast(0f)),
        style = Stroke(width = stroke),
    )
}

/**
 * 前后灯：车头一对暖白、车尾一对红。
 * 夜里把车头灯调亮（[night] 越大越亮），与地面光斑呼应。
 *
 * ⚠️ 左右那一对用模块常量 [MIRROR_SIGNS]，别写 `intArrayOf(-1, 1)`：
 * 这是**每辆车每帧**都要走一遍的内层循环，一百辆车 120fps 就是每秒两万多个一次性数组。
 */
private fun DrawScope.lamps(cx: Float, cy: Float, l: Float, w: Float, a: Float, night: Float) {
    val lampLen = max(1.2f, l * 0.040f)
    val lampH = max(1.0f, w * 0.15f)
    val head = Color(0xFFFFF4D8).copy(alpha = (0.62f + 0.38f * night) * a)
    val tail = Color(0xFFD8443A).copy(alpha = (0.66f + 0.20f * night) * a)
    val y = w * 0.31f
    for (sign in MIRROR_SIGNS) {
        box(head, cx + l * 0.455f, cy + sign * y, lampLen, lampH, lampH * 0.35f)
        box(tail, cx - l * 0.455f, cy + sign * y, lampLen * 0.85f, lampH, lampH * 0.35f)
    }
}

// ---- 由车身色派生出的三种辅助色：保持与车身同色系，不会突然跳色 ----

/** 玻璃：向深蓝灰方向压，模拟俯视看进去的深色玻璃 */
private fun glassOf(body: Color): Color = lerp(body, Color(0xFF232A33), 0.62f)

/** 车顶：向白提亮一点（受光面） */
private fun roofOf(body: Color): Color = lerp(body, Color.White, 0.20f)

/** 轮廓 / 结构线：向黑压深 */
private fun edgeOf(body: Color): Color = lerp(body, Color(0xFF161A1E), 0.30f)
