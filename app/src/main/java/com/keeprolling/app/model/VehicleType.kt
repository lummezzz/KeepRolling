package com.keeprolling.app.model

import kotlinx.serialization.Serializable

/**
 * 车辆类型（v2 规格）：
 *  - **所有车型车宽一致**（2.6m，略小于 3.5m 车道）；
 *  - 长度按用户要求：小型车 = 宽 × 1.5；中型车 = 小型车 × 1.5；大型车 = 小型车 × 2；
 *  - 不同车型的加减速能力不同（中型/大型更肉），保证车流观感真实。
 *
 * 命名（本轮需求 2）：显示名由「轿车 / 巴士 / 货车」改为「小型车 / 中型车 / 大型车」，
 * 枚举常量名保持不变，避免影响序列化与既有引用。
 */
enum class VehicleKind(
    val label: String,
    val lengthM: Double,
    val maxAccel: Double,
    val comfyDecel: Double,
    val minGap: Double,
    val headwayTime: Double,
) {
    CAR("小型车", 2.6 * 1.5, 2.0, 2.5, 2.0, 1.2),        // 3.9 m
    BUS("中型车", 2.6 * 1.5 * 1.5, 1.0, 2.0, 3.0, 1.6),   // 5.85 m
    TRUCK("大型车", 2.6 * 1.5 * 2.0, 1.2, 2.0, 3.0, 1.5); // 7.8 m

    companion object {
        /** 统一车宽（米） */
        const val WIDTH_M = 2.6
    }
}

/** 车辆随机配色池（低饱和、互相区分度高） */
val VEHICLE_PALETTE: LongArray = longArrayOf(
    0xFF378ADD, 0xFF7F77DD, 0xFF1D9E75, 0xFFD85A30, 0xFFBA7517,
    0xFFD4537E, 0xFF185FA5, 0xFF993C1D, 0xFF534AB7, 0xFF0F6E56,
)

// ==================== 速度曲线（需求 1）====================

/** 速度控制点数量：40 / 50 / … / 120，共 9 个 */
const val SPEED_CURVE_POINTS = 9

/** 速度区间与步长（km/h）：硬约束 40~120，控制点每 10 一个 */
const val SPEED_CURVE_MIN_KMH = 40.0
const val SPEED_CURVE_MAX_KMH = 120.0
const val SPEED_CURVE_STEP_KMH = 10.0

/**
 * 默认速度曲线（本轮需求 1 定稿的形状）：
 *
 * | 速度 km/h | 40 | 50 | 60 | 70   | 80 | 90   | 100 | 110 | 120 |
 * | 相对概率  | 0  | 0  | 0  | 0.5  | 1  | 0.5  | 0   | 0   | 0   |
 *
 * 即：**40–60 与 100–120 完全不来车**，峰值在 **80**，70 / 90 各为峰值的一半。
 * 控制点之间由 [speedCurveValueAt] 的 Catmull-Rom 样条平滑连接（曲线是三角形的，
 * 但顶点是圆滑的，不会出现尖角）。
 *
 * 这是**开箱即用值**：新建路口、旧存档里没有曲线、用户把曲线清空 —— 都回落到这条。
 */
fun defaultSpeedCurve(): List<Double> = listOf(
    0.0, 0.0, 0.0,     // 40 / 50 / 60   —— 概率 0
    0.5, 1.0, 0.5,     // 70 / 80 / 90   —— 半峰 / 峰值 / 半峰
    0.0, 0.0, 0.0,     // 100 / 110 / 120 —— 概率 0
)

/**
 * 曲线在 [kmh] 处的取值：控制点之间用 **Catmull-Rom 样条**平滑插值（与画布上画出来的一致），
 * 结果夹到 0~1。曲线两端之外的横坐标按端点值处理。
 */
fun speedCurveValueAt(curve: List<Double>, kmh: Double): Double {
    if (curve.isEmpty()) return 0.0
    if (curve.size == 1) return curve[0].coerceIn(0.0, 1.0)
    val t = ((kmh - SPEED_CURVE_MIN_KMH) / SPEED_CURVE_STEP_KMH)
        .coerceIn(0.0, (curve.size - 1).toDouble())
    val i = t.toInt().coerceIn(0, curve.size - 2)
    val f = t - i
    val p0 = curve[(i - 1).coerceAtLeast(0)]
    val p1 = curve[i]
    val p2 = curve[i + 1]
    val p3 = curve[(i + 2).coerceAtMost(curve.size - 1)]
    val q = 0.5 * (
        2.0 * p1 +
            (-p0 + p2) * f +
            (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f * f +
            (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f * f * f
        )
    return q.coerceIn(0.0, 1.0)
}

/**
 * 开放路口的出入口配置（对应「出入口设置」对话框）。
 *
 * @param spawnIntervalSec 车辆生成间隔（秒/辆），取值 1~10 的整数；**默认 8 秒/辆**
 * @param carRatio/busRatio/truckRatio 三种车型的生成比例（三段式双滑块决定，和为 1）
 * @param speedMeanKmh / speedSigmaKmh **纯兼容字段**：v12 之前的存档只有这两个值，
 *        为了能反序列化旧 JSON 而保留，**已不参与任何计算**。
 * @param speedCurve 需求 1：自定义速度曲线（[SPEED_CURVE_POINTS] 个控制点，40/50/…/120 km/h 的相对概率 0~1）。
 *                   默认 [defaultSpeedCurve]（80 峰、70/90 半峰、两端为 0）；
 *                   旧存档缺这个字段时也拿到同一个默认值，长度不对时同样回落到它（见 [effectiveCurve]）。
 * @param heat 路口热度 1~10：从某路口出发时，目的地按 heat 加权抽取，
 *             即 P(去该路口) = 该路口 heat / **除出发路口外**其余开放路口 heat 之和
 *             （自己不能当自己的目的地，所以分母要排除出发地）。
 */
@Serializable
data class JunctionConfig(
    var spawnIntervalSec: Int = 8,
    var carRatio: Double = 0.60,
    var busRatio: Double = 0.25,
    var truckRatio: Double = 0.15,
    var speedMeanKmh: Double = 60.0,
    var speedSigmaKmh: Double = 10.0,
    var speedCurve: List<Double> = defaultSpeedCurve(),
    var heat: Int = 5,
) {
    /** 每秒生成速率 */
    val spawnRatePerSec: Double get() = 1.0 / spawnIntervalSec.coerceIn(1, 10)

    /** 归一化后的车型比例（三段滑块理论上已和为 1，这里再兜一层） */
    fun normalizedRatios(): Triple<Double, Double, Double> {
        val sum = carRatio + busRatio + truckRatio
        if (sum <= 1e-6) return Triple(1.0, 0.0, 0.0)
        return Triple(carRatio / sum, busRatio / sum, truckRatio / sum)
    }

    /**
     * 实际生效的速度曲线：长度正确就用它，否则（旧存档 / 被清空 / 数据损坏）
     * 回落到 [defaultSpeedCurve] —— 保证"默认值一定生效"，不会出现空曲线导致车辆无法生成。
     */
    fun effectiveCurve(): List<Double> =
        if (speedCurve.size == SPEED_CURVE_POINTS) speedCurve
        else defaultSpeedCurve()
}

/**
 * 全局仿真设置（设置面板里调，模拟中可实时生效）。
 *
 * 注意：speedMultiplier 由 v2 的 Int(1/2/4) 升级为 Double，
 * 以支持 0.5x / 1.5x / 3x / 5x 这些小数倍速。
 */
@Serializable
data class SimSettings(
    var speedMultiplier: Double = 1.0,     // 倍速：0.5 / 1 / 1.5 / 2 / 3 / 5
    var reactionTimeSec: Double = 1.0,     // 反应时间基准（每辆车再随机浮动 ±20%）
    var crashBlockSeconds: Double = 15.0,  // 开放路口持续堵塞多少秒判定网络崩溃
    var gridSnap: Boolean = true,          // 添加道路时是否吸附网格
    var simpleRenderThreshold: Int = 800,  // 车辆超过该数量时简化渲染，保帧率
    var showStats: Boolean = false,        // 右上角「运行时间 + 车辆数」统计面板开关

    /**
     * **夜间模式**：深色路面 + 车辆前灯 + 路灯照明。
     *
     * 这是一个**纯渲染开关** —— 仿真里的车速、信号灯周期、让行判定一概不变，
     * 只是换了一套配色与光源。随设置一起持久化，所以重启后还是同一个昼夜状态。
     *
     * ⚠️ 新增 [dayMode] 之后，这个字段只剩**兼容旧存档**的作用（旧档没有 dayMode），
     * 新代码请读 [dayMode]。
     */
    var nightMode: Boolean = false,

    /**
     * **昼夜模式**（本轮需求 1）：`"DAY"` / `"NIGHT"` / `"AUTO"`。
     *
     * 空串 = 旧存档（那时只有 nightMode 一个布尔），读取时按 [nightMode] 推断；
     * 用字符串而不是枚举，是为了 `SimSettings` 保持 `@Serializable` 且对未知值宽容。
     */
    var dayMode: String = "",

    // ---------- 红绿灯（本轮新增）----------
    /** 十字 / 丁字路口（度为 3 及以上）自动配置信号灯，车辆按灯通行 */
    var trafficLightsEnabled: Boolean = true,

    // ---------- 自主规划（拥堵自动改道）----------
    /** 总开关：关掉后车辆只在路口按拥堵代价自然选路，不做"堵住了主动绕行"的额外判断 */
    var autoReroute: Boolean = true,
    /** 观察窗口（秒）：看最近这段时间的平均速度 */
    var rerouteWindowSec: Double = 5.0,
    /** 触发阈值：窗口内平均速度 < 本车初始期望速度 × 该比例 → 认为"被堵住了" */
    var rerouteSpeedRatio: Double = 0.30,
    /** 冷却（秒）：改道一次后这段时间内不再改道，避免来回抖动 */
    var rerouteCooldownSec: Double = 10.0,

    // ---------- 界面语言（需求 5）----------
    /** 界面语言："ZH" 中文 / "EN" 英文；随设置一起持久化（重启后保持） */
    var language: String = "ZH",

    // ---------- 声音（本轮新增）----------
    /**
     * **背景音乐开关**（左侧「音频」按钮展开后的第一个圆钮）。
     *
     * 关掉只是"听不到"，`MediaPlayer` 保留播放位置 —— 再打开从原处接着放，
     * 不会从头重来。这个值随设置持久化，所以重启后还是同一个状态。
     */
    var musicEnabled: Boolean = true,

    /**
     * **音效开关**（左侧「音频」按钮展开后的第二个圆钮）。
     *
     * 关掉后 `SoundPool` 不再出声（正在循环的画路底噪会立刻掐断）。
     * 与音乐开关互相独立：可以"只听音效"或"只听音乐"。
     */
    var sfxEnabled: Boolean = true,

    /**
     * 音乐音量 0~1。
     *
     * 界面上**没有**暴露滑块（本轮只要求两个开关）：它是一份有合理默认值的内部音量，
     * 让混音比例可调而不必改代码。环境层的音量由它派生（× 0.4），不单独存。
     */
    var bgmVolume: Double = 0.55,

    /** 音效音量 0~1（同上，默认比音乐高，保证操作反馈听得清） */
    var sfxVolume: Double = 0.85,

    /**
     * **触觉反馈开关**（本轮新增，音频浮层里排在「音效」右边的那个「轻震」）。
     *
     * 关掉后按下按钮不再有那一下轻震 —— **声音与缩放动画不受影响**，三者是独立的通道。
     * 默认开：这个 App 的按钮全是自绘圆钮（没有 Material 水波纹），没有触觉就只剩声音，
     * 静音场景下完全没有"我按到了"的确认。但确实有人讨厌手机震，所以给开关、并且持久化。
     */
    var hapticEnabled: Boolean = true,
) {
    companion object {
        /** 倍速档位（按钮循环切换用） */
        val SPEED_MULTIPLIERS = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0)
    }
}

/** 撤销栈用的快照：路网 + 各路口配置（序列化成 JSON 存储，简单可靠） */
@Serializable
data class SimSnapshot(
    val network: RoadNetwork,
    val configs: Map<Long, JunctionConfig>,
)
