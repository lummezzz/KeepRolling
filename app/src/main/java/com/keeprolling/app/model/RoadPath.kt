package com.keeprolling.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 道路路径抽象（v2 新增）：
 * 直线道路与曲线道路统一成"可沿里程取点/取切向"的对象，
 * 车辆行驶、命中检测、渲染都只依赖这三个能力：
 *   length / pointAt(s) / tangentAt(s)
 *
 * 曲线定义为**圆弧**：由两端网格交点 + 一个曲率参数唯一确定。
 */
sealed class RoadPath {

    abstract val length: Double
    abstract val start: Vector2
    abstract val end: Vector2

    /** 沿路径里程 s（米）处的坐标 */
    abstract fun pointAt(s: Double): Vector2

    /** 沿路径里程 s（米）处的单位切向量（行驶方向） */
    abstract fun tangentAt(s: Double): Vector2

    /** 弧高（带符号，米）；直线为 0。用于绘制曲率拖拽手柄 */
    abstract val sagitta: Double

    /** 采样成折线（命中检测、渲染共用） */
    fun sample(segments: Int = 24): List<Vector2> {
        val n = segments.coerceAtLeast(2)
        return (0..n).map { pointAt(length * it / n) }
    }

    /** 弦中点（曲率手柄的参考位置） */
    val chordMid: Vector2 get() = (start + end) / 2.0

    /** 弦的单位法线（指向前进方向左侧） */
    val chordNormal: Vector2 get() = (end - start).perp().normalized()

    // ---------------- 直线 ----------------
    class Straight(private val a: Vector2, private val b: Vector2) : RoadPath() {
        override val length: Double = a.distanceTo(b)
        override val start: Vector2 get() = a
        override val end: Vector2 get() = b
        override val sagitta: Double get() = 0.0
        private val dir = (b - a).normalized()

        override fun pointAt(s: Double): Vector2 = a + dir * s.coerceIn(0.0, length)
        override fun tangentAt(s: Double): Vector2 = dir
    }

    // ---------------- 圆弧 ----------------
    class Arc(
        private val center: Vector2,
        private val radius: Double,
        private val startAngle: Double,
        private val sweep: Double,
        override val start: Vector2,
        override val end: Vector2,
        override val sagitta: Double,
    ) : RoadPath() {
        override val length: Double = abs(sweep) * radius

        override fun pointAt(s: Double): Vector2 {
            val t = if (length < 1e-9) 0.0 else (s / length).coerceIn(0.0, 1.0)
            val ang = startAngle + sweep * t
            return Vector2(center.x + radius * cos(ang), center.y + radius * sin(ang))
        }

        override fun tangentAt(s: Double): Vector2 {
            val t = if (length < 1e-9) 0.0 else (s / length).coerceIn(0.0, 1.0)
            val ang = startAngle + sweep * t
            val sign = if (sweep >= 0) 1.0 else -1.0
            // 圆上点的切向：(-sin, cos) 逆时针；顺时针取反
            return Vector2(-sign * sin(ang), sign * cos(ang))
        }

        /**
         * **本弧上两点组成的子弧**（按本弧的前进方向从 [p0] 到 [p1]）所需的曲率参数。
         *
         * 为什么要单算：曲率参数 c = 2h/弦长 **不是尺度无关量**——弦变短、h 也变小，
         * 直接沿用母弧的 c 会让子段的半径变小，两段曲线在交汇点"折"开、不再连续。
         * 这里按母弧的半径 R 反算子弦的弧高 h' = R − √(R² − (L'/2)²)，两段必然共圆（需求 4）。
         */
        fun subChordCurvature(p0: Vector2, p1: Vector2): Double {
            val l = p0.distanceTo(p1)
            if (l < 1e-6 || radius < 1e-6) return 0.0
            val half = l / 2.0
            val h = radius - sqrt((radius * radius - half * half).coerceAtLeast(0.0))
            // 鼓出方向与母弧一致（沿母弧前进方向取子弦，鼓出侧不变）
            val sign = if (sagitta >= 0) 1.0 else -1.0
            return (2.0 * sign * h / l).coerceIn(-RoadPath.MAX_CURVATURE, RoadPath.MAX_CURVATURE)
        }
    }

    companion object {
        /** 曲率参数上限（0.95 ≈ 近半圆），防止半径退化到 0 */
        const val MAX_CURVATURE = 0.95

        /** 曲率小于此值直接视为直线，避免数值抖动 */
        private const val FLAT_EPS = 0.02

        /**
         * 由两端点 + 曲率参数构造路径。
         * curvature ∈ [-0.95, 0.95]：0 = 直线；正负表示弧向弦的哪一侧鼓出；
         * 绝对值越大越弯（1.0 即半圆，这里限制到 0.95）。
         */
        fun of(a: Vector2, b: Vector2, curvature: Double): RoadPath {
            val chord = b - a
            val l = chord.length
            if (l < 1e-6) return Straight(a, b)
            val c = curvature.coerceIn(-MAX_CURVATURE, MAX_CURVATURE)
            if (abs(c) < FLAT_EPS) return Straight(a, b)

            val h = c * l / 2.0                     // 弧高（带符号）
            val n = chord.perp().normalized()        // 弦的左法线
            val mid = (a + b) / 2.0
            // 三点 (a, b, 弧顶) 定圆：圆心在弦的中垂线上
            val yc = (h * h - l * l / 4.0) / (2.0 * h)
            val center = mid + n * yc
            val radius = (h * h + l * l / 4.0) / (2.0 * abs(h))
            val apex = mid + n * h

            val a1 = atan2(a.y - center.y, a.x - center.x)
            val a2 = atan2(b.y - center.y, b.x - center.x)
            val ap = atan2(apex.y - center.y, apex.x - center.x)

            var sweep = norm2Pi(a2 - a1)
            // 判断按这个方向走的弧顶是否经过 apex；否则走另一半（反向）
            val midAngle = a1 + sweep / 2.0
            if (angleDelta(midAngle, ap) > PI / 2.0) sweep -= 2.0 * PI

            return Arc(center, radius, a1, sweep, a, b, h)
        }

        private fun norm2Pi(x: Double): Double {
            var v = x % (2.0 * PI)
            if (v < 0) v += 2.0 * PI
            return v
        }

        private fun angleDelta(x: Double, y: Double): Double {
            var d = (x - y) % (2.0 * PI)
            if (d > PI) d -= 2.0 * PI
            if (d < -PI) d += 2.0 * PI
            return abs(d)
        }

        /**
         * 曲率手柄拖拽 → 新曲率。
         * 思路：把手指位置投影到"弦法线"方向得到弧高，再换算成曲率。
         */
        fun curvatureFromHandle(start: Vector2, end: Vector2, handlePos: Vector2): Double {
            val chord = end - start
            val l = chord.length
            if (l < 1e-6) return 0.0
            val n = chord.perp().normalized()
            val mid = (start + end) / 2.0
            val h = (handlePos - mid).dot(n)
            return (2.0 * h / l).coerceIn(-MAX_CURVATURE, MAX_CURVATURE)
        }

        /**
         * 构造一条**在某一端与给定方向相切**的圆弧（需求 5：从直线引出的曲线匝道）。
         *
         * 几何：圆心一定落在"过锚点、垂直于切向"的直线上；再由"另一点也落在圆上"解出半径。
         * 于是曲线在锚点处的切向**精确等于** [dir]，视觉上就是从直线道路平滑分出的匝道。
         *
         * @param anchor 锚点（曲线的起点或终点，即与直线道路相接的那一端）
         * @param dir    锚点处的**行驶切向**（在起点=出发方向；在终点=到达方向）
         * @param other  曲线的另一端
         * @param atStart true = [anchor] 是起点；false = [anchor] 是终点
         * @return 相切圆弧；若 [other] 恰好落在切线上（退化为直线）则返回 [Straight]
         */
        fun tangentArc(anchor: Vector2, dir: Vector2, other: Vector2, atStart: Boolean): RoadPath {
            val startP = if (atStart) anchor else other
            val endP = if (atStart) other else anchor
            if (startP.distanceTo(endP) < 1e-6) return Straight(startP, endP)
            val d = dir.normalized()
            if (d.length < 1e-9) return Straight(startP, endP)

            val n = d.perp()
            // 圆心 = anchor + n·R；由"另一端到圆心距离 = |R|"解出 R
            val w = if (atStart) endP - anchor else startP - anchor
            val denom = 2.0 * n.dot(w)
            if (abs(denom) < 1e-9) return Straight(startP, endP)   // 另一端就在切线上 → 直线
            val signedR = w.lengthSquared / denom
            val center = anchor + n * signedR
            val radius = abs(signedR)

            val a1 = atan2(startP.y - center.y, startP.x - center.x)
            val a2 = atan2(endP.y - center.y, endP.x - center.x)
            var sweep = norm2Pi(a2 - a1)
            // 校验切向：逆时针（sweep > 0）时切向 = 半径向量逆时针转 90°
            val ccw = if (atStart) {
                (startP - center).perp().normalized()
            } else {
                (endP - center).perp().normalized()
            }
            if (ccw.dot(d) < 0.0) sweep -= 2.0 * PI

            return makeArc(center, radius, a1, sweep, startP, endP)
        }

        /** 由"圆心 + 半径 + 起始角 + 扫过角"装配 [Arc]，并算出带符号弧高（曲率手柄用） */
        private fun makeArc(
            center: Vector2,
            radius: Double,
            startAngle: Double,
            sweep: Double,
            startP: Vector2,
            endP: Vector2,
        ): Arc {
            val midAng = startAngle + sweep / 2.0
            val apex = Vector2(center.x + radius * cos(midAng), center.y + radius * sin(midAng))
            val chordN = (endP - startP).perp().normalized()
            val sag = (apex - (startP + endP) / 2.0).dot(chordN)
            return Arc(center, radius, startAngle, sweep, startP, endP, sag)
        }
    }
}
