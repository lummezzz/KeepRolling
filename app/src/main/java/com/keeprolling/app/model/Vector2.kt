package com.keeprolling.app.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 二维向量（世界坐标，单位：米）。
 * 仿真世界统一用"米"做单位，渲染时再按相机缩放转成屏幕像素。
 */
data class Vector2(val x: Double, val y: Double) {

    operator fun plus(o: Vector2) = Vector2(x + o.x, y + o.y)
    operator fun minus(o: Vector2) = Vector2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vector2(x * k, y * k)
    operator fun div(k: Double) = Vector2(x / k, y / k)
    operator fun unaryMinus() = Vector2(-x, -y)

    /** 向量长度 */
    val length: Double get() = sqrt(x * x + y * y)

    /** 向量长度的平方（比较距离时省一次开方） */
    val lengthSquared: Double get() = x * x + y * y

    /** 点积 */
    fun dot(o: Vector2) = x * o.x + y * o.y

    /** 垂直向量（逆时针转 90°）：取"道路右/左法线""弧高方向"时用 */
    fun perp() = Vector2(-y, x)

    /** 两点距离 */
    fun distanceTo(o: Vector2) = (this - o).length

    /** 单位化（零向量安全返回零向量） */
    fun normalized(): Vector2 {
        val l = length
        return if (l < 1e-9) ZERO else this / l
    }

    /** 与 x 轴正方向的夹角（弧度）。屏幕坐标系 y 轴向下 */
    val angleRad: Double get() = atan2(y, x)

    companion object {
        val ZERO = Vector2(0.0, 0.0)
    }
}

/** 几何工具：道路编辑与命中检测共用 */
object Geometry {

    /**
     * 线段 p1-p2 与线段 p3-p4 是否相交。返回：交点坐标 + 两条线段上的参数位置 (t, u)。
     *
     * **[includeEndpoints] 为什么必须有（需求 1 的根因）：**
     * 道路是**采样成折线**再求交的，采样点按等分弦长均匀分布。两条道路**对称相交**时
     * （最典型的就是垂直十字：两条路的交点恰好各自在中点），交点正好落在两条折线的
     * **共同顶点**上 —— 此时 t、u 恰好是 0.0 或 1.0。若沿用"严格内部"（0.02~0.98）判定，
     * 这种交点会被**全部拒绝**，于是十字路口根本不会被识别出来（垂直 + 各种对称斜交全中招）。
     *
     * 因此求"道路 × 道路"的交点时传 [includeEndpoints] = true：允许 t、u 取到 0/1，
     * 落在采样顶点上的交点也能被捕获。重复命中（一个顶点最多被相邻 4 段各报一次）
     * 由调用方按 SNAP_EPS_M 去重，不会产生多余节点。
     */
    fun segmentIntersection(
        p1: Vector2, p2: Vector2, p3: Vector2, p4: Vector2,
        includeEndpoints: Boolean = false,
    ): Pair<Vector2, Pair<Double, Double>>? {
        val d1 = p2 - p1
        val d2 = p4 - p3
        val denom = d1.x * d2.y - d1.y * d2.x
        if (abs(denom) < 1e-9) return null
        val e = p3 - p1
        val t = (e.x * d2.y - e.y * d2.x) / denom
        val u = (e.x * d1.y - e.y * d1.x) / denom
        val eps = 1e-9
        val lo = if (includeEndpoints) -eps else 0.02
        val hi = if (includeEndpoints) 1.0 + eps else 0.98
        if (t in lo..hi && u in lo..hi) {
            return (p1 + d1 * t) to (t to u)
        }
        return null
    }

    /** 点 p 到线段 a-b 的最短距离 */
    fun distPointToSegment(p: Vector2, a: Vector2, b: Vector2): Double {
        val ab = b - a
        val len2 = ab.lengthSquared
        if (len2 < 1e-9) return p.distanceTo(a)
        val t = ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
        return p.distanceTo(a + ab * t)
    }

    /**
     * 点 p 到折线的最短距离 + 折线上最近的点。
     * 命中检测统一走这里：曲线先采样成折线，直线也是两条点组成的折线。
     */
    fun distPointToPolyline(p: Vector2, pts: List<Vector2>): Pair<Double, Vector2> {
        if (pts.isEmpty()) return Double.MAX_VALUE to p
        if (pts.size == 1) return p.distanceTo(pts[0]) to pts[0]
        var bestDist = Double.MAX_VALUE
        var bestPt = pts[0]
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val ab = b - a
            val len2 = ab.lengthSquared
            val t = if (len2 < 1e-9) 0.0 else ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
            val cand = a + ab * t
            val d = p.distanceTo(cand)
            if (d < bestDist) {
                bestDist = d
                bestPt = cand
            }
        }
        return bestDist to bestPt
    }
}
