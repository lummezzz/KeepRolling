package com.keeprolling.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import com.keeprolling.app.model.BuildingLot
import com.keeprolling.app.model.Scenery
import com.keeprolling.app.model.TreeKind
import com.keeprolling.app.model.TreeLot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 环境装饰的绘制。
 *
 * 分四次调用，因为它们要分别落在**道路的上下四层**：
 *  - [drawScenery]：草地 / 建筑**本体** —— 画在道路**之前**（道路压在上面，路口不会被建筑盖住）；
 *  - [drawBuildingShadows]：建筑**影子** —— 画在道路**之后**（本轮需求 3）。
 *    楼体必须在路下、影子必须在路上，这两件事同时成立只能拆成两层；
 *  - [drawTrees]：树 —— 画在道路**之后**（树冠伸到路面上时，应当是树挡住路，符合现实）；
 *  - [drawStreetLights]：路灯光晕 —— 画在最后（光要洒在路面和树冠上，否则被盖掉）。
 *
 * 昼夜统一用 [DayLight] 这一个对象驱动：
 *  - [DayLight.night]（0 = 白天，1 = 夜晚）决定颜色插值，整片街区一起暗下去；
 *  - [DayLight.shadowDx]/[shadowDy]/[shadowLen] 决定**影子的方向与长度** ——
 *    自动循环的一天里，同一栋楼的影子会慢慢转半圈、并且在清晨黄昏被拉长；
 *  - [DayLight.shadowAlpha] 决定影子的浓度（夜里几乎没有太阳，影子只剩一点环境暗角）。
 * 三样都来自同一份计算，所以楼、树、车的影子永远是同一个光源打出来的。
 */

// ---------------- 配色 ----------------

/** 建筑屋顶的白天配色（低饱和暖灰 / 砂色 / 砖色 / 冷灰）：五档，和 [BuildingLot.TONES] 对应 */
private val BUILDING_DAY = longArrayOf(
    0xFFDAD3C5, 0xFFC9C0AF, 0xFFD9C8B8, 0xFFC3C9CD, 0xFFCFC5B8,
)

/** 夜间屋顶：压成深灰蓝，但保留细微色差，不至于整片糊成一块黑板 */
private val BUILDING_NIGHT = longArrayOf(
    0xFF2B3038, 0xFF292D33, 0xFF31302F, 0xFF272C33, 0xFF2C3139,
)

/** 白天屋顶设备的颜色（比屋顶稍深） */
private val ROOF_GEAR_DAY = Color(0xFFB2A996)

/** 夜间窗光的暖色（与路灯同为暖白光，两者协调） */
private val WINDOW_LIGHT = Color(0xFFFFCF8A)

private val TREE_OUTER_DAY = Color(0xFF6E9A62)
private val TREE_OUTER_NIGHT = Color(0xFF1D3323)
private val TREE_INNER_DAY = Color(0xFF8FBA7C)
private val TREE_INNER_NIGHT = Color(0xFF27452C)
private val TREE_PINE_DAY = Color(0xFF4F7B52)
private val TREE_PINE_NIGHT = Color(0xFF18301D)

private val LAWN_DAY = Color(0xFFA8C48B)
private val LAWN_NIGHT = Color(0xFF1E2C1E)

/** 路灯光晕的暖白 */
private val LAMP_GLOW = Color(0xFFFFD9A0)

/** 路灯灯头本身的白点（比光晕更白一点，让"光从哪儿发出"有落点） */
private val LAMP_BULB = Color(0xFFFFF3D6)

// 太阳方向不再是常量：改由 `DayLight`（见 DayCycle.kt）随时刻给出，
// 楼 / 树 / 车读同一份，才能保证"同一条街上影子朝向一致"。

/**
 * 车灯照射距离（米）：**8 米**（约两个中型车长）。
 *
 * 光束的张角与亮度都不变 —— 远端半宽按 `beamPx × 0.26` 算，长度一缩，宽度等比跟着缩，
 * 所以看上去还是同一束灯，只是照得近了一些（近距离近光灯的感觉）。
 */
internal const val BEAM_M = 8.0

/** 车灯光束的两种颜色（透明度随夜色在绘制时给，见 [drawHeadBeam]） */
private val BEAM_OUTER = Color(0xFFFFE9C4)
private val BEAM_INNER = Color(0xFFFFF1D8)

/**
 * 左右对称的那一对符号（两个前照灯 / 两个尾灯）。
 *
 * 提成模块常量：写在**每辆车每帧**的内层循环里就是 `intArrayOf(-1, 1)`，
 * 一百辆车 120fps 等于每秒两万多个一次性数组，纯喂 GC。
 * 用 `internal` 是因为 [VehicleDraw.kt] 里的车灯也要它（同包不同文件）。
 */
internal val MIRROR_SIGNS = intArrayOf(-1, 1)

// ==================== 道路之下：草地 / 建筑 ====================

/**
 * 画整片街区的**地面层**：绿化 + 建筑。
 *
 * 树**不在这里**画 —— 它由 [drawTrees] 在道路之后单独画一层（见文件头说明）。
 *
 * @param night 夜色（0 = 白天，1 = 夜晚）。
 *   ⚠️ 这里刻意**只收夜色的数值**、而不是整份 [DayLight]：这一层（连同底色、网格、路面）
 *   被拆成了独立的画布层，只要它读的东西不变，那一层就**一次都不会重录**。
 *   收整份 [DayLight] 的话，自动昼夜模式下影子方向每帧都在变，
 *   地面层就会跟着每帧重录 —— 可楼的颜色其实只跟 `night` 有关，白重录几千条指令。
 *   收 `Float` 之后，调用方可以放心把这个值量化到 1/128 再传进来（见 `SimCanvas` 里的
 *   `autoLevel` / `NIGHT_STEPS`）。
 * @param detail 是否画细节（投影、屋顶设备、窗光）：缩得很远时关掉，省性能也更干净
 */
internal fun DrawScope.drawScenery(
    scenery: Scenery,
    camera: CameraState,
    night: Float,
    detail: Boolean,
) {
    if (scenery.isEmpty) return
    val n = night.coerceIn(0f, 1f)
    val scale = camera.renderScale
    if (scale < 0.12) return                       // 缩到看不见了，整层跳过

    // ⚠️ 坐标一律用 camera.worldToLocal（world × **renderScale**，既不含平移也不含真实缩放）：
    // 平移与缩放由这一层的 Modifier.worldLayer 承担，于是**平移 / 捏合时这一层一次都不重录**。
    // 也正因为坐标不在屏幕坐标系里，**不能再用 size.width/height 做屏幕外剔除** ——
    // 那个矩形在局部坐标里是错的位置，会把该画的楼裁掉。剔除交给 GPU 按 layer 边界裁。

    // ---- ① 绿化：垫在最下层 ----
    val lawnColor = lerp(LAWN_DAY, LAWN_NIGHT, n)
    for (l in scenery.lawns) {
        val c = camera.worldToLocal(l.x, l.y)
        val rx = (l.rx * scale).toFloat()
        val ry = (l.ry * scale).toFloat()
        if (rx < 0.7f || ry < 0.7f) continue
        rotate(Math.toDegrees(l.rot).toFloat(), c) {
            drawOval(
                color = lawnColor.copy(alpha = 0.55f),
                topLeft = Offset(c.x - rx, c.y - ry),
                size = Size(rx * 2f, ry * 2f),
            )
        }
    }

    // ---- ② 建筑 ----
    for (b in scenery.buildings) {
        drawOneBuilding(b, camera, n, detail)
    }
}

/**
 * 画整片街区的**建筑影子** —— 单独一层，画在**道路之后**（本轮需求 3）。
 *
 * ## 为什么必须单独一层
 * 建筑本体必须压在道路**下面**（否则楼会把路口铺装和车道线切掉），
 * 可影子一旦也跟着在道路下面，落到路面上的那半边就被路面整个盖住 ——
 * 于是出现怪现象：楼在自己脚下有影子，一伸到马路上就凭空消失。
 * 树和车没这个毛病，因为它们本来就画在道路之后。
 *
 * 拆成两层后，层序是：**楼体（路下）→ 路面 → 楼影（路上）→ 车 → 树**。
 * 副作用是影子现在会盖在**楼顶**上，所以形状要特别处理 ↓
 *
 * ## 影子是"从楼底边往外拉"，不是"整块平移"
 * 以前影子 = 楼的矩形整体平移 (sx, sy)。平移版有一半压在楼体上 ——
 * 那时楼最后画，压住了看不出来；现在楼先画，那块重叠会变成屋顶上一片脏乎乎的暗斑。
 *
 * 这里改成**沿影子方向把矩形拉长**：局部 x/y 各加 `len·|分量|`，中心同时沿影子方向挪 `len/2`。
 * 于是矩形的**内侧边正好贴在楼边上**，露在楼外的部分才是影子。
 * 方向与楼的朝向平行时完全精确；斜着时是一个略大一点的包围盒（太阳很低时本来也看不清边界）。
 *
 * @param detail 与楼体一致：缩得很远时不画影子（几个像素的暗块纯属噪点）
 */
internal fun DrawScope.drawBuildingShadows(
    scenery: Scenery,
    camera: CameraState,
    light: DayLight,
    detail: Boolean,
) {
    if (!detail || scenery.buildings.isEmpty()) return
    val night = light.night.coerceIn(0f, 1f)
    // 浓度：白天 0.10、夜里 0.20（与楼体、树、车同一套公式）
    val shadowAlpha = (0.10f + 0.10f * night) * light.shadowAlpha
    if (shadowAlpha <= 0.012f) return
    val scale = camera.renderScale
    if (scale < 0.12) return

    val dirX = light.shadowDx
    val dirY = light.shadowDy

    // 坐标同样是**局部坐标**（world × scale）；平移由 ShadowLayer 的 graphicsLayer 承担。
    // 因此这里不做屏幕外剔除（理由见 drawScenery）。
    for (b in scenery.buildings) {
        val c = camera.worldToLocal(b.cx, b.cy)
        val wPx = (b.halfW * 2.0 * scale).toFloat()
        val dPx = (b.halfD * 2.0 * scale).toFloat()
        if (wPx < 1.2f || dPx < 1.2f) continue

        // 影子长度（px）：楼高的 42%（上限 16m）→ 米 → 像素，再乘当天影子的倍数。
        // 俯视图里"影子多长"是判断这栋楼多高最自然的线索，别把它抹平。
        val lenPx = (min(b.heightM * 0.42, 16.0) * light.shadowLen * scale).toFloat()
        if (lenPx < 0.7f) continue

        // 影子方向在**楼的局部坐标**里的分量：决定矩形往哪一侧拉长、拉多长
        val cosR = cos(b.rot).toFloat()
        val sinR = sin(b.rot).toFloat()
        val lx = dirX * cosR + dirY * sinR
        val ly = -dirX * sinR + dirY * cosR

        val sw = wPx + lenPx * abs(lx)
        val sd = dPx + lenPx * abs(ly)
        val sc = Offset(c.x + dirX * lenPx * 0.5f, c.y + dirY * lenPx * 0.5f)
        rotate(Math.toDegrees(b.rot).toFloat(), sc) {
            drawRoundRect(
                color = Color(0xFF2C2A24).copy(alpha = shadowAlpha),
                topLeft = Offset(sc.x - sw / 2f, sc.y - sd / 2f),
                size = Size(sw, sd),
                cornerRadius = CornerRadius((0.7 * scale).toFloat().coerceIn(0.5f, 5f)),
            )
        }
    }
}

/**
 * 画整片街区的**树**（画在道路之后：树冠伸到路面上时由树遮挡道路）。
 *
 * 为什么单独一层：建筑必须压在道路**之下**（否则楼会盖住路口铺装），
 * 而树在现实里是长在人行道外侧、树冠会伸到车道上方的 —— 树冠与路面重叠时，
 * 正确的层叠是"树在上、路在下"。两者的层叠需求正好相反，所以拆成两次调用。
 */
internal fun DrawScope.drawTrees(
    scenery: Scenery,
    camera: CameraState,
    light: DayLight,
    detail: Boolean,
) {
    if (scenery.trees.isEmpty()) return
    val scale = camera.renderScale
    if (scale < 0.12) return

    // 局部坐标 + graphicsLayer 平移（见 drawScenery 的说明）：不做屏幕外剔除。
    for (t in scenery.trees) {
        drawOneTree(t, camera, light, detail)
    }
}

private fun DrawScope.drawOneBuilding(
    b: BuildingLot,
    camera: CameraState,
    night: Float,
    detail: Boolean,
) {
    val scale = camera.renderScale
    val c = camera.worldToLocal(b.cx, b.cy)
    val wPx = (b.halfW * 2.0 * scale).toFloat()
    val dPx = (b.halfD * 2.0 * scale).toFloat()
    if (wPx < 1.2f || dPx < 1.2f) return

    val deg = Math.toDegrees(b.rot).toFloat()
    val body = lerp(Color(BUILDING_DAY[b.tone]), Color(BUILDING_NIGHT[b.tone]), night)
    val radius = CornerRadius((0.7 * scale).toFloat().coerceIn(0.5f, 5f))

    // ① 屋顶本体（影子已移到 [drawBuildingShadows] 单独一层，见那个函数的说明）
    rotate(deg, c) {
        drawRoundRect(
            color = body,
            topLeft = Offset(c.x - wPx / 2f, c.y - dPx / 2f),
            size = Size(wPx, dPx),
            cornerRadius = radius,
        )
        // 极细的深色描边：让相邻建筑的边界在缩小时也能分开
        if (detail && wPx > 8f) {
            drawRoundRect(
                color = Color(0xFF5F5A4E).copy(alpha = 0.18f + 0.22f * night),
                topLeft = Offset(c.x - wPx / 2f, c.y - dPx / 2f),
                size = Size(wPx, dPx),
                cornerRadius = radius,
                style = Stroke(width = 1f),
            )
        }

        // ③ 屋顶设备（水箱 / 楼梯间）：只有足够大的楼才画，小图上纯属噪点
        if (detail && wPx > 26f && dPx > 20f) {
            val gw = wPx * 0.30f
            val gd = dPx * 0.30f
            drawRoundRect(
                color = lerp(ROOF_GEAR_DAY, body, night * 0.55f).copy(alpha = 0.9f),
                topLeft = Offset(c.x - gw / 2f, c.y - gd / 2f),
                size = Size(gw, gd),
                cornerRadius = CornerRadius(1.5f),
            )
        }

        // ④ 夜间窗光：沿两条临街立面点几盏"屋里亮着灯"的窗
        if (night > 0.05f && detail && wPx > 14f) {
            val count = b.windows.coerceIn(1, 3)
            val ww = (1.5 * scale).toFloat().coerceIn(1.4f, 9f)
            val wd = (0.9 * scale).toFloat().coerceIn(1.2f, 7f)
            val edge = dPx / 2f - wd * 0.9f
            for (i in 0 until count) {
                val t = (i + 1.0) / (count + 1.0)
                val px = ((b.halfW * 2.0 * (t - 0.5)) * scale).toFloat()
                drawRoundRect(
                    color = WINDOW_LIGHT.copy(alpha = 0.80f * night),
                    topLeft = Offset(c.x + px - ww / 2f, c.y - edge - wd / 2f),
                    size = Size(ww, wd),
                    cornerRadius = CornerRadius(1f),
                )
                drawRoundRect(
                    color = WINDOW_LIGHT.copy(alpha = 0.70f * night),
                    topLeft = Offset(c.x + px - ww / 2f, c.y + edge - wd / 2f),
                    size = Size(ww, wd),
                    cornerRadius = CornerRadius(1f),
                )
            }
        }
    }
}

private fun DrawScope.drawOneTree(
    t: TreeLot,
    camera: CameraState,
    light: DayLight,
    detail: Boolean,
) {
    val night = light.night.coerceIn(0f, 1f)
    val scale = camera.renderScale
    val c = camera.worldToLocal(t.x, t.y)
    val rPx = (t.r * scale).toFloat()
    if (rPx < 0.9f) return

    // 影子（与建筑、车辆同一份光源）
    if (detail) {
        val sh = (t.r * 0.85 * light.shadowLen * scale).toFloat()
        drawCircle(
            color = Color(0xFF2C2A24).copy(alpha = (0.07f + 0.09f * night) * light.shadowAlpha),
            radius = rPx * 0.92f,
            center = Offset(c.x + sh * light.shadowDx, c.y + sh * light.shadowDy),
        )
    }

    val pine = t.kind == TreeKind.PINE
    val outer = if (pine) lerp(TREE_PINE_DAY, TREE_PINE_NIGHT, night)
    else lerp(TREE_OUTER_DAY, TREE_OUTER_NIGHT, night)
    val innerBase = if (pine) TREE_PINE_DAY else TREE_INNER_DAY
    val innerNight = if (pine) TREE_PINE_NIGHT else TREE_INNER_NIGHT
    drawCircle(outer.copy(alpha = 0.95f), radius = rPx, center = c)
    if (detail && rPx > 2.2f) {
        // 深浅两档：同一色系里微微错开，一条街上不会像复制粘贴
        val inner = if (t.tone == 0) {
            lerp(innerBase, innerNight, night)
        } else {
            lerp(lerp(innerBase, innerNight, night), outer, 0.35f)
        }
        drawCircle(inner, radius = rPx * 0.68f, center = Offset(c.x - rPx * 0.12f, c.y - rPx * 0.12f))
        // 树冠高光：白天是受光面，夜里弱化成一点月光折射
        drawCircle(
            color = Color.White.copy(alpha = (0.16f - 0.10f * night).coerceAtLeast(0.04f)),
            radius = rPx * (if (pine) 0.20f else 0.28f),
            center = Offset(c.x - rPx * 0.34f, c.y - rPx * 0.34f),
        )
    }
}

// ==================== 路灯（画在道路之后）====================

/**
 * 夜间路灯光晕：三层同心圆模拟"中心亮、边缘散"的衰减。
 *
 * 为什么不用径向渐变（`Brush.radialGradient`）：那需要给每盏灯每帧新建一个 Brush 对象，
 * 几十盏灯 60fps 下来就是每秒几千次分配；三层纯色圆视觉上几乎一样，却零分配。
 *
 * ## P5：按"半径层"批绘，320 盏灯从 1280 条指令降到 4 条
 * 每盏灯原本是 4 次 `drawCircle`。合并成 4 次 `drawPoints` 之后，**画面逐像素不变**：
 * 四层用的都是同一个 [LAMP_GLOW]（最后一层是灯头白点，单独一次），只是 alpha 不同；
 * 而同色多层 SrcOver 叠加的结果是
 * `c·(1-(1-a₁)(1-a₂)) + 底色·(1-a₁)(1-a₂)` —— 对两个 alpha **完全对称**，
 * 也就是与叠加顺序无关。所以"逐盏画四层"与"逐层画全部"结果相同，路灯互相重叠时也一样。
 */
internal fun DrawScope.drawStreetLights(scenery: Scenery, camera: CameraState, night: Float) {
    val n = night.coerceIn(0f, 1f)
    if (n < 0.02f || scenery.lamps.isEmpty()) return
    val scale = camera.renderScale
    if (scale < 0.5) return                     // 缩得太小：灯光会糊成一片，直接不画

    // 单盏灯照亮半径约 11m（比中型车长 5.85m 大一倍多，符合"路灯照亮一段路面"的直觉）
    val rPx = (11.0 * scale).toFloat()
    if (rPx < 4f) return

    // 局部坐标 + graphicsLayer 平移（见 drawScenery）：不做屏幕外剔除。
    val centers = ArrayList<Offset>(scenery.lamps.size)
    for (l in scenery.lamps) centers.add(camera.worldToLocal(l.x, l.y))
    if (centers.isEmpty()) return

    // `PointMode.Points` 画的是"零长度线段 + 圆头"，所以 strokeWidth 就是**直径**
    drawPoints(
        centers, PointMode.Points, LAMP_GLOW,
        strokeWidth = rPx * 2f, cap = StrokeCap.Round, alpha = 0.055f * n,
    )
    drawPoints(
        centers, PointMode.Points, LAMP_GLOW,
        strokeWidth = rPx * 0.62f * 2f, cap = StrokeCap.Round, alpha = 0.075f * n,
    )
    drawPoints(
        centers, PointMode.Points, LAMP_GLOW,
        strokeWidth = rPx * 0.34f * 2f, cap = StrokeCap.Round, alpha = 0.10f * n,
    )
    // 灯头本体：一个小小的白点，让"光是从这里发出来的"有落点
    drawPoints(
        centers, PointMode.Points, LAMP_BULB,
        strokeWidth = max(1.2f, rPx * 0.045f) * 2f, cap = StrokeCap.Round, alpha = 0.85f * n,
    )
}

// ==================== 车辆灯光 ====================

/**
 * 车辆前照灯的地面光斑（需求 2）。
 *
 * 两道梯形光束从车头射出去：长约 [BEAM_M] 米、远端张角约 30°，
 * 铺在路面上（画在车身之前），夜里一眼就能看出车头朝哪儿。
 *
 * @param beamPx 光束长度的**像素**值（= [BEAM_M] × 相机缩放），由调用方算好传进来，
 *               避免这里再依赖相机（也避免"米当成像素"这类单位错）
 * @param beamPath **可复用的 Path**（P7）。以前每辆车每帧 `Path()` 四次，
 *   一百辆车 120fps 就是每秒近五万个 Skia Path 对象。现在同一份 `rewind()` 反复用：
 *   Skia 的录制器在路径被改写时会自己把旧内容拷出去，所以录制结果与新建完全一致。
 */
internal fun DrawScope.drawHeadBeam(
    center: Offset,
    lengthPx: Float,
    widthPx: Float,
    deg: Float,
    beamPx: Float,
    night: Float,
    beamPath: Path,
) {
    val n = night.coerceIn(0f, 1f)
    if (n < 0.03f || beamPx < 6f) return

    rotate(deg, center) {
        val noseX = center.x + lengthPx / 2f
        val halfGap = widthPx * 0.30f          // 两个灯之间的间距（半个车宽以内）
        val headHalf = widthPx * 0.13f         // 灯本身的小宽度
        val farHalf = beamPx * 0.26f           // 远端张开的半宽
        for (sign in MIRROR_SIGNS) {
            val y = center.y + sign * halfGap
            // 外层：宽而淡的主光束
            beamPath.rewind()
            beamPath.moveTo(noseX, y - headHalf)
            beamPath.lineTo(noseX + beamPx, y - farHalf)
            beamPath.lineTo(noseX + beamPx, y + farHalf)
            beamPath.lineTo(noseX, y + headHalf)
            beamPath.close()
            drawPath(beamPath, BEAM_OUTER, alpha = 0.11f * n)
            // 内层：窄而亮，靠近车头的那一段（模拟灯芯的近场强光）
            beamPath.rewind()
            beamPath.moveTo(noseX, y - headHalf * 0.75f)
            beamPath.lineTo(noseX + beamPx * 0.55f, y - farHalf * 0.55f)
            beamPath.lineTo(noseX + beamPx * 0.55f, y + farHalf * 0.55f)
            beamPath.lineTo(noseX, y + headHalf * 0.75f)
            beamPath.close()
            drawPath(beamPath, BEAM_INNER, alpha = 0.10f * n)
        }
    }
}

/** 车尾红色示宽灯（画在车身之后，只有两个小点，成本可忽略） */
internal fun DrawScope.drawTailLights(
    center: Offset,
    lengthPx: Float,
    widthPx: Float,
    deg: Float,
    night: Float,
) {
    val n = night.coerceIn(0f, 1f)
    if (n < 0.03f) return
    val r = (widthPx * 0.075f).coerceIn(0.9f, 3.2f)
    rotate(deg, center) {
        val tailX = center.x - lengthPx / 2f + r
        for (sign in MIRROR_SIGNS) {
            drawCircle(
                color = Color(0xFFFF5A46).copy(alpha = 0.85f * n),
                radius = r,
                center = Offset(tailX, center.y + sign * widthPx * 0.34f),
            )
        }
    }
}
