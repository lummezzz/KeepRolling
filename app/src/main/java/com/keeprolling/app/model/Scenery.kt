package com.keeprolling.app.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 环境装饰元素（本轮需求 1）：沿道路两侧**程序化生成**的建筑、树木、绿化与路灯。
 *
 * 设计原则：
 *  1. **纯几何、可确定性重建**：同一份路网（同一个 networkVersion）每次生成的结果完全一致
 *     —— 随机数种子取自 `edge.id`，所以画面上不会出现"每帧建筑换位置"的抖动；
 *     路网一改（加/删路、拖端点），整片街区随之重排。
 *  2. **只做数据，不做绘制**：生成在 model 层，绘制在 ui 层（`ui/SceneryRender.kt`），
 *     与既有的 `JunctionGeometry → SimCanvas` 分层方式保持一致。
 *  3. **真实比例**：单位统一是米。小型车 3.9m 长，所以
 *     建筑沿街面 9~26m、进深 8~20m、楼高 6~50m；树冠 3.6~9m；路灯高 8m。
 *     把这些尺寸和车辆放在同一画面上，比例一目了然。
 */

/** 一栋建筑的**俯视占地**：一个可旋转的矩形 + 楼高（楼高只用来算投影长度和小细节） */
data class BuildingLot(
    val cx: Double,
    val cy: Double,
    /** 沿街方向半宽（米） */
    val halfW: Double,
    /** 进深方向半深（米） */
    val halfD: Double,
    /** 沿街方向的世界朝向（弧度）：0 = 沿 +x */
    val rot: Double,
    /** 楼高（米）：决定投影长度，也用来给夜间"亮灯窗口数量"分级 */
    val heightM: Double,
    /** 配色档（0..[BuildingLot.TONES]-1） */
    val tone: Int,
    /** 夜间亮着的窗格数量（0 = 全暗，夜晚更有层次） */
    val windows: Int,
) {
    /** 外接圆半径（米）：剔除、粗略相交判断用 */
    val radiusM: Double get() = hypot(halfW, halfD)

    companion object {
        /** 配色档数量（渲染层按这个数量准备色板） */
        const val TONES = 5
    }
}

/** 树的形态：阔叶（圆形树冠）/ 针叶（收拢的尖顶） */
enum class TreeKind { LEAFY, PINE }

/** 一棵树（俯视：树冠圆） */
data class TreeLot(
    val x: Double,
    val y: Double,
    /** 树冠半径（米） */
    val r: Double,
    val kind: TreeKind,
    /** 深浅档 0/1：增加层次，避免整条街一个绿色 */
    val tone: Int,
)

/** 一块绿化（草带 / 花坛），俯视是一个椭圆 */
data class LawnLot(val x: Double, val y: Double, val rx: Double, val ry: Double, val rot: Double)

/** 一盏路灯（灯头位置，世界坐标） */
data class LampLot(val x: Double, val y: Double)

/**
 * 一片街区的全部装饰元素。由 [SceneryGenerator.generate] 一次生成，
 * 在画布上按 `networkVersion` 缓存复用。
 */
class Scenery(
    val buildings: List<BuildingLot>,
    val trees: List<TreeLot>,
    val lawns: List<LawnLot>,
    val lamps: List<LampLot>,
) {
    val isEmpty: Boolean get() = buildings.isEmpty() && trees.isEmpty() && lawns.isEmpty()
}

/**
 * 环境元素生成器。
 *
 * 生成顺序很关键（决定了碰撞判断能不能做准）：
 *  1. **建筑**：每侧沿街首尾排布，保证同一条街上的房子不会重叠；
 *     跨街道（转角、斜交）用旋转矩形 SAT 精确判交，宁可留出一点空隙也不允许穿模。
 *  2. **树**：贴着人行道外侧种，只与道路净空 + 建筑占地判交（树可以挨得很近，不必互相避让）。
 *  3. **绿化**：填在建筑退界与人行道之间的空档里。
 *  4. **路灯**：每 28~36m 一盏、左右交替，紧贴路缘 —— 夜间它们就是整片街区的主光源。
 */
object SceneryGenerator {

    /** 路缘到建筑红线的退界（米）：人行道 + 门前绿化带 */
    private const val SETBACK_M = 5.0

    /** 路灯离路缘的距离（米） */
    private const val LAMP_KERB_M = 1.6

    // 数量上限：极端大的路网也不至于把一帧画崩
    private const val MAX_BUILDINGS = 900
    private const val MAX_TREES = 1600
    private const val MAX_LAWNS = 900
    private const val MAX_LAMPS = 320

    /** 道路采样间隔（米）：净空查询用的距离场精度 */
    private const val ROAD_SAMPLE_M = 4.0

    fun generate(network: RoadNetwork): Scenery {
        if (network.edges.isEmpty()) {
            return Scenery(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val roads = RoadField(network)
        val index = BuildingIndex()

        // ---------- ① 建筑 ----------
        val buildings = ArrayList<BuildingLot>()
        for (e in network.edges.sortedBy { it.id }) {
            val path = network.pathOf(e) ?: continue
            val len = path.length
            if (len < 14.0) continue
            val hw = e.halfWidthM
            val rng = Random(e.id * 1_000_003L)

            for (side in doubleArrayOf(1.0, -1.0)) {
                var cur = 5.0 + rng.nextDouble() * 7.0
                while (cur < len - 8.0 && buildings.size < MAX_BUILDINGS) {
                    // 沿街面 9~26m、进深 8~20m —— 大约是 2~7 个小汽车首尾相接的宽度
                    val halfW = (9.0 + rng.nextDouble() * 17.0) / 2.0
                    val halfD = (8.0 + rng.nextDouble() * 12.0) / 2.0
                    val gap = 2.5 + rng.nextDouble() * 7.0
                    val mid = cur + halfW
                    if (mid > len - 6.0) break

                    val p = path.pointAt(mid)
                    val t = path.tangentAt(mid)
                    val n = t.perp()
                    val back = SETBACK_M + rng.nextDouble() * 2.5
                    val c = p + n * ((hw + back + halfD) * side)

                    // 楼高：幂分布 → 多数是 6~18m 的多层楼，偶尔出现 20~50m 的高层
                    val height = 6.0 + rng.nextDouble().pow(2.2) * 44.0
                    val lot = BuildingLot(
                        cx = c.x,
                        cy = c.y,
                        halfW = halfW,
                        halfD = halfD,
                        rot = atan2(t.y, t.x),
                        heightM = height,
                        tone = rng.nextInt(BuildingLot.TONES),
                        windows = 1 + rng.nextInt(3),
                    )

                    if (siteIsFree(lot, roads, index)) {
                        buildings += lot
                        index.add(lot)
                    }
                    cur = mid + halfW + gap
                }
            }
        }

        // ---------- ② 树 ----------
        val trees = ArrayList<TreeLot>()
        for (e in network.edges.sortedBy { it.id }) {
            val path = network.pathOf(e) ?: continue
            val len = path.length
            if (len < 8.0) continue
            val hw = e.halfWidthM
            val rng = Random(e.id * 7_777_777L + 13L)

            for (side in doubleArrayOf(1.0, -1.0)) {
                var s = 3.0 + rng.nextDouble() * 6.0
                while (s < len - 3.0 && trees.size < MAX_TREES) {
                    val p = path.pointAt(s)
                    val n = path.tangentAt(s).perp()
                    val off = hw + 2.0 + rng.nextDouble() * 2.8
                    val q = p + n * (off * side)
                    // 树冠 3.6~9m：比一辆小汽车（3.9m）宽，比中型车长（5.85m）也大一点，符合街景直觉
                    val r = 1.8 + rng.nextDouble() * 2.7
                    val kind = if (rng.nextDouble() < 0.22) TreeKind.PINE else TreeKind.LEAFY
                    if (roads.clearance(q.x, q.y) > r * 0.35 && !insideAnyBuilding(index.near(q.x, q.y), q.x, q.y, r * 0.45)) {
                        trees += TreeLot(q.x, q.y, r, kind, rng.nextInt(2))
                    }
                    s += 8.0 + rng.nextDouble() * 6.0
                }
            }
        }

        // ---------- ③ 绿化（草带 / 花坛）----------
        val lawns = ArrayList<LawnLot>()
        for (e in network.edges.sortedBy { it.id }) {
            val path = network.pathOf(e) ?: continue
            val len = path.length
            if (len < 12.0) continue
            val hw = e.halfWidthM
            val rng = Random(e.id * 31_415_927L + 7L)

            for (side in doubleArrayOf(1.0, -1.0)) {
                var s = 8.0 + rng.nextDouble() * 10.0
                while (s < len - 6.0 && lawns.size < MAX_LAWNS) {
                    val p = path.pointAt(s)
                    val n = path.tangentAt(s).perp()
                    val off = hw + 3.0 + rng.nextDouble() * 3.0
                    val q = p + n * (off * side)
                    val rx = 1.6 + rng.nextDouble() * 2.6
                    val ry = 1.1 + rng.nextDouble() * 1.8
                    if (roads.clearance(q.x, q.y) > 0.6) {
                        lawns += LawnLot(q.x, q.y, rx, ry, rng.nextDouble() * Math.PI)
                    }
                    s += 16.0 + rng.nextDouble() * 14.0
                }
            }
        }

        // ---------- ④ 路灯 ----------
        val lamps = ArrayList<LampLot>()
        for (e in network.edges.sortedBy { it.id }) {
            val path = network.pathOf(e) ?: continue
            val len = path.length
            if (len < 10.0) continue
            val hw = e.halfWidthM
            val rng = Random(e.id * 2_654_435L + 17L)

            var s = 10.0 + rng.nextDouble() * 8.0
            var side = if (rng.nextBoolean()) 1.0 else -1.0
            while (s < len - 6.0 && lamps.size < MAX_LAMPS) {
                val p = path.pointAt(s)
                val n = path.tangentAt(s).perp()
                val q = p + n * ((hw + LAMP_KERB_M) * side)
                lamps += LampLot(q.x, q.y)
                s += 28.0 + rng.nextDouble() * 8.0
                side = -side       // 左右交替：一条街两侧交错布灯，光斑不重叠
            }
        }

        return Scenery(buildings, trees, lawns, lamps)
    }

    /**
     * 建筑能不能放在这里：9 个采样点（4 角 + 4 边中点 + 中心）都必须离路面留出余量，
     * 且不能与任何已放置建筑相交（旋转矩形 SAT）。
     */
    private fun siteIsFree(lot: BuildingLot, roads: RoadField, index: BuildingIndex): Boolean {
        val ca = cos(lot.rot)
        val sa = sin(lot.rot)
        for (sx in intArrayOf(-1, 0, 1)) {
            for (sy in intArrayOf(-1, 0, 1)) {
                // 局部坐标 (sx·halfW, sy·halfD) → 世界坐标
                val lx = sx * lot.halfW
                val ly = sy * lot.halfD
                val wx = lot.cx + lx * ca - ly * sa
                val wy = lot.cy + lx * sa + ly * ca
                if (roads.clearance(wx, wy) < 1.2) return false
            }
        }
        for (other in index.near(lot.cx, lot.cy)) {
            if (overlaps(lot, other, 1.0)) return false
        }
        return true
    }

    /** 旋转矩形相交（分离轴定理）。[margin] 是额外要求的最小间距（米）。 */
    private fun overlaps(a: BuildingLot, b: BuildingLot, margin: Double): Boolean {
        val ca = cos(a.rot); val sa = sin(a.rot)
        val cb = cos(b.rot); val sb = sin(b.rot)
        val dx = b.cx - a.cx
        val dy = b.cy - a.cy
        // 四条候选分离轴：两个矩形的两组局部轴
        val axes = doubleArrayOf(ca, sa, -sa, ca, cb, sb, -sb, cb)
        for (k in 0 until 4) {
            val ux = axes[k * 2]
            val uy = axes[k * 2 + 1]
            val ra = a.halfW * abs(ca * ux + sa * uy) + a.halfD * abs(-sa * ux + ca * uy)
            val rb = b.halfW * abs(cb * ux + sb * uy) + b.halfD * abs(-sb * ux + cb * uy)
            if (abs(dx * ux + dy * uy) > ra + rb + margin) return false
        }
        return true
    }

    /** 点是否落在某个建筑占地内（局部坐标判定，[margin] 为外扩量） */
    private fun insideAnyBuilding(list: List<BuildingLot>, x: Double, y: Double, margin: Double): Boolean {
        for (b in list) {
            val ca = cos(b.rot)
            val sa = sin(b.rot)
            val dx = x - b.cx
            val dy = y - b.cy
            val lx = dx * ca + dy * sa
            val ly = -dx * sa + dy * ca
            if (abs(lx) < b.halfW + margin && abs(ly) < b.halfD + margin) return true
        }
        return false
    }
}

// ==================== 空间索引（生成期内部用，不参与渲染）====================

/**
 * 道路距离场：把每条道路按 [SceneryGenerator] 的采样间隔打成点，按 8m 网格分桶。
 * 查询"这个点离最近的路面边缘有多远"时只看邻近 3×3 个桶 —— 否则
 * 上千个候选点 × 上万条采样点就是 O(n·m)，搭一片街区要卡半秒。
 */
private class RoadField(network: RoadNetwork) {

    private val cell = 8.0
    private val buckets = HashMap<Long, MutableList<DoubleArray>>()

    init {
        for (e in network.edges) {
            val path = network.pathOf(e) ?: continue
            val len = path.length
            if (len < 0.5) continue
            val n = max(1, ceil(len / 4.0).toInt())
            val hw = e.halfWidthM
            for (i in 0..n) {
                val p = path.pointAt(len * i / n)
                val k = keyOf(p.x, p.y)
                buckets.getOrPut(k) { ArrayList() }.add(doubleArrayOf(p.x, p.y, hw))
            }
        }
    }

    /** 点到最近路面边缘的距离（米）：负值 = 落在路面（含路缘）之内 */
    fun clearance(x: Double, y: Double): Double {
        val gx = floor(x / cell).toInt()
        val gy = floor(y / cell).toInt()
        var best = Double.MAX_VALUE
        for (i in gx - 1..gx + 1) {
            for (j in gy - 1..gy + 1) {
                val list = buckets[keyOfCell(i, j)] ?: continue
                for (s in list) {
                    val d = hypot(x - s[0], y - s[1]) - s[2]
                    if (d < best) best = d
                }
            }
        }
        // 邻近 24m 内没有任何采样点 → 离路很远，视作足够空
        return if (best == Double.MAX_VALUE) 99.0 else best
    }

    private fun keyOf(x: Double, y: Double): Long =
        keyOfCell(floor(x / cell).toInt(), floor(y / cell).toInt())

    private fun keyOfCell(ix: Int, iy: Int): Long =
        (ix.toLong() shl 32) or (iy.toLong() and 0xFFFF_FFFFL)
}

/**
 * 建筑索引：把每栋楼的外接圆覆盖到的格子都登记一遍。
 * 查询某个点时，只要该点落在楼的外接圆内，就一定能在这点所在格子里查到它。
 */
private class BuildingIndex {

    private val cell = 16.0
    private val buckets = HashMap<Long, MutableList<BuildingLot>>()

    fun add(b: BuildingLot) {
        val r = b.radiusM
        val x0 = floor((b.cx - r) / cell).toInt()
        val x1 = floor((b.cx + r) / cell).toInt()
        val y0 = floor((b.cy - r) / cell).toInt()
        val y1 = floor((b.cy + r) / cell).toInt()
        for (ix in x0..x1) {
            for (iy in y0..y1) {
                val k = (ix.toLong() shl 32) or (iy.toLong() and 0xFFFF_FFFFL)
                buckets.getOrPut(k) { ArrayList() }.add(b)
            }
        }
    }

    fun near(x: Double, y: Double): List<BuildingLot> {
        val ix = floor(x / cell).toInt()
        val iy = floor(y / cell).toInt()
        val k = (ix.toLong() shl 32) or (iy.toLong() and 0xFFFF_FFFFL)
        return buckets[k] ?: emptyList()
    }
}
