package com.keeprolling.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * 有向弧：一条道路的两个通行方向各拆成一条弧。
 * 弧上的坐标/切向全部委托给母道路的 [RoadPath]，因此直线与曲线对仿真完全透明。
 *
 * [lanes]：该道路**每方向**的车道数（1/2/3），车辆在弧上按车道横向偏移行驶。
 */
data class Arc(
    val edgeId: Long,
    val forward: Boolean,   // true = 沿 edge.from → edge.to 方向行驶
    val fromNode: Long,
    val toNode: Long,
    val path: RoadPath,     // 母道路路径（按 edge.from → edge.to 方向）
    val lanes: Int = 1,     // 每方向车道数
) {
    val key: String get() = "$edgeId:${if (forward) "F" else "B"}"
    val length: Double = path.length

    /** 弧上里程 s 处的世界坐标（倒向弧把 s 映射回母路径的对称位置） */
    fun pointAtS(s: Double): Vector2 =
        if (forward) path.pointAt(s) else path.pointAt(length - s)

    /**
     * 弧上里程 s 处的单位行驶方向。
     *
     * **注意倒向弧的镜像**：倒向弧的点是 `path.pointAt(length - s)`，对它求导得到
     * `-path.tangentAt(length - s)`——必须和 [pointAtS] 用**同一个** `length - s` 代换。
     * 曾经写成 `-path.tangentAt(s)`：直线因切向恒定侥幸正确，但**曲线**的切向随 s 变化，
     * 结果曲线反向行驶的车辆朝向、车道侧向、信号灯摆位全部偏掉（需求 1 / 需求 7）。
     */
    fun tangentAtS(s: Double): Vector2 =
        if (forward) path.tangentAt(s) else -path.tangentAt(length - s)
}

/**
 * **信号相位快照**：某个路口"现在放行谁、还剩几秒"，供界面上那条相位条显示。
 *
 * 相位表（[Signal.Phase]）本身对 UI 是不可见的黑盒 —— 用户只能看到灯的颜色，
 * 看不出"这一轮还有几段、下一段会放什么"。这个快照把配时翻译成人话：
 * 第几段 / 共几段、放行的是上下还是左右、直行还是左转、本级还剩多少秒。
 *
 * 之所以在**仿真侧**算好而不是让 UI 自己去查：相位推进在仿真线程上，
 * 界面只拿不可变快照（和车辆位置、信号灯颜色同一帧），三者永远对得上。
 */
data class SignalPhaseRender(
    /** 当前第几段（1 起，直接可显示） */
    val index: Int,
    /** 一个周期共几段 */
    val count: Int,
    /** 本级剩余秒数（绿灯 + 黄灯都算在 [segSec] 里） */
    val remainingSec: Double,
    /** 本级绿灯总时长 */
    val greenSec: Double,
    /** 本级总时长（绿灯 + 黄灯）：进度条的整条长度 */
    val segSec: Double,
    /** 是否已进入黄灯（timer 超过 greenSec） */
    val isYellow: Boolean,
    /** 放行轴向：0 = 上下（竖直），1 = 左右（水平），-1 = 无法判定（多重方向混合） */
    val axis: Int,
    /** 放行转向：0 = 直行，1 = 左转，2 = 直行 + 左转 */
    val turn: Int,
)

/** 渲染快照里的单辆车（不可变，跨线程传给 UI 安全） */
data class VehicleRender(
    val id: Long,
    val x: Double, val y: Double,
    val angleRad: Double,
    val lengthM: Double,
    val colorHex: Long,
    val speedKmh: Double,
    val alpha: Float,
    /**
     * 拥堵度 0~1（0 = 畅通，1 = 停死）：渲染层据此把车身从本色渐变到琥珀 / 红。
     * 默认 0 是为了让"没有这个信息"的构造点（测试 / 预览）保持原色。
     */
    val congestion: Float = 0f,
    /**
     * 车型（本轮新增）：渲染层据此画出轿车 / 中巴 / 大货各自的车身细节
     * （座舱玻璃、侧窗、货箱棱线……）。默认小型车，让"没有这个信息"的构造点照常工作。
     */
    val kind: VehicleKind = VehicleKind.CAR,
)

/**
 * **车辆聚焦**的快照（本轮需求 4）：信息框要显示的数据 + 前方已规划路径。
 *
 * [path] 是**车头往前**的世界坐标折线（含转弯圆弧、已按未来车道偏移采样），
 * 画成"向前箭头组成的虚线"；车后面的路一概不含，所以虚线永远从车头开始。
 * 每次仿真步重新生成：车一变道 / 一换路，虚线立刻跟着变。
 */
data class FocusRender(
    val id: Long,
    val x: Double,
    val y: Double,
    /** 实时速度（km/h） */
    val speedKmh: Double,
    /** 预设速度（km/h） */
    val desiredKmh: Double,
    /** 起始路口（生成时所在路口 id） */
    val originJunction: Long,
    /** 目标路口 id */
    val targetJunction: Long,
    /** 车长（米）：画布按它定"底部光圈"的大小，让光圈始终刚好包住车身 */
    val lengthM: Double,
    /** 前方规划路径（世界坐标，从车头开始） */
    val path: List<Vector2>,
)

/**
 * 渲染快照里的一盏路口信号灯：位于某个进口的停止线旁，[state] 0=绿 1=黄 2=红。
 * [angleRad] 是该进口的**行驶方向**——灯头据此垂直于来车方向摆放，任何相交角度朝向都正确（需求 7）。
 */
data class SignalLightRender(val x: Double, val y: Double, val state: Int, val angleRad: Double)

/** 渲染快照里的一条停止线：位于进口弧上、距路口中心 [halfWidthM] 处，垂直于行驶方向 */
data class StopLineRender(val x: Double, val y: Double, val angleRad: Double, val halfWidthM: Double)

/**
 * 车辆在世界坐标下的**显示姿态**：位置 + 朝向。
 * 渲染与"视觉防重叠"共用它，保证"看到的"和"用来判重叠的"永远是同一个位置。
 */
private data class RenderPose(val x: Double, val y: Double, val angle: Double)

/** 模拟统计数据快照 */
data class SimStats(
    val active: Int,
    val spawned: Int,
    val arrived: Int,
    val avgSpeedKmh: Double,
    /** 正在堵塞的开放路口：节点 id -> 已持续堵塞秒数 */
    val blockedJunctions: Map<Long, Double>,
    /** 累计自主**换路**次数：被堵住时主动更换路线的总次数（配合"自主规划"设置观察效果） */
    val reroutes: Int,
    /** 累计**变道**次数：多车道道路上自主变道的总次数（需求 2） */
    val laneChanges: Int = 0,
)

/**
 * 车辆在路口的转向（相对**当前弧的行驶方向**）。
 *
 * 判定方式：屏幕坐标 y 轴向下，所以"逆时针转 90°"（数学意义）在画面上是**右转**，
 * 于是 `angleDiff(入向, 出向)` 的**符号**直接就是转向：正 = 右、负 = 左、接近 0 = 直行。
 */
enum class TurnDir { LEFT, STRAIGHT, RIGHT }

/**
 * **路径偏好**：一辆车"从出发路口到目的地路口"按什么标准选路。
 *
 * | 值 | 权值 | 什么时候用 |
 * |---|---|---|
 * | [SHORTEST] | 弧长（米）纯几何距离 | **生成时的初始路线**：优先走最短的那条路 |
 * | [FASTEST] | 弧长 ÷ 该弧实测平均车速 = 预计耗时 | 车被堵住、判定"改道更划算"之后 |
 *
 * 两套距离场是**分开维护**的：
 *  - 最短距离场只依赖路网拓扑 → 只在 [TrafficSimulator.rebuildTopology] 后重算一次；
 *  - 最快耗时场依赖实时拥堵 → 每 [TrafficSimulator.ROUTE_REFRESH_SECONDS] 秒重算。
 *
 * 生成时一律用 [SHORTEST]（用户需求：初始路线优先最短道路），
 * 只有确实被堵到平均速度低于阈值时，[TrafficSimulator.stepRerouteLogic] 才把车切到 [FASTEST]。
 */
enum class RouteMode { SHORTEST, FASTEST }

/** 由"进入方向"与"驶出方向"判定转向；转角小于 [TrafficSimulator.TURN_MIN_ANGLE_RAD] 视为直行 */
fun turnDirOf(uIn: Vector2, uOut: Vector2): TurnDir {
    var ang = (uOut.angleRad - uIn.angleRad) % (2.0 * PI)
    if (ang > PI) ang -= 2.0 * PI
    if (ang < -PI) ang += 2.0 * PI
    return when {
        abs(ang) < TrafficSimulator.TURN_MIN_ANGLE_RAD -> TurnDir.STRAIGHT
        ang > 0.0 -> TurnDir.RIGHT
        else -> TurnDir.LEFT
    }
}

/**
 * **需求 5：按转向 + 每方向车道数决定"可以走哪几条车道"**（0 = 最左 / 靠中线，越大越靠右 / 路缘）。
 *
 * | 每方向车道 | 左转 | 直行 | 右转 |
 * |---|---|---|---|
 * | 3 | 最左 (0) | 中间 (1) **或** 最右 (2) | 最右 (2) |
 * | 2 | 最左 (0) | 最右 (1) | 最右 (1) |
 * | 1 | 唯一 (0) | 唯一 (0) | 唯一 (0) |
 *
 * 直行返回一个**区间**而不是单条：三车道时中间与最右都合法，车在哪条就待在哪条 ——
 * 若强行只认中间一条，右侧车道的直行车会一路往中间挤，看起来就像"莫名变道"。
 *
 * 右转就是**最后一条真实车道**：本轮起不再有"再往外一条虚拟专用道"（需求 1 取消了它），
 * 右转车直接在最右侧车道里沿路口的**圆角弧**拐过去，路面宽度一点没变。
 */
fun allowedLanes(lanes: Int, dir: TurnDir): IntRange {
    val n = lanes.coerceAtLeast(1)
    if (n <= 1) return 0..0
    return when (dir) {
        TurnDir.LEFT -> 0..0
        TurnDir.RIGHT -> (n - 1)..(n - 1)
        TurnDir.STRAIGHT -> if (n >= 3) (n / 2)..(n - 1) else (n - 1)..(n - 1)
    }
}

// ==================== 信号灯放行掩码（文件内使用）====================

/** 位掩码：该进口在本状态放行**直行** */
private const val MOVE_STRAIGHT = 1
/** 位掩码：该进口在本状态放行**左转** */
private const val MOVE_LEFT = 2

/** 转向 → 放行掩码（右转不受相位约束，这里给"全放行"占位，正常不会被查询） */
private fun moveBit(m: TurnDir): Int = when (m) {
    TurnDir.STRAIGHT -> MOVE_STRAIGHT
    TurnDir.LEFT -> MOVE_LEFT
    TurnDir.RIGHT -> MOVE_STRAIGHT or MOVE_LEFT
}

/**
 * 微观交通仿真器（v2）。
 *
 * 与 v1 的差异：
 *  1. 车辆沿 [RoadPath] 行驶，曲线道路天然支持；
 *  2. 生成点从"入口"改为"开放路口"：按 a 秒/辆 生成，车型三段概率、速度正态分布；
 *  3. 目的地按**路口热度加权**（heat / Σheat，且排除出发路口自身）；
 *  4. 车辆抵达目的地后原地淡出（观感更完整）；
 *  5. 崩溃判定：任一开放路口持续堵塞超过设置阈值 → 网络崩溃；
 *  6. 配置对象是**活引用**：模拟运行中改参数立即生效（实时调参）；
 *  7. 自主规划：车辆被堵住（最近窗口均速过低）时主动改道，带冷却防抖，参数可在设置里调。
 *  8. 防碰撞：同一车道由 IDM 跟车（含反应时间）+ 每帧安全层兜底；路口按"冲突区互斥"让行
 *     （谁离路口近谁先行，任一方驶出后另一方恢复通行），保证车辆不重合、不相撞。
 *     本轮再叠一层**现实优先权**：转弯让直行（[priorityOf]）；直行的车道若已被转弯车
 *     完全占据，直行减速让行；双方僵持超过 [TURN_DEADLOCK_SEC] → 转弯车**倒车**让直行先过
 *     （[stepDeadlockReverse]）。
 *  9. 多车道**按预设路线选道**：进入一条道路时就按"这条路末端要左转 / 直行 / 右转"
 *     定好车道（[allowedLanes]：左转靠最左；直行走中间或最右（两车道只走最右）；
 *     右转就在最右车道），行驶中只朝该区间**对齐**（[stepLaneChange]），不做超车变道；
 *     目标**粘在本段弧上**（[Vehicle.laneGoalArcKey]），路由刷新不会让车在路段中间反复并线；
 *     **路口交汇区域内一律不变道**（[inJunctionZone]，与标线同一套内缩量）、
 *     正在过转弯圆弧时不变道、等灯期间不变道。
 *     右转**不再有专用加宽道**（本轮需求 1 取消），而是在最右车道里沿路口**圆角弧**通过：
 *     该弧与两条最右侧边线相切，车辆的同心弧（半径 = 路缘半径 + 半个车道宽）就是车道本身
 *     （[JunctionGeometry]）。因为整条弧都在路口外侧、从不进冲突区，右转**无需减速、
 *     也无需让行**。其它转向仍走"交汇区边缘双切点"的普通圆弧（[buildTurnArc]）。
 * 10. 红绿灯：丁字 / 十字路口（相接道路数 ≥ 3，与车道数无关）自动配置信号灯，
 *     放行顺序由**该路口选定的规则**决定（[buildSignals]、[planPhases]）：
 *     规则一 直行+左转同时放行（35 秒一档）、规则二 直行/左转分开（20 秒一档）、
 *     规则三 四方向轮流（20 秒一档），每档之后 2 秒黄灯；
 *     **右转在任何规则下都无需等灯**。各路口用节点 id 生成的稳定初值**错开相位**。
 *     停止线按路口净空半径动态计算（相接道路最大半宽 + 余量），
 *     保证等灯的车绝不越过停止线驶入横向车道（需求 1）。
 * 11. 停止线是**硬约束**：柔性由 IDM 负责，另有逐帧硬夹取兜底，单帧位移再大也不越线。
 *
 * 线程模型：tick() 与 renderPositions()/snapshotStats()/renderSignals() 必须同一线程调用。
 */
class TrafficSimulator(
    val network: RoadNetwork,
    val junctionConfigs: Map<Long, JunctionConfig>,
    val settings: SimSettings,
    private val rng: Random = Random.Default,
) {
    companion object {
        const val SIM_DT = 1.0 / 60.0           // 仿真步长
        const val ROUTE_REFRESH_SECONDS = 2.0   // 路由重算周期
        const val SPAWN_CLEAR_M = 10.0          // 生成净空：车尾须距路口这么远
        const val LOOKAHEAD_M = 60.0            // 跨弧看前车的距离
        const val HARD_DECEL = 8.0              // 物理极限减速度
        const val FREE_FLOW_MS = 16.7           // 空畅假设速度（约 60km/h），用于路由代价
        const val FADE_SECONDS = 0.45           // 到达淡出时长
        /** 需求 5：无论速度分布怎么设，抽样速度都硬约束在 [40, 120] km/h */
        const val SPEED_MIN_KMH = 40.0
        const val SPEED_MAX_KMH = 120.0

        const val SPEED_MIN_MS = SPEED_MIN_KMH / 3.6   // 速度抽样下限（40 km/h）
        const val SPEED_MAX_MS = SPEED_MAX_KMH / 3.6   // 速度抽样上限（120 km/h）

        // ---------- 防碰撞 ----------
        /**
         * 停止线 / 路口冲突区的余量（米）：真实停止线取
         * `相接道路最大半宽 + 本余量`，保证等灯的车停在**横向车道行车区之外**。
         * 固定值（旧的 5m / 6m）在单车道时勉强够用，一旦是多车道（半宽可达 10.5m）
         * 就会把车停在横向车道里 —— 这正是"越线相撞"的根因。
         */
        const val STOP_LINE_MARGIN_M = 2.0
        const val JUNCTION_APPROACH_M = 24.0    // 路口接近判定距离：这个范围内才参与让行
        const val CONFLICT_DOT = -0.766         // cos140°：行驶方向夹角小于 140° 才算"会冲突"
                                                // （近乎正对的反向车 = 分道而行，不互相让行）
        const val EMERGENCY_TIME = 0.5          // 紧急制动余量（秒）：间距 < minGap + v*该值 →
                                                // 不等反应时间，立即减速（避免反应延迟内追尾）
        const val ARC_SWITCH_MIN_ROOM_M = 0.5   // 换弧最少净空：下一段弧尾部留不出这点空间就停在路口前等

        // ---------- 路口让行：转弯让直行（本轮需求）----------
        /**
         * "对方就在路口门前"的判定距离（米）：在停止线外再算这么一段。
         * 转弯车只对**真的到了门前或已经在动**的直行车让行 —— 否则远处排队的直行车
         * 会让转弯车一直不敢走（尤其规则一同时放行时）。
         */
        const val CONFLICT_ZONE_M = 8.0
        /**
         * 让行判定的**到达时间阈值**（秒）：更优先的车"按当前速度还有几秒到路口"小于这个值才让。
         *
         * 为什么不用纯距离：24m 外一辆 5 m/s 的慢车其实还有近 5 秒才到，转弯车完全来得及走；
         * 而 24m 外一辆 16 m/s 的车 1.5 秒就到，必须等。用时间才既安全又不至于把转弯车
         * "按"在路口半天不动。
         */
        const val YIELD_ETA_SEC = 2.0
        /** 直行车判定"我的车道被转弯车占据"时，向前看的走廊长度（米） */
        const val CROSS_CORRIDOR_M = 14.0
        /** 判定为"僵持"所需的**实际没动**时长（秒）：按滑窗净位移累计（[Vehicle.blockedSec]），不看速度 */
        const val TURN_DEADLOCK_SEC = 2.0
        /** 倒车速度（米/秒）：比倒车影像里的蠕行再慢一点，看得清 */
        const val REVERSE_SPEED_MS = 1.5
        /** 一次倒车至少退出这么远（米），保证真的让开对方车头 */
        const val REVERSE_MIN_DIST_M = 4.0
        /** 一次倒车的**上限**（米）：卡得很深时也不要一路倒回上一个路口，退够就停、下次再说 */
        const val REVERSE_MAX_DIST_M = 6.0
        /** 倒车后的冷却（秒）：防止在两车之间来回抽搐 */
        const val REVERSE_COOLDOWN_SEC = 6.0
        /** 判定"车已经停住"的速度上限（米/秒） */
        const val STOPPED_MS = 0.3

        // ---------- 红绿灯（本轮：三种可选规则）----------
        /**
         * **规则一**：直行与转弯同时放行 —— 一个方向"直行 + 左转"一起绿，另一个方向全红。
         * 每个状态持续 **35 秒**。
         *
         * 周期 = Σ(各状态绿灯 + [YELLOW_SEC])：两个状态 → 2 × (35 + 2) = 74 秒。
         */
        const val RULE1_GREEN_SEC = 35.0
        /**
         * **规则二**：直行与转弯分开放行 —— 一次只放"一个方向的直行"或"一个方向的左转"。
         * 每个状态持续 **20 秒**，四个状态一个循环。
         */
        const val RULE2_GREEN_SEC = 20.0
        /**
         * **规则三**：四方向轮流通行 —— 一次只放"一个方向（上/下/左/右）"的直行 + 左转。
         * 每个状态持续 **20 秒**，四个状态一个循环。
         */
        const val RULE3_GREEN_SEC = 20.0
        /** 黄灯时长（秒）：每个绿灯状态结束后的过渡缓冲，保持 2 秒 */
        const val YELLOW_SEC = 2.0
        const val SIGNAL_APPROACH_M = 45.0      // 进入这个距离才看信号灯（避免远处无谓减速）

        /**
         * "已经贴到停止线"的距离（米）。小于它就按 [HARD_DECEL] 按住不动。
         *
         * 单靠 `v² = 2·a·s` 是**渐近**收敛：dt 离散步进会在末尾剩下 0.3~0.5 m/s，
         * 车会以这个速度一路蹭过线，再被"硬夹取"拽回来 —— 观感上就是"到线前一顿"。
         */
        const val STOP_HOLD_M = 0.35

        /**
         * 判定"在排队等灯"的蠕行速度上限（m/s，2 m/s ≈ 7 km/h）：
         * 队列往往比 [SIGNAL_APPROACH_M] 长，红灯方向上只要车已经停住或只剩这点速度，
         * 就认为它在等灯（需求 2：等灯车辆不得自主变道）。
         */
        const val QUEUE_CRAWL_MS = 2.0

        // ---------- 车道对齐（需求 1：车道选择由预设路线决定）----------
        /** 发现"不在路口所需车道上"后先稳定开这么一会儿再并线（秒），避免刚出路口就甩头 */
        const val LANE_ALIGN_DELAY_SEC = 0.5
        /** 对齐式变道的冷却（秒）：比自由变道短，保证一个路段内能并到位 */
        const val LANE_ALIGN_COOLDOWN_SEC = 1.8
        /**
         * **路口交汇区域禁变道**（本轮需求）：车辆从"车道线被路口截断的位置"起，
         * 到"完全驶出交汇区"为止，一律不得变道；驶出后（再多留这么一点余量）才恢复。
         *
         * 判定直接用与标线 / 转弯圆弧同一套内缩量（[arcEndInset] / [arcStartInset]），
         * 所以"禁变道区"和画面上看到的路口区域**完全重合**。
         */
        const val LANE_LOCK_MARGIN_M = 1.0

        // ---------- 平滑动画（变道 / 转弯）----------
        /** 一次变道动画时长（秒）：约 1.1s，接近真实并线 */
        const val LANE_CHANGE_ANIM_SEC = 1.1
        /** 变道过程中车头最大偏摆角（弧度，约 7.5°）——"先偏进相邻车道再回正" */
        const val LANE_CHANGE_MAX_YAW_RAD = 0.13

        // ---------- 绿灯起步（本轮需求 2：车队跟得更紧凑）----------
        /**
         * 后车看到前车起步后，还要等这么久才允许加速（秒）。
         *
         * 原来 0.5 秒 —— 一条 8 辆车的队尾要等前车开出去近 4 秒才动，从高处看就是
         * "前车跑远了、后面的车还钉在原地"。真实路口的饱和车头时距也就 2 秒左右，
         * 但那是**车头过线**的间隔；这里只模拟"抬脚"这一下，不该再叠加半秒。
         * 压到 0.18 秒：单个司机仍有反应时间，整队却是一串连贯的涟漪。
         */
        const val START_REACTION_SEC = 0.18
        /** 前车速度超过这个值才算"它动了"（m/s，约 1 km/h）。原来 0.8 m/s ≈ 3 km/h，
         *  前车都滑出去一米多了才算数，白白又拖了 0.4 秒。 */
        const val START_LEADER_MS = 0.30
        /** 与前方车辆间距超过这个值（米）就算"头车"：绿灯一亮立刻走，不用等任何人。
         *  原来 12 m 太远 —— 车流本来就稀疏的路口，后车一直以为自己要排队。 */
        const val START_HEAD_GAP_M = 8.0
        /**
         * 起步助推：允许起步时，加速度至少给到 `maxAccel × 该比例`。
         *
         * 为什么必须有：IDM 在 `间距 ≈ minGap` 时算出来的加速度**正好是 0**
         * （`sStar = minGap`，`1 - (sStar/gap)² = 0`）—— 停着的车正停在平衡间距上，
         * 于是它只会"等前车把间距拉开再动"，起步像被黏在地上。
         * 现实里司机是**预判**的：看见前车动了就抬脚，不等自己这 2 米空隙真变宽。
         */
        const val START_BOOST_RATIO = 0.55
        /** 助推只在"间距比最小车距还多出这么多"时才给，否则一律交给 IDM 兜底（防追尾） */
        const val START_ROOM_MARGIN_M = 0.15

        /**
         * 规划路径（虚线箭头）的**近处**采样步长（米）：车头附近用细步长，箭头贴得准。
         */
        const val PATH_STEP_M = 3.5
        /**
         * 路径**远处**的采样步长（米）。
         *
         * 需求（本轮）：虚线必须一直画到**目标路口**，不能被距离截断。
         * 但全程 3.5m 采样在长路线上会画出上千个点（屏幕上一个点不到 1px，纯浪费），
         * 所以超过 [PATH_DETAIL_POINTS] 个点之后改用粗步长 —— 远处本来也看不清细节，
         * 而箭头是按**屏幕弧长**等距排布的，视觉上完全看不出差别。
         */
        const val PATH_COARSE_STEP_M = 14.0
        /** 前多少个点用 [PATH_STEP_M] 细采样（≈ 560m 的细节区） */
        const val PATH_DETAIL_POINTS = 160
        /**
         * 路径点数上限：纯粹的**病态保护**（防止路网被拖得极长时画布卡死）。
         * 按 [PATH_COARSE_STEP_M] 折算 ≈ 10km，正常路网永远碰不到。
         */
        const val PATH_MAX_POINTS = 900
        /**
         * 路径最多跨多少段弧：纯粹的兜底（正常路线一个路口一段，[PATH_MAX_POINTS] 才是真正
         * 盯得住的边界）。取 256 是为了让"绕来绕去的长路线"也不会被段数单独卡断。
         */
        const val PATH_MAX_ARCS = 256

        // ---------- 过路口转弯圆弧（需求 2）----------
        /**
         * 转弯圆弧的最小半径（米）：路口极窄时也不让半径退化到 0（否则等于原地打转）。
         * 只在"交汇区进深 < 1.2m"这种极端情况下才生效。
         */
        const val TURN_ARC_MIN_R = 1.2
        /** 转角小于此值视为直行，不生成圆弧（约 2°） */
        const val TURN_MIN_ANGLE_RAD = 0.035
        /**
         * 两条车道中心线的夹角正弦小于此值 → 视为平行（直行 / 掉头），圆弧构造退化。
         * 0.10 ≈ 6°：这么小的转角用一个半径巨大的圆弧去接，反而不如直接走直线自然。
         */
        const val TURN_ARC_MIN_SIN = 0.10

        // ---------- 视觉防重叠 ----------
        /** 车身"胶囊"圆的半径（略小于半车宽，允许贴边但不重叠） */
        const val BODY_DISC_R = VehicleKind.WIDTH_M * 0.5 - 0.05
        /** 单帧最多把车推开多少米（越小越平滑，但收敛越慢） */
        const val OVERLAP_MAX_PUSH_M = 1.2

        /** 车辆横向偏移：lane 号（0 = 最靠中线）→ 距道路中线的米数 */
        fun laneOffsetM(lane: Int): Double =
            RoadNetwork.LANE_WIDTH_M * (lane.coerceAtLeast(0) + 0.5)

        /** 同上，但允许**小数车道**——变道动画在两条车道之间插值时用 */
        fun laneOffsetM(lane: Double): Double =
            RoadNetwork.LANE_WIDTH_M * (lane.coerceAtLeast(0.0) + 0.5)
    }

    // ---------- 拓扑 ----------
    var arcsByKey: Map<String, Arc> = emptyMap(); private set
    var arcsFromNode: Map<Long, List<Arc>> = emptyMap(); private set
    private var arcsToNode: Map<Long, List<Arc>> = emptyMap()

    /** 各节点的停止线距离（米，节点中心 → 停止线）：= 相接道路最大半宽 + 余量 */
    private var nodeStopLine: Map<Long, Double> = emptyMap()

    /**
     * 每条弧两端"交汇区边缘"的内缩距离（米）。
     *
     * 与**标线渲染**用的是同一个 [RoadNetwork.junctionInsetM]，因此转弯圆弧的起终点必定落在
     * 车道线被截断的那个位置 —— 需求 2 的"曲线与车道边界精确衔接"靠这一条保证。
     * 拓扑重建时算一次（O(E²)，只在编辑路网时发生），运行期直接查表。
     */
    private var arcStartInset: Map<String, Double> = emptyMap()
    private var arcEndInset: Map<String, Double> = emptyMap()

    /**
     * **路口圆角缓存**：节点 id → 该节点上所有右转圆角（[CornerArc]）。
     *
     * 与路面渲染用的是**同一个** [JunctionGeometry]，所以"车跑的那条弧"与"画出来的那块圆角"
     * 必然是同一条曲线 —— 需求 1 的"全程沿圆角弧线行驶、不开进白色区域"就是靠这条保证的。
     * 拓扑重建时清空（车道数变化会改变半径与切点）。
     */
    private val cornerCache = HashMap<Long, List<CornerArc>>()

    /** 取"从 [arc] 驶入、从 [next] 驶出"这条右转角的圆弧；不是右转或几何退化时返回 null */
    private fun cornerBetween(arc: Arc, next: Arc): CornerArc? =
        cornerCache.getOrPut(arc.toNode) { JunctionGeometry.cornersOf(network, arc.toNode) }
            .firstOrNull { it.edgeIn == arc.edgeId && it.edgeOut == next.edgeId }

    /** 某节点的停止线距离（无记录时给单车道兜底值） */
    private fun stopLineOf(node: Long): Double =
        nodeStopLine[node] ?: (RoadNetwork.LANE_WIDTH_M + STOP_LINE_MARGIN_M)

    /**
     * 停车余量（米）：车体是**以 s 为中心**绘制的，所以要让"整个车身"都停在停止线之后，
     * 停车目标还要再退回半个车长。这样无论车身多长，车头都不会压到停止线上。
     */
    private fun stopOffsetM(v: Vehicle): Double = v.kind.lengthM * 0.5

    /** 本车在某弧上"允许到达的最大里程"（停在停止线之后）；该节点无停止线约束时返回弧长 */
    private fun stopLimitS(v: Vehicle, arc: Arc): Double =
        arc.length - stopLineOf(arc.toNode) - stopOffsetM(v)

    // ---------- 信号灯（本轮：三种可选规则）----------
    /**
     * 单个路口信号灯的运行状态机。
     *
     * 一个路口的配时被展开成一串 [Phase]（状态）：每个状态持续 `greenSec` 秒绿灯 + [YELLOW_SEC] 黄灯，
     * 然后切到下一个，循环。每个状态里明确写着**哪些进口、哪些转向可以走**（[Phase.allow]）。
     * 于是三种规则只是"生成不同的状态表"，运行期逻辑完全统一：
     *
     * | 规则 | 状态数 | 每状态绿灯 | 内容 |
     * |---|---|---|---|
     * | 一 | 2 | 35 s | 一个方向（上下 / 左右）的直行 + 左转一起放行 |
     * | 二 | 4 | 20 s | 一次只放一个方向的一种转向（直行 / 左转分开） |
     * | 三 | 4 | 20 s | 一次只放一个方向（上 / 下 / 左 / 右）的直行 + 左转 |
     *
     * **右转在任何规则、任何状态下都能走**（[stateOf] 直接返回绿），
     * 所以"右转无需等灯"不需要在任何状态表里体现。
     *
     * @param startPhase 初始放行的状态序号
     * @param startTimer 初始计时（0 ~ 该状态的一段长度）
     *
     * ⚠️ 为什么要传起始相位/计时：所有信号灯是在 `buildSignals()` 里**同一瞬间**创建的，
     * 若都从 (phase=0, timer=0) 起步，全城会严格同步 —— 绿灯 35 秒，就会出现
     * "所有路口同时变红、整张路网一起停"的假象。按节点 id 取一个稳定伪随机初值即可错开，
     * 且**同一存档每次加载都一致**（不会跳）。
     */
    private class Signal(
        val phases: List<Phase>,
        /** 需要单独灯头的转向（规则二：直行 / 左转各一个灯头；其它规则只有一个总灯头） */
        val splitHeads: Boolean,
        startPhase: Int = 0,
        startTimer: Double = 0.0,
    ) {
        /**
         * @param greenSec 该状态的绿灯时长（秒），黄灯另加 [YELLOW_SEC]
         * @param allow    进口弧 key → 允许的转向位掩码（[MOVE_STRAIGHT] / [MOVE_LEFT]）
         */
        class Phase(val greenSec: Double, val allow: Map<String, Int>) {
            val segSec: Double get() = greenSec + YELLOW_SEC
        }

        // startPhase 由 Random.nextInt 给出，必为非负，取模即可
        var phase: Int = if (phases.isEmpty()) 0 else startPhase % phases.size
        var timer: Double = startTimer

        fun step(dt: Double) {
            if (phases.isEmpty()) return
            timer += dt
            // 用 while 而不是 if：掉帧（dt 很大）时也能一次跨过多个状态，不会卡住
            while (timer >= phases[phase].segSec) {
                timer -= phases[phase].segSec
                phase = (phase + 1) % phases.size
            }
        }

        /** 0=绿 1=黄 2=红 —— 按"哪条进口 + 什么转向"分别判定 */
        fun stateOf(arcKey: String, move: TurnDir): Int {
            if (move == TurnDir.RIGHT) return 0            // 右转随时可走，永不红
            val p = phases.getOrNull(phase) ?: return 0     // 状态表异常 → 放行，避免死锁
            val mask = p.allow[arcKey] ?: return 2          // 该进口不在本状态放行名单里 → 红
            if (mask and moveBit(move) == 0) return 2
            return if (timer < p.greenSec) 0 else 1
        }

        fun isGreen(arcKey: String, move: TurnDir): Boolean = stateOf(arcKey, move) == 0
    }

    /** 各信号灯路口：节点 id → 信号灯状态（仅本类内部使用） */
    private var signals: Map<Long, Signal> = emptyMap()

    /**
     * 一个**灯头**的几何信息（位置 + 朝向），只有颜色是逐帧变化的。
     *
     * 灯头位置只取决于路网形状与车道数（弧长、切向、路缘外偏距），跟信号相位无关，
     * 所以整份几何只在拓扑重建时算一次。逐帧渲染时只查 `stateOf()` 取颜色，
     * 省掉每帧上千次 `pointAtS` / `tangentAtS` / `perp` 的三角函数运算。
     */
    private class SignalHeadGeom(
        val nodeId: Long,
        val arcKey: String,
        val x: Double, val y: Double, val angleRad: Double,
        val leftX: Double, val leftY: Double, val hasLeft: Boolean,
    )

    /** 灯头几何缓存（见 [SignalHeadGeom]）；拓扑变化 / 灯组重建时置空 */
    private var signalHeadCache: List<SignalHeadGeom>? = null

    /** 停止线缓存：位置同样只依赖路网，逐帧复用同一个 list */
    private var stopLineCache: List<StopLineRender>? = null

    // ---------- 运行时 ----------
    val vehicles = mutableListOf<Vehicle>()
    var simTime = 0.0; private set
    var totalSpawned = 0; private set
    var totalArrived = 0; private set
    var totalReroutes = 0; private set
    /** 累计自主变道次数（多车道自主变道每触发一次记一次，需求 2） */
    var totalLaneChanges = 0; private set
    var crashedJunctionId: Long? = null; private set
    private var nextVehicleId = 1L

    private class JunctionState(var spawnBudget: Double = 0.0, var blockedTime: Double = 0.0)
    private val junctionStates = mutableMapOf<Long, JunctionState>()

    private val arcSpeedEst = mutableMapOf<String, Double>()

    /**
     * 「(弧, 目的地) → 到路口的转向」缓存。
     *
     * 信号灯、让行、灯头渲染每帧都会反复问同一批问题，而车的路线每 2 秒才变一次；
     * 缓存后单帧开销从 O(车数 × 出度) 降到几乎为零。路由重算时清表（[recomputeRoutes]），
     * 所以永远不会用到过期的转向判断。
     */
    private val moveMemo = HashMap<String, TurnDir>()

    private var routeRefreshTimer = 0.0
    private var distToJunction: Map<Long, Map<Long, Double>> = emptyMap()

    /**
     * **最短距离场**（米）：`target 路口 id → (节点 → 沿最短路线到 target 的总米数)`。
     *
     * 与 [distToJunction]（耗时场）刻意分开，因为两者的更新时机完全不同：
     *  - 距离场只跟**路网拓扑**有关（弧长、连通性），拥堵再严重也不会变 →
     *    只在 [rebuildTopology] 之后重算一次，用 [lengthFieldDirty] 标记；
     *  - 耗时场跟实时车速有关 → 每 [ROUTE_REFRESH_SECONDS] 秒重算。
     *
     * 车辆**生成时**用它选路（[RouteMode.SHORTEST]），这就是"初始路线优先走最短道路"的落点。
     */
    private var distByLength: Map<Long, Map<Long, Double>> = emptyMap()

    /** 路网变过 → 距离场需要在下次 [recomputeRoutes] 时重算 */
    private var lengthFieldDirty = true

    /** 有车辆因拥堵要求立即刷新路由时置位（每帧至多刷新一次，避免 Dijkstra 风暴） */
    private var pendingRouteRefresh = false

    init {
        rebuildTopology()
    }

    // ==================== 拓扑 ====================

    /** 路网变化后重建弧表 + 信号灯（编辑期间车辆已清空，因此这里不需要迁移车辆） */
    fun rebuildTopology() {
        val newArcs = mutableListOf<Arc>()
        for (e in network.edges) {
            val path = network.pathOf(e) ?: continue
            if (path.length < 1.0) continue
            newArcs += Arc(e.id, true, e.from, e.to, path, e.lanes)
            newArcs += Arc(e.id, false, e.to, e.from, path, e.lanes)
        }
        arcsByKey = newArcs.associateBy { it.key }
        arcsFromNode = newArcs.groupBy { it.fromNode }
        arcsToNode = newArcs.groupBy { it.toNode }
        // 逐节点求"路口净空半径"= 相接道路最大半宽 + 余量（停止线/冲突区都以它为准）
        val stopMap = HashMap<Long, Double>()
        for (a in newArcs) {
            val r = a.lanes * RoadNetwork.LANE_WIDTH_M + STOP_LINE_MARGIN_M
            stopMap[a.toNode] = max(stopMap[a.toNode] ?: 0.0, r)
            stopMap[a.fromNode] = max(stopMap[a.fromNode] ?: 0.0, r)
        }
        nodeStopLine = stopMap
        // 每条弧两端的"路口边界"（= 标线截断点 = 转弯圆弧起终点 = 禁变道区起点）。
        // 本轮需求 2：改用 RoadNetwork.junctionBoundaryM —— 它取「对方半宽/夹角的放大值」
        // 与「本路口最大半宽」的较大者，于是**任何车道数组合**的标线都停在同一条边界上，
        // 不会再出现"三车道那边中心线穿进路口、单车道那边却早早截断"的错位。
        val insStart = HashMap<String, Double>(newArcs.size)
        val insEnd = HashMap<String, Double>(newArcs.size)
        for (e in network.edges) {
            val iFrom = network.junctionBoundaryM(e, e.from)
            val iTo = network.junctionBoundaryM(e, e.to)
            insStart["${e.id}:F"] = iFrom
            insEnd["${e.id}:F"] = iTo
            insStart["${e.id}:B"] = iTo
            insEnd["${e.id}:B"] = iFrom
        }
        arcStartInset = insStart
        arcEndInset = insEnd
        cornerCache.clear()          // 车道数/几何变了 → 右转角圆弧要重算
        // 停止线 / 灯头的位置全部来自上面这几个 inset 与弧几何 → 一并失效
        stopLineCache = null
        signalHeadCache = null
        lengthFieldDirty = true      // 拓扑变了 → 最短距离场必须重算（拥堵场每次刷新都会重算）
        buildSignals()
        vehicles.removeAll { it.arcKey !in arcsByKey }
        recomputeRoutes()
    }

    // ==================== 信号灯（三种可配置规则） ====================

    /**
     * 只要节点是**丁字路口或十字路口**（进边数 ≥ 3）就配信号灯——单车道 / 多车道一视同仁，
     * 因为"是否配灯"看的是**相交道路的条数**，与每条路有几条车道无关。
     *
     * 具体放行顺序由该路口的**规则**决定（[RoadNetwork.signalRuleOf]，用户可在编辑模式里改）：
     *  - **规则一**：直行 + 左转一起放行，两个方向交替（每状态 35 秒）；
     *  - **规则二**：直行 / 左转分开放行，四个状态轮转（每状态 20 秒）；
     *  - **规则三**：四方向轮流放行，一次只放一个方向（每状态 20 秒）。
     *
     * 三种规则共用同一套"状态表"结构（[Signal.Phase]），差别只在 [planPhases] 怎么造表。
     * 右转不在任何状态表里 —— 它由 [Signal.stateOf] 直接判绿，所以"右转无需等灯"是天然成立的。
     *
     * 各路口用节点 id 作种子取稳定的初始相位 / 计时，**错开相位**，不会全城同时变红。
     */
    private fun buildSignals() {
        // 灯组可能整体重建（开关切换 / 规则改变会改变 splitHeads）→ 灯头几何必须重算
        signalHeadCache = null
        if (!settings.trafficLightsEnabled) {
            signals = emptyMap()
            return
        }
        val result = mutableMapOf<Long, Signal>()
        for (node in network.nodes.values) {
            val incoming = arcsToNode[node.id].orEmpty()
            if (incoming.size < 3) continue
            // 需求 6：用户手工"删除红绿灯"的路口不再配灯（开关随存档持久化）
            if (!network.signalEnabled(node.id)) continue
            val rule = network.signalRuleOf(node.id)
            val phases = planPhases(incoming, rule)
            if (phases.isEmpty()) continue
            val rnd = Random(node.id)
            val startPhase = rnd.nextInt(phases.size)
            result[node.id] = Signal(
                phases = phases,
                splitHeads = rule == 2,     // 规则二"直行 / 左转分开"，两种灯头各亮各的
                startPhase = startPhase,
                startTimer = rnd.nextDouble() * phases[startPhase].segSec,
            )
        }
        signals = result
    }

    // ---------- 规则 → 状态表 ----------

    /**
     * 把某路口的进口按规则展开成一串放行状态。
     *
     * 所有规则都建立在两种分组之上：
     *  - **轴组**（[axisGroupsOf]）：`heading mod π` 相近的进口归一组 —— 正对的两条进口
     *    （"上"与"下"、"左"与"右"）自然同组，也就是"一个方向的两个行驶朝向"；
     *  - **单臂组**（[armGroupsOf]）：按屏幕方位把进口分成上 / 下 / 左 / 右四类。
     *
     * 边界处理：
     *  - 规则一 / 二需要至少 2 个轴组（丁字、十字都满足）。若几何过于古怪（例如 X 形斜交
     *    导致聚出 1 组或 3 组以上），**自动退化为规则三**（单臂轮流放行）——保证任何路口
     *    都有一套不会互相冲突的配时，而不是"这个路口干脆没灯"。
     *  - 丁字路口只有 3 个进口，规则三就只生成 3 个状态，缺的方向自动跳过。
     */
    private fun planPhases(incoming: List<Arc>, rule: Int): List<Signal.Phase> {
        val headings = incoming.map { it.tangentAtS(it.length).angleRad }
        val axis = axisGroupsOf(headings)
        val axisCount = (axis.maxOrNull() ?: -1) + 1
        val arm = armGroupsOf(headings)

        return when {
            rule == 1 && axisCount >= 2 -> ruleOnePhases(incoming, axis, axisCount)
            rule == 2 && axisCount >= 2 -> ruleTwoPhases(incoming, axis, axisCount)
            else -> ruleThreePhases(incoming, arm)
        }
    }

    /** 规则一：每个轴组一个状态，**直行 + 左转一起绿** */
    private fun ruleOnePhases(incoming: List<Arc>, axis: List<Int>, axisCount: Int): List<Signal.Phase> =
        (0 until axisCount).map { g ->
            val allow = HashMap<String, Int>()
            incoming.forEachIndexed { i, a ->
                if (axis[i] == g) allow[a.key] = MOVE_STRAIGHT or MOVE_LEFT
            }
            Signal.Phase(RULE1_GREEN_SEC, allow)
        }

    /**
     * 规则二：每个轴组两个状态（**先直行、后左转**），四个状态轮转。
     * 正是需求里的顺序：①上下直行 ②上下左转 ③左右直行 ④左右左转。
     */
    private fun ruleTwoPhases(incoming: List<Arc>, axis: List<Int>, axisCount: Int): List<Signal.Phase> {
        val out = ArrayList<Signal.Phase>(axisCount * 2)
        for (g in 0 until axisCount) {
            val straight = HashMap<String, Int>()
            val left = HashMap<String, Int>()
            incoming.forEachIndexed { i, a ->
                if (axis[i] == g) {
                    straight[a.key] = MOVE_STRAIGHT
                    left[a.key] = MOVE_LEFT
                }
            }
            out += Signal.Phase(RULE2_GREEN_SEC, straight)
            out += Signal.Phase(RULE2_GREEN_SEC, left)
        }
        return out
    }

    /**
     * 规则三：每个**单臂**一个状态，一次只放一个方向（上 → 下 → 左 → 右），
     * 该方向的直行 + 左转一起绿。
     */
    private fun ruleThreePhases(incoming: List<Arc>, arm: List<Int>): List<Signal.Phase> {
        // armOf 的取值 0=上 1=下 2=左 3=右；按这个固定顺序生成状态（缺的方向跳过）
        val out = ArrayList<Signal.Phase>(4)
        for (dir in 0..3) {
            val allow = HashMap<String, Int>()
            incoming.forEachIndexed { i, a ->
                if (arm[i] == dir) allow[a.key] = MOVE_STRAIGHT or MOVE_LEFT
            }
            if (allow.isNotEmpty()) out += Signal.Phase(RULE3_GREEN_SEC, allow)
        }
        return out
    }

    /**
     * **轴组**：`heading mod π` 上贪心聚类（容差 35°），正对的两条进口落到同一组。
     * 组号按"越接近竖直方向越靠前"重排：规则一 / 二的状态顺序因此稳定为
     * ①上下 ②左右（而不是随路网绘制顺序乱跳）。
     */
    private fun axisGroupsOf(headings: List<Double>): List<Int> {
        val half = headings.map { normalizeHalfPi(it) }
        val raw = clusterHalfPi(half, Math.toRadians(35.0))
        // 每个原始组的"代表方向"（单位向量之和再取半角）→ 按 |cos| 升序（越竖直越前）
        val count = (raw.maxOrNull() ?: -1) + 1
        val verticalness = (0 until count).map { g ->
            var sx = 0.0
            var sy = 0.0
            half.forEachIndexed { i, a -> if (raw[i] == g) { sx += cos(a); sy += sin(a) } }
            abs(cos(atan2(sy, sx)))      // cos 越小 → 越接近竖直
        }
        val order = (0 until count).sortedBy { verticalness[it] }
        return raw.map { order.indexOf(it) }
    }

    /**
     * **单臂组**：把行驶方向按屏幕方位分成 0=上 / 1=下 / 2=左 / 3=右。
     *
     * 注意屏幕 y 轴向下：`sin < 0` 才是"往上开"。分界取 ±45°、±135°，四个象限一一对应。
     */
    private fun armGroupsOf(headings: List<Double>): List<Int> =
        headings.map { h ->
            val a = atan2(sin(h), cos(h))     // 归一到 (−π, π]
            when {
                a > -3.0 * PI / 4 && a <= -PI / 4 -> 0    // 向上
                a > PI / 4 && a <= 3.0 * PI / 4 -> 1      // 向下
                a <= -3.0 * PI / 4 || a > 3.0 * PI / 4 -> 2  // 向左
                else -> 3                                  // 向右
            }
        }

    /** 方向角归一到 [0, π)（正对的两条进口落在同一取值上） */
    private fun normalizeHalfPi(angle: Double): Double {
        var a = angle % Math.PI
        if (a < 0) a += Math.PI
        return a
    }

    /** [0, π) 上的环形距离 */
    private fun halfPiDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % Math.PI
        return min(d, Math.PI - d)
    }

    /** 贪心聚类：与任一簇代表的角度差 ≤ tol 就归入该簇，否则新建簇。返回每个元素所属簇号 */
    private fun clusterHalfPi(angles: List<Double>, tol: Double): List<Int> {
        val reps = mutableListOf<Double>()
        val res = IntArray(angles.size)
        for (i in angles.indices) {
            val a = angles[i]
            var found = -1
            for (j in reps.indices) {
                if (halfPiDistance(a, reps[j]) <= tol) { found = j; break }
            }
            if (found < 0) { reps += a; found = reps.size - 1 }
            res[i] = found
        }
        return res.toList()
    }

    private fun stepSignals(dt: Double) {
        for (s in signals.values) s.step(dt)
    }

    /**
     * 本车在某条进口弧上、按它**当前的目的地**，到路口会怎么走（左转 / 直行 / 右转）。
     *
     * 结果按 `(弧, 目的地)` 记忆化：同一帧里信号灯、让行、灯头渲染都会问很多次，
     * 而路由每 2 秒才变一次，所以命中率很高。路由重算时清表（见 [recomputeRoutes]）。
     */
    private fun movementOn(arc: Arc, target: Long, mode: RouteMode): TurnDir {
        val cacheKey = "${arc.key}|$target|${mode.name}"
        moveMemo[cacheKey]?.let { return it }
        val next = chooseArc(arc.toNode, target, mode)
        val m = if (next == null || next.key == arc.key) {
            TurnDir.STRAIGHT
        } else {
            turnDirOf(arc.tangentAtS(arc.length), next.tangentAtS(0.0))
        }
        moveMemo[cacheKey] = m
        return m
    }

    /** 本车"按它当前的路径偏好"在这条弧末端会怎么走 */
    private fun movementOf(v: Vehicle, arc: Arc): TurnDir =
        movementOn(arc, v.targetJunction, v.routeMode)

    /**
     * 红灯 / 黄灯 → 停在停止线外所需的间距（米）；绿灯或无需停车返回 null。
     *
     * 停止线位置 = 该路口的净空半径（相接道路最大半宽 + 余量），多车道道路也因此不会
     * 把车停在横向车道里。
     *
     * **右转一律返回 null**：三种规则下右转都无需等灯（需求原文"任意时刻均可右转无需等待"）。
     * 右转的安全由路口让行保证（转弯让直行，见 [junctionYieldGap]）。
     */
    private fun signalStopGap(v: Vehicle): Double? {
        val arc = arcsByKey[v.arcKey] ?: return null
        val sig = signals[arc.toNode] ?: return null
        if (movementOf(v, arc) == TurnDir.RIGHT) return null   // 右转不看灯
        val stop = stopLineOf(arc.toNode)
        val d = arc.length - v.s
        if (d > SIGNAL_APPROACH_M) return null     // 还远，不必理会
        // 严格小于：**正好停在停止线上**（硬夹取的结果）仍算"未进入"，
        // 否则红灯车会因 d == stop 被当成"已进入"而直接闯红灯。
        if (d < stop) return null                  // 确实已越过停止线 → 视为已进入，继续通行
        if (sig.isGreen(v.arcKey, movementOf(v, arc))) return null
        return (d - stop - stopOffsetM(v)).coerceAtLeast(0.0)
    }

    /**
     * 是否处于**等信号灯**状态（需求 2）：
     *  · [sigGap] 非空 —— 红灯 / 黄灯，且车还没越过停止线，正被这盏灯拦着；
     *  · 队列可能比 [SIGNAL_APPROACH_M] 还长，所以再补一条兜底：本弧末端是信号灯路口、
     *    这个方向不是绿灯、而车已经停住或只剩蠕行速度 → 也算在排队等灯。
     *
     * 绿灯亮起（[Signal.isGreen]）或车已越过停止线 → 立刻返回 false，
     * 也就是"结束等待后马上恢复变道能力"。
     */
    private fun isWaitingAtSignal(v: Vehicle, arc: Arc, sigGap: Double?): Boolean {
        if (sigGap != null) return true
        val sig = signals[arc.toNode] ?: return false
        // 右转不受灯控 → 永远不算"等灯"（否则右转车会被误判成排队而停止变道对齐）
        val move = movementOf(v, arc)
        if (move == TurnDir.RIGHT) return false
        if (sig.isGreen(v.arcKey, move)) return false
        return v.speed < QUEUE_CRAWL_MS
    }

    // ==================== 路由（两套距离场） ====================

    /** **耗时**权值：这条弧按当前实测车速要开多久（秒）。拥堵时变大 → 触发绕行 */
    private fun arcCost(arc: Arc): Double {
        val v = arcSpeedEst[arc.key] ?: FREE_FLOW_MS
        return arc.length / max(v, 1.5)
    }

    /** **距离**权值：这条弧有多长（米）。与拥堵无关，只跟几何有关 */
    private fun arcLength(arc: Arc): Double = arc.length

    /**
     * 对每个开放路口跑一次**反向 Dijkstra**：任意节点开到该路口的"累计代价"。
     *
     * @param weight 单条弧的代价函数（米 或 秒），由调用方传入 —— 这样同一段代码
     *               既能算「最短距离场」也能算「最快耗时场」。
     */
    private fun dijkstraBy(weight: (Arc) -> Double): Map<Long, Map<Long, Double>> {
        val result = mutableMapOf<Long, Map<Long, Double>>()
        for (target in network.openNodes()) {
            val dist = mutableMapOf(target.id to 0.0)
            val pq = java.util.PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
            pq += target.id to 0.0
            while (pq.isNotEmpty()) {
                // poll() 返回可空；此处虽已有 isNotEmpty 守卫，仍显式判空以避免可空接收者警告
                val top = pq.poll() ?: break
                val u = top.first
                val d = top.second
                if (d > (dist[u] ?: Double.MAX_VALUE) + 1e-9) continue
                for (arc in arcsToNode[u].orEmpty()) {
                    val nd = d + weight(arc)
                    val old = dist[arc.fromNode] ?: Double.MAX_VALUE
                    if (nd < old - 1e-9) {
                        dist[arc.fromNode] = nd
                        pq += arc.fromNode to nd
                    }
                }
            }
            result[target.id] = dist
        }
        return result
    }

    /** 重算耗时场（每 2 秒）；距离场只在拓扑变过时补算（见 [lengthFieldDirty]） */
    private fun recomputeRoutes() {
        distToJunction = dijkstraBy { arcCost(it) }
        ensureLengthField()
        // 路由变了 → "某条弧走到路口会怎么转"也可能变 → 清掉转向缓存
        moveMemo.clear()
    }

    /**
     * **按需**构建最短距离场。没变就什么都不做（一次布尔判断）。
     *
     * 独立成函数是为了让 [chooseArc] 也能兜底调用：即使某次拓扑重建的顺序出现意外，
     * `RouteMode.SHORTEST` 也绝不会因为"距离场还是空的"而返回 null（那会让车停在路口不动）。
     */
    private fun ensureLengthField() {
        if (!lengthFieldDirty) return
        distByLength = dijkstraBy { arcLength(it) }
        lengthFieldDirty = false
        moveMemo.clear()             // 转向判断依赖距离场，场变了就作废
    }

    /**
     * 站在 nodeId 处、要去 targetId，按 [mode] 选最优下一段弧。
     *
     * [mode] **故意不给默认值**：路线选择决定"走哪条路、到哪个路口怎么转"，
     * 调错一次整车的轨迹就全错了（比如生成时误用耗时场会绕过短路口）。
     * 强制调用方写明意图，编译器就能帮我们挡住这类错误。
     */
    fun chooseArc(nodeId: Long, targetId: Long, mode: RouteMode): Arc? {
        if (mode == RouteMode.SHORTEST) ensureLengthField()
        // 括号不能省：`?:` 只应作用于整个 if 表达式的结果
        val dist = (if (mode == RouteMode.SHORTEST) distByLength[targetId] else distToJunction[targetId])
            ?: return null
        val candidates = arcsFromNode[nodeId].orEmpty()
        var best: Arc? = null
        var bestCost = Double.MAX_VALUE
        for (arc in candidates) {
            val remaining = if (arc.toNode == targetId) 0.0 else (dist[arc.toNode] ?: Double.MAX_VALUE / 2)
            val own = if (mode == RouteMode.SHORTEST) arcLength(arc) else arcCost(arc)
            val cost = own + remaining
            if (cost < bestCost) {
                bestCost = cost
                best = arc
            }
        }
        return best
    }

    /** 走 [arc] 之后到 [target] 的**纯距离**剩余（米）；不可达返回极大值（生成时排序用） */
    private fun lengthRemaining(arc: Arc, target: Long): Double {
        ensureLengthField()
        val d = distByLength[target] ?: return Double.MAX_VALUE / 2
        val rest = if (arc.toNode == target) 0.0 else (d[arc.toNode] ?: return Double.MAX_VALUE / 2)
        return arc.length + rest
    }

    private fun updateCongestion(dt: Double) {
        val byArc = vehicles.filter { !it.arrived }.groupBy { it.arcKey }
        for (key in arcsByKey.keys) {
            val onArc = byArc[key]
            val old = arcSpeedEst[key] ?: FREE_FLOW_MS
            val new = if (onArc.isNullOrEmpty()) {
                min(FREE_FLOW_MS, old + 5.0 * dt)
            } else {
                val avg = onArc.sumOf { it.speed } / onArc.size
                0.5 * old + 0.5 * max(avg, 0.5)
            }
            arcSpeedEst[key] = new
        }
    }

    // ==================== 车道工具 ====================

    /** 车道桶的键：同一条弧 + 同一车道 */
    private fun laneKey(arcKey: String, lane: Int) = "$arcKey#$lane"

    private fun lanesByArcLane(active: List<Vehicle>): Map<String, List<Vehicle>> =
        active.groupBy { laneKey(it.arcKey, it.lane) }
            .mapValues { (_, list) -> list.sortedByDescending { it.s } }

    /** 某条弧某条车道的**尾部净空**（米）：最近一辆车的车尾到弧起点还有多远 */
    private fun roomOn(arc: Arc, lane: Int, byLane: Map<String, List<Vehicle>>): Double {
        val rear = byLane[laneKey(arc.key, lane)]?.lastOrNull()
        return if (rear == null) arc.length else rear.s - rear.kind.lengthM
    }

    /**
     * 换弧时选入口车道。
     *
     * **第一优先是"别动"**：本车现在这条车道只要落在允许区间里，就直接进这条 ——
     * 换弧瞬间的横向偏移必须与转弯圆弧终点完全一致，否则画面上就是一次瞬移
     * （用户看到的就是"在十字路口莫名变道"）。只有当前车道不合法、或尾部塞不下时，
     * 才按"首选车道 → 更靠外 → 更靠内"的顺序另找。
     *
     * @param allowed      本弧末端转向允许的车道区间（[allowedLanes]）
     * @param currentLane  换弧前所在车道（可能是"本次转向不允许"的那条，兜底时仍优先不动）
     * @return null = 都排满，调用方停在路口前等
     */
    private fun pickEntryLane(
        next: Arc,
        allowed: IntRange,
        currentLane: Int,
        byLane: Map<String, List<Vehicle>>,
        needRoom: Double,
    ): Int? {
        val maxLane = (next.lanes - 1).coerceAtLeast(0)
        val lo = allowed.first.coerceIn(0, maxLane)
        val hi = allowed.last.coerceIn(0, maxLane)
        val cur = currentLane.coerceIn(0, maxLane)
        val order = buildList {
            if (cur in lo..hi) add(cur)          // ① 原地不动
            add(lo)                              // ② 首选车道
            for (l in (lo + 1)..hi) add(l)       // ③ 区间内其它车道
            for (l in (lo - 1) downTo 0) add(l)  // ④ 更靠内
            for (l in (hi + 1)..maxLane) add(l)  // ⑤ 更靠外
        }.distinct()
        for (l in order) {
            if (roomOn(next, l, byLane) >= needRoom) return l
        }
        return null
    }

    /**
     * 需求 5：在某条弧上行驶、要去 [target] 时，**该弧末端路口允许使用**的车道区间。
     *
     * 返回 null = 这条弧末端没有可走的下一段（终点开放路口 / 断头路）：
     * 此时车道号没有意义，**不该为了"选道"去并线** —— 否则车会在终点前莫名其妙变道。
     */
    private fun laneGoalRange(arc: Arc, target: Long, mode: RouteMode): IntRange? {
        val node = network.node(arc.toNode) ?: return null
        if (node.type == NodeType.OPEN && node.id == target) return null   // 到终点
        if (chooseArc(arc.toNode, target, mode) == null) return null       // 断头路
        val n = arc.lanes
        val r = allowedLanes(n, movementOn(arc, target, mode))
        return r.first.coerceIn(0, n - 1)..r.last.coerceIn(0, n - 1)
    }

    /** 只要"首选车道"的简化版（生成 / 换弧用）；末端无路时退回 0 */
    private fun laneGoalOn(arc: Arc, target: Long, mode: RouteMode): Int =
        laneGoalRange(arc, target, mode)?.first ?: 0

    /**
     * **本次转弯**要求进入的车道区间（按"这一转弯"的转向算，而不是下一段路末端要往哪走）。
     *
     * 为什么不用 [laneGoalRange]：那是"进这条路后**到路尽头**要用的车道"。若拿它当入口车道，
     * 一辆右转车如果下一个路口要左转，就会从"最外一条车道之外"一路斜切到最内侧车道 ——
     * 半径巨大、横扫整条路。正确做法是：**本次右转只进最右道**，进路之后再按下一段路线
     * 一格一格对齐（[stepLaneChange]）。
     */
    private fun entryLanesFor(arc: Arc, next: Arc, target: Long, mode: RouteMode): IntRange {
        val n = next.lanes.coerceAtLeast(1)
        val r = allowedLanes(n, movementOn(arc, target, mode))
        return r.first.coerceIn(0, n - 1)..r.last.coerceIn(0, n - 1)
    }

    /**
     * 取**本段弧的车道目标**（粘性）：同一段路只算一次，见 [Vehicle.laneGoalArcKey]。
     * 换了弧（或目的地被改 / 路径偏好被改）才会重算。
     */
    private fun stickyLaneGoal(arc: Arc, v: Vehicle): IntRange? {
        if (v.laneGoalArcKey == arc.key) return v.laneGoal
        v.laneGoalArcKey = arc.key
        v.laneGoal = laneGoalRange(arc, v.targetJunction, v.routeMode)
        v.laneGoalRight = movementOn(arc, v.targetJunction, v.routeMode) == TurnDir.RIGHT
        return v.laneGoal
    }

    // ==================== 车道对齐（需求 1：车道由预设路线决定） ====================

    /**
     * 车道**对齐** —— 需求 1："车道选择规则按预设路线执行"。
     *
     * 与旧版"被慢车压着才自主变道"的根本区别：**变道的目标由路线唯一决定**。
     * 本车当前弧末端要左转就站最左、右转就站最右、直行就站规定车道（[allowedLanes] 区间）；
     * 一旦发现自己不在这条车道上（进路口时被挤到别的道、或路线刷新后目标变了），
     * 就**一格一格**地朝它并过去，绝不越过、也不为超车离开。
     *
     * 抑制条件：
     *  - 已在目标区间内 → 完全不变道（这就是"三车道直行车辆可以走中间也可以走最右"的落地方式）；
     *  - 目标**粘在本段弧上**（[stickyLaneGoal]），路由刷新不会让车在路段中间来回并线；
     *  - **路口交汇区域内一律不变道**（[inJunctionZone]）：从车道线被路口截断的位置起，
     *    到完全驶出交汇区为止都不许并线 —— 需求原文"任何车辆在路口交汇区域不得变道，
     *    直至完全进入目标车道后方可变道"；
     *  - 正在过转弯圆弧（[Vehicle.turnActive]）→ 不变道：车正在弯上，横向再挪会破坏轨迹；
     *  - 正在等红灯 / 黄灯（[Vehicle.waitingSignal]）→ 不变道，绿灯起步后自动恢复。
     *
     * 注意：变道只改车道序号、不改 s；本帧 [byLane] 仍是旧快照，但帧末硬防撞会按新的
     * (弧, 车道) 重新分桶，所以不会漏检。
     */
    private fun stepLaneChange(v: Vehicle, dt: Double, byLane: Map<String, List<Vehicle>>) {
        // 冷却先走时（即使当前在单车道道路上，也要正常倒数）
        if (v.laneChangeCooldown > 0.0) v.laneChangeCooldown -= dt
        // 本帧可能刚跨过路口换了弧 —— 必须按**当前弧**取车道数，不能用步进开始时的局部变量
        val arc = arcsByKey[v.arcKey] ?: run { v.followSlowTime = 0.0; return }
        if (v.arrived) { v.followSlowTime = 0.0; return }
        // 等红灯 / 黄灯的车不得变道 —— 绿灯亮起、车重新起步后能力自动恢复
        if (v.waitingSignal) { v.followSlowTime = 0.0; return }
        // 正在过转弯圆弧：轨迹是圆弧，横向再挪会破坏"与车道相切"的衔接。
        // 注意只在**真的进到圆弧上**之后才禁；圆弧是提前十几米建好的（右转圆角尤甚），
        // 若挂上就禁，车会在整段路最后 20m 里失去对齐能力。
        if (v.turnActive && v.arcKey == v.turnPreArcKey && v.s >= v.turnPreStartS) {
            v.followSlowTime = 0.0; return
        }
        // 路口交汇区域内一律不得变道（无论是哪个方向的车、有没有灯）
        if (inJunctionZone(v, arc)) { v.followSlowTime = 0.0; return }

        // 需求 5：目标是一个**区间**（三车道直行时中间与最右都合法），已在区间里就一步不动。
        // 目标**锁在本段弧**上（[stickyLaneGoal]）：路由每 2 秒刷新一次，若跟着刷新反复改目标，
        // 车就会在路段中间来回并线。
        val goalRange = stickyLaneGoal(arc, v) ?: run { v.followSlowTime = 0.0; return }
        if (v.lane in goalRange) { v.followSlowTime = 0.0; return }

        // 一次只走一格：朝允许区间靠拢
        val step = when {
            v.lane < goalRange.first -> v.lane + 1
            v.lane > goalRange.last -> v.lane - 1
            else -> { v.followSlowTime = 0.0; return }
        }
        if (step < 0 || step > arc.lanes - 1) { v.followSlowTime = 0.0; return }

        // 稳定开一小会儿再并线（避免刚出路口就甩头）；冷却期内不再并。
        v.followSlowTime += dt
        if (v.followSlowTime < LANE_ALIGN_DELAY_SEC) return
        if (v.laneChangeCooldown > 0.0) return
        if (!laneChangeSafe(v, step, arc, byLane)) return

        // 只启动动画：逻辑车道立即生效（跟车/防撞按新车道），显示位置随后平滑滑过去
        v.beginLaneChange(step, LANE_CHANGE_ANIM_SEC)
        // 车道变了 → 转弯圆弧的起点偏移也变了，撤掉重算（否则进弯那一刻会横向跳一下）
        v.clearTurn()
        v.turnSkipArcKey = null
        totalLaneChanges++                    // 累计变道次数 +1
        v.followSlowTime = 0.0
        v.laneChangeCooldown = LANE_ALIGN_COOLDOWN_SEC
    }

    /**
     * 是否处在**路口交汇区域**内（本轮需求：这里绝对不许变道）。
     *
     * 判定用的就是**画标线 / 建转弯圆弧的那一套内缩量**（[arcEndInset] / [arcStartInset]）：
     *  - 接近段：本弧末端有交汇区，且车已进入"车道线被截断"之后的那一段；
     *  - 出口段：本弧起点有交汇区，且车还没走完交汇区长度。
     *
     * 于是"禁变道区"与画面上看到的路口区域**完全重合** —— 车不会在路口里横移，
     * 也不会刚出路口就贴着路口边缘抢道（各多留 [LANE_LOCK_MARGIN_M] 米）。
     * 与信号灯无关：没配灯的路口同样禁止，这正是"任何车辆"的含义。
     */
    private fun inJunctionZone(v: Vehicle, arc: Arc): Boolean {
        val endInset = arcEndInset[arc.key] ?: 0.0
        if (endInset > 0.0 && arc.length - v.s <= endInset + LANE_LOCK_MARGIN_M) return true
        val startInset = arcStartInset[arc.key] ?: 0.0
        if (startInset > 0.0 && v.s <= startInset + LANE_LOCK_MARGIN_M) return true
        return false
    }

    /** 目标车道是否"安全"：与本车位置处的前后车都留有足够间距 */
    private fun laneChangeSafe(
        v: Vehicle,
        lane: Int,
        arc: Arc,
        byLane: Map<String, List<Vehicle>>,
    ): Boolean {
        val list = byLane[laneKey(arc.key, lane)].orEmpty()   // 按 s 降序
        // 降序列表里：第一个 s > v.s 的就是"最近的同车道前车"；
        // 第一个 s <= v.s 的就是"最近的跟车"（再往后是更远的，不是我们关心的）。
        val front = list.firstOrNull { it.s > v.s }
        val rear = list.firstOrNull { it.s <= v.s }
        val frontGap = if (front == null) Double.MAX_VALUE else front.s - front.kind.lengthM - v.s
        val rearGap = if (rear == null) Double.MAX_VALUE else v.s - rear.kind.lengthM - rear.s
        val needFront = v.kind.minGap + v.speed * 1.0 + 3.0
        val needRear = if (rear == null) Double.MAX_VALUE else rear.kind.minGap + rear.speed * 0.6 + 3.0
        return frontGap >= needFront && rearGap >= needRear
    }

    // ==================== IDM 跟车模型（含反应时间） ====================

    private fun idmAccel(v: Vehicle, gap: Double?, leaderSpeed: Double?): Double {
        val p = v.kind
        val freeTerm = 1.0 - (v.speed / max(v.desiredSpeed, 1.0)).pow(4)
        val acc = if (gap == null) {
            p.maxAccel * freeTerm
        } else {
            val dv = v.speed - (leaderSpeed ?: 0.0)
            val sStar = p.minGap + max(
                0.0,
                v.speed * p.headwayTime + v.speed * dv / (2 * sqrt(p.maxAccel * p.comfyDecel))
            )
            p.maxAccel * (freeTerm - (sStar / max(gap, 0.5)).pow(2))
        }
        return acc.coerceIn(-HARD_DECEL, p.maxAccel)
    }

    /**
     * 把"停止线"当成**一个必须停在它前面的位置**，而不是一辆静止的车。
     *
     * ## 为什么不能用 `idmAccel(v, gap, 0.0)`
     * IDM 的期望间距 `sStar` 里含着车头时距与整个制动项（80km/h 时能到 130 米），
     * 而停止线通常只在 20~45 米外 —— `sStar/gap` 直接爆表，加速度被夹到 -8 m/s²。
     * 于是**离路口还有二十多米就开始急刹**，一旦中途有任意一帧触发让行，车已经停死，
     * 只能从 0 重新加速（2 m/s² 爬到 80km/h 要十几秒）—— 这就是"绿灯通过路口仍会停顿"。
     *
     * ## 运动学约束
     * 只要"现在开始按舒适减速度还停得住"就**完全不干预**（返回 `maxAccel`，`min()` 吃掉它）；
     * 真来不及了才按 `v_max = sqrt(2·a·s)` 给一个精确的减速度。
     * 远处的车因此一点不减速，近处的车沿着一条平滑曲线刚好停在线前。
     */
    private fun stopLineAccel(v: Vehicle, gap: Double): Double {
        // 已经贴到线上：直接按住。纯 `v²/(2s)` 是**渐近**收敛的，dt 离散化之后
        // 会以 0.5 m/s 左右一直蹭着走，最后靠硬夹取才停下 —— 表现为"到线前一顿"。
        if (gap < STOP_HOLD_M) return -HARD_DECEL
        val g = max(gap, 0.05)
        val vMax = sqrt(2.0 * v.kind.comfyDecel * g)   // 这个距离内还允许的最大速度
        if (v.speed <= vMax) return v.kind.maxAccel    // 还停得住 → 不干预
        return -(v.speed * v.speed / (2.0 * g)).coerceIn(0.0, HARD_DECEL)
    }

    /** 找**同一车道**的前车；快到弧末时也看下一段弧（目标车道）尾部的前车 */
    private fun findLeader(v: Vehicle, byLane: Map<String, List<Vehicle>>): Pair<Double?, Double?> {
        val arc = arcsByKey[v.arcKey] ?: return null to null
        val onLane = byLane[laneKey(v.arcKey, v.lane)].orEmpty()
        val idx = onLane.indexOf(v)
        var gap: Double? = null
        var leaderSpeed: Double? = null

        if (idx > 0) {
            val leader = onLane[idx - 1]
            gap = leader.s - leader.kind.lengthM - v.s
            leaderSpeed = leader.speed
        }
        if (v.s > arc.length - LOOKAHEAD_M && gap == null) {
            val nextArc = chooseArc(arc.toNode, v.targetJunction, v.routeMode)
            if (nextArc != null) {
                // 入口车道按**本次转弯**要求取（与 prepareTurn / 换弧完全同一套判定），
                // 否则会去看一条自己根本不会进的车道，把"前车"看错。
                val maxLane = (nextArc.lanes - 1).coerceAtLeast(0)
                val range = entryLanesFor(arc, nextArc, v.targetJunction, v.routeMode)
                val cur = v.lane.coerceIn(0, maxLane)
                val entryLane = if (cur in range) cur else range.first.coerceIn(0, maxLane)
                val rear = byLane[laneKey(nextArc.key, entryLane)]?.lastOrNull()
                if (rear != null) {
                    gap = (arc.length - v.s) + rear.s - rear.kind.lengthM
                    leaderSpeed = rear.speed
                }
            }
        }
        return gap to leaderSpeed
    }

    private fun stepVehicle(
        v: Vehicle,
        dt: Double,
        byLane: Map<String, List<Vehicle>>,
        nearByNode: Map<Long, List<Vehicle>>,
    ) {
        stepAnimations(v, dt)
        val arc = arcsByKey[v.arcKey] ?: return

        // ---- 僵持倒车（本轮需求）：转弯车被直行车堵住、双方都停死 → 转弯车先倒车让行 ----
        // 倒车的这一帧直接返回：不能再去跑 IDM / 硬夹取，否则刚退出去的几厘米会被推回来。
        if (stepDeadlockReverse(v, arc, dt, nearByNode)) return

        // 前车间距每帧取一次，供"反应决策"与"安全层"共用
        val (gapNow, leaderNow) = findLeader(v, byLane)

        // 反应时间：周期到期才重新决策一次加速度
        v.reactionTimer -= dt
        if (v.reactionTimer <= 0.0) {
            v.plannedAccel = idmAccel(v, gapNow, leaderNow)
            v.reactionTimer = v.reactionTime
        }

        // ---- 绿灯起步（本轮需求 2）----
        // 顺序很重要：这一整段必须排在下面"安全层"**之前**。
        // 它会在放行瞬间**重算**加速度（覆盖反应时间留下的旧值），
        // 而红灯 / 让行 / 紧急制动的 clamp 必须最后生效 —— 否则刚放行的车会跟着
        // 一个过期（甚至是正数）的加速度冲过停止线。
        if (v.speed <= STOPPED_MS) {
            val leaderMoving = leaderNow != null && leaderNow > START_LEADER_MS
            val headCar = gapNow == null || gapNow > START_HEAD_GAP_M
            when {
                // 头车（前面压根没车，或前车已在 8 m 外）：绿灯一亮就走
                headCar -> v.startDelayTimer = START_REACTION_SEC
                // 前车确实动起来了：开始累计这一辆车的反应时间
                leaderMoving -> v.startDelayTimer += dt
                // 前车还没动 / 又停下了：计时作废。否则一次"假放行"会留下余额，
                // 下次真放行时瞬发 —— 队尾会莫名其妙地抢跑。
                else -> v.startDelayTimer = 0.0
            }

            if (v.startDelayTimer < START_REACTION_SEC) {
                // 反应时间没到：压住油门（只压"往前窜"，刹车照常）
                if (v.plannedAccel > 0.0) v.plannedAccel = 0.0
            } else {
                // 放行瞬间**立刻重算一次**，不等 reactionTimer 到期。
                // 这是本轮最关键的改动：停车等灯期间算出的加速度是负的（把停止线当静止前车），
                // 反应周期又是 1.0s±20%，于是绿灯亮了之后这辆车还要抱着那笔"负账"最多 1.2 秒
                // —— 画面上就是"绿灯亮着、车一动不动"。现在零延迟。
                v.plannedAccel = idmAccel(v, gapNow, leaderNow)
                v.reactionTimer = v.reactionTime
                // 起步助推：IDM 在平衡间距上输出 0，光靠它起步会黏住（见 START_BOOST_RATIO）。
                // 只在间距确实还有余量时给，且下面安全层仍会再 clamp 一次。
                if (gapNow != null && gapNow > v.kind.minGap + START_ROOM_MARGIN_M) {
                    v.plannedAccel = max(v.plannedAccel, v.kind.maxAccel * START_BOOST_RATIO)
                }
            }
        } else {
            v.startDelayTimer = 0.0
        }

        // ---- 每帧安全层（不受反应时间影响）：把"即将相撞"挡在发生之前 ----
        // 1) 跟车紧急制动：间距明显偏近时立即减速，避免反应延迟内追尾
        if (gapNow != null) {
            val trigger = v.kind.minGap + v.speed * EMERGENCY_TIME
            if (gapNow < trigger) {
                v.plannedAccel = min(v.plannedAccel, idmAccel(v, gapNow, leaderNow))
            }
        }
        // 2) 路口让行：需要让行时，停在停止线之前（运动学约束，见 [stopLineAccel]）
        val yieldGap = junctionYieldGap(v, nearByNode)
        if (yieldGap != null) {
            v.plannedAccel = min(v.plannedAccel, stopLineAccel(v, yieldGap))
        }
        // 3) 红绿灯：红灯 / 黄灯时同样停在停止线之前
        val sigGap = signalStopGap(v)
        if (sigGap != null) {
            v.plannedAccel = min(v.plannedAccel, stopLineAccel(v, sigGap))
        }
        // 需求 3：记录"是否正在等信号灯"——等灯期间禁止自主变道（见 stepLaneChange）
        v.waitingSignal = isWaitingAtSignal(v, arc, sigGap)

        v.accel = v.plannedAccel

        v.speed = max(0.0, v.speed + v.plannedAccel * dt)
        v.s += v.speed * dt

        // ---- 硬性停止线（需求 1）：凡"当前必须停车"的车，绝不允许越过停止线驶入路口 ----
        // 上面用 IDM 把停止线当静止前车来减速，属于"柔性"控制；这里再加一道硬夹取，
        // 保证即使速度很高、单帧位移较大，车头也不会越过停止线进入横向车道。
        if (sigGap != null || yieldGap != null) {
            val limit = stopLimitS(v, arc)
            // 只在"车本来就在停止线附近"时才夹取：已经深入路口的车（s 远大于 limit）若被强行
            // 拉回停止线，画面上就是一次瞬移 —— 宁可让它按 IDM 慢慢刹，也不要 teleport。
            if (limit >= 0.0 && v.s > limit && v.s <= limit + 2.0) {
                v.s = limit
                if (v.speed > 0.0) v.speed = 0.0
                v.plannedAccel = 0.0
            }
        }

        // 需求 2：快到交汇区边缘就把转弯圆弧挂上
        // （圆弧起点在"车道被截停"的位置，比换弧节点更靠外，必须提前准备）
        prepareTurn(v, arc, byLane)
        // 圆弧走完 → 收掉，回到普通车道渲染
        if (v.turnActive && v.arcKey == v.turnPostArcKey && v.s > v.turnPostS + 1e-3) v.clearTurn()

        if (v.s >= arc.length) {
            val endNode = network.node(arc.toNode)
            // 抵达目的地（开放路口）→ 原地淡出
            if (endNode != null && endNode.type == NodeType.OPEN && endNode.id == v.targetJunction) {
                v.arrived = true
                v.fade = 1.0
                // 需求 3：「到达车辆数」在**这一瞬**就 +1，不等淡出播完。
                // 以前是等淡出结束、把车从列表里移除时才统计（多等 FADE_SECONDS 秒），
                // 于是面板上的数字总比画面慢半拍 —— 车轮已经到站了，计数还没跳。
                totalArrived += 1
                return
            }
            // 目的地被关闭/删除 → 重新抽一个
            if (network.node(v.targetJunction)?.type != NodeType.OPEN) {
                val newTarget = pickDestination(arc.toNode)
                if (newTarget == null) {
                    v.s = arc.length
                    v.speed = 0.0
                    return
                }
                v.targetJunction = newTarget
                v.laneGoalArcKey = null    // 换了目的地 → 车道目标必须重算
            }
            val next = chooseArc(arc.toNode, v.targetJunction, v.routeMode)
            if (next == null) {
                // 断头路：就地停车排队（转弯圆弧没有落脚点，一并撤掉）
                v.clearTurn()
                v.s = arc.length
                v.speed = 0.0
                v.plannedAccel = 0.0
                return
            }
            // 需求 5：入口车道的首选是**已经建好的转弯圆弧所认定的那条**。
            // 圆弧在 prepareTurn() 里就按某条入口车道算好了终点偏移；这里若再重新挑一次，
            // 一旦结果不同，圆弧终点与新车道偏移就对不上 —— 画面上就是"在路口正中横向瞬移"。
            // 所以只要圆弧还挂在这条弧上，就沿用它；只有圆弧不存在（几何退化 / 刚改过路线）
            // 才重新挑，并且必须挑得下（尾部净空够）才换，否则停在路口前等。
            val needRoom = v.kind.lengthM + ARC_SWITCH_MIN_ROOM_M
            val arcOk = v.turnActive && v.turnPostArcKey == next.key
            val nextLane: Int? = if (arcOk) {
                // 圆角终点落在出口路的**最右侧真实车道**上，夹取上限就是 `lanes - 1`；
                // 与圆弧终点偏移（rLane 同心弧）完全一致，换弧瞬间不会横移。
                val l = v.turnEntryLane.coerceIn(0, (next.lanes - 1).coerceAtLeast(0))
                if (roomOn(next, l, byLane) >= needRoom) l else null
            } else {
                // 兜底：**优先原地不动**。哪怕当前车道不在"本次转向允许"的范围内（路线刚刷新、
                // 或上一个路口被挤进来的），也先按原车道进新弧 —— 在路口正中横移一次 3.5m，
                // 比"多开几十米再对齐"难看得多。真正对齐交给过路口之后的 stepLaneChange。
                val maxLane = (next.lanes - 1).coerceAtLeast(0)
                val cur = v.lane.coerceIn(0, maxLane)
                if (roomOn(next, cur, byLane) >= needRoom) cur
                else pickEntryLane(next, entryLanesFor(arc, next, v.targetJunction, v.routeMode), v.lane, byLane, needRoom)
            }
            if (nextLane == null) {
                // 进不去：停在路口前等（圆弧保留 —— 车头停在圆弧上，比弹回车道更连贯）
                v.s = arc.length
                v.speed = 0.0
                v.plannedAccel = 0.0
                return
            }
            // ---- 换弧 ----
            // 转弯圆弧在 prepareTurn() 里已按"交汇区边缘"提前算好，这里直接沿用；
            // 没有可用圆弧时**就按车道直着穿过去**。
            // ⚠️ 千万不要在这里"临时补一条弧"：那条弧的起点在路口边缘，而车已经在路口正中，
            //    补上去等于把车往回瞬移十几米 —— 旧版"路口里莫名一跳"有一部分就是这么来的。
            v.s -= arc.length
            v.arcKey = next.key
            // 车道号立即切换（碰撞检测按新车道算）；有圆弧时其终点偏移与本车道一致，所以不跳
            v.snapLane(nextLane)
            if (!arcOk) v.clearTurn()
            // 记下"进入这条弧后，计划在它末端要走的那条弧"（自主规划的比较基准）
            v.plannedNextKey = chooseArc(next.toNode, v.targetJunction, v.routeMode)?.key
            v.histTimer = 0.0
            v.followSlowTime = 0.0        // 刚过路口，车道对齐计时重新算
        }

        // ---- 多车道车道对齐（需求 1）----
        stepLaneChange(v, dt, byLane)

        // ---- 自主规划：速度采样 + 拥堵判定 ----
        stepRerouteLogic(v, dt)
    }

    // ==================== 平滑动画（变道） ====================

    /** 推进变道动画的计时（纯视觉，不参与动力学）。转弯靠 s 推进弧长，不需要计时器 */
    private fun stepAnimations(v: Vehicle, dt: Double) {
        if (v.laneBlend < 1.0) {
            v.laneBlend = (v.laneBlend + dt / v.laneAnimSec.coerceAtLeast(0.05)).coerceAtMost(1.0)
        }
    }

    /** 两个角度之间的**最短带符号差**（b − a），落在 (−π, π] */
    private fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % (2.0 * PI)
        if (d > PI) d -= 2.0 * PI
        if (d < -PI) d += 2.0 * PI
        return d
    }

    /**
     * 车辆当前的**显示姿态**（世界坐标 + 朝向）。
     *
     * 三层叠加，全部只影响观感：
     *  1. 基础：弧上里程 s 处的坐标 + 车道横向偏移（多车道）；
     *  2. 变道：横向偏移在 laneFrom→laneTo 之间按 smoothstep 插值，车头同时偏一下再回正
     *     （偏摆用 sin(π·e) —— 起点终点都是 0，中途最大，正好是"打方向 → 回正"）；
     *  3. 过路口：落在转弯圆弧上时**直接取圆弧上的点**（位置与朝向都来自圆弧）。
     *     圆弧两端与两条车道的中心线精确相切，所以拐角处既不平移跳变、也不瞬间掰车头。
     *
     * 渲染与防重叠判定都用它，因此"看起来贴在一起"和"算出来重叠"永远一致。
     */
    private fun poseOf(v: Vehicle): RenderPose? {
        val arc = arcsByKey[v.arcKey] ?: return null
        val s = v.s.coerceIn(0.0, arc.length)
        val u = arc.tangentAtS(s)
        val n = u.perp()
        val base = arc.pointAtS(s)
        val dispLane = v.laneDisplayed()
        val off = laneOffsetM(dispLane)

        var x = base.x + n.x * off
        var y = base.y + n.y * off
        var ang = u.angleRad

        // 2) 变道：车头先偏入目标车道，再回正
        if (v.laneBlend < 1.0) {
            val e = smoothStep01(v.laneBlend)
            val dir = sign(v.laneTo - v.laneFrom)          // +1 = 往路缘侧（+n）并线
            ang += dir * LANE_CHANGE_MAX_YAW_RAD * sin(PI * e)
        }

        // 3) 过路口转弯圆弧：落在圆弧上就直接取圆弧上的点
        //    （圆弧两端与两侧车道中心线**精确相切**，所以进出都没有跳变、也没有折角）
        val turnU = v.turnUAt(v.arcKey, s)
        if (turnU != null) {
            val pose = v.turnPoseAtU(turnU)
            x = pose.x
            y = pose.y
            ang = pose.angleRad
        }
        return RenderPose(x, y, ang)
    }

    // ==================== 过路口转弯圆弧（需求 2） ====================

    /**
     * 一段转弯圆弧：两个切点（起点 / 终点）、圆心、半径、起始极角、有符号扫角。
     * 切点严格落在两条车道的中心线上，且圆心到两点等距 —— 相切条件成立。
     */
    private class TurnArc(
        val t1: Vector2,
        val t2: Vector2,
        val c: Vector2,
        val r: Double,
        val a0: Double,
        val sweep: Double,
    )

    /**
     * 需求 1：**提前**把转弯圆弧算好并挂到车上。
     *
     * 为什么不能等到换弧那一刻才算：圆弧的起点在"当前车道被交汇区截停"的位置，
     * 而逻辑换弧发生在交汇区正中的节点上 —— 起点比节点更靠外。等换弧时再算，车已经在
     * 路口里了，起点必然对不上车道边界（旧版"拐角一跳"就是这么来的）。
     *
     * 分两条路：
     *  - **右转**（车在最右侧真实车道）：直接用 [JunctionGeometry] 给出的**路口圆角弧** ——
     *    圆心落在路口外侧、半径 = 路缘半径 + 半个车道宽，与两侧车道中心线精确相切。
     *    这条弧与画布上补的那块圆角路面（`SimCanvas.CornerPathCache`，几何来自同一个
     *    [JunctionGeometry.allCorners]）是同一个
     *    几何对象，所以车一定跑在路面上，且全程不进冲突区（无需减速 / 让行）。
     *  - **其它转弯**（左转 / 斜交路口的左转等）：沿用"两条车道中心线 + 双切点"的构造。
     *
     * 几何退化（近似直行 / 掉头 / 中心线在起点后方相交）时**什么也不挂**，
     * 车辆按车道正常渲染；同一条弧上不重复尝试（[Vehicle.turnSkipArcKey]）。
     */
    private fun prepareTurn(v: Vehicle, arc: Arc, byLane: Map<String, List<Vehicle>>) {
        if (v.turnActive) return
        if (v.turnSkipArcKey == arc.key) return          // 这条弧已经试过且失败，别每帧重算
        val insetOld = arcEndInset[arc.key] ?: return
        if (insetOld <= 0.05) return                     // 这一端没有交汇区
        // 末端就是本车目的地（开放路口）→ 到节点就淡出，不该再往某个车道拐
        val endNode = network.node(arc.toNode)
        if (endNode != null && endNode.type == NodeType.OPEN && endNode.id == v.targetJunction) return
        val next = chooseArc(arc.toNode, v.targetJunction, v.routeMode) ?: return
        if (next.key == arc.key) return                  // 原地掉头：不画圆弧

        // ---------- ① 右转：沿路口**圆角**走过去（需求 1） ----------
        // 圆角是"路缘边线被倒圆"的那一段，右转车道中心线正好是它的同心弧：
        // 圆心在路口外侧、半径 = 路缘半径 + 半个车道宽。车只要在最右侧真实车道上，
        // 到了切点就挂上这条弧 —— 圆弧与两侧车道中心线精确相切，进出无折角。
        // 圆角起点比交汇区边缘更靠外（约 10~25m），所以**尽早**挂上也不会提早转向：
        // turnUAt() 在 s < turnPreStartS 时返回 null，车照旧按车道渲染。
        if (movementOn(arc, v.targetJunction, v.routeMode) == TurnDir.RIGHT) {
            val rightLane = (arc.lanes - 1).coerceAtLeast(0)
            if (v.lane == rightLane) {
                val corner = cornerBetween(arc, next)
                if (corner != null) {
                    val startS = arc.length - corner.extentIn
                    if (startS >= 0.0 && v.s <= startS + 0.05) {
                        if (v.laneBlend < 1.0) return       // 并线动画还没走完，等它稳定
                        v.beginTurnArc(
                            corner.center.x, corner.center.y, corner.rLane, corner.a0, corner.sweep,
                            arc.key, startS, arc.length - startS,
                            next.key, corner.extentOut,
                        )
                        // 出口同样落在最右侧真实车道（圆角与它相切，换弧不跳）
                        v.turnEntryLane = (next.lanes - 1).coerceAtLeast(0)
                        return
                    }
                }
            }
        }

        if (arc.length - v.s > insetOld) return          // 还没到边缘
        // 变道动画还没走完 → 车道偏移仍在变，此刻建弧的起点会对不上，等它稳定后再建
        if (v.laneBlend < 1.0) return
        val allowed = entryLanesFor(arc, next, v.targetJunction, v.routeMode)
        val entryLane = pickEntryLane(
            next, allowed, v.lane, byLane, v.kind.lengthM + ARC_SWITCH_MIN_ROOM_M,
        ) ?: allowed.first
        val insetNew = arcStartInset[next.key] ?: 0.0
        if (!attachTurnArc(v, arc, next, entryLane, laneOffsetM(v.laneDisplayed()), insetOld, insetNew)) {
            v.turnSkipArcKey = arc.key
        }
    }

    /**
     * 计算并挂上转弯圆弧。
     *
     * @param offOld 起点侧车道偏移（米）。传 `laneOffsetM(v.laneDisplayed())` ——
     *               用**显示**车道而不是逻辑车道，变道动画的一半也能对上。
     * @return false = 几何退化，没有挂上圆弧
     */
    private fun attachTurnArc(
        v: Vehicle,
        arc: Arc,
        next: Arc,
        entryLane: Int,
        offOld: Double,
        insetOld: Double,
        insetNew: Double,
    ): Boolean {
        val uIn = arc.tangentAtS(arc.length)
        val uOut = next.tangentAtS(0.0)
        val offNew = laneOffsetM(entryLane.toDouble())
        // 起点 / 终点：两条车道中心线在**交汇区边缘**被截停的位置
        val sPoint = arc.pointAtS(arc.length - insetOld) + uIn.perp() * offOld
        val ePoint = next.pointAtS(insetNew) + uOut.perp() * offNew

        val curve = buildTurnArc(sPoint, uIn, ePoint, uOut) ?: return false

        // 切点回算到各自弧上的里程（沿各自行驶方向的带符号位移）
        val sT1 = (arc.length - insetOld) + (curve.t1 - sPoint).dot(uIn)
        val sT2 = insetNew + (curve.t2 - ePoint).dot(uOut)
        if (sT1 > arc.length + 1e-6 || sT2 > next.length + 1e-6) return false

        val preSpan = (arc.length - sT1).coerceAtLeast(0.0)
        v.beginTurnArc(
            curve.c.x, curve.c.y, curve.r, curve.a0, curve.sweep,
            arc.key, sT1.coerceIn(0.0, arc.length), preSpan,
            next.key, sT2.coerceIn(0.0, next.length),
        )
        // 记下圆弧终点所在车道：换弧那一刻必须用同一个值（否则横向会跳），见 stepVehicle
        v.turnEntryLane = entryLane
        return true
    }

    /**
     * 由「起点 S + 进入方向 uIn」与「终点 E + 驶出方向 uOut」构造转弯圆弧。
     *
     * **构造**（全部是世界坐标，单位米）：
     *  1. 两条车道中心线延长后交于 V（路口内部）；
     *  2. 起点到 V、V 到终点的距离取**较短**的一侧为 d —— 圆弧就整个落在交汇区内；
     *  3. 半径 `R = d / tan(|τ| / 2)`（τ = 转角）：这个值保证切点距 V 恰好为 d，
     *     所以圆弧与两条车道都在切点处相切；
     *  4. 圆心 O 落在起点车道的垂线上（右转落行驶方向右侧、左转落左侧），
     *     到此 `|OS| = |OE| = R` —— **两条垂线的交点就是圆心**，与需求 2 的描述一致。
     *
     * 返回 null 表示几何退化：两车道近似平行（直行 / 掉头）、或中心线在起点后方相交。
     */
    private fun buildTurnArc(s: Vector2, uIn: Vector2, e: Vector2, uOut: Vector2): TurnArc? {
        val cross = uIn.x * uOut.y - uIn.y * uOut.x
        if (abs(cross) < TURN_ARC_MIN_SIN) return null       // 夹角 < 6°：直行 / 掉头
        val w = e - s
        val t = (w.x * uOut.y - w.y * uOut.x) / cross
        if (t <= 1e-3) return null                            // 交点在起点后方 → 构造无意义
        val vPoint = s + uIn * t                              // 两条车道中心线的交点 V
        val a = t
        val c = (e - vPoint).length
        if (c <= 1e-3) return null

        val tau = angleDiff(uIn.angleRad, uOut.angleRad)      // 屏幕坐标：正 = 右转、负 = 左转
        val half = abs(tau) * 0.5
        if (half < 1e-3) return null

        val dMax = min(a, c)                                  // 取短边，圆弧整个落在交汇区内
        val r = (dMax / tan(half)).coerceAtLeast(TURN_ARC_MIN_R)
        val d = r * tan(half)                                 // 切点到 V 的距离（与 r 严格配套）
        val t1 = vPoint - uIn * d
        val t2 = vPoint + uOut * d

        // 圆心：沿起点车道的垂线偏移 r；右转在行驶方向右侧（perp），左转在左侧
        val n = uIn.perp()
        val side = if (tau > 0.0) 1.0 else -1.0
        val cx = t1.x + n.x * side * r
        val cy = t1.y + n.y * side * r

        val a0 = atan2(t1.y - cy, t1.x - cx)
        val a2 = atan2(t2.y - cy, t2.x - cx)
        var sweep = a2 - a0
        while (sweep > PI) sweep -= 2.0 * PI
        while (sweep < -PI) sweep += 2.0 * PI
        // 校验起点切向必须等于 uIn：不成立说明圆心取到了另一侧，构造无效
        val sgn = if (sweep >= 0.0) 1.0 else -1.0
        val tx = -sin(a0) * sgn
        val ty = cos(a0) * sgn
        if (tx * uIn.x + ty * uIn.y < 0.0) return null

        return TurnArc(t1, t2, Vector2(cx, cy), r, a0, sweep)
    }

    // ==================== 路口让行（防碰撞） ====================

    /** 车辆到某节点的最近距离（米）：含"刚驶出该节点"的情形（此时它在下一段弧上，s 很小） */
    private fun distToNode(v: Vehicle, node: Long): Double {
        val a = arcsByKey[v.arcKey] ?: return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        if (a.toNode == node) best = min(best, a.length - v.s)
        if (a.fromNode == node) best = min(best, v.s)
        return best
    }

    /** 把"接近某节点"的车按节点归拢，避免每帧 O(n²) 全量两两扫描 */
    private fun buildNearNodes(active: List<Vehicle>): Map<Long, List<Vehicle>> {
        val map = HashMap<Long, MutableList<Vehicle>>()
        for (v in active) {
            val a = arcsByKey[v.arcKey] ?: continue
            if (a.length - v.s <= JUNCTION_APPROACH_M) map.getOrPut(a.toNode) { mutableListOf() } += v
            if (v.s <= JUNCTION_APPROACH_M) map.getOrPut(a.fromNode) { mutableListOf() } += v
        }
        return map
    }

    /**
     * 路口让行：返回"停在停止线前"所需的间距（米）；不需要让行返回 null。
     *
     * 这是**信号灯之外**的一层现实规则（信号灯只管"什么时候可以进路口"，这里管"谁先进"）。
     * 优先级：**直行 0 < 左转 1 < 右转 2**（数字越小越优先），也就是需求原文
     * "转弯车辆避让直行车辆"；左转让右转先行是国标"相对方向右转让左转"的落地。
     *
     * 判定顺序：
     *  1. **转弯让直行**：我是转弯车，且有一个优先级比我高的车（直行车）正**驶向本路口**
     *     （已经到门口，或正在动）→ 我停在停止线外等它过。远处排队的直行车不算，
     *     否则转弯车会被几十米外的车流"按住"永远走不了。
     *  2. **已在交汇区里的车优先**：谁越过了停止线，谁就先把这段走完。
     *  3. **对方转弯车已占据我的车道**：我的优先级更高，但它的车身已经横在我前面的走廊里
     *     （[occupiesMyLane]）→ 我减速让行，不去顶它（需求原文"若转弯车辆已完全占据直行
     *     车辆所在车道，直行车辆应减速让行"）。此时双方都停住 → 由 [stepDeadlockReverse]
     *     让转弯车倒车破解。
     *  4. **同优先级**（都是直行 / 都是转弯）：维持原来的"离路口更近者先行"（并列看 id），
     *     保证十字方向的车不会对冲着开进对方车身里。
     *  5. 近乎正对的反向车视为分道而行（同一物理道路的两侧车道），不互相让行；
     *     被红灯 / 黄灯拦停的车不参与比较（否则红灯车会把绿灯方向也按停）。
     */
    private fun junctionYieldGap(v: Vehicle, nearByNode: Map<Long, List<Vehicle>>): Double? {
        val arc = arcsByKey[v.arcKey] ?: return null
        val node = arc.toNode
        val stop = stopLineOf(node)                 // 路口冲突区 / 停止线都以净空半径为准
        val d = arc.length - v.s
        if (d < stop) return null                   // 确实已越过停止线（进入冲突区）：继续通行
        if (d > JUNCTION_APPROACH_M) return null
        // 断头路（该节点不存在交叉/后续方向）无需让行
        if (arcsToNode[node].orEmpty().size <= 1 && arcsFromNode[node].orEmpty().size <= 1) return null

        val myDir = arc.tangentAtS(v.s)
        val myMove = movementOf(v, arc)
        val myPrio = priorityOf(myMove)
        // **需求 1：右转车不参与让行**。路口四角改成圆角之后，右转车走的是"路缘外侧那条
        // 同心弧"，从头到尾没有进过冲突区（切点距节点 ≈ 对方半宽 + 路缘半径，远在停止线之外），
        // 因此既不需要给直行/左转让路，也不会占住别人的路 —— 一路匀速拐过去，
        // 这正是需求里"右转时无需减速"。
        if (myMove == TurnDir.RIGHT && network.degree(node) >= 3) return null

        // **本轮需求 1：有信号灯的路口，绿灯的直行车不再参与"路口让行"。**
        //
        // 灯权已经决定了谁走谁停：绿灯方向上，横向进口是红的（那些车正停在停止线后等着），
        // 同轴的对向进口虽然一起放行，但两条直行车流各走各的、本来就不冲突。
        //
        // 可旧代码对绿灯车也照跑"转弯让直行 / 谁近谁先"，于是后车开到白线前会被
        //   · 对向车（弯曲路段上两条进口的切向点积到不了 -0.766，被误判成"会冲突"），
        //   · 或是刚过线、车身还没摆正的前车（切向还没转到同向）
        // 判成冲突，生生在停止线前停一下再走。画面上就是"绿灯亮着，后车到白线却要减速等待"。
        //
        // 只豁免**直行**：左转仍要按"转弯让直行"判断 —— 规则一/三里同轴进口是
        // "直行 + 左转一起绿"，对向左转与我的直行确实会交叉，那部分安全交给
        // 下面的规则 ② （对方已进入冲突区 → 我让）兜底。
        val sig = signals[node]
        val greenStraight = sig != null &&
            myMove == TurnDir.STRAIGHT &&
            sig.isGreen(v.arcKey, myMove)

        for (w in nearByNode[node].orEmpty()) {
            if (w === v || w.arcKey == v.arcKey) continue   // 同弧由 IDM 负责
            val wa = arcsByKey[w.arcKey] ?: continue
            // 同理：对面那辆右转车也在圆角上，永远不会挡我的路 → 直接跳过，
            // 否则"冲突区里有车"会被它误触发，横向车流白白停在路口。
            if (movementOf(w, wa) == TurnDir.RIGHT && network.degree(node) >= 3) continue
            val wDist = distToNode(w, node)
            if (wDist > JUNCTION_APPROACH_M) continue
            // 被红灯 / 黄灯拦停的车不会驶入冲突区 → 既不算"区内有车"，也不抢先行权
            if (signalStopGap(w) != null) continue
            val aligned = myDir.dot(wa.tangentAtS(w.s))
            // 近乎正对的反向车 → 分道而行，不冲突
            if (aligned < CONFLICT_DOT) continue
            // w 刚驶出本节点且与 v 同向 → 只是"跟车穿过路口"，交给 IDM，不算冲突
            if (wa.fromNode == node && aligned > 0.5) continue

            val wMove = movementOf(w, wa)
            val wPrio = priorityOf(wMove)

            // ① 转弯让直行：更高优先级的车**马上**就到（或已经在门口）→ 我让
            //    （绿灯直行车跳过：灯权已经排好了顺序，见上面 greenStraight）
            val wEta = if (w.speed > STOPPED_MS) wDist / w.speed else Double.MAX_VALUE
            if (!greenStraight && wPrio < myPrio &&
                (wDist < stop + CONFLICT_ZONE_M || wEta < YIELD_ETA_SEC)
            ) {
                return yieldGapFor(v, d, stop)
            }
            // ② 已进入交汇区的车先把路走完（我还没进去，就得等）
            //    ⚠️ 这条**任何情况都保留**（含绿灯直行）：对方真的挡在路上，不让就是穿模。
            //
            //    但"进了交汇区"≠"挡着我"：路口中间可能停着一辆**刚过线的对向左转车**
            //    （它正等着对向直行的空档），它的车身离我这条直行车道还有半个路口远，
            //    我却因为它"在路口里"就停在停止线前 —— 画面上就是"绿灯亮了，后车到白线还得停一下"。
            //    所以绿灯直行额外要求**几何上真的压在我这条车道上**（[occupiesMyLane]：
            //    沿我的行驶方向前 14 米内、横向不出我的车道带），一条精确的碰撞走廊判定。
            if (wDist < stop) {
                if (!greenStraight || occupiesMyLane(w, v, node)) return yieldGapFor(v, d, stop)
            }
            // ③ 对方是转弯车、且车身已经横在我正前方的走廊里 → 我减速让行，不顶它
            if (!greenStraight && wPrio > myPrio && occupiesMyLane(w, v, node)) {
                return yieldGapFor(v, d, stop)
            }
            // ④ 同优先级：离路口更近者先行（并列时 id 小者先行）
            if (!greenStraight && wPrio == myPrio &&
                wa.toNode == node &&
                (wDist < d - 1e-6 || (abs(wDist - d) <= 1e-6 && w.id < v.id))
            ) {
                return yieldGapFor(v, d, stop)
            }
        }
        return null
    }

    /** 让行时"停在停止线之后"的间距（米） */
    private fun yieldGapFor(v: Vehicle, d: Double, stop: Double): Double =
        (d - stop - stopOffsetM(v)).coerceAtLeast(0.0)

    /** 转向优先级：直行最先走，左转次之，右转最后（数字越小越优先） */
    private fun priorityOf(m: TurnDir): Int = when (m) {
        TurnDir.STRAIGHT -> 0
        TurnDir.LEFT -> 1
        TurnDir.RIGHT -> 2
    }

    /**
     * 那辆**转弯车**是不是已经"完全占据"我的车道？
     *
     * 判据（都在世界坐标里算，世界米 → 与车道宽度同一量纲）：
     *  1. 它已经越过**它的**停止线（真的进路口了，不是还在门口等）；
     *  2. 它在我正前方 [CROSS_CORRIDOR_M] 米以内（沿我的行驶方向投影）；
     *  3. 它横向偏离我的车道中心线不超过"我的半宽 + 它的半车宽" —— 也就是它的车身
     *     实打实压在我这条路上，我开过去必然撞上。
     *
     * 三条同时成立才算"占住"，所以只是从旁边擦过去的转弯车不会把直行车无故按停。
     */
    private fun occupiesMyLane(w: Vehicle, v: Vehicle, node: Long): Boolean {
        val wa = arcsByKey[w.arcKey] ?: return false
        val va = arcsByKey[v.arcKey] ?: return false
        if (wa.length - w.s > stopLineOf(node)) return false      // 它还没进交汇区
        val pw = poseOf(w) ?: return false
        val dir = va.tangentAtS(va.length)                        // 我穿过路口时的前进方向
        val origin = va.pointAtS(va.length)
        val rel = Vector2(pw.x - origin.x, pw.y - origin.y)
        val along = rel.dot(dir)
        if (along < -2.0 || along > CROSS_CORRIDOR_M) return false
        val lateral = abs(rel.x * dir.y - rel.y * dir.x)           // 二维叉积 = 横向偏离
        return lateral <= va.lanes * RoadNetwork.LANE_WIDTH_M + VehicleKind.WIDTH_M * 0.5
    }

    /**
     * **僵持倒车**（本轮需求）：转弯车与直行车互不相让、双方都停死时，
     * 让**转弯车倒车**，把路让给直行车，直行通过后它再继续转弯。
     *
     * 判定条件（全部满足才倒）：
     *  · 我是转弯车（左转 / 右转），并且**实际卡住了**（[Vehicle.blockedSec] 攒够
     *    [TURN_DEADLOCK_SEC]，按逐帧位移判，不看速度 —— 见下方说明）；
     *  · 有一个**直行车**也动不了，而且它就在路口门口、与我有冲突；它不是被自己的红灯按停的
     *    （否则规则二里"左转绿、直行红"会被误判成僵持）；
     *  · 不在倒车冷却里。
     *
     * 倒车过程：每帧只改 s（按 [REVERSE_SPEED_MS] 往后退），速度置 0 —— 动力学保持干净，
     * 画面就是"慢慢退出去"。退到停止线后 1 米（至少 [REVERSE_MIN_DIST_M] 米）即结束，
     * 然后进入冷却，避免在两车之间来回抽搐。
     *
     * @return true = 本帧正在倒车（调用方应跳过跟车 / 灯控，避免又被推回去）
     */
    private fun stepDeadlockReverse(
        v: Vehicle,
        arc: Arc,
        dt: Double,
        nearByNode: Map<Long, List<Vehicle>>,
    ): Boolean {
        if (v.reverseCooldown > 0.0) v.reverseCooldown -= dt

        // ---- 正在倒车：按固定速度退回 ----
        if (v.reverseRemaining > 0.0) {
            val back = min(v.reverseRemaining, REVERSE_SPEED_MS * dt)
            val target = (v.s - back).coerceAtLeast(0.0)
            v.reverseRemaining -= (v.s - target)
            v.s = target
            v.speed = 0.0
            v.plannedAccel = 0.0
            v.stopTime = 0.0
            if (v.reverseRemaining <= 1e-6) {
                v.reverseRemaining = 0.0
                v.reverseCooldown = REVERSE_COOLDOWN_SEC
            }
            return true
        }

        if (v.reverseCooldown > 0.0) { v.stopTime = 0.0; return false }
        // 只有**转弯车**需要倒车让直行
        if (movementOf(v, arc) == TurnDir.STRAIGHT) { v.stopTime = 0.0; return false }
        // 我自己正被红灯 / 黄灯按着 → 这是正常等灯，不是僵持（否则红灯车会莫名其妙倒车）
        if (signalStopGap(v) != null) { v.stopTime = 0.0; return false }

        // 位置条件：从停止线前 2m 到路口内 12m 这一段都算"卡在路口"
        val node = arc.toNode
        val stop = stopLineOf(node)
        val d = arc.length - v.s
        if (d > stop + 2.0) { v.stopTime = 0.0; return false }

        // 关键：用**实际位移**判"停住"，不看速度。
        // 僵持时 IDM 一直在给正加速度（速度不为 0），可车身被视觉防重叠层每帧推回原位，
        // 弧上里程根本没变 —— 旧版按速度判，僵持时间永远攒不够，于是永远不倒车。
        if (v.blockedSec < TURN_DEADLOCK_SEC) { v.stopTime = 0.0; return false }
        if (!hasStuckStraightBlocker(v, arc, nearByNode)) { v.stopTime = 0.0; return false }

        // ---- 僵持成立 → 倒车让行 ----
        v.stopTime = 0.0
        val lineS = stopLimitS(v, arc)                       // 停在线上时对应的 s
        val backTo = (lineS - 1.0).coerceAtLeast(0.0)
        // 已经在线上就至少退出 REVERSE_MIN_DIST_M；不能退到弧起点之外，也不倒太长（免得像在倒车比赛）
        val need = max(v.s - backTo, REVERSE_MIN_DIST_M)
        v.reverseRemaining = min(need, v.s).coerceAtMost(REVERSE_MAX_DIST_M)
        return false
    }

    /**
     * 是否有一辆**被僵住的直行车**：它在路口门口、与我有冲突、自己动不了，
     * 而且**不是**被自己的红灯 / 黄灯按停的（那种情况我该走，不该倒车）。
     *
     * "动不了"用位移判（[Vehicle.blockedSec]）：`speed` 在僵持时可能是正的
     * （IDM 想走、被推回来），只看速度会漏判。
     */
    private fun hasStuckStraightBlocker(
        v: Vehicle,
        arc: Arc,
        nearByNode: Map<Long, List<Vehicle>>,
    ): Boolean {
        val node = arc.toNode
        val stop = stopLineOf(node)
        val myDir = arc.tangentAtS(v.s)
        for (w in nearByNode[node].orEmpty()) {
            if (w === v) continue
            val wa = arcsByKey[w.arcKey] ?: continue
            if (movementOf(w, wa) != TurnDir.STRAIGHT) continue
            if (signalStopGap(w) != null) continue                 // 它被灯按着，不算僵持
            val wDist = distToNode(w, node)
            if (wDist > stop + CONFLICT_ZONE_M) continue
            if (myDir.dot(wa.tangentAtS(w.s)) < CONFLICT_DOT) continue
            // 真·动不了：速度几乎为零，或位移已经停了足够久
            if (w.speed <= STOPPED_MS || w.blockedSec >= TURN_DEADLOCK_SEC) return true
        }
        return false
    }

    /**
     * 自主规划：维护最近窗口的平均速度；被堵住且冷却结束 → 请求刷新路由并评估是否真的改了道。
     *
     * 说明：车辆不能在路段中间掉头，真正的"改道"发生在**下一个路口**——
     * 这里做的是提前用最新拥堵情况重算最优解，让它在路口能选到更快的那条弧。
     */
    private fun stepRerouteLogic(v: Vehicle, dt: Double) {
        // 先做与"位置"无关的记账：采样 + 冷却
        v.histTimer -= dt
        if (v.histTimer <= 0.0) {
            v.pushSpeed(v.speed)
            v.histTimer = Vehicle.HIST_DT
        }
        if (v.rerouteCooldown > 0.0) v.rerouteCooldown -= dt
        if (!settings.autoReroute || v.rerouteCooldown > 0.0) return

        val samples = (settings.rerouteWindowSec / Vehicle.HIST_DT).toInt()
            .coerceIn(1, Vehicle.HIST_SIZE)
        if (!v.hasSamples(samples)) return

        // 窗口内平均速度 vs 本车初始期望速度
        val avg = v.avgSpeedOver(samples)
        if (avg >= settings.rerouteSpeedRatio * v.desiredSpeed) return

        // 注意：这里必须按 v.arcKey 取"当前弧"，不能用步进开始时的局部变量
        // （车辆本帧可能刚跨过路口换了弧，用旧弧的末端节点比较会错位）
        val cur = arcsByKey[v.arcKey] ?: return
        // 用**耗时**场评估"绕一条更畅通的路是否更划算"—— 这才是改道的意义所在。
        // （生成时用的是纯距离场，见 RouteMode.SHORTEST；两者刻意分开。）
        val best = chooseArc(cur.toNode, v.targetJunction, RouteMode.FASTEST) ?: return

        // 判定为"被堵住了"：请求刷新路由（每帧至多一次），并重置冷却
        v.rerouteCooldown = settings.rerouteCooldownSec
        pendingRouteRefresh = true

        val planned = v.plannedNextKey
        if (planned != null && planned != best.key) {
            // 原计划的下一段弧与最新最优解不同 → 记一次真实改道
            totalReroutes++
            v.rerouteCount++
            // **关键**：真的换了路，就要把本车的路径偏好切到"最快"。
            // 否则下一帧 chooseArc 仍按最短距离走，会把它拉回原路 ——
            // 结果就是"统计里记了改道，画面上什么都没变"。
            if (v.routeMode != RouteMode.FASTEST) {
                v.routeMode = RouteMode.FASTEST
                v.laneGoalArcKey = null    // 偏好变了 → 车道目标必须重算
            }
        }
        v.plannedNextKey = best.key
    }

    // ==================== 生成（按路口配置 + 热度目的地） ====================

    /** 按热度加权抽目的地（排除出发路口自身） */
    fun pickDestination(originId: Long): Long? {
        val candidates = network.openNodes().filter { it.id != originId }
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { (junctionConfigs[it.id]?.heat ?: 5).coerceAtLeast(1) }.toDouble()
        var r = rng.nextDouble() * total
        for (n in candidates) {
            r -= (junctionConfigs[n.id]?.heat ?: 5).coerceAtLeast(1)
            if (r <= 0.0) return n.id
        }
        return candidates.last().id
    }

    private fun sampleKind(cfg: JunctionConfig): VehicleKind {
        val (car, bus, _) = cfg.normalizedRatios()
        val r = rng.nextDouble()
        return when {
            r < car -> VehicleKind.CAR
            r < car + bus -> VehicleKind.BUS
            else -> VehicleKind.TRUCK
        }
    }

    /**
     * 需求 1：按**自定义速度曲线**抽样速度，返回 m/s。
     *
     * 曲线是 [SPEED_CURVE_MIN_KMH, SPEED_CURVE_MAX_KMH] 上的连续概率密度（控制点之间平滑插值）：
     * 先对曲线做数值积分得到 CDF，再逆变换采样出实数速度，最后**四舍五入为整数 km/h**。
     * 曲线全为 0 时退化为 60 km/h（兜底），保证任何设置下都能取到合法速度。
     */
    private fun sampleSpeed(cfg: JunctionConfig): Double {
        val curve = cfg.effectiveCurve()
        val n = 160
        val step = (SPEED_CURVE_MAX_KMH - SPEED_CURVE_MIN_KMH) / n
        val cdf = DoubleArray(n + 1)
        var acc = 0.0
        for (i in 1..n) {
            val v = SPEED_CURVE_MIN_KMH + (i - 0.5) * step
            acc += speedCurveValueAt(curve, v)
            cdf[i] = acc
        }
        if (acc <= 1e-9) return 60.0 / 3.6    // 曲线全 0 → 兜底 60 km/h
        val r = rng.nextDouble() * acc
        var lo = 0
        var hi = n
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (cdf[mid] < r) lo = mid else hi = mid
        }
        val kmh = SPEED_CURVE_MIN_KMH + (lo + 0.5) * step
        val rounded = kmh.roundToInt().coerceIn(SPEED_CURVE_MIN_KMH.toInt(), SPEED_CURVE_MAX_KMH.toInt())
        return rounded / 3.6
    }

    private fun trySpawn(junctionId: Long, cfg: JunctionConfig): Boolean {
        val target = pickDestination(junctionId) ?: return false
        val candidates = arcsFromNode[junctionId].orEmpty().ifEmpty { return false }
        // **需求：初始路线优先最短道路** —— 第一条弧按"走这条之后到目的地还要开多少米"排序，
        // 用纯距离场（distByLength），与拥堵无关。旧版这里用的是耗时场，一旦某条路堵了，
        // 新车一出生就会被规划去绕路（路线更长），正是"初始路线不一定最短"的来源。
        val ordered = candidates.sortedBy { lengthRemaining(it, target) }
        val kind = sampleKind(cfg)
        val desired = sampleSpeed(cfg)
        for (arc in ordered) {
            // 需求 1：直接生成在"这条弧末端转向所需"的车道上
            // （左转靠最左 / 右转靠最右 / 直行走规定车道），一出生就站对位置
            val lane = laneGoalOn(arc, target, RouteMode.SHORTEST)
                .coerceIn(0, (arc.lanes - 1).coerceAtLeast(0))
            val nearest = vehicles
                .filter { it.arcKey == arc.key && it.lane == lane && !it.arrived }
                .minByOrNull { it.s }
            val room = if (nearest == null) arc.length else nearest.s - nearest.kind.lengthM
            if (room < kind.lengthM + SPAWN_CLEAR_M) continue
            vehicles += Vehicle(
                id = nextVehicleId++,
                kind = kind,
                desiredSpeed = desired,
                targetJunction = target,
                reactionTime = settings.reactionTimeSec * (0.8 + rng.nextDouble() * 0.4),
                arcKey = arc.key,
                s = 0.0,
                // 生成时的速度 = 该车的初始速度（即速度曲线抽样得到的整数值）；
                // 之后被堵 / 等红灯减速，只要前方恢复空畅，IDM 会把速度重新拉回这个初始速度。
                speed = desired,
                colorHex = VEHICLE_PALETTE[rng.nextInt(VEHICLE_PALETTE.size)],
                lane = lane,
                // 起始路口（信息框显示用，需求 4）
                originJunction = junctionId,
                // 生成时的路径偏好 = **最短道路**（RouteMode 默认值，这里显式写出以表明意图）
                routeMode = RouteMode.SHORTEST,
            ).also { v ->
                // 自主规划的比较基准：计划在入口弧末端要走的那条弧（同样按最短距离）
                v.plannedNextKey = chooseArc(arc.toNode, target, RouteMode.SHORTEST)?.key
            }
            totalSpawned++
            return true
        }
        return false
    }

    private fun stepJunctions(dt: Double) {
        for (node in network.openNodes()) {
            val cfg = junctionConfigs[node.id] ?: continue
            val st = junctionStates.getOrPut(node.id) { JunctionState() }
            val outArcs = arcsFromNode[node.id].orEmpty()

            if (outArcs.isEmpty()) {
                st.blockedTime += dt
            } else {
                val blocked = outArcs.any { arc ->
                    // 某条出口弧的**每条车道**都贴着路口 → 该出口满；任一出口满即视为堵住
                    // （单车道时等价于"该弧有车贴着路口"，保持原有崩溃判定灵敏度）
                    (0 until arc.lanes).all { l ->
                        vehicles.any {
                            it.arcKey == arc.key && it.lane == l && !it.arrived &&
                                it.s - it.kind.lengthM < SPAWN_CLEAR_M
                        }
                    }
                }
                if (blocked) {
                    st.blockedTime += dt
                } else {
                    st.blockedTime = 0.0
                    st.spawnBudget += cfg.spawnRatePerSec * dt
                    while (st.spawnBudget >= 1.0) {
                        if (trySpawn(node.id, cfg)) st.spawnBudget -= 1.0 else break
                    }
                }
            }

            if (st.blockedTime >= settings.crashBlockSeconds) {
                crashedJunctionId = node.id
                return
            }
        }
    }

    // ==================== 视觉防重叠（跨弧 / 跨车道兜底） ====================

    /**
     * 车身用"三圆胶囊"近似：沿车轴排 3 个半径 = 半车宽的圆，覆盖整条车身
     * （长车如公交/卡车用两圆会中间断开，三圆刚好连续）。
     * 返回 6 个分量 `[x0,y0, x1,y1, x2,y2]`（世界坐标）。
     */
    private fun bodyDiscs(p: RenderPose, lengthM: Double): DoubleArray {
        val off = (lengthM * 0.5 - BODY_DISC_R).coerceAtLeast(0.0)
        val cx = cos(p.angle)
        val cy = sin(p.angle)
        return doubleArrayOf(
            p.x - cx * off, p.y - cy * off,
            p.x, p.y,
            p.x + cx * off, p.y + cy * off,
        )
    }

    /**
     * 视觉防重叠兜底（本轮新增）。
     *
     * 同一弧 + 同一车道由 IDM 跟车与逐帧硬夹取保证；但**跨弧 / 跨车道**的两辆车
     * （最典型的是路口里左转车与对向直行车、以及一条路刚驶出与另一条路刚驶入）
     * 分属不同桶，前面的约束彼此看不见，于是画面上会"叠"在一起。
     *
     * 这里按世界坐标的**显示姿态**做一次兜底：车身相交即沿各自弧线方向把两车推开。
     * 只微调弧上里程 s（不动车道、不改拓扑），再叠加单帧限幅 [OVERLAP_MAX_PUSH_M]，
     * 剩余重叠下一帧继续收敛 —— 因此不会出现"瞬移"，只会看到后车稍稍退让。
     */
    private fun resolveOverlaps(active: List<Vehicle>) {
        if (active.size < 2) return
        val list = ArrayList<Vehicle>(active.size)
        val poses = ArrayList<RenderPose>(active.size)
        for (v in active) {
            val p = poseOf(v) ?: continue
            list += v
            poses += p
        }
        val n = list.size
        if (n < 2) return
        for (i in 0 until n) {
            val a = list[i]
            val pa = poses[i]
            val reachA = a.kind.lengthM * 0.5 + BODY_DISC_R
            for (j in i + 1 until n) {
                val b = list[j]
                val pb = poses[j]
                // 粗筛：中心距超过两车半长之和 → 车身不可能相交，跳过昂贵的胶囊检测
                val reach = reachA + b.kind.lengthM * 0.5
                val dx = pa.x - pb.x
                val dy = pa.y - pb.y
                if (dx * dx + dy * dy > reach * reach) continue
                separatePair(a, pa, b, pb)
            }
        }
    }

    /** 若两车车身相交，沿各自弧方向按最小二乘分配修正量把它们分开 */
    private fun separatePair(a: Vehicle, pa: RenderPose, b: Vehicle, pb: RenderPose) {
        val da = bodyDiscs(pa, a.kind.lengthM)
        val db = bodyDiscs(pb, b.kind.lengthM)
        val twoR = BODY_DISC_R * 2.0
        var bestPen = 0.0
        var nx = 0.0
        var ny = 0.0
        for (k in 0 until 9) {
            val ia = (k / 3) * 2
            val ib = (k % 3) * 2
            val dx = da[ia] - db[ib]
            val dy = da[ia + 1] - db[ib + 1]
            val d2 = dx * dx + dy * dy
            if (d2 < twoR * twoR) {
                val d = sqrt(d2)
                val pen = twoR - d
                if (pen > bestPen) {
                    bestPen = pen
                    if (d > 1e-6) { nx = dx / d; ny = dy / d }
                }
            }
        }
        if (bestPen <= 1e-6) return

        val ta = Vector2(cos(pa.angle), sin(pa.angle))
        val tb = Vector2(cos(pb.angle), sin(pb.angle))
        // 各自弧方向在"分离方向"上的投影：投影越大，说明沿这条方向挪动越有效 → 多承担一点
        val projA = nx * ta.x + ny * ta.y
        val projB = nx * tb.x + ny * tb.y
        val denom = projA * projA + projB * projB
        if (denom < 1e-6) return          // 两车都几乎垂直于分离方向 → 交给下一帧的车道偏移解决

        val depth = min(bestPen * 0.75, OVERLAP_MAX_PUSH_M)
        // 注意 sign(proj)·|proj| 恒等于 proj，所以修正量就是 depth·proj/denom：
        // 投影为正 → 沿自身前进方向让开；为负 → 后退让开。
        shiftAlongArc(a, depth * projA / denom)
        shiftAlongArc(b, -depth * projB / denom)
    }

    /** 沿当前弧微调里程；不越过弧两端，且"该停车的车"不会被推过停止线 */
    private fun shiftAlongArc(v: Vehicle, ds: Double) {
        if (abs(ds) < 1e-9) return
        // 正在倒车让行的车**不许被"视觉防重叠"往前推**：僵持时两车本来就贴着，
        // 一推就退不出去，会变成"退一点、推回来"的死循环。
        if (ds > 0.0 && v.reverseRemaining > 0.0) return
        val arc = arcsByKey[v.arcKey] ?: return
        var hi = arc.length
        if (ds > 0.0 && signalStopGap(v) != null) {
            hi = min(hi, stopLimitS(v, arc)).coerceAtLeast(0.0)
        }
        v.s = (v.s + ds).coerceIn(0.0, hi)
    }

    // ==================== 主循环 ====================

    fun tick(dt: Double) {
        if (crashedJunctionId != null) return
        simTime += dt

        stepSignals(dt)
        updateCongestion(dt)
        routeRefreshTimer -= dt
        // 定时刷新；或有车辆因拥堵请求立即刷新（两者共用同一次重算，避免 Dijkstra 风暴）
        if (routeRefreshTimer <= 0.0 || pendingRouteRefresh) {
            recomputeRoutes()
            routeRefreshTimer = ROUTE_REFRESH_SECONDS
            pendingRouteRefresh = false
        }

        // 到达淡出：未淡完的车辆继续变淡，淡完移除
        val fading = vehicles.filter { it.arrived }
        for (v in fading) v.fade -= dt / FADE_SECONDS
        // ⚠️ 到达计数已经在 `v.arrived = true` 那一刻加过了（见 stepVehicle），
        //    这里只管把淡出结束的车从列表里摘掉，**不要再统计一次**，否则会重复计数。
        vehicles.removeAll { it.arrived && it.fade <= 0.0 }

        // 正常车辆步进（到达车辆不再参与跟车）
        val active = vehicles.filter { !it.arrived }
        val byLane = lanesByArcLane(active)
        val nearByNode = buildNearNodes(active)
        for (v in active) stepVehicle(v, dt, byLane, nearByNode)

        // 跨弧 / 跨车道的视觉重叠兜底（路口里最容易出现）——先做，
        // 随后同车道的硬夹取再收一遍，保证兜底不会把同车道两车挤到一起。
        val alive = vehicles.filter { !it.arrived }
        resolveOverlaps(alive)

        // 硬防撞兜底：同一弧 + 同一车道的车至少 0.5m，绝不重叠（s 不得被压到负数）
        val afterByLane = lanesByArcLane(alive)
        for (list in afterByLane.values) {
            for (i in 1 until list.size) {
                val front = list[i - 1]
                val back = list[i]
                // 正在倒车让行的车不许被这条硬夹取拽回去（否则永远退不出路口）
                if (back.reverseRemaining > 0.0) continue
                val gap = front.s - front.kind.lengthM - back.s
                if (gap < 0.5) {
                    back.s = max(0.0, front.s - front.kind.lengthM - 0.5)
                    if (back.speed > front.speed) back.speed = front.speed
                }
            }
        }

        // ---- "实际没动"计时（供僵持倒车判定）----
        // 按弧上里程的逐帧位移算，而不是速度：僵持时速度可能是正的（IDM 想走、被防重叠层推回），
        // 只有位移能说明它真的没挪动。位移恢复后按 3 倍速率衰减，避免一点点抖动累积成误判。
        for (v in vehicles) {
            if (v.arrived) { v.blockedSec = 0.0; continue }
            // 等红灯 / 黄灯不算"僵持"：这类"不动"是灯管着的，绿灯一亮就会走。
            // 若把它算进去，红灯期间攒的秒数会在绿灯瞬间触发一次莫名其妙的倒车。
            if (v.waitingSignal) {
                v.blockedSec = 0.0
                v.blockedRefTimer = 0.0
                v.lastArcKey = v.arcKey
                v.lastS = v.s
                continue
            }
            v.blockedRefTimer += dt
            if (v.blockedRefTimer < Vehicle.BLOCK_WINDOW_SEC) continue
            // 一个窗口结束：看这 0.4 秒里**净**挪了多远（来回推挤会互相抵消，不会被误判成"在动"）
            val sameArc = v.lastArcKey == v.arcKey
            val moved = if (sameArc) abs(v.s - v.lastS) else Double.MAX_VALUE
            if (v.reverseRemaining > 0.0 || moved >= Vehicle.BLOCK_MIN_MOVE_M) {
                v.blockedSec = max(0.0, v.blockedSec - v.blockedRefTimer * 2.0)
            } else {
                v.blockedSec += v.blockedRefTimer
            }
            v.blockedRefTimer = 0.0
            v.lastArcKey = v.arcKey
            v.lastS = v.s
        }

        stepJunctions(dt)

        // ---- 拥堵度（纯渲染用，见 Vehicle.congestion01）----
        // 放在所有位移处理之后算：这一帧的 s / speed 都已经定稿，读到的才是最终态。
        for (v in vehicles) {
            if (v.arrived) continue
            val target = if (v.desiredSpeed < 0.5) {
                0.0
            } else {
                (1.0 - v.speed / v.desiredSpeed).coerceIn(0.0, 1.0)
            }
            // 不对称平滑：堵上得快、疏通得慢，避免颜色来回闪。
            // 上升取 0.6s 而不是更短：车刚生成时 speed 还是 0，若反应太快，
            // 每辆新车出生瞬间都会"闪一下红"，看起来像事故。0.6s 刚好让它起步后就被消化掉。
            val tau = if (target > v.congestion01) 0.6 else 1.8
            v.congestion01 += (target - v.congestion01) * (dt / tau).coerceIn(0.0, 1.0)
        }
    }

    // ==================== 渲染快照 ====================

    fun renderPositions(): List<VehicleRender> {
        val result = mutableListOf<VehicleRender>()
        for (v in vehicles) {
            // 位置与朝向统一由 poseOf 给出（含变道插值 / 转弯平滑）
            val pose = poseOf(v) ?: continue
            result += VehicleRender(
                id = v.id,
                x = pose.x,
                y = pose.y,
                angleRad = pose.angle,
                lengthM = v.kind.lengthM,
                colorHex = v.colorHex,
                speedKmh = v.speed * 3.6,
                alpha = if (v.arrived) v.fade.toFloat() else 1f,
                congestion = v.congestion01.toFloat(),
                kind = v.kind,
            )
        }
        return result
    }

    /**
     * 需求 4：聚焦车辆的快照（信息框数据 + 前方规划路径）。
     *
     * 与 [renderPositions] 在同一帧生成，所以信息框里的速度、虚线路径与画面上的车
     * **永远是同一时刻的状态**，不会出现"车已经拐完了、虚线还在直走"的错位。
     * 车已到站 / 被删除时返回 null（UI 据此收起信息框）。
     */
    fun renderFocus(id: Long?): FocusRender? {
        if (id == null) return null
        val v = vehicles.firstOrNull { it.id == id && !it.arrived } ?: return null
        val pose = poseOf(v) ?: return null
        return FocusRender(
            id = v.id,
            x = pose.x,
            y = pose.y,
            speedKmh = v.speed * 3.6,
            desiredKmh = v.desiredSpeed * 3.6,
            originJunction = v.originJunction,
            targetJunction = v.targetJunction,
            lengthM = v.kind.lengthM,
            path = pathAhead(v),
        )
    }

    /** 某条（弧, 里程, 车道）处的世界坐标：正在走转弯圆弧时直接取圆弧上的点 */
    private fun poseOnRoute(arc: Arc, s: Double, lane: Int, turn: Vehicle?): Vector2 {
        if (turn != null) {
            val u = turn.turnUAt(arc.key, s)
            if (u != null) {
                val p = turn.turnPoseAtU(u)
                return Vector2(p.x, p.y)
            }
        }
        val base = arc.pointAtS(s)
        return base + arc.tangentAtS(s).perp() * laneOffsetM(lane.toDouble())
    }

    /**
     * **车头往前**的已规划路径（世界坐标折线）。
     *
     * 从当前里程开始逐段往后串：第一段用本车的真实姿态（含转弯圆弧 / 变道插值），
     * 后续段按"该转向要求的入口车道"取偏移 —— 与真正换弧时的选道逻辑一致。
     * 只往前、不包含车后的任何点，所以画出来一定是"车前方的箭头虚线"。
     *
     * ## 为什么不再有"最多 170 米"的限制（本轮修复）
     *
     * 旧版 `PATH_MAX_M = 170` 是"太长会糊满屏幕"的懒办法，结果是**离终点还远时虚线就断了**：
     * 用户看到的是"部分车辆的虚线过短、没有延伸到目标路口"。
     * 现在改成**一直画到目标路口**（`if (arc.toNode == v.targetJunction) break` 天然就停在那儿），
     * 只保留两道**点数/段数**保护（[PATH_MAX_POINTS] / [PATH_MAX_ARCS]），避免病态路网卡死。
     *
     * 采样步长是**近细远粗**（[PATH_STEP_M] → [PATH_COARSE_STEP_M]，分界见 [PATH_DETAIL_POINTS]）：
     * 车头附近要贴着车道线，几百米外一条 3.5m 的折线段在屏幕上还不到 1 像素，纯属浪费。
     * 箭头本身按屏幕弧长等距排布（见 `SimCanvas.drawRouteGuide`），所以肉眼完全看不出粗细变化。
     */
    private fun pathAhead(v: Vehicle): List<Vector2> {
        val out = ArrayList<Vector2>(96)
        var arc = arcsByKey[v.arcKey] ?: return out
        var s = v.s
        var lane = v.lane

        // 近处细、远处粗（同一个函数里动态取，避免两套循环）
        fun stepM(): Double =
            if (out.size < PATH_DETAIL_POINTS) PATH_STEP_M else PATH_COARSE_STEP_M

        // ---------- ① 正在过转弯弧（左转弧 / 右转圆角都一样）----------
        // 转弯弧是**绕过节点**的：它的中点并不在节点上（右转圆角的弯心离节点还有 4~5m）。
        // 若照旧"沿车道走到节点、再从下一段车道中心线接着画"，箭头会在弯心处**向后跳一下**，
        // 看上去像虚线打了个折返。所以这里先把弧上剩下的部分按弧长采样出来，
        // 再从弧终点（它正好落在出口车道中心线上）继续往后串 —— 全程连续、无回折。
        if (v.turnActive) {
            val u0 = v.turnUAt(v.arcKey, s)
            val postArc = v.turnPostArcKey?.let { arcsByKey[it] }
            if (u0 != null && u0 < 1.0 && postArc != null) {
                out += poseOnRoute(arc, s, lane, v)          // 车当前位置
                val restM = v.turnLen * (1.0 - u0)
                val steps = ceil(restM / PATH_STEP_M).toInt().coerceIn(1, 48)
                for (i in 1..steps) {
                    val p = v.turnPoseAtU(u0 + (1.0 - u0) * i / steps)
                    out += Vector2(p.x, p.y)
                }
                arc = postArc
                s = v.turnPostS
                lane = v.turnEntryLane
            }
        }

        var guard = 0
        while (guard++ < PATH_MAX_ARCS && out.size < PATH_MAX_POINTS) {
            var p = s
            while (p < arc.length - 0.01 && out.size < PATH_MAX_POINTS) {
                out += poseOnRoute(arc, p, lane, null)
                p += stepM()
            }
            out += poseOnRoute(arc, arc.length, lane, null)
            // **需求 3 的根因修复**：终点路口就在这段弧末端 → 路径到此为止。
            // 旧版在这里继续 `chooseArc(...)`，于是过了终点还会再拐进一条**驶离**该路口的弧 ——
            // 画面上就是"虚线到了终点不但没停，反而折返出去"。
            // 车辆本身到终点就停（arrived → 淡出），虚线必须停在同一处，两者才对得起来。
            if (arc.toNode == v.targetJunction) break
            val next = chooseArc(arc.toNode, v.targetJunction, v.routeMode) ?: break
            if (next.key == arc.key) break            // 原地掉头：不画
            val maxReal = (next.lanes - 1).coerceAtLeast(0)
            val r = entryLanesFor(arc, next, v.targetJunction, v.routeMode)
            lane = lane.coerceIn(r.first.coerceIn(0, maxReal), r.last.coerceIn(0, maxReal))
            val junctionId = arc.toNode
            val lastPt = out.lastOrNull()
            arc = next
            s = 0.0
            // **换弧补桥**（本轮需求 4：转弯时虚线变成"杂乱折线"的根因）。
            // 两条弧的"车道中心线端点"其实并不重合：车道偏移是沿**各自切向**的法向算的，
            // 一转弯法向就翻转 90°，两端能差出一整个车道宽（3.5~7m）。直接连过去，画成
            // 虚线就是"先横跳一下再继续"，看着像乱七八糟的折线。
            // 这里用一个以**路口中心**为控制点的二次贝塞尔把两端接起来 —— 视觉上就是一个
            // 圆滑的转角，箭头顺着它排，自然是弧线。
            if (lastPt != null && out.size < PATH_MAX_POINTS) {
                out += bendPoints(lastPt, network.node(junctionId)?.pos, poseOnRoute(arc, 0.0, lane, null))
            }
        }
        return out
    }

    /**
     * 换弧处的"转角桥"：从 [a] 到 [b] 的一段二次贝塞尔（控制点取路口中心 [corner]）。
     *
     * 两端本来就贴着（直行）时直接返回空列表 —— 绝不给直行路段凭空加几个点。
     * 不含两端：起点是上一段的终点，终点会由下一段弧从 s=0 开始补上。
     */
    private fun bendPoints(a: Vector2, corner: Vector2?, b: Vector2): List<Vector2> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 0.35) return emptyList()
        val cx = corner?.x ?: ((a.x + b.x) / 2)
        val cy = corner?.y ?: ((a.y + b.y) / 2)
        val steps = ceil(dist / PATH_STEP_M).toInt().coerceIn(3, 12)
        val res = ArrayList<Vector2>(steps - 1)
        for (i in 1 until steps) {
            val t = i.toDouble() / steps
            val mt = 1.0 - t
            res += Vector2(
                mt * mt * a.x + 2 * mt * t * cx + t * t * b.x,
                mt * mt * a.y + 2 * mt * t * cy + t * t * b.y,
            )
        }
        return res
    }

    /**
     * 各个信号灯进口的灯位（画在停止线旁、道路外侧），供画布绘制红/黄/绿。
     *
     * **规则二**下"直行"和"左转"是分别放行的，所以每个进口要画**两个灯头**：
     *  - 直行灯头立在行驶方向右侧路缘外（和其它规则共用同一个位置）；
     *  - 左转灯头再往外挪一个车道宽（同一根横臂上的第二个灯），一眼就能看出
     *    "直行绿、左转红"。规则一 / 三里两种转向永远同色，只画一个灯头。
     *
     * 右转不受灯控，**不单独画灯头**（否则会让人以为右转要看灯）。
     */
    fun renderSignals(): List<SignalLightRender> {
        if (signals.isEmpty()) return emptyList()
        val heads = signalHeadGeometry()
        // 每帧只重建"会变的那部分"：位置、朝向全部来自缓存，这里只是查一次颜色。
        val out = ArrayList<SignalLightRender>(heads.size + heads.count { it.hasLeft })
        for (h in heads) {
            val sig = signals[h.nodeId] ?: continue
            out += SignalLightRender(h.x, h.y, sig.stateOf(h.arcKey, TurnDir.STRAIGHT), h.angleRad)
            // 规则二：左转单独一个灯头
            if (h.hasLeft) {
                out += SignalLightRender(
                    h.leftX, h.leftY,
                    sig.stateOf(h.arcKey, TurnDir.LEFT),
                    h.angleRad,
                )
            }
        }
        return out
    }

    /**
     * 某路口**当前相位**的人话版本（见 [SignalPhaseRender]）；未配灯返回 null。
     *
     * 只在界面请求时才算，不参与仿真循环 —— 每帧一个路口、几次三角函数，开销可忽略。
     */
    fun signalPhaseInfo(nodeId: Long): SignalPhaseRender? {
        val sig = signals[nodeId] ?: return null
        val phases = sig.phases
        if (phases.isEmpty()) return null
        val idx = sig.phase.coerceIn(0, phases.size - 1)
        val p = phases[idx]

        // 转向：本状态给各进口的掩码全一致时直接翻译；混合（规则一）就是"直行 + 左转"
        val masks = p.allow.values
        val turn = when {
            masks.isEmpty() -> 0
            masks.all { it == MOVE_STRAIGHT } -> 0
            masks.all { it == MOVE_LEFT } -> 1
            else -> 2
        }

        // 轴向：把每条进口的行驶 heading 折到"半角域"（乘 2）再求平均。
        // 直接平均会在 ±π 附近互相抵消（一条朝东、一条朝西，向量和接近 0），
        // 折半角后两者方向一致，平均才有意义。
        var sx = 0.0
        var sy = 0.0
        for (k in p.allow.keys) {
            val a = arcsByKey[k] ?: continue
            val h = a.tangentAtS(a.length).angleRad * 2.0
            sx += cos(h)
            sy += sin(h)
        }
        val axis = if (abs(sx) < 1e-6 && abs(sy) < 1e-6) {
            -1
        } else {
            val h = atan2(sy, sx) / 2.0
            if (abs(sin(h)) > abs(cos(h))) 0 else 1   // 更偏竖直 → 上下；否则左右
        }

        return SignalPhaseRender(
            index = idx + 1,
            count = phases.size,
            remainingSec = (p.segSec - sig.timer).coerceAtLeast(0.0),
            greenSec = p.greenSec,
            segSec = p.segSec,
            isYellow = sig.timer >= p.greenSec,
            axis = axis,
            turn = turn,
        )
    }

    /** 灯头几何（惰性构建 + 缓存）：位置只依赖路网，与相位无关 */
    private fun signalHeadGeometry(): List<SignalHeadGeom> {
        signalHeadCache?.let { return it }
        val out = ArrayList<SignalHeadGeom>()
        for ((nodeId, sig) in signals) {
            val stop = stopLineOf(nodeId)
            for (arc in arcsToNode[nodeId].orEmpty()) {
                val s = arc.length - stop
                if (s < 0.0) continue
                val dir = arc.tangentAtS(s)              // 该进口的**行驶方向**（倒向弧已修正）
                val base = arc.pointAtS(s)
                val n = dir.perp()                       // 行驶方向的右侧：灯就立在这一侧路缘外
                val off = RoadNetwork.LANE_WIDTH_M * arc.lanes + 1.2
                val p = base + n * off
                // 规则二才需要第二个灯头；几何一次算好，之后规则变了会连带清缓存
                if (sig.splitHeads) {
                    val pl = base + n * (off + RoadNetwork.LANE_WIDTH_M)
                    out += SignalHeadGeom(nodeId, arc.key, p.x, p.y, dir.angleRad, pl.x, pl.y, true)
                } else {
                    out += SignalHeadGeom(nodeId, arc.key, p.x, p.y, dir.angleRad, 0.0, 0.0, false)
                }
            }
        }
        signalHeadCache = out
        return out
    }

    /**
     * 各路口进口的**停止线**位置（十字 / 丁字路口才画）。
     * 画布据此画出白色横线，让"车停在线上、不越线"这件事肉眼可见（需求 1）。
     *
     * 位置只跟路网几何有关（弧长、内缩量、车道数），与仿真状态无关 →
     * 首次调用构建一次后整份复用，直到拓扑重建（[rebuildTopology]）才失效。
     */
    fun renderStopLines(): List<StopLineRender> {
        stopLineCache?.let { return it }
        val out = ArrayList<StopLineRender>()
        for (node in network.nodes.values) {
            if (network.degree(node.id) < 3) continue
            val stop = stopLineOf(node.id)
            for (arc in arcsToNode[node.id].orEmpty()) {
                val s = arc.length - stop
                if (s < 0.0) continue
                val p = arc.pointAtS(s)
                out += StopLineRender(
                    x = p.x,
                    y = p.y,
                    angleRad = arc.tangentAtS(s).angleRad,
                    halfWidthM = arc.lanes * RoadNetwork.LANE_WIDTH_M,
                )
            }
        }
        stopLineCache = out
        return out
    }

    fun snapshotStats(): SimStats {
        val active = vehicles.count { !it.arrived }
        val avg = if (active == 0) 0.0 else vehicles.filter { !it.arrived }.sumOf { it.speed } / active * 3.6
        val blocked = junctionStates
            .filter { it.value.blockedTime > 1.0 }
            .mapValues { it.value.blockedTime }
        return SimStats(active, totalSpawned, totalArrived, avg, blocked, totalReroutes, totalLaneChanges)
    }
}
