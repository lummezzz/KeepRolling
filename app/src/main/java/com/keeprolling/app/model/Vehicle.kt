package com.keeprolling.app.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 单辆车的运行时状态。
 *
 * 位置用"弧（有向道路）+ 弧上里程 s"表示：
 *  - 跟车/防撞只需比较同弧上的 s 值；
 *  - 弧末切换即"过路口"，由路由选择下一段弧。
 * 曲线道路不需要特殊处理——弧上的坐标与切向都由 [RoadPath] 给出。
 *
 * 反应时间机制：reactionTime 为"观察到前车减速 → 开始刹车"的延迟，
 * plannedAccel 只在反应周期到期时重算一次，期间保持不变。
 *
 * 自主规划（v3 新增）：维护一个最近若干秒的**速度环形缓冲**，
 * 当窗口内平均速度掉到"初始期望速度的若干比例"以下时，判定为被堵住，
 * 通知仿真器刷新路由并尝试改道（冷却期内不重复改道）。
 */
class Vehicle(
    val id: Long,
    val kind: VehicleKind,
    val desiredSpeed: Double,     // 期望速度 m/s（由路口速度正态分布抽样）→ 也作为"初始速度"基准
    var targetJunction: Long,     // 目的地开放路口 id（目的地被关闭时会重抽）
    val reactionTime: Double,     // 反应时间（秒）
    var arcKey: String,           // 当前所在有向弧
    var s: Double,                // 弧上里程（米）
    var speed: Double = 0.0,
    val colorHex: Long,           // 车辆颜色（随机取色，终生不变）
    /** 当前所在**车道序号**（0 = 最靠中线，越大越靠路缘）。多车道道路才有意义 */
    var lane: Int = 0,
    /** 生成时所在的路口（信息框里的"起始路口"） */
    val originJunction: Long = -1L,
    /**
     * **路径偏好**（见 [RouteMode]）。
     *
     * 生成时一律是 [RouteMode.SHORTEST] —— 初始路线优先走最短的那条路。
     * 只有被堵住、且换路确实更快时，[TrafficSimulator.stepRerouteLogic] 才把它改成
     * [RouteMode.FASTEST]（按实时耗时选路）。
     */
    var routeMode: RouteMode = RouteMode.SHORTEST,
) {
    var accel: Double = 0.0
    var plannedAccel: Double = 0.0
    var reactionTimer: Double = 0.0
    var arrived: Boolean = false  // 抵达目的地：进入淡出阶段
    var fade: Double = 1.0        // 淡出进度 1 → 0

    // ---------------- 自主规划相关 ----------------

    /** 速度采样环形缓冲（按 [HIST_DT] 采一次，容量 [HIST_SIZE] 覆盖约 5 秒） */
    private val speedHist = DoubleArray(HIST_SIZE)
    private var histIdx = 0
    private var histCount = 0

    /** 采样计时器 */
    var histTimer: Double = 0.0

    /** 改道冷却剩余秒数 */
    var rerouteCooldown: Double = 0.0

    /** 本车累计换路次数（用于统计） */
    var rerouteCount: Int = 0

    // ---------------- 多车道自主变道（需求 7）----------------

    /** 连续"被前方慢车压着"的时长（秒）；超过阈值且相邻车道有空即自主变道 */
    var followSlowTime: Double = 0.0

    /** 变道冷却剩余秒数：刚变过道就再等一会儿，避免在两车道间来回横跳 */
    var laneChangeCooldown: Double = 0.0

    /**
     * 是否正在等信号灯（需求 2）：红灯/黄灯下排队（停住或只剩蠕行速度）时为 true。
     * 等灯期间**不得自主变道**，绿灯亮起、车重新起步后自动恢复（见 [stepLaneChange]）。
     */
    var waitingSignal: Boolean = false

    // ---------------- 路口让行 / 僵持倒车（本轮新增）----------------

    /**
     * 在停止线前**停住**的累计时长（秒）。
     *
     * 用途：识别"转弯车与直行车互不相让、双双停住"的僵局 —— 停够
     * [TrafficSimulator.TURN_DEADLOCK_SEC] 就让**转弯车倒车**，把路让给直行车。
     * 只要车能动（速度起来、或不再被拦）就清零，所以正常等红灯不会误判。
     */
    var stopTime: Double = 0.0

    /** 剩余倒车距离（米）：> 0 表示正在倒车让行，倒完自动恢复前进 */
    var reverseRemaining: Double = 0.0

    /**
     * **绿灯起步反应计时**（秒）—— 本轮需求 3。
     *
     * 头车（前方没车）绿灯一亮立刻走；后面的车必须**先看到前车动了**，再等
     * [TrafficSimulator.START_REACTION_SEC] 才允许加速。
     * 于是车流是一辆接一辆起步，而不是整排同时窜出去。
     * 只要车动起来（速度超过 [TrafficSimulator.STOPPED_MS]）就清零。
     */
    var startDelayTimer: Double = 0.0

    /** 倒车冷却（秒）：倒过一次后这段时间不再倒，避免在两车之间来回抽搐 */
    var reverseCooldown: Double = 0.0

    /**
     * **本段弧的车道目标（粘性）**：进入一条弧时算一次，之后整段路都不再重算。
     *
     * 为什么必须粘住：路由每 2 秒按拥堵重算一次，`chooseArc` 选出的下一段弧可能在
     * 左/直/右之间来回跳。若每帧都按最新转向去"对齐车道"，车就会在路段中间反复并线 ——
     * 用户看到的就是"在十字路口莫名变道"。锁定一次，等进入下一段路再重新决策。
     */
    var laneGoalArcKey: String? = null

    /** 锁定的车道区间；null = 本段末端没有可走的路（终点 / 断头路），不要动车道 */
    var laneGoal: IntRange? = null

    /** 锁定的"末端是否右转"（与 [laneGoal] 同一时刻算出，供右转圆角 / 选道沿用） */
    var laneGoalRight: Boolean = false

    /**
     * **"实际没动"的累计时长（秒）**——按"滑动窗口内的净位移"判定，不看速度。
     *
     * 为什么不能看速度：路口僵持时，车的速度往往一直是正的（IDM 想往前走），
     * 但被视觉防重叠层每帧推回来，弧上里程在原地小幅来回。按速度判会永远认为"它还在动"，
     * 于是僵持时间永远攒不够、倒车永远不触发 —— 这正是"两车顶死不退让"的根因。
     *
     * 为什么不用"逐帧位移"：互相推挤时单帧位移可能很大（0.5m/帧），但净位移是 0。
     * 所以按 [BLOCK_WINDOW_SEC] 取一次快照，只比较"这 0.4 秒一共挪了几厘米"。
     */
    var blockedSec: Double = 0.0

    /** 上一次位移快照的时间累计（秒），见 [blockedSec] */
    var blockedRefTimer: Double = 0.0

    /**
     * **拥堵度**（0 = 畅通，1 = 完全停住），纯粹给渲染用。
     *
     * 由「实际速度 ÷ 期望速度」得来，并做**不对称平滑**：
     *  - 上升快（0.6s 时间常数）→ 堵上后不到一秒就能看见变红；
     *    但也不能更快 —— 车刚生成时速度还是 0，反应太快会让每辆新车出生时"闪一下红"；
     *  - 下降慢（1.8s 时间常数）→ 刚疏通时不至于瞬间"跳回绿色"闪烁。
     *
     * 注意：等红灯的车同样会被算成拥堵（速度接近 0）—— 这是**故意**的，
     * 排队本身就是拥堵。于是路口红灯时那一条队会整条泛红，一眼就能看出堵点。
     */
    var congestion01: Double = 0.0

    /** 位移快照：上一次采样时的（弧, 里程） */
    var lastArcKey: String? = null
    var lastS: Double = 0.0

    // ---------------- 过路口转弯圆弧（纯视觉）----------------

    /**
     * 过路口时车辆从「当前车道在**交汇区边缘**的终点 S」驶向「目标车道在交汇区边缘的起点 E」，
     * 走的是一段**圆弧**：
     *
     *  - 在 S / E 处分别作所在车道的垂线，两条垂线的交点就是圆心 O；
     *  - |OS| = |OE| = R，所以圆弧与两条车道中心线**在端点处精确相切** —— 没有折角、没有平移跳变。
     *
     * **相位怎么接**：圆弧的起点 S 在旧弧上、终点 E 在新弧上，而"逻辑换弧"发生在旧弧末端的
     * 节点（在交汇区正中）。因此圆弧要**跨着换弧动作**渲染：旧弧最后一段走 u∈[0, uSwitch]，
     * 新弧头一段走 u∈[uSwitch, 1]。两段按各自长度等比例映射，换弧瞬间位置连续。
     *
     * 这些字段只服务渲染与防重叠判定，不参与动力学（速度仍由 IDM 决定）。
     */
    var turnCx = 0.0
    var turnCy = 0.0

    /** 圆弧半径（米） */
    var turnR = 0.0

    /** 起点相对圆心的极角（弧度） */
    var turnA0 = 0.0

    /** 有符号扫角：屏幕坐标下正 = 右转、负 = 左转 */
    var turnSweep = 0.0

    /** 圆弧长度（米）= |sweep| · R */
    var turnLen: Double = 0.0

    /** 圆弧起点所在的（旧）弧 */
    var turnPreArcKey: String? = null

    /** 圆弧起点在旧弧上的里程 */
    var turnPreStartS: Double = 0.0

    /** 旧弧上从起点到节点还剩多少米 */
    var turnPreSpan: Double = 0.0

    /** 圆弧终点所在的（新）弧 */
    var turnPostArcKey: String? = null

    /** 圆弧终点在新弧上的里程 */
    var turnPostS: Double = 0.0

    /** 过节点那一刻已完成的圆弧比例（0~1） */
    var turnUSwitch: Double = 0.0

    /** 当前是否有生效中的转弯圆弧 */
    var turnActive: Boolean = false

    /**
     * 建圆弧时**定好**的入口车道（圆弧终点所在车道）。
     *
     * 换弧那一刻必须用这同一个值，不能再重算一次 —— 否则一旦两次挑选结果不同，
     * 圆弧的终点偏移与换弧后的车道偏移就对不上，车会在路口正中**横向跳一下**
     * （看上去就是"在十字路口莫名变道"）。
     */
    var turnEntryLane: Int = 0

    /**
     * 已经试过"建圆弧但几何退化"的那条弧（近似直行 / 掉头 / 中心线不相交）。
     * 同一条弧上不重复尝试 —— 否则车停在路口前等灯时会每帧重算一次几何。
     */
    var turnSkipArcKey: String? = null

    /** 清掉转弯圆弧 */
    fun clearTurn() {
        turnActive = false
        turnLen = 0.0
        turnPreArcKey = null
        turnPostArcKey = null
    }

    /**
     * 挂上一段转弯圆弧。
     *
     * @param cx/cy/r    圆心与半径（世界坐标、米）
     * @param a0/sweep   起点极角与有符号扫角
     * @param preArcKey  起点所在弧（旧弧）
     * @param preStartS  起点在旧弧上的里程
     * @param preSpan    旧弧上"起点 → 节点"的剩余长度（换弧前要走完的圆弧比例按它算）
     * @param postArcKey 终点所在弧（新弧）
     * @param postS      终点在新弧上的里程
     */
    fun beginTurnArc(
        cx: Double, cy: Double, r: Double, a0: Double, sweep: Double,
        preArcKey: String, preStartS: Double, preSpan: Double,
        postArcKey: String, postS: Double,
    ) {
        turnCx = cx; turnCy = cy
        turnR = r; turnA0 = a0; turnSweep = sweep
        turnLen = r * abs(sweep)
        turnPreArcKey = preArcKey
        turnPreStartS = preStartS
        turnPreSpan = preSpan.coerceAtLeast(0.0)
        turnPostArcKey = postArcKey
        turnPostS = postS.coerceAtLeast(0.0)
        val total = turnPreSpan + turnPostS
        turnUSwitch = if (total <= 1e-6) 1.0 else (turnPreSpan / total).coerceIn(0.0, 1.0)
        turnActive = true
    }

    /**
     * 当前（弧, 里程）是否落在转弯圆弧上；落在上面时返回归一化进度 u∈[0,1]。
     *
     * 旧弧段：u 从 0 线性长到 [turnUSwitch]；新弧段：u 从 [turnUSwitch] 长到 1。
     * 两段用**各自剩余长度**做分母，所以换弧那一刻 u 恰好衔接，位置不会跳。
     */
    fun turnUAt(arcKey: String, s: Double): Double? {
        if (!turnActive) return null
        if (arcKey == turnPreArcKey) {
            if (s < turnPreStartS) return null
            val f = if (turnPreSpan <= 1e-6) 1.0
            else ((s - turnPreStartS) / turnPreSpan).coerceIn(0.0, 1.0)
            return f * turnUSwitch
        }
        if (arcKey != turnPostArcKey) return null
        if (s > turnPostS) return null
        val f = if (turnPostS <= 1e-6) 1.0 else (s / turnPostS).coerceIn(0.0, 1.0)
        return turnUSwitch + (1.0 - turnUSwitch) * f
    }

    /** 圆弧上归一化进度 [u] 处的位姿（位置 + 切向朝向） */
    internal fun turnPoseAtU(u: Double): CurvePose {
        val uu = u.coerceIn(0.0, 1.0)
        val ang = turnA0 + turnSweep * uu
        val sgn = if (turnSweep >= 0.0) 1.0 else -1.0
        val tx = -sin(ang) * sgn
        val ty = cos(ang) * sgn
        return CurvePose(
            turnCx + turnR * cos(ang),
            turnCy + turnR * sin(ang),
            atan2(ty, tx),
        )
    }

    // ---------------- 渲染动画（变道 / 转弯平滑，纯视觉状态）----------------

    /**
     * 变道动画：显示车道从 [laneFrom] 平滑滑到 [laneTo]。
     *
     * 逻辑车道 [lane] 在变道决定那一刻就切换（跟车/防撞立刻按新车道计算），
     * 只有**画面上的横向位置**用 [laneBlend] 插值 —— 两者解耦，才不会为了好看
     * 牺牲碰撞检测的正确性。
     */
    var laneFrom: Double = lane.toDouble()
    var laneTo: Double = lane.toDouble()

    /** 变道动画进度：[0,1]，1 表示已完全就位（无动画） */
    var laneBlend: Double = 1.0

    /**
     * 本次变道动画的时长（秒）。
     *
     * 普通"车道对齐"用 [TrafficSimulator.LANE_CHANGE_ANIM_SEC]（1.1s，动作从容）；
     * 保留成变量是为了将来个别场景（如匝道汇入）可以给更短的时长。
     * 右转已改为沿路口圆角通过，不再需要"快速并进专用道"，因此这一项只有普通并线在用。
     */
    var laneAnimSec: Double = 1.1

    /** 当前**显示**车道（含变道动画中间态，可能是小数） */
    fun laneDisplayed(): Double =
        if (laneBlend >= 1.0) laneTo else laneFrom + (laneTo - laneFrom) * smoothStep01(laneBlend)

    /** 开始一次平滑变道：逻辑车道立即切换，显示位置随后插值跟上 */
    fun beginLaneChange(target: Int, animSec: Double = 1.1) {
        laneFrom = laneDisplayed()
        laneTo = target.toDouble()
        laneBlend = 0.0
        laneAnimSec = animSec.coerceAtLeast(0.05)
        lane = target
    }

    /** 不做动画，直接把显示车道对齐到 [target]（过路口重新分配车道时用） */
    fun snapLane(target: Int) {
        lane = target
        laneFrom = target.toDouble()
        laneTo = target.toDouble()
        laneBlend = 1.0
    }

    /**
     * 进入当前弧时"计划"在弧末要走的那条弧。
     * 拥堵触发重算后，若最优弧与它不同，就算一次真实改道。
     */
    var plannedNextKey: String? = null

    /** 记录一次速度采样 */
    fun pushSpeed(v: Double) {
        speedHist[histIdx] = v
        histIdx = (histIdx + 1) % HIST_SIZE
        if (histCount < HIST_SIZE) histCount++
    }

    /** 最近 n 次采样的平均速度（不足则用已有的全部） */
    fun avgSpeedOver(samples: Int): Double {
        if (histCount == 0) return speed
        val n = samples.coerceIn(1, histCount)
        var sum = 0.0
        var i = histIdx
        repeat(n) {
            i = if (i == 0) HIST_SIZE - 1 else i - 1
            sum += speedHist[i]
        }
        return sum / n
    }

    /** 是否已经攒够 n 次采样（攒不够说明刚生成不久，先不做拥堵判定） */
    fun hasSamples(n: Int): Boolean = histCount >= n.coerceIn(1, HIST_SIZE)

    companion object {
        /** 速度采样间隔（秒） */
        const val HIST_DT = 0.125

        /** 环形缓冲容量：0.125 × 40 = 5 秒 */
        const val HIST_SIZE = 40

        /** "是否真的没动"的采样窗口（秒）：窗口内净位移不足 [BLOCK_MIN_MOVE_M] 即算卡住 */
        const val BLOCK_WINDOW_SEC = 0.4

        /** 窗口内至少要走这么远（米）才算"在动"：0.35m / 0.4s ≈ 0.9 m/s */
        const val BLOCK_MIN_MOVE_M = 0.35
    }
}

/**
 * 平滑插值曲线（smoothstep）：两端一阶导数为 0。
 *
 * 变道横向位移、转弯朝向插值都用它 —— 若用线性插值，动作起止会"顿"一下；
 * smoothstep 让开始与结束都柔和，观感接近真实的打方向 / 回方向。
 */
internal fun smoothStep01(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)
}

/** 过路口过渡曲线上一点的位姿（渲染/防重叠内部传递用，避免每帧拆成两个返回值） */
internal data class CurvePose(val x: Double, val y: Double, val angleRad: Double)
