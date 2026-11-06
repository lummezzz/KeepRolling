package com.keeprolling.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import com.keeprolling.app.model.RoadNetwork
import com.keeprolling.app.model.RoadPath
import com.keeprolling.app.ui.LocalUiPalette
import kotlin.math.min

/**
 * **路网缩略图**：把一张 `RoadNetwork` 压成一张"小地图"，画在保存 / 加载弹窗的顶部。
 *
 * ## 为什么不用截图（`PixelCopy` / `drawToBitmap`）
 * 三条理由，每条都足以否掉截图方案：
 *  1. **加载弹窗需要的是"别的"路网**。那些路网只存在于 JSON 文件里，根本不在画布上，
 *     没有东西可截；
 *  2. 截图要等画布真的画完（异步），弹窗一打开就得先空一块再"啪"地出现图，很跳；
 *  3. 截图会把当时的路面阴影、树、车、路灯一起带进来 —— 缩略图只有 100dp 高，
 *     这些细节全糊成一片。重新按"地图"的抽象画一遍，反而更清楚。
 *
 * 所以这里只画三样东西：**路面（按车道数给粗细）+ 中线 + 出入口圆点**。
 *
 * ## 数据先"烘焙"一次
 * [buildNetworkThumb] 把每条路采样成折线、并算好整体包围盒，之后绘制只是一堆连线。
 * 好处是绘制期**零分配**（Path 复用 `rewind()`），而且 `@Immutable` 的快照可以让
 * `NetworkThumbnail` 在父层重组时被跳过 —— 加载面板里一屏有六张图，这一条省得不少。
 */

/** 缩略图里的一条路：扁平 `[x0,y0,x1,y1,…]`（世界坐标，米）+ 每方向车道数 */
@Immutable
class ThumbRoad(val points: FloatArray, val lanes: Int)

/**
 * 一张"烘焙好"的路网缩略图。
 *
 * `@Immutable` 是**承诺**：这类只在 [buildNetworkThumb] 里被写一次，之后全项目只读。
 * 里面的 `FloatArray` 严格说是可变类型，但没有任何地方会去改它。
 */
@Immutable
class NetworkThumb(
    val roads: List<ThumbRoad>,
    /** 出入口（开放路口）的坐标，扁平 `[x0,y0,x1,y1,…]` */
    val openNodes: FloatArray,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val isEmpty: Boolean get() = roads.isEmpty()
}

/**
 * 把路网烘焙成缩略图数据。
 *
 * 曲线采样 18 段：缩略图最宽也就 300dp，一条弧 18 段已经看不出折角；
 * 直线只需 2 段（`sample(n)` 返回 n+1 个点，首尾各一个）。
 * 没有路时包围盒给 0，[NetworkThumbnail] 会走"空白路网"分支。
 */
fun buildNetworkThumb(network: RoadNetwork): NetworkThumb {
    val roads = ArrayList<ThumbRoad>(network.edges.size)
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE

    for (e in network.edges) {
        val path = network.pathOf(e) ?: continue
        val poly = path.sample(if (path is RoadPath.Straight) 2 else 18)
        val arr = FloatArray(poly.size * 2)
        for (i in poly.indices) {
            val p = poly[i]
            arr[i * 2] = p.x.toFloat()
            arr[i * 2 + 1] = p.y.toFloat()
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        roads += ThumbRoad(arr, e.lanes)
    }

    val open = network.openNodes()
    val openArr = FloatArray(open.size * 2)
    for (i in open.indices) {
        val n = open[i]
        openArr[i * 2] = n.x.toFloat()
        openArr[i * 2 + 1] = n.y.toFloat()
        if (n.x < minX) minX = n.x
        if (n.x > maxX) maxX = n.x
        if (n.y < minY) minY = n.y
        if (n.y > maxY) maxY = n.y
    }

    if (roads.isEmpty()) {
        return NetworkThumb(emptyList(), FloatArray(0), 0.0, 0.0, 0.0, 0.0)
    }
    return NetworkThumb(roads, openArr, minX, minY, maxX, maxY)
}

/**
 * 画一张缩略图，**铺满调用方给的尺寸**（弹窗里就是"上部那一整块"，四周不留间距）。
 *
 * @param emptyLabel 一条路都没有时显示的一行占位字（传 null 就什么都不显示）
 */
@Composable
fun NetworkThumbnail(
    thumb: NetworkThumb,
    modifier: Modifier = Modifier,
    emptyLabel: String? = null,
) {
    val night = LocalUiPalette.current.night
    // 缩略图跟着昼夜走：白天是"纸质地图"（暖白底 + 暖灰路），
    // 夜里换成深色底 + 浅灰路 —— 夜里弹一张亮白海报会晃眼。
    val paper = lerp(PAPER_DAY, PAPER_NIGHT, night)
    val roadColor = lerp(ROAD_DAY, ROAD_NIGHT, night)
    val centerColor = lerp(CENTER_DAY, CENTER_NIGHT, night)
    val exitColor = lerp(EXIT_DAY, EXIT_NIGHT, night)

    Box(modifier.background(paper), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (thumb.isEmpty) return@Canvas

            // ---- 等比缩放到框内，并居中 ----
            // 四周留 8% 的边：既让"最外圈那条路"不贴着边（贴边会被对话框圆角切掉），
            // 又不会小到显得整张图缩成一团。
            val pad = min(size.width, size.height) * 0.08f
            val availW = (size.width - pad * 2f).coerceAtLeast(1f)
            val availH = (size.height - pad * 2f).coerceAtLeast(1f)
            // 路网退化成一条水平/竖直线时世界宽高可能为 0 —— 兜一个 1 米，避免除零后图像消失
            val worldW = (thumb.maxX - thumb.minX).coerceAtLeast(1.0)
            val worldH = (thumb.maxY - thumb.minY).coerceAtLeast(1.0)
            val s = min(availW / worldW, availH / worldH).toFloat()
            val drawnW = (worldW * s).toFloat()
            val drawnH = (worldH * s).toFloat()
            val ox = pad + (availW - drawnW) / 2f - thumb.minX.toFloat() * s
            val oy = pad + (availH - drawnH) / 2f - thumb.minY.toFloat() * s

            val path = Path()
            for (r in thumb.roads) {
                val pts = r.points
                // 一条路只有 2 个采样点时 i 走到 2 就结束，不会越界
                var i = 0
                while (i + 1 < pts.size) {
                    val x = pts[i] * s + ox
                    val y = pts[i + 1] * s + oy
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    i += 2
                }
                // 路面宽度 = 总宽（米）× 缩放比；再窄也要有 1.6px，否则细路直接消失
                val w = (2.0 * r.lanes * LANE_WIDTH_M * s).toFloat().coerceAtLeast(MIN_ROAD_PX)
                drawPath(
                    path = path,
                    color = roadColor,
                    style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // 中线：只有路宽画到 6px 以上才加。更细的时候那条线会盖住整条路，
                // 缩略图就变成"黄线网"，反而看不清路网形状。
                if (w >= CENTER_MIN_PX) {
                    drawPath(
                        path = path,
                        color = centerColor,
                        style = Stroke(
                            width = w * 0.16f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
                path.rewind()
            }

            // ---- 出入口：一个小圆点 + 一圈浅色托底 ----
            // 缩略图里这是唯一"有语义"的标记（哪些地方能生成/接收车），
            // 尺寸跟着整张图走：小图不至于变成一大块膏药。
            val dot = (min(drawnW, drawnH) * 0.05f).coerceIn(2.2f, 6.5f)
            val open = thumb.openNodes
            var k = 0
            while (k + 1 < open.size) {
                val c = Offset(open[k] * s + ox, open[k + 1] * s + oy)
                drawCircle(roadColor, radius = dot * 1.55f, center = c)
                drawCircle(exitColor, radius = dot, center = c)
                k += 2
            }
        }

        if (thumb.isEmpty && emptyLabel != null) {
            Text(
                text = emptyLabel,
                color = LocalUiPalette.current.muted,
                fontSize = 12.sp,
            )
        }
    }
}

/** 单方向一条车道宽（与 `RoadNetwork.LANE_WIDTH_M` 一致，这里写常量是为了不在绘制期读模型常量） */
private const val LANE_WIDTH_M = 3.5

/** 路面最细画到多少 px（再细就看不见了） */
private const val MIN_ROAD_PX = 1.6f

/** 路面细到这个宽度以下就不画中线 */
private const val CENTER_MIN_PX = 6f

// ---- 缩略图配色（白天 / 夜间两套，按 `LocalUiPalette.current.night` 插值）----
private val PAPER_DAY = Color(0xFFF6F4EE)
private val PAPER_NIGHT = Color(0xFF141A21)
private val ROAD_DAY = Color(0xFFB4B2A9)
private val ROAD_NIGHT = Color(0xFF49515C)
private val CENTER_DAY = Color(0xFFEDD9A0)
private val CENTER_NIGHT = Color(0xFF6E5C33)
private val EXIT_DAY = Color(0xFF1D9E75)
private val EXIT_NIGHT = Color(0xFF4FBF97)
