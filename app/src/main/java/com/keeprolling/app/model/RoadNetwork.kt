package com.keeprolling.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** 节点类型（v2）：NORMAL = 普通节点（度为 1 时表现为"断头路"）；OPEN = 开放路口（可生成/接收车辆） */
@Serializable
enum class NodeType { NORMAL, OPEN }

/** 路网节点：位于网格交点上的道路端点或交叉点 */
@Serializable
data class SimNode(
    val id: Long,
    val x: Double,
    val y: Double,
    var type: NodeType = NodeType.NORMAL,
) {
    val pos: Vector2 get() = Vector2(x, y)
}

/**
 * 道路边：连接两个网格交点；curvature 为曲率参数（0 = 直线，±0.95 = 接近半圆）。
 *
 * 本轮新增 [lanesPerDirection]：**每个方向**的车道数（1 = 双向单车道；2 = 双向各两车道；3 = 双向各三车道）。
 * 车行道总宽 = 2 × LANE_WIDTH_M × lanesPerDirection。
 */
@Serializable
data class RoadEdge(
    val id: Long,
    val from: Long,
    val to: Long,
    val curvature: Double = 0.0,
    val lanesPerDirection: Int = 1,
) {
    /** 每方向车道数（夹到 1~3，兜底旧存档） */
    val lanes: Int get() = lanesPerDirection.coerceIn(1, RoadNetwork.MAX_LANES)

    /** 该道路的**半宽**（米）：中线到路缘 */
    val halfWidthM: Double get() = lanes * RoadNetwork.LANE_WIDTH_M

    /** 该道路的总宽（米） */
    val widthM: Double get() = 2.0 * halfWidthM
}

/**
 * 路网图（v2）：
 *  1. 所有节点都落在**网格交点**上（添加道路时自动吸附）；
 *  2. 道路支持直线与圆弧两种形态（由 curvature 决定）；
 *  3. 相交道路自动融合：在交点处打断既有道路，形成十字 / 丁字 / 转角路口。
 */
@Serializable
data class RoadNetwork(
    val nodes: MutableMap<Long, SimNode> = mutableMapOf(),
    val edges: MutableList<RoadEdge> = mutableListOf(),
    var nextNodeId: Long = 1,
    var nextEdgeId: Long = 1,
    /**
     * 下一个要分配的开放路口编号（路口1、路口2…）。
     * 编号只增不减：**一经分配即占用保留**，路口被封闭也不会释放，重新开放时沿用原编号。
     */
    var nextJunctionNumber: Int = 1,
    /** 路口 id → 已分配的编号（封闭后仍保留，因此重新开放能恢复原名） */
    val junctionNumbers: MutableMap<Long, Int> = mutableMapOf(),
    /**
     * **逐路口的红绿灯开关覆盖**：nodeId → 是否启用。
     *
     * 无记录 = 沿用默认（度 ≥ 3 的路口自动配灯）。用户在编辑模式点某路口的红绿灯
     * 可以单独删掉（false）或加回来（true）；它随存档 / 自动暂存 / 撤销一起持久化（需求 6）。
     */
    val signalOverride: MutableMap<Long, Boolean> = mutableMapOf(),
    /**
     * **逐路口的红绿灯运行规则**：nodeId → 1 / 2 / 3。
     *
     * 三种规则由用户在「红绿灯设置」里选（见 [signalRuleOf]）：
     *  - 1 = 直行与转弯同时放行（35 秒一档，两个方向交替）；
     *  - 2 = 直行与转弯分开放行（20 秒一档，四个状态轮转）；
     *  - 3 = 四方向轮流通行（20 秒一档，一次只放一个方向）。
     * 无记录 = 默认规则二（直行与转弯分开放行，最常见、最贴近现实）。随存档 / 自动暂存 / 撤销一起持久化。
     */
    val signalRule: MutableMap<Long, Int> = mutableMapOf(),
) {
    companion object {
        /** 默认规则：规则二（直行与转弯分开放行，先直后左，四个状态轮转） */
        const val DEFAULT_SIGNAL_RULE = 2
        /** 规则总数（1~3） */
        const val SIGNAL_RULE_COUNT = 3
        const val GRID_M = 10.0            // 网格间距（米）：较密网格
        const val SNAP_M = 7.0             // 端点吸附既有节点的半径
        const val SNAP_EPS_M = 1.5         // 交点合并容差
        const val SNAP_ROAD_M = 6.0        // 画路端点吸附到**既有道路内部**的半径（→ 丁字路口）
        const val RAMP_TANGENT_TOL_DEG = 25.0  // 曲线与直线"近似相切"的判定角（度）
        const val MIN_SEGMENT_M = 6.0      // 最短路段
        const val ROAD_WIDTH_M = 7.0       // 双车道（每方向 1 条）总宽，兼容旧引用
        const val LANE_WIDTH_M = 3.5       // 单条车道宽
        const val MAX_LANES = 3            // 每方向最大车道数（双车道 / 三车道）
        const val MAX_CURVATURE = 0.95

        /** 坐标吸附到最近的网格交点 */
        fun snap(p: Vector2): Vector2 =
            Vector2(round(p.x / GRID_M) * GRID_M, round(p.y / GRID_M) * GRID_M)
    }

    /**
     * **邻接表缓存**：nodeId → 与该节点相接的道路。
     *
     * 为什么必须有：`edgesAt` 原来实现成 `edges.filter { it.from == want || it.to == want }`，
     * 是 **O(E)** 且**每次调用都新建一个 List**。而渲染侧每帧对每个节点要调它 2~4 次
     * （路口铺装取最大半宽、节点符号取度数、信号灯标记取净空半径与最空方向……），
     * 40 个节点 × 40 条边 × 3 次 ≈ 每帧 5000 次比较 + 上百个 List ——
     * 而且这是**平方增长**：路网到 200 条边时就是每帧 4~8 万次比较。
     *
     * 建一次表的代价是 O(E)，之后每次查询 O(1) 且**零分配**（命中返回缓存里的列表，
     * 未命中返回 `emptyList()` 这个单例）。
     *
     * 失效时机（**只有结构真的变了才需要重建**）：
     *  - 增 / 删边、删节点、清空 —— 见各处的 [invalidateAdjacency]；
     *  - `setLanes` / `setCurvature` —— 它们用 `copy()` **换掉了 RoadEdge 实例**，
     *    旧表里握着的还是老对象（车道数 / 曲率是画路口铺装和净空半径的依据）；
     *  - **拖动端点不需要失效**：只改节点坐标，谁连着谁没变。
     *
     * `@Transient` = 不写进存档；它随时可以重建，属于纯运行时派生数据。
     */
    @Transient
    private var adjacencyCache: HashMap<Long, List<RoadEdge>>? = null

    private fun invalidateAdjacency() {
        adjacencyCache = null
    }

    private fun adjacency(): HashMap<Long, List<RoadEdge>> {
        adjacencyCache?.let { return it }
        val building = HashMap<Long, MutableList<RoadEdge>>(nodes.size * 2 + 4)
        for (e in edges) {
            building.getOrPut(e.from) { ArrayList(2) }.add(e)
            building.getOrPut(e.to) { ArrayList(2) }.add(e)
        }
        val frozen = HashMap<Long, List<RoadEdge>>(building.size * 2 + 4)
        for ((id, list) in building) frozen[id] = list
        adjacencyCache = frozen
        return frozen
    }

    // ---------------- 基础查询 ----------------

    fun node(id: Long): SimNode? = nodes[id]
    fun edge(id: Long): RoadEdge? = edges.firstOrNull { it.id == id }

    /** 道路的几何路径（直线或圆弧） */
    fun pathOf(e: RoadEdge): RoadPath? {
        val a = node(e.from) ?: return null
        val b = node(e.to) ?: return null
        return RoadPath.of(a.pos, b.pos, e.curvature)
    }

    fun edgeLength(e: RoadEdge): Double = pathOf(e)?.length ?: 0.0

    /** 道路采样成折线（渲染/命中检测共用） */
    fun polylineOf(e: RoadEdge): List<Vector2>? {
        val p = pathOf(e) ?: return null
        return samplePolyline(p)
    }

    /**
     * 折线采样，复用调用方**已经算好的** [path]。
     *
     * 渲染每条路都需要 path 与 polyline，若分别调 [pathOf] / [polylineOf]，
     * `RoadPath.of` 会被调用两次（它每次 `new` 一个对象）→ 40 条路每帧多 40 个对象。
     */
    fun samplePolyline(path: RoadPath): List<Vector2> =
        path.sample(if (path is RoadPath.Straight) 4 else 24)

    /** 节点的连接道路数（走邻接表缓存：O(1)，不再全表扫描） */
    fun degree(nodeId: Long): Int = edgesAt(nodeId).size

    /**
     * 与某节点相接的全部道路（走邻接表缓存：O(1)、零分配）。
     *
     * ⚠️ 返回的是**缓存里的列表本身**，调用方**不得修改**它。现有调用点
     * （`maxOfOrNull` / `mapNotNull` / `for` / `maxOf` …）都只读，安全。
     */
    fun edgesAt(nodeId: Long): List<RoadEdge> = adjacency()[nodeId] ?: emptyList()

    /** 是否为"断头路"（度为 1 且未开放）→ 画圆形停止符号 */
    fun isDeadEnd(nodeId: Long): Boolean =
        node(nodeId)?.type == NodeType.NORMAL && degree(nodeId) <= 1

    fun openNodes(): List<SimNode> = nodes.values.filter { it.type == NodeType.OPEN }

    /**
     * 节点处的**路口净空半径**（米）：该节点相接道路中最大的半宽。
     *
     * 停止线 / 路口冲突区都要以它为准——多车道道路半宽可达 10.5m，
     * 若沿用固定的 6m 停止线，等灯的车会停在横向车道的行车区内（与横穿车辆相撞）。
     */
    fun clearanceM(nodeId: Long): Double =
        edgesAt(nodeId).maxOfOrNull { it.halfWidthM } ?: LANE_WIDTH_M

    /** 分配路口编号：已分配过则**沿用**（封闭不释放编号），首次分配取下一个序号 */
    fun assignJunctionNumber(nodeId: Long): Int =
        junctionNumbers.getOrPut(nodeId) { nextJunctionNumber++ }

    /** 已分配的路口编号（未分配返回 null） */
    fun junctionNumberOf(nodeId: Long): Int? = junctionNumbers[nodeId]

    /** 该路口的红绿灯是否启用（无覆盖记录时默认启用） */
    fun signalEnabled(nodeId: Long): Boolean = signalOverride[nodeId] ?: true

    /** 设置该路口的红绿灯开关（需求 6：单独删除 / 添加某个路口的灯） */
    fun setSignalEnabled(nodeId: Long, on: Boolean) {
        signalOverride[nodeId] = on
    }

    /** 该路口当前使用的红绿灯规则（1 / 2 / 3；无记录时取 [DEFAULT_SIGNAL_RULE]，即规则二） */
    fun signalRuleOf(nodeId: Long): Int =
        (signalRule[nodeId] ?: DEFAULT_SIGNAL_RULE).coerceIn(1, SIGNAL_RULE_COUNT)

    /** 设置该路口的红绿灯规则（1 / 2 / 3，越界自动夹取） */
    fun setSignalRule(nodeId: Long, rule: Int) {
        signalRule[nodeId] = rule.coerceIn(1, SIGNAL_RULE_COUNT)
    }

    /**
     * 路口处"最空"的方向：把各相接道路的方位角排序，取**最大角间隔**的角平分线。
     *
     * 用途：把红绿灯小图标放到这个方向上——它一定落在两条道路之间的空档里，
     * 任何相交角度（含大角度斜交）都不会压到路面上（需求 7）。
     */
    fun freeDirection(nodeId: Long): Vector2 {
        val n = node(nodeId) ?: return Vector2(0.0, -1.0)
        val angles = edgesAt(nodeId).mapNotNull { e ->
            val o = node(otherEnd(e, nodeId)) ?: return@mapNotNull null
            val d = o.pos - n.pos
            if (d.length < 1e-9) null else atan2(d.y, d.x)
        }.sorted()
        if (angles.isEmpty()) return Vector2(0.0, -1.0)
        if (angles.size == 1) return Vector2(-cos(angles[0]), -sin(angles[0]))
        var bestGap = -1.0
        var bestMid = 0.0
        for (i in angles.indices) {
            val a = angles[i]
            val b = if (i == angles.size - 1) angles[0] + 2.0 * PI else angles[i + 1]
            val gap = b - a
            if (gap > bestGap) {
                bestGap = gap
                bestMid = a + gap / 2.0
            }
        }
        return Vector2(cos(bestMid), sin(bestMid))
    }

    /** 编辑模式下红绿灯小图标的**世界坐标**：路口外侧最空方向、净空半径再往外 [gapM] 米 */
    fun signalMarkerPos(nodeId: Long, gapM: Double): Vector2? {
        val n = node(nodeId) ?: return null
        return n.pos + freeDirection(nodeId) * (clearanceM(nodeId) + gapM)
    }

    fun otherEnd(e: RoadEdge, nodeId: Long): Long = if (e.from == nodeId) e.to else e.from

    /**
     * 某条道路在 [nodeId] 这一端"驶入路口"时应内缩的距离（米）——**车道标线的起画点**。
     *
     * 两条相交道路的行车带会交出一块**四边形**（一般情形是平行四边形）：宽度各为
     * 2·halfWidthM 的两条带子相交，交集就是那块区域。本条道路的车道线应当止于
     * 这块四边形**面向自己**的那条边。
     *
     * 几何推导：设本路在节点处的单位方向为 u，对方道路为 v、半宽为 h。
     * 对方行车带是 |p·n_v| ≤ h（n_v = v 的法线）。沿 u 走到 t 处要离开这条带子，
     * 需 t·|u·n_v| ≤ h，即
     *
     *     t = h / |u · n_v| = h / |sin θ|      （θ 为两条道路的夹角）
     *
     * 多条道路时取**最大值**（最外那条边才把整块路口让出来）。
     *
     * 近乎共线（|sin θ| 很小，其实是同一条路的延续）的对方**不构成横向边界**，直接忽略；
     * 否则会把"一条直路被节点断开"误判成路口，标线被莫名截掉一大段。
     *
     * @return 内缩距离（米）；0 表示该端不需要内缩（断头路 / 共线延续）。
     */
    fun junctionInsetM(edge: RoadEdge, nodeId: Long): Double {
        if (node(nodeId) == null) return 0.0
        val selfPath = pathOf(edge) ?: return 0.0
        // 本路**离开该节点**的行驶方向：from 端取起点切向，to 端取终点切向的反向
        val u = if (edge.from == nodeId) selfPath.tangentAt(0.0) else -selfPath.tangentAt(selfPath.length)
        if (u.length < 1e-6) return 0.0

        var best = 0.0
        for (e in edgesAt(nodeId)) {
            if (e.id == edge.id) continue
            val p = pathOf(e) ?: continue
            val v = if (e.from == nodeId) p.tangentAt(0.0) else -p.tangentAt(p.length)
            if (v.length < 1e-6) continue
            // |sin θ| = |u × v|（二维叉积），即 u 在 v 法线上的投影长度
            val sinT = abs(u.x * v.y - u.y * v.x)
            if (sinT < 0.17) continue            // 夹角 < 10°：同一条路的延续，不算横向边界
            val d = e.halfWidthM / sinT
            if (d > best) best = d
        }
        // 安全上限：两端各留至少 1m 标线，避免"路比路口还短"时整段标线消失
        val cap = ((selfPath.length - 2.0) * 0.5).coerceAtLeast(0.0)
        return best.coerceAtMost(cap)
    }

    /**
     * **统一的路口边界距离（米）**——车道标线的截断点、转弯圆弧的起终点、路口禁变道区，
     * 三者统一用这一个数（本轮需求 2 的唯一来源）。
     *
     * 取两者的**较大值**，各自负责一件事：
     *  - [clearanceM]（相接道路的最大半宽）：保证**任何车道数组合**下，标线都不会伸进路口。
     *    旧版只用了 [junctionInsetM]，于是"三车道 × 单车道"的路口会出现：三车道那一侧
     *    按“对方半宽 3.5m”截断，而本侧半宽 10.5m —— 中心线一路穿进路口内部、越过了停止线，
     *    与另一条路的标线明显对不齐。这就是需求 2 说的“截断不一致”。
     *  - [junctionInsetM]（对方半宽 / |sinθ|）：大角度斜交时路口在路的方向上更长，
     *    需要按夹角放大，否则标线会切进斜交路口的四边形容器里。
     *
     * 所有道路在同一个路口都至少截到 `clearanceM` 这一圈，因此**无论几条车道、几度斜交**，
     * 各方向标线都停在同一条"路口边界"上。
     *
     * **只对真正的路口（相接道路 ≥ 3）使用**：一条路被节点断成两段（度 = 2，且多半共线）时，
     * 若也按 `clearanceM` 截断，直路中间会凭空出现一段两三米的空白标线 —— 那不是路口。
     */
    fun junctionBoundaryM(edge: RoadEdge, nodeId: Long): Double {
        val base = junctionInsetM(edge, nodeId)
        if (degree(nodeId) < 3) return base
        return maxOf(base, clearanceM(nodeId))
    }

    fun nearestNode(pos: Vector2, maxDist: Double, excludeId: Long? = null): SimNode? =
        nodes.values.asSequence()
            .filter { it.id != excludeId }
            .minByOrNull { it.pos.distanceTo(pos) }
            ?.takeIf { it.pos.distanceTo(pos) <= maxDist }

    /** 命中道路：返回 (道路, 最近点, 距离)，超过 maxDist 返回 null */
    fun hitRoad(pos: Vector2, maxDist: Double): Triple<RoadEdge, Vector2, Double>? {
        var best: Triple<RoadEdge, Vector2, Double>? = null
        for (e in edges) {
            val poly = polylineOf(e) ?: continue
            val (d, pt) = Geometry.distPointToPolyline(pos, poly)
            if (d <= maxDist && (best == null || d < best!!.third)) best = Triple(e, pt, d)
        }
        return best
    }

    /** 距某点**最近的道路 id**（不限容差）；没有任何道路时返回 null。
     *  用途：曲线被交叉路口打断成多条子路后，据此重新选中离手柄最近的那条。 */
    fun edgeNearestTo(pos: Vector2): Long? {
        var best: Long? = null
        var bestD = Double.MAX_VALUE
        for (e in edges) {
            val poly = polylineOf(e) ?: continue
            val (d, _) = Geometry.distPointToPolyline(pos, poly)
            if (d < bestD) { bestD = d; best = e.id }
        }
        return best
    }

    // ---------------- 编辑操作 ----------------

    fun addNode(pos: Vector2, type: NodeType = NodeType.NORMAL): SimNode {
        val n = SimNode(nextNodeId++, pos.x, pos.y, type)
        nodes[n.id] = n
        return n
    }

    /** 只删掉这条边，**不**清理孤立节点。
     *  打断既有道路时必须用它：否则端点会被 prune 掉，重建链时取不到坐标，
     *  就会出现"旧道路一端凭空消失"的 bug。 */
    private fun removeEdgeOnly(id: Long) {
        edges.removeAll { it.id == id }
        invalidateAdjacency()
    }

    /** 对外删除道路：删边 + 清理因此产生的孤立节点 */
    fun removeEdge(id: Long) {
        removeEdgeOnly(id)
        pruneOrphanNodes()
    }

    fun removeNode(id: Long) {
        nodes.remove(id)
        edges.removeAll { it.from == id || it.to == id }
        invalidateAdjacency()
    }

    /** 清理孤立节点（没有任何道路连接的节点没有存在意义） */
    fun pruneOrphanNodes() {
        val used = mutableSetOf<Long>()
        for (e in edges) {
            used += e.from
            used += e.to
        }
        nodes.keys.retainAll(used)
        signalOverride.keys.retainAll(used)   // 节点没了，它的红绿灯开关也没有意义
        signalRule.keys.retainAll(used)       // 同上：规则跟着路口走
    }

    private fun edgeExists(a: Long, b: Long) =
        edges.any { (it.from == a && it.to == b) || (it.from == b && it.to == a) }

    private fun nodeAtOrCreateTight(p: Vector2): SimNode =
        nearestNode(p, SNAP_EPS_M) ?: addNode(p)

    private fun addEdgeInternal(from: Long, to: Long, curvature: Double, lanes: Int): RoadEdge? {
        if (from == to || edgeExists(from, to)) return null
        val e = RoadEdge(nextEdgeId++, from, to, curvature, lanes.coerceIn(1, MAX_LANES))
        edges.add(e)
        invalidateAdjacency()
        return e
    }

    /** 端点吸附后，还要不要先把它"钉"成节点（打断既有道路）：返回落在道路内部的那个点 */
    private fun roadInteriorProjection(p: Vector2): Vector2? {
        val hit = hitRoad(p, SNAP_EPS_M) ?: return null
        val (e, pt, _) = hit
        val a = node(e.from)?.pos ?: return null
        val b = node(e.to)?.pos ?: return null
        // 贴着端点 → 交给节点吸附（形成转角），不在道路"内部"打断
        if (pt.distanceTo(a) < SNAP_EPS_M || pt.distanceTo(b) < SNAP_EPS_M) return null
        return pt
    }

    /** 把某条道路在 [point] 处打断成两段，返回新节点 id（不清理孤立节点） */
    private fun splitEdgeAt(edgeId: Long, point: Vector2): Long? {
        val e = edge(edgeId) ?: return null
        val a = node(e.from) ?: return null
        val b = node(e.to) ?: return null
        if (point.distanceTo(a.pos) < SNAP_EPS_M) return a.id
        if (point.distanceTo(b.pos) < SNAP_EPS_M) return b.id
        val idx = edges.indexOfFirst { it.id == edgeId }
        if (idx < 0) return null
        val curvature = e.curvature
        val lanes = e.lanes
        edges.removeAt(idx)
        invalidateAdjacency()
        val mid = addNode(point)
        // 子段保持**同圆**：曲率参数按母弧半径重算（否则曲线被打断后会折开、不连续）
        val parent = RoadPath.of(a.pos, b.pos, curvature)
        fun segCurv(p0: Vector2, p1: Vector2): Double =
            if (parent is RoadPath.Arc) parent.subChordCurvature(p0, p1) else 0.0
        addEdgeInternal(a.id, mid.id, segCurv(a.pos, mid.pos), lanes)
        addEdgeInternal(mid.id, b.id, segCurv(mid.pos, b.pos), lanes)
        return mid.id
    }

    private fun splitEdgeAtInterior(p: Vector2) {
        val hit = hitRoad(p, SNAP_EPS_M) ?: return
        splitEdgeAt(hit.first.id, hit.second)
    }

    /** 一条**直线**道路在 [p] 附近的接入点（用于曲线匝道判定）；贴近端点的不算 */
    private data class RoadApproach(val point: Vector2, val dir: Vector2)

    private fun straightApproachAt(p: Vector2, maxDist: Double = SNAP_EPS_M): RoadApproach? {
        var best: RoadApproach? = null
        var bestD = Double.MAX_VALUE
        for (e in edges) {
            if (e.curvature != 0.0) continue
            val a = node(e.from)?.pos ?: continue
            val b = node(e.to)?.pos ?: continue
            val (d, pt) = Geometry.distPointToPolyline(p, listOf(a, b))
            if (d > maxDist || d >= bestD) continue
            if (pt.distanceTo(a) < SNAP_M || pt.distanceTo(b) < SNAP_M) continue
            bestD = d
            best = RoadApproach(pt, (b - a).normalized())
        }
        return best
    }

    /** 两个方向的夹角（度，0~180） */
    private fun angleDeg(u: Vector2, v: Vector2): Double {
        val x = u.normalized().dot(v.normalized()).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(x))
    }

    /**
     * 构造"已吸附的两个端点 → 路径"，含**曲线匝道**的相切调整（需求 5）。
     *
     * 预览与 [addRoad] 生成共用这一套逻辑，所以拖拽时看到的就是抬手后生成的形状。
     */
    fun roadPathFor(startPos: Vector2, endPos: Vector2, curvature: Double): RoadPath {
        val base = RoadPath.of(startPos, endPos, curvature)
        if (abs(curvature) <= 1e-6) return base    // 直线工具，无需相切处理
        // 起点贴在某条**直线**道路上、且曲线在该点的切向与道路方向近似一致 → 从这条直线引出
        val apStart = straightApproachAt(startPos)
        if (apStart != null) {
            // 取与前进方向同侧的直线方向，避免匝道一上来就掉头 180°
            val d = if (apStart.dir.dot(endPos - startPos) >= 0) apStart.dir else -apStart.dir
            if (angleDeg(base.tangentAt(0.0), d) <= RAMP_TANGENT_TOL_DEG) {
                return RoadPath.tangentArc(startPos, d, endPos, atStart = true)
            }
            return base
        }
        // 起点不贴直线时，再看**终点**是否贴在某条直线道路上
        val apEnd = straightApproachAt(endPos)
        if (apEnd != null) {
            val d = if (apEnd.dir.dot(endPos - startPos) >= 0) apEnd.dir else -apEnd.dir
            if (angleDeg(base.tangentAt(base.length), d) <= RAMP_TANGENT_TOL_DEG) {
                return RoadPath.tangentArc(endPos, d, startPos, atStart = false)
            }
        }
        return base
    }

    /**
     * 画路端点的吸附（**预览与最终生成共用** → 看到什么就生成什么）：
     *  ① **既有节点优先**（距端点 [SNAP_M] 内）→ 直接贴到该节点：
     *     接在道路端点上即形成**转角**（该点度为 2，不是丁字路口）；
     *  ② 否则贴到**既有道路内部**（距道路 [SNAP_ROAD_M] 内）→ 端点落到路面上：
     *     与已有道路交汇成**丁字路口**；
     *  ③ 都不满足 → 按设置吸附网格。
     */
    fun snapDrawPoint(raw: Vector2, snapToGrid: Boolean): Vector2 {
        nearestNode(raw, SNAP_M)?.let { return it.pos }
        hitRoad(raw, SNAP_ROAD_M)?.let { hit ->
            val (e, pt, _) = hit
            val a = node(e.from)?.pos
            val b = node(e.to)?.pos
            val interior = a != null && b != null &&
                pt.distanceTo(a) >= SNAP_EPS_M && pt.distanceTo(b) >= SNAP_EPS_M
            if (interior) return pt
        }
        return if (snapToGrid) snap(raw) else raw
    }

    /**
     * **拖拽道路端点**时的吸附：只吸附到**其它节点**或**网格**，**不吸附到道路内部**。
     *
     * 为什么不吸附到道路内部：端点所属的那条道路本身就是最近的"道路"，
     * 一旦允许吸附到道路内部，这个端点会被吸回自己所在的直线上，只能在原直线上滑动、
     * 永远拖不出去。所以要显式跳过道路内部吸附（需求 2）。
     *
     * @param excludeNodeId 正在被拖拽的节点本身（不能吸附到自己）
     */
    fun snapDragPoint(raw: Vector2, snapToGrid: Boolean, excludeNodeId: Long): Vector2 {
        nearestNode(raw, SNAP_M, excludeNodeId)?.let { return it.pos }
        return if (snapToGrid) snap(raw) else raw
    }

    /** 移动一个节点（拖拽道路端点用）：改坐标即可，所有相接道路自动跟随（需求 2） */
    fun moveNode(id: Long, pos: Vector2) {
        val n = nodes[id] ?: return
        nodes[id] = n.copy(x = pos.x, y = pos.y)
    }

    /**
     * 拖拽端点后，把"被移动节点相接的道路"与其它道路的交叉处补做打断（需求 3）。
     *
     * 为什么需要它：拖动端点只改节点坐标，**不会新建交点节点**——两条路即使被拖到压在一起，
     * 也不会自动成路口。这里复用 [addRoad] 的同一套"求交 → 打断 → 合并重合节点"能力，
     * 把新产生的交叉补成路口；随后重建拓扑时，度 ≥ 3 的新路口会按自动配灯规则带上红绿灯。
     *
     * 处理两种情况：
     *  ① **端点落上另一条路**（T 形）：先把端点吸附到该路面上，再打断那条路 → 成丁字路口；
     *  ② **道路中段穿过另一条路**（十字）：两条路都在交点处打断。
     *
     * @return 是否发生了结构性变化（新增 / 打断了道路）
     */
    fun stitchCrossingsAt(nodeId: Long): Boolean {
        val n = node(nodeId) ?: return false
        val movedIds = edgesAt(nodeId).map { it.id }.toSet()
        if (movedIds.isEmpty()) return false

        // ① 端点落在**别的**道路内部附近 → 吸附到路面上（稍后打断即成丁字路口）
        var snapTo: Vector2? = null
        var bestD = SNAP_ROAD_M
        for (e in edges) {
            if (e.id in movedIds) continue
            val poly = polylineOf(e) ?: continue
            val (d, pt) = Geometry.distPointToPolyline(n.pos, poly)
            if (d > bestD) continue
            val a = node(e.from)?.pos
            val b = node(e.to)?.pos
            val interior = a != null && b != null &&
                pt.distanceTo(a) >= SNAP_EPS_M && pt.distanceTo(b) >= SNAP_EPS_M
            if (interior) { bestD = d; snapTo = pt }
        }
        if (snapTo != null) moveNode(nodeId, snapTo)

        return stitchMovedEdges(movedIds)
    }

    /** 调节曲线曲率后，把这条曲线新产生的交叉补成路口（与 [stitchCrossingsAt] 同一套逻辑） */
    fun stitchCrossingsOfEdge(edgeId: Long): Boolean = stitchMovedEdges(setOf(edgeId))

    /**
     * 把 [movedIds] 里这些「位置 / 形状刚变过」的道路，与其它道路的交叉处补做打断：
     * 交点在**对方内部** → 打断对方；在**自己内部** → 打断自己。同一道路上的多个交点按顺序重建成链
     * （[nodeAtOrCreateTight] 合并重合节点保证连通；子段按母弧半径重算曲率保证同圆连续；保留原车道数）。
     * 「拖端点」的 [stitchCrossingsAt] 与「调曲率」的 [stitchCrossingsOfEdge] 共用本函数。
     *
     * @return 是否发生了结构性变化（新增 / 打断了道路）
     */
    private fun stitchMovedEdges(movedIds: Set<Long>): Boolean {
        if (movedIds.isEmpty()) return false

        // ② 求"这些道路 × 其它道路"的全部交点，按"被打断的那条边"归拢
        data class Cut(val t: Double, val point: Vector2)
        val cutsByEdge = HashMap<Long, MutableList<Cut>>()
        for (eid in movedIds) {
            val e = edge(eid) ?: continue
            val poly = polylineOf(e) ?: continue
            val eLens = (0 until poly.size - 1).map { poly[it].distanceTo(poly[it + 1]) }
            val eTotal = eLens.sum()
            if (eTotal < 1e-6) continue
            for (f in edges.toList()) {
                if (f.id == eid) continue
                val fp = polylineOf(f) ?: continue
                val fLens = (0 until fp.size - 1).map { fp[it].distanceTo(fp[it + 1]) }
                val fTotal = fLens.sum()
                if (fTotal < 1e-6) continue
                var eAcc = 0.0
                for (i in 0 until poly.size - 1) {
                    var fAcc = 0.0
                    for (j in 0 until fp.size - 1) {
                        val hit = Geometry.segmentIntersection(
                            poly[i], poly[i + 1], fp[j], fp[j + 1],
                            includeEndpoints = true,   // 端点压在路上也要算（否则丁字识别不到）
                        )
                        if (hit != null) {
                            val (pt, params) = hit
                            // 落在这条道路的**内部** → 打断它
                            if (params.second > 1e-6 && params.second < 1.0 - 1e-6) {
                                val tF = (fAcc + fLens[j] * params.second) / fTotal
                                cutsByEdge.getOrPut(f.id) { mutableListOf() } += Cut(tF, pt)
                            }
                            // 交点在被移动道路的**内部**（被横穿）→ 也打断它
                            if (params.first > 1e-6 && params.first < 1.0 - 1e-6) {
                                val tE = (eAcc + eLens[i] * params.first) / eTotal
                                cutsByEdge.getOrPut(eid) { mutableListOf() } += Cut(tE, pt)
                            }
                        }
                        fAcc += fLens[j]
                    }
                    eAcc += eLens[i]
                }
            }
        }
        if (cutsByEdge.isEmpty()) return false

        // ③ 打断：同一道路上的多个交点按顺序重建成链。
        //    用 nodeAtOrCreateTight 合并重合节点 → 端点正好落在新节点上时两者**真正连通**；
        //    子段按母弧半径重算曲率（同圆连续）；保留道路原有车道数。
        for ((edgeId, list) in cutsByEdge) {
            val e = edge(edgeId) ?: continue
            val fromPos = node(e.from)?.pos ?: continue
            val toPos = node(e.to)?.pos ?: continue
            val edgeCurv = e.curvature
            val edgeLanes = e.lanes
            val ordered = list.sortedBy { it.t }
            removeEdgeOnly(edgeId)
            val pts = mutableListOf(fromPos)
            for (c in ordered) if (c.point.distanceTo(pts.last()) >= SNAP_EPS_M) pts += c.point
            if (toPos.distanceTo(pts.last()) >= SNAP_EPS_M) pts += toPos
            if (pts.size < 2) continue
            val parent = RoadPath.of(fromPos, toPos, edgeCurv)
            fun segC(p0: Vector2, p1: Vector2): Double =
                if (parent is RoadPath.Arc) parent.subChordCurvature(p0, p1) else 0.0
            var prev = nodeAtOrCreateTight(pts.first())
            for (i in 1 until pts.size) {
                val cur = nodeAtOrCreateTight(pts[i])
                addEdgeInternal(prev.id, cur.id, segC(pts[i - 1], pts[i]), edgeLanes)
                prev = cur
            }
        }
        pruneOrphanNodes()
        return true
    }

    /**
     * 铺一段道路（核心编辑操作）。流程：
     *  ① 端点吸附（节点 → 道路内部 → 网格，见 [snapDrawPoint]）；
     *  ② 端点若落在既有道路**内部**，先把那条路在该点**打断成节点**（否则只会"贴着"而不会形成丁字路口）；
     *  ③ 曲线且端点与既有**直线**道路近似相切 → 重建为"从直线引出"的相切圆弧（曲线匝道，需求 5）；
     *  ④ 求新路与所有既有道路的交点，在交点处打断既有道路（十字/丁字路口自动融合，
     *     **子段保持同圆** → 曲线被打断后仍连续，**并保留被打断道路自身的车道数**）；
     *  ⑤ 沿新路的路径点链逐段建路，每段同样保持同圆。
     *
     * @param lanesPerDirection 本次新建道路的每方向车道数（1/2/3）
     * 返回是否成功创建。
     */
    fun addRoad(
        rawStart: Vector2,
        rawEnd: Vector2,
        curvature: Double,
        lanesPerDirection: Int = 1,
        snapToGrid: Boolean = true,
    ): Boolean {
        val lanes = lanesPerDirection.coerceIn(1, MAX_LANES)

        // ---- ① 端点吸附 ----
        var startPos = snapDrawPoint(rawStart, snapToGrid)
        var endPos = snapDrawPoint(rawEnd, snapToGrid)
        if (startPos.distanceTo(endPos) < MIN_SEGMENT_M) return false

        // ---- ② 端点落在既有道路内部 → 记录精确落点（稍后打断成丁字路口）----
        val startJ = roadInteriorProjection(startPos)
        val endJ = roadInteriorProjection(endPos)
        if (startJ != null) startPos = startJ
        if (endJ != null) endPos = endJ
        if (startPos.distanceTo(endPos) < MIN_SEGMENT_M) return false

        // ---- ③ 构造路径（含曲线匝道相切调整，与预览同一套逻辑）----
        val cand: RoadPath = roadPathFor(startPos, endPos, curvature)
        val candArc = cand as? RoadPath.Arc
        /** 新路第 i 段（pts[i-1] → pts[i]）的曲率参数：与母弧同圆 */
        fun segCurv(p0: Vector2, p1: Vector2): Double =
            candArc?.subChordCurvature(p0, p1) ?: 0.0

        val candPoly = cand.sample(if (candArc != null) 36 else 4)
        val candLens = (0 until candPoly.size - 1).map { candPoly[it].distanceTo(candPoly[it + 1]) }

        // ---- ④ 端点先"钉"成节点（丁字路口）----
        if (startJ != null) splitEdgeAtInterior(startPos)
        if (endJ != null) splitEdgeAtInterior(endPos)

        // ---- ⑤ 求交点 ----
        data class Cut(val tCand: Double, val point: Vector2, val edgeId: Long, val tEdge: Double)
        val cuts = mutableListOf<Cut>()
        for (e in edges.toList()) {
            val poly = polylineOf(e) ?: continue
            val lens = (0 until poly.size - 1).map { poly[it].distanceTo(poly[it + 1]) }
            val edgeTotal = lens.sum()
            if (edgeTotal < 1e-6) continue
            var candAcc = 0.0
            for (i in 0 until candPoly.size - 1) {
                var edgeAcc = 0.0
                for (j in 0 until poly.size - 1) {
                    val hit = Geometry.segmentIntersection(
                        candPoly[i], candPoly[i + 1], poly[j], poly[j + 1],
                        // 需求 1：必须允许"交点落在采样顶点上"——否则对称十字永远识别不到
                        includeEndpoints = true,
                    )
                    if (hit != null) {
                        val (pt, params) = hit
                        val tCand = (candAcc + candLens[i] * params.first) / cand.length
                        val tEdge = (edgeAcc + lens[j] * params.second) / edgeTotal
                        cuts += Cut(tCand, pt, e.id, tEdge)
                    }
                    edgeAcc += lens[j]
                }
                candAcc += candLens[i]
            }
        }

        // ---- ⑥ 打断既有道路（同一道路上的多个交点按顺序重建为链）----
        // 关键：整段重建**基于坐标**而非节点 id，且中途绝不 prune 孤立节点，
        // 否则被交叉道路的度为 1 的端点会提前被删掉，导致旧路某一端消失。
        for ((edgeId, list) in cuts.groupBy { it.edgeId }) {
            val e = edge(edgeId) ?: continue
            val fromPos = node(e.from)?.pos ?: continue
            val toPos = node(e.to)?.pos ?: continue
            val edgeCurv = e.curvature
            val edgeLanes = e.lanes          // 关键：打断后各子段**保留原道路的车道数**
            val ordered = list.sortedBy { it.tEdge }
            removeEdgeOnly(edgeId)

            // 端点 → 各交点 → 端点（用位置重建，对节点被合并/删除都不敏感）
            val pts = mutableListOf(fromPos)
            for (c in ordered) {
                // 与上一点几乎重合的交点视为同一点（交给 nodeAtOrCreateTight 合并）；
                // 否则即使离端点很近也保留，避免出现"看着相交其实没打通"的假路口
                if (c.point.distanceTo(pts.last()) >= SNAP_EPS_M) pts += c.point
            }
            if (toPos.distanceTo(pts.last()) >= SNAP_EPS_M) pts += toPos
            if (pts.size < 2) continue

            // 子段曲率按**母弧半径**重算 → 曲线被路口打断后两段仍然共圆、视觉连续（需求 4）
            val parent = RoadPath.of(fromPos, toPos, edgeCurv)
            fun segC(p0: Vector2, p1: Vector2): Double =
                if (parent is RoadPath.Arc) parent.subChordCurvature(p0, p1) else 0.0

            var prev = nodeAtOrCreateTight(pts.first())
            for (i in 1 until pts.size) {
                val cur = nodeAtOrCreateTight(pts[i])
                addEdgeInternal(prev.id, cur.id, segC(pts[i - 1], pts[i]), edgeLanes)
                prev = cur
            }
        }

        // ---- ⑦ 沿新路生成边链（每段与新路母弧同圆）----
        val pts = mutableListOf(startPos)
        for (c in cuts.sortedBy { it.tCand }) {
            if (c.point.distanceTo(pts.last()) >= SNAP_EPS_M) pts += c.point
        }
        if (endPos.distanceTo(pts.last()) >= SNAP_EPS_M) pts += endPos

        var prev = nodeAtOrCreateTight(pts.first())
        for (i in 1 until pts.size) {
            val cur = nodeAtOrCreateTight(pts[i])
            addEdgeInternal(prev.id, cur.id, segCurv(pts[i - 1], pts[i]), lanes)
            prev = cur
        }
        pruneOrphanNodes()
        return true
    }

    /** 调整道路曲率（曲率拖拽手柄用） */
    fun setCurvature(edgeId: Long, curvature: Double) {
        val e = edge(edgeId) ?: return
        val idx = edges.indexOfFirst { it.id == edgeId }
        if (idx < 0) return
        edges[idx] = e.copy(curvature = curvature.coerceIn(-MAX_CURVATURE, MAX_CURVATURE))
        invalidateAdjacency()   // 换了 RoadEdge 实例，邻接表里握着的是旧对象
    }

    /**
     * 设置道路的**每方向车道数**（需求 7：选中道路后一键变双车道 / 三车道）。
     * 道路几何不变，只改宽度；相接路口的净空半径会随之重算。
     */
    fun setLanes(edgeId: Long, lanesPerDirection: Int) {
        val idx = edges.indexOfFirst { it.id == edgeId }
        if (idx < 0) return
        edges[idx] = edges[idx].copy(lanesPerDirection = lanesPerDirection.coerceIn(1, MAX_LANES))
        invalidateAdjacency()   // 同上：实例被替换，且车道数决定路口净空半径
    }

    fun clear() {
        nodes.clear()
        edges.clear()
        invalidateAdjacency()
        nextNodeId = 1
        nextEdgeId = 1
        junctionNumbers.clear()
        nextJunctionNumber = 1
        signalOverride.clear()
        signalRule.clear()
    }
}
