package com.keeprolling.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * 路口**圆角**：正面回答需求 1 的几何定义。
 *
 * 一个路口有 N 条路交汇就有 N 个夹角，每个夹角都倒成圆角 —— **十字 4 个、丁字 2 个**
 * （丁字第三个方向是贯通的直路，本身没有夹角）。见 [JunctionGeometry.cornersOf]。
 *
 * ## 需求原文
 * > 道路交汇形成十字路口时，将四个夹角改为圆角过渡，使所有右转车辆沿该弧线车道行驶。
 * > 圆角定义为：**在不增加道路宽度的前提下**，由道路最右侧边线分支出一条弧形边线，
 * > 该弧形边线连接到交汇道路的最右侧边线，并与两条最右侧边线**同时相切**。
 *
 * ## 这条弧是怎么定出来的
 *
 * 设车从进口 A 驶入节点、再从出口 B 驶出。进口 A 的"最右侧边线"是距其中心线
 * `halfWidthA` 的那条线（在行驶方向的右手侧），出口 B 同理。
 *
 *  1. 圆角圆心 O 必须同时距两条边线 `rKerb`，且都落在**路口外侧**那一侧：
 *     `(O − A)·nA = rKerb`、`(O − B)·nB = rKerb`（n = 行驶方向的右法向）——
 *     解这个二元一次方程组即得 O，它必然落在两条边线之间的**外侧夹角**里；
 *  2. 切点 = O 向两条边线作垂线的垂足，于是圆弧与两条最右侧边线**精确相切**：
 *     进弯、出弯都没有折角，也不会在路边留下一个小三角缺口；
 *  3. 扫角 = 两条行驶方向的有符号夹角（右转为正）。
 *
 * 圆弧把原本那个 90°（或任意角度）的凹角"填"成了圆角 —— 路面一点没变宽，
 * 只是把路缘外侧那块没铺装的死角补齐了。这正是现实里街角的做法。
 *
 * ## 与"道路是怎么画的"无关
 *
 * 一条路被路口切成两半时，两半的存储方向取决于当初拖拽的方向，常常相反。
 * 圆角的构造必须对两种朝向给出**完全相同**的结果 —— 否则就会出现"四个角只圆了两个"。
 * 关键在 [JunctionGeometry.kerbFrame]：把切点投影回边线时只能用**路径自身**的切向。
 *
 * ## 车辆为什么不会开出路面
 *
 * 车的右转车道中心线，恰好是"最右侧边线再往路内半个车道"，与边线平行。
 * 所以 O 到车道中心线的距离 = `rKerb + 车道宽/2` —— 同一圆心、同一起止角的
 * **同心弧**就是车的行驶线（[rLane]）。
 *
 * 半径同时保证"一条完整车道能塞进圆角"：
 * `rKerb ≥ 车道宽 · sin(τ/2) / (1 − sin(τ/2))`（见 [fitRadiusM]），
 * 于是车在弯心处的外沿仍在铺装范围内，不会切到白色区域。
 *
 * ## 弧形道路
 *
 * 两条边线本身可能是弯的（道路带曲率）。圆心方程组用的是"切点处的边线点 + 法向"，
 * 而切点又依赖圆心 —— 这是一个不动点问题。构造时迭代 3 轮（[JunctionGeometry.kerbFrame]
 * 负责把点投影回真实边线），因此曲线道路上的圆角同样相切、同样自然。
 */
class CornerArc(
    val nodeId: Long,
    /** 进口道路 id（车辆从这条路边驶入节点） */
    val edgeIn: Long,
    /** 出口道路 id（车辆从这条路边驶出节点） */
    val edgeOut: Long,
    /** 圆角圆心（世界坐标，米）：落在路口**外侧**的夹角里 */
    val center: Vector2,
    /** 路缘圆角半径（米）：圆心到两条最右侧边线的距离 */
    val rKerb: Double,
    /** 车辆行驶半径（米）= rKerb + 半个车道宽：右转车正好压在这条车道中间 */
    val rLane: Double,
    /** 起点（进口切点）相对圆心的极角 */
    val a0: Double,
    /** 有符号扫角：屏幕坐标下正 = 右转 */
    val sweep: Double,
    /** 进口侧切点到节点的沿路距离（米） */
    val extentIn: Double,
    /** 出口侧切点到节点的沿路距离（米） */
    val extentOut: Double,
    /** 圆角填充区域的边界折线（世界坐标，闭合多边形；含路缘弧与两侧边线） */
    val fill: List<Vector2>,
) {
    /** 车辆弧上的起点 */
    val tIn: Vector2 get() = pointAt(0.0)

    /** 车辆弧上的终点 */
    val tOut: Vector2 get() = pointAt(1.0)

    /** 车辆行驶弧上归一化进度 [u] ∈ [0,1] 处的点 */
    fun pointAt(u: Double): Vector2 = arcPoint(rLane, u)

    /** 路缘弧（圆角本身，半径 [rKerb]）上归一化进度 [u] 处的点 */
    fun kerbPointAt(u: Double): Vector2 = arcPoint(rKerb, u)

    private fun arcPoint(radius: Double, u: Double): Vector2 {
        val ang = a0 + sweep * u.coerceIn(0.0, 1.0)
        return Vector2(center.x + cos(ang) * radius, center.y + sin(ang) * radius)
    }

    /** 车辆行驶弧的长度（米） */
    val lengthM: Double get() = rLane * abs(sweep)

    /** 这段圆角是否真的可以走车（半径、扫角都合理） */
    val valid: Boolean get() = rKerb > 1.0 && lengthM > 0.5
}

object JunctionGeometry {

    /** 转角小于这个值（≈17°）不算转弯：几乎共线的两条路之间没有圆角 */
    const val MIN_TURN_RAD = 0.30

    /** 转角大于这个值（≈155°）视为掉头，不做圆角 */
    const val MAX_TURN_RAD = 2.70

    /** 路缘圆角半径下限（米）：再小就不像街角，车也拐不进去 */
    const val MIN_R_M = 4.0

    /** 路缘圆角半径上限（米）：再大圆角就会吃掉整段路 */
    const val MAX_R_M = 30.0

    /**
     * 路缘圆角半径的**硬地板**（米）：路段短到连这么小的弧都放不下时，才放弃这个角。
     *
     * 它比 [MIN_R_M] 小得多 —— 后者是"看起来像街角"的舒适下限，前者只保证
     * "这个角一定有个圆"。两者的差别就是为了避免出现"四角只圆了三个"。
     */
    const val MIN_R_FLOOR_M = 1.5

    /** 切点距节点的最大沿路距离（米）：超过这个值就不做圆角，退回普通转弯弧 */
    const val MAX_EXTENT_M = 26.0

    /**
     * 一个路口上所有的**转角圆角**：N 条道路交汇 → N 个夹角 → N 个圆角。
     *
     * 十字路口 = 4 个，丁字路口 = 2 个（丁字的第三个方向是贯通的直路，没有夹角）。
     *
     * 每条进口边**只认一个出口边**：车辆朝着路口开进来，右手侧的那条臂就是它右转
     * 要去的方向，两者的夹角就是这一侧的物理夹角。具体做法是在所有"右转（有符号
     * 夹角为正）"的候选里取**夹角最小**的那一个 —— 也就是顺时针方向的近邻。
     *
     * 为什么不把所有正夹角都建成圆角：多岔路口（例如 5 条路每 72° 一条）里，同一条
     * 进口边的 144° 方向夹角也是正的，全收就会让"一个物理夹角算成两个圆角"。
     * 只取最小正角，N 条臂就恰好得到 N 个角。
     *
     * **只对真正的路口（相接道路 ≥ 3）生效**：两条路成一定角度相接的"肘弯"（度 = 2）
     * 没有路口外侧的夹角，也没有第二个方向要避让，画圆角反而是凭空多出一块路面。
     */
    fun cornersOf(network: RoadNetwork, nodeId: Long): List<CornerArc> {
        val node = network.node(nodeId) ?: return emptyList()
        val edges = network.edgesAt(nodeId)
        if (edges.size < 3) return emptyList()
        val out = ArrayList<CornerArc>(edges.size)
        for (eIn in edges) {
            val pIn = network.pathOf(eIn) ?: continue
            val dirIn = if (eIn.to == nodeId) 1.0 else -1.0
            // 车的行驶方向：进口是"驶入节点"，出口是"驶离节点"
            val uIn = pIn.tangentAt(if (dirIn > 0) pIn.length else 0.0) * dirIn
            if (uIn.length < 1e-6) continue

            // ---- ① 挑出这一侧"右手边的近邻"（右转角最小的那条出口边）----
            var pick: RoadEdge? = null
            var pickTurn = Double.MAX_VALUE
            for (eOut in edges) {
                if (eOut.id == eIn.id) continue
                val pOut = network.pathOf(eOut) ?: continue
                val dirOut = if (eOut.from == nodeId) 1.0 else -1.0
                val uOut = pOut.tangentAt(if (dirOut > 0) 0.0 else pOut.length) * dirOut
                if (uOut.length < 1e-6) continue
                val turn = signedAngle(uIn, uOut)
                // 屏幕坐标 y 轴向下：转向为正 = 右转；直行 / 掉头 / 左转都不在路口外侧拐弯
                if (turn <= MIN_TURN_RAD || turn >= MAX_TURN_RAD) continue
                if (turn < pickTurn) { pickTurn = turn; pick = eOut }
            }
            val eOut = pick ?: continue
            val dirOut = if (eOut.from == nodeId) 1.0 else -1.0
            val pOut = network.pathOf(eOut) ?: continue

            // ---- ② 把这一侧的夹角倒成圆角 ----
            build(node, eIn, eOut, pIn, pOut, dirIn, dirOut, pickTurn)?.let { out += it }
        }
        return out
    }

    /** 全路网所有路口的圆角（渲染用；只在路网变化时调一次） */
    fun allCorners(network: RoadNetwork): List<CornerArc> {
        val out = mutableListOf<CornerArc>()
        for (node in network.nodes.values) {
            if (network.degree(node.id) < 3) continue
            out += cornersOf(network, node.id)
        }
        return out
    }

    /**
     * 按「进口边 + 出口边」精确取一个圆角（车辆过路口用）。
     * 找不到（该转向不是右转 / 几何退化 / 不是路口）返回 null。
     */
    fun cornerFor(network: RoadNetwork, nodeId: Long, edgeIn: Long, edgeOut: Long): CornerArc? =
        cornersOf(network, nodeId).firstOrNull {
            it.edgeIn == edgeIn && it.edgeOut == edgeOut && it.valid
        }

    // ==================== 内部构造 ====================

    /** 边线上的一点 + 该处的行驶方向右法向 + 沿路里程（投影结果） */
    private class KerbFrame(val s: Double, val point: Vector2, val normal: Vector2)

    /** 边线上里程 s 处的点（[h] = 该路半宽，[dir] = 该方向是否沿 from→to） */
    private fun kerbPointAt(path: RoadPath, h: Double, dir: Double, s: Double): Vector2 =
        path.pointAt(s) + (path.tangentAt(s) * dir).perp() * h

    private fun frameAt(path: RoadPath, h: Double, dir: Double, s: Double): KerbFrame {
        val n = (path.tangentAt(s) * dir).perp()
        return KerbFrame(s, path.pointAt(s) + n * h, n)
    }

    /**
     * 把世界点 [target] 投影到"这条路的右侧边线"上，返回里程、边线上的点与该处右法向。
     *
     * 直线路直接解析投影（一次到位）；弧形路先粗采样再三分细化 —— 12 次迭代后
     * 位置误差远小于 1cm，圆角的相切关系因此是"贴着真实边线"成立，而不是贴着切线。
     */
    private fun kerbFrame(path: RoadPath, h: Double, dir: Double, target: Vector2): KerbFrame {
        if (path is RoadPath.Straight) {
            // ⚠️ 投影必须用**路径自身**的切向，绝不能用行驶切向 `dir`。
            //
            // 边线上的点由 `pointAt(s) + 法向·h` 决定，而 `pointAt(s)` 的 s 是**沿路径
            // 存储方向**（start → end）量的，与这辆车是顺着开还是逆着开完全无关。
            // 一旦乘上 `dir`：
            //   · dir = +1（节点在路径终点，正常）→ s 正确；
            //   · dir = -1（节点在路径起点，也就是车辆逆着存储方向行驶）→ 求出的 s
            //     是负数，被 `coerceIn` 夹到 0 → 切点被拉回路口角上 → 圆心解不出来
            //     → **整个角的圆角直接消失**。
            //
            // 真实路网里一条路被路口切成两半，两半的存储方向常常相反（取决于当初
            // 拖拽的方向）。于是四个角里只有"进口、出口都顺着存储方向"的那一两个
            // 能建出圆角 —— 这正是"十字路口只圆了两个角"的根因。
            val t = path.tangentAt(0.0)
            val s = (target - path.start).dot(t).coerceIn(0.0, path.length)
            return frameAt(path, h, dir, s)
        }
        val steps = 48
        var best = 0.0
        var bestD = Double.MAX_VALUE
        for (i in 0..steps) {
            val s = path.length * i / steps
            val d = kerbPointAt(path, h, dir, s).distanceTo(target)
            if (d < bestD) { bestD = d; best = s }
        }
        val half = path.length / steps
        var lo = (best - half).coerceAtLeast(0.0)
        var hi = (best + half).coerceAtMost(path.length)
        repeat(12) {
            val m1 = lo + (hi - lo) / 3.0
            val m2 = hi - (hi - lo) / 3.0
            val d1 = kerbPointAt(path, h, dir, m1).distanceTo(target)
            val d2 = kerbPointAt(path, h, dir, m2).distanceTo(target)
            if (d1 < d2) hi = m2 else lo = m1
        }
        return frameAt(path, h, dir, (lo + hi) / 2.0)
    }

    /**
     * 一条完整车道塞进圆角所需的最小路缘半径。
     *
     * 圆角的"厚度"沿角平分线是 `rKerb / sin(τ/2) − rKerb`；要让它容得下一条车道
     * （宽 [RoadNetwork.LANE_WIDTH_M]），解出
     *
     *     rKerb ≥ laneW · sin(τ/2) / (1 − sin(τ/2))
     *
     * 90° 街角（laneW = 3.5）给出约 8.5m 路缘半径 —— 跟现实里城市支路的转角半径吻合，
     * 这正是"圆角看起来自然"的来源：不是随便拍一个数，而是由车道宽度反推出来的。
     */
    private fun fitRadiusM(halfTurn: Double): Double {
        val s = sin(halfTurn)
        if (s >= 0.985) return MAX_R_M                 // 近乎掉头，交给 MAX_R_M 兜底
        return RoadNetwork.LANE_WIDTH_M * s / (1.0 - s)
    }

    private fun build(
        node: SimNode,
        eIn: RoadEdge,
        eOut: RoadEdge,
        pIn: RoadPath,
        pOut: RoadPath,
        dirIn: Double,
        dirOut: Double,
        turn: Double,
    ): CornerArc? {
        val laneW = RoadNetwork.LANE_WIDTH_M
        val hIn = eIn.halfWidthM
        val hOut = eOut.halfWidthM
        val half = turn * 0.5
        val tanH = tan(half)
        val sinTurn = abs(sin(turn))
        if (tanH < 0.08 || sinTurn < 0.20) return null

        // ---- ① 半径：既"像街角"，又保证一条车道真的塞得进圆角 ----
        val want = (hIn + hOut) * 0.5 + 4.0            // 路越宽，转角越舒展
        var rKerb = max(want, fitRadiusM(half)).coerceIn(MIN_R_M, MAX_R_M)

        // 切点不得超过这一段路能容纳的范围（否则圆角会吃掉整条路）
        val maxExtent = minOf(MAX_EXTENT_M, pIn.length - 3.0, pOut.length - 3.0)
        if (maxExtent < 2.0) return null
        // 切点距节点的沿路距离 = 「节点 → 两条边线交点 C 的距离」+ 「C → 切点的切线长」
        //                        = dC + rKerb·tan(τ/2)
        // 后者与半径成正比，所以可以直接反解出"刚好不超过 maxExtent"的半径上限：
        //     rKerb ≤ (maxExtent − dC) / tan(τ/2)
        // （除以 tan 而不是乘 —— 转角越钝，切线长增长越快，半径要收得越狠。）
        val dIn = hOut / sinTurn
        val dOut = hIn / sinTurn
        val rBound = minOf((maxExtent - dIn) / tanH, (maxExtent - dOut) / tanH)
        // 这只是一个"起手不要贪心"的初值：钝角时它偏保守、极小角时偏激进，
        // 真正把半径收紧到刚好放得下的是下面 [place] 的循环。所以这里只做夹取，不放弃。
        rKerb = min(rKerb, max(rBound, MIN_R_FLOOR_M))

        // ---- ② 圆心 + 半径：一起迭代到"切点落在路段可容纳范围内"为止 ----
        val nIn0 = (pIn.tangentAt(if (dirIn > 0) pIn.length else 0.0) * dirIn).perp()
        val nOut0 = (pOut.tangentAt(if (dirOut > 0) 0.0 else pOut.length) * dirOut).perp()
        val sIn0 = if (dirIn > 0) pIn.length else 0.0
        val sOut0 = if (dirOut > 0) 0.0 else pOut.length

        var nIn = nIn0
        var nOut = nOut0
        var sIn = sIn0
        var sOut = sOut0
        var center = node.pos
        var extentIn = 0.0
        var extentOut = 0.0

        /** 两条边线（各以"线上一点 + 单位法向"表示）到圆心取等距：解二元一次方程组 */
        fun solveCenter(a: Vector2, b: Vector2, qa: Vector2, qb: Vector2, r: Double): Vector2? {
            val det = a.x * b.y - a.y * b.x
            if (abs(det) < 1e-4) return null
            val b1 = qa.dot(a) + r
            val b2 = qb.dot(b) + r
            return Vector2((b1 * b.y - a.y * b2) / det, (a.x * b2 - b1 * b.x) / det)
        }

        /**
         * 用半径 [r] 重新解一次圆角：3 轮"圆心 → 切点 → 边线法向 → 圆心"的不动点迭代，
         * 曲线道路也会收敛（直线道路一轮就到位）。成功后写回 nIn/nOut/sIn/sOut/center/extent*。
         */
        fun place(r: Double): Boolean {
            var a = nIn0
            var b = nOut0
            var qa = node.pos + a * hIn
            var qb = node.pos + b * hOut
            var sa = sIn0
            var sb = sOut0
            var c = solveCenter(a, b, qa, qb, r) ?: return false
            repeat(3) {
                val fIn = kerbFrame(pIn, hIn, dirIn, c - a * r)
                val fOut = kerbFrame(pOut, hOut, dirOut, c - b * r)
                a = fIn.normal; qa = fIn.point; sa = fIn.s
                b = fOut.normal; qb = fOut.point; sb = fOut.s
                c = solveCenter(a, b, qa, qb, r) ?: return false
            }
            center = c
            nIn = a; nOut = b; sIn = sa; sOut = sb
            extentIn = if (dirIn > 0) pIn.length - sa else sa
            extentOut = if (dirOut > 0) sb else pOut.length - sb
            return true
        }

        if (!place(rKerb)) return null

        // 切点太远（转角很钝时切线长增长得比解析式估计更快）就把半径收一档重算。
        // 宁可给一个略小的圆角，也不要让这个角变成尖的 —— 那正是"四角只圆了两个"的观感来源。
        var shrinkGuard = 0
        while (maxOf(extentIn, extentOut) > maxExtent + 0.5) {
            if (++shrinkGuard > 5) return null
            val shrunk = rKerb * (maxExtent / maxOf(extentIn, extentOut))
            if (shrunk < MIN_R_FLOOR_M) return null
            rKerb = minOf(shrunk, rKerb - 0.05)
            if (!place(rKerb)) return null
        }

        // ---- ③ 起止角与扫角 ----
        val tInVec = center - nIn * rKerb
        val tOutVec = center - nOut * rKerb
        val a0 = atan2(tInVec.y - center.y, tInVec.x - center.x)
        var sweep = signedAngle(tInVec - center, tOutVec - center)
        if (sweep < 0.0) sweep += 2.0 * PI
        // 扫角必须与转角一致：不一致说明圆心被解到了另一侧（构造无效）
        if (abs(sweep - turn) > 0.35 || sweep < MIN_TURN_RAD) return null

        // ---- ④ 切点必须落在这一段路之内 ----
        if (extentIn < 1.0 || extentOut < 1.0) return null
        if (extentIn > maxExtent + 0.5 || extentOut > maxExtent + 0.5) return null

        val rLane = rKerb + laneW * 0.5
        val arc = CornerArc(
            nodeId = node.id,
            edgeIn = eIn.id,
            edgeOut = eOut.id,
            center = center,
            rKerb = rKerb,
            rLane = rLane,
            a0 = a0,
            sweep = sweep,
            extentIn = extentIn,
            extentOut = extentOut,
            fill = buildFill(pIn, pOut, hIn, hOut, dirIn, dirOut, sIn, sOut, center, rKerb, a0, sweep),
        )
        return if (arc.valid) arc else null
    }

    /**
     * 圆角填充区域的边界（世界坐标，闭合）。
     *
     * 顺序：沿进口路边线从切点走回边线交点 C → 再沿出口路边线从 C 走到它的切点
     * → 最后沿路缘弧回到起点。两侧走的是**真实边线**（弧形道路也是采样出来的曲线），
     * 所以圆角与路边严丝合缝，不会出现"补了一小块三角形"的接缝。
     */
    private fun buildFill(
        pIn: RoadPath,
        pOut: RoadPath,
        hIn: Double,
        hOut: Double,
        dirIn: Double,
        dirOut: Double,
        sIn: Double,
        sOut: Double,
        center: Vector2,
        rKerb: Double,
        a0: Double,
        sweep: Double,
    ): List<Vector2> {
        // 两条边线的交点 C（用切点处的切线近似求交）
        val nIn = (pIn.tangentAt(sIn) * dirIn).perp()
        val nOut = (pOut.tangentAt(sOut) * dirOut).perp()
        val det = nIn.x * nOut.y - nIn.y * nOut.x
        if (abs(det) < 1e-4) return emptyList()
        val qIn = pIn.pointAt(sIn) + nIn * hIn
        val qOut = pOut.pointAt(sOut) + nOut * hOut
        val b1 = qIn.dot(nIn)
        val b2 = qOut.dot(nOut)
        val c = Vector2(
            (b1 * nOut.y - nIn.y * b2) / det,
            (nIn.x * b2 - b1 * nOut.x) / det,
        )
        val sCIn = kerbFrame(pIn, hIn, dirIn, c).s
        val sCOut = kerbFrame(pOut, hOut, dirOut, c).s

        val out = ArrayList<Vector2>(24)
        val seg = 7
        for (i in 0..seg) {
            val s = sIn + (sCIn - sIn) * i / seg
            out += kerbPointAt(pIn, hIn, dirIn, s.coerceIn(0.0, pIn.length))
        }
        for (i in 0..seg) {
            val s = sCOut + (sOut - sCOut) * i / seg
            out += kerbPointAt(pOut, hOut, dirOut, s.coerceIn(0.0, pOut.length))
        }
        // 路缘弧：从终点回到起点（与多边形走向一致）
        val arcSteps = 12
        for (i in 1 until arcSteps) {
            out += Vector2(
                center.x + cos(a0 + sweep * (1.0 - i.toDouble() / arcSteps)) * rKerb,
                center.y + sin(a0 + sweep * (1.0 - i.toDouble() / arcSteps)) * rKerb,
            )
        }
        return out
    }

    /** 两个方向的**有符号**夹角（b − a），落在 (−π, π]；屏幕坐标下正 = 右转 */
    private fun signedAngle(a: Vector2, b: Vector2): Double {
        var d = (b.angleRad - a.angleRad) % (2.0 * PI)
        if (d > PI) d -= 2.0 * PI
        if (d <= -PI) d += 2.0 * PI
        return d
    }
}
