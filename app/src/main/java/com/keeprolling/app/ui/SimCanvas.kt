package com.keeprolling.app.ui

import android.util.LongSparseArray
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.keeprolling.app.model.CornerArc
import com.keeprolling.app.model.FocusRender
import com.keeprolling.app.model.JunctionGeometry
import com.keeprolling.app.model.NodeType
import com.keeprolling.app.model.RoadEdge
import com.keeprolling.app.model.RoadNetwork
import com.keeprolling.app.model.RoadPath
import com.keeprolling.app.model.Scenery
import com.keeprolling.app.model.SceneryGenerator
import com.keeprolling.app.model.SignalLightRender
import com.keeprolling.app.model.SignalPhaseRender
import com.keeprolling.app.model.StopLineRender
import com.keeprolling.app.model.VehicleKind
import com.keeprolling.app.model.VehicleRender
import com.keeprolling.app.model.Vector2
import com.keeprolling.app.ui.components.rememberDragHaptics
import com.keeprolling.app.ui.i18n.LocalMessages
import com.keeprolling.app.ui.i18n.Messages
import com.keeprolling.app.ui.i18n.StrKey
import com.keeprolling.app.viewmodel.RenderFrame
import com.keeprolling.app.viewmodel.SimulatorViewModel
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

// ---------------- 配色 ----------------
private val GRID_COLOR = Color(0xFFECE9E1)
private val GRID_MAJOR_COLOR = Color(0xFFE0DCD1)
private val ROAD_COLOR = Color(0xFFB4B2A9)
private val LANE_LINE_COLOR = Color(0xFFFDFCF9)
private val CENTER_LINE_COLOR = Color(0xFFE8C15A)
private val ROAD_SELECTED = Color(0xFF7F77DD)
private val ACCENT = Color(0xFF534AB7)
private val OPEN_COLOR = Color(0xFF1D9E75)
private val DEAD_END_COLOR = Color(0xFFE24B4A)
private val DANGER_COLOR = Color(0xFFE24B4A)
private val WARM_WHITE = Color(0xFFFAF8F3)
private val LIGHT_GRAY = Color(0xFFF1EFE8)
private val SIGNAL_GREEN = Color(0xFF1D9E75)
private val SIGNAL_YELLOW = Color(0xFFEF9F27)
private val SIGNAL_RED = Color(0xFFE24B4A)

/** 聚焦车辆的前方路线箭头（需求 4）：紫色，与"选中道路"的高亮同色系但不抢眼 */
private val ROUTE_GUIDE_COLOR = Color(0xFF534AB7)

/** 路口编号文字（需求 6）：深绿，与开放路口的符号同色系 */
private val JUNCTION_NUM_COLOR = Color(0xFF0F6E56)

/**
 * 聚焦车辆时的缩放（像素/米）：约 11 → 一条 3.5m 的车道在屏上约 38px，
 * 车宽 2.6m 约 29px —— 既能看清车道与弯道，又不至于只能看到车自己。
 */
private const val FOCUS_SCALE_PX_PER_M = 11f

/**
 * 车道标线的采样间隔（米）。
 *
 * 常规 2m —— 弯道上也足够平滑；拖动路网时放宽到 8m：整条路都在手里移动，
 * 采样点少 3/4 肉眼看不出来，但每帧能省掉几千次 `pointAt` 与对应的对象分配。
 */
private const val LANE_LINE_STEP_M = 2.0
private const val LANE_LINE_STEP_DRAG_M = 8.0

/**
 * 车道分隔线的虚线样式。
 *
 * `PathEffect` 是不可变对象，**一份就够全项目共用** ——
 * 旧代码在每条路的循环里现建 `PathEffect.dashPathEffect(...)`，
 * 40 条路就是每帧 40 个新对象（还带原生句柄）。
 */
private val LANE_DASH = PathEffect.dashPathEffect(floatArrayOf(11f, 9f))

/** 「画路预览」的虚线样式（同上：不可变对象，模块里一份就够，别每帧新建） */
private val PREVIEW_DASH = PathEffect.dashPathEffect(floatArrayOf(16f, 12f))

/**
 * 捏合（缩放）停下多久之后，才把「参考缩放」推进到真实缩放（P12）。
 *
 * ## 为什么要延迟
 * 世界类图层的几何是按 `camera.renderScale` 画的，真实缩放只在 `graphicsLayer` 里乘上去。
 * 所以捏合过程中**一次都不重录**；但"参考缩放"总得跟上，否则几何精度会越跑越偏
 * （缩到 5 倍还拿着 1 倍时建的 Path）。
 *
 * 90ms 是个折中：
 *  - 太短（< 1 帧间隔）→ 等于每帧提交，P12 白做；
 *  - 太长（> 200ms）→ 松手后要等一小会儿，线宽 / 细节层级才"啪"地对上，看得出迟滞。
 * 手指还按着的时候每帧都在改缩放，[kotlinx.coroutines.flow.collectLatest] 会不停打断这段
 * 等待，所以只有真正停下来才会提交。
 */
private const val ZOOM_COMMIT_SETTLE_MS = 90L

/**
 * 测"快照间隔"时允许的范围（纳秒）—— 只用于算平滑估计值，不参与坐标映射。
 *
 * 仿真是 `delay(16)` 驱动的（约 60Hz，且**不锁 vsync**），显示是 vsync 驱动的。
 * 单次间隔可能因为一次卡顿冲到两三百毫秒，若原样喂进平滑值，要好几秒才能缓回来，
 * 期间画面会一直滞后。上下界把它夹在"正常的一两帧"范围内。
 */
private const val SNAPSHOT_GAP_MIN_NS = 4_000_000L
private const val SNAPSHOT_GAP_MAX_NS = 40_000_000L

/**
 * **昼夜配色表**（本轮需求 2）。
 *
 * [night] 是 0（白天）~1（夜晚）的**连续值** —— 点夜间按钮时用 340ms 动画把它推过去，
 * 于是整张画面（背景、网格、路面、标线、停止线）是"一起暗下来"的，而不是硬切一下。
 *
 * 为什么做成一个类而不是十几个三元表达式：颜色在 [BackdropLayer] / [GridLayer] /
 * `drawRoads` / `CornerPathCache` / `drawJunctionPavement` / `drawStopLine` 里都要用，
 * 随函数一路传下去比到处读全局变量更安全 —— 将来想加"黄昏""雨天"只改这一个类。
 */
internal class SimPalette(night: Float) {

    private val t: Float = night.coerceIn(0f, 1f)

    private fun mix(day: Color, dark: Color): Color = if (t <= 0f) day else lerp(day, dark, t)

    /** 画布底色：白天温润白 / 编辑浅灰；夜间统一深蓝黑（编辑时略亮，方便看清刚画的路） */
    val canvasBg: Color = mix(WARM_WHITE, Color(0xFF0F1319))
    val editBg: Color = mix(LIGHT_GRAY, Color(0xFF161B22))

    val gridDot: Color = mix(GRID_COLOR, Color(0xFF1C222B))
    val gridMajor: Color = mix(GRID_MAJOR_COLOR, Color(0xFF262D37))

    /** 路面：夜间是深灰蓝（不是纯黑 —— 纯黑会让白色标线显得刺眼、也看不出路面质感） */
    val road: Color = mix(ROAD_COLOR, Color(0xFF3A3F47))
    val laneLine: Color = mix(LANE_LINE_COLOR, Color(0xFF8B94A1))
    val centerLine: Color = mix(CENTER_LINE_COLOR, Color(0xFFBE9A45))
    val roadSelected: Color = mix(ROAD_SELECTED, Color(0xFF8D87E9))
    val stopLine: Color = mix(Color.White, Color(0xFFC3CAD3))

    /** 车辆在这个夜色下的整体压暗比例（车身不该比路灯还亮） */
    val vehicleDim: Float get() = 0.22f * t

    // ---- 画在画布上的"小卡片"（路口编号胶囊、信号相位卡）----
    // 它们是 Canvas 里画出来的界面元素，不走 Compose 的 MaterialTheme，
    // 所以昼夜配色必须在这里另备一份，否则夜里会在深色画布上糊一块白。
    val cardBg: Color = mix(Color.White, Color(0xFF1B222C))
    val cardInk: Color = mix(Color(0xFF26215C), Color(0xFFE8EAF0))
    val cardSub: Color = mix(Color(0xFF6B6A78), Color(0xFFA9B2C0))
    val cardTrack: Color = mix(Color(0xFFE6E2D8), Color(0xFF333C48))
    val cardShadow: Color = mix(Color(0x16000000), Color(0x66000000))

    /** 路口编号文字：白天墨绿，夜间提亮成薄荷绿，深色底上才看得清 */
    val junctionLabel: Color = mix(Color(0xFF0F6E56), Color(0xFF7FD8BC))

    /** 强调色（锁定车辆的起 / 终点路口名字）：白天紫，夜间提亮 */
    val accent: Color = mix(ACCENT, Color(0xFFA79FF0))
}

/**
 * 锁定跟随的**绘制锚点**：相机与「被聚焦的那辆车」共用同一个坐标，而且这个坐标在
 * **帧时钟**上是连续的。
 *
 * ## 为什么需要它（抖动的根因）
 * 仿真快照由 `Dispatchers.Default` 上 `delay(16)` 的循环产出（约 60Hz，**不锁 vsync**），
 * 而画面是 vsync 驱动的（本机 120Hz）。旧实现每帧把相机**硬对齐到最新快照**，于是：
 *
 * ```
 * 帧 1  取到快照 A → 相机跳到 A          背景动了 1 格，车钉住
 * 帧 2  快照还没变 → 相机原地不动        背景动了 0 格   ← 肉眼看到的"顿一下"
 * 帧 3  取到快照 B → 相机跳到 B          背景猛动 2 格
 * ```
 *
 * 快照间隔在 8~33ms 之间飘，这个"0 格 / 1 格 / 2 格"的花样就**一直在变**，
 * 于是表现为间歇性的抽动。另外相机是在帧回调里读快照、车流层是在绘制期读快照，
 * 两者**偶尔差一份快照**（背景线程刚好在这几毫秒里发布了新快照），
 * 车会在屏幕上闪一下就位 —— 这是"车辆位置抽动"的来源。
 *
 * ## 怎么解决：**固定滞后一份快照，然后在已知区间里插值**
 * 手里始终握着两份相邻快照 `P0(t0)` 与 `P1(t1)`（t 是它们**被看到**的帧时刻）。
 * 每帧取样的时刻不是"现在"，而是 **`现在 − lag`**（lag ≈ 一份快照的时长），
 * 于是取样点永远落在 `[t0, t1]` 这个**已知两端**的区间里：
 *
 * ```
 * 帧时刻   t1        t1+8ms     t1+16ms(=t2)   t2+8ms    t2+16ms
 * 取样点   t0        中点       t1             中点      t2
 * 位置     P0        中点       P1             中点      P2
 * 位移      —        0.5格      0.5格          0.5格     0.5格   ← 每帧都一样
 * ```
 *
 * 每帧位移恒定 = 背景匀速流动 = 人眼判定为"顺滑"。
 * 相机与车**都用这个位置**，于是车严格钉在同一屏幕点上，两者不会互相错位。
 *
 * 代价是画面比仿真晚一份快照（约 16ms）—— 对"看车流"这个场景不可感知。
 *
 * ## 为什么不能用"上次画到哪儿"当插值起点
 * 那样做在快照间隔 = 2 个显示帧时，会得到"0 格、半格、0 格、半格"的取样序列
 * （每次新快照到达时位置不动，隔一帧才走到一半），也就是位移里有一半是 0 ——
 * 抖动并没有消失，只是换了个花样。必须让取样点落在**已知两端**的区间里才不会丢步。
 *
 * ## 三个状态为什么用 `mutableXxxStateOf`
 * 车流层在**绘制期**读它们（`anchorId` / `anchorX` / `anchorY`），于是写入只让绘制失效、
 * 不触发重组 —— 与"每帧都变的状态只在绘制期读"这条铁律一致。
 * 用 `Float` 发布坐标：世界坐标是米、量级最多几百，float 的精度远小于 0.01px。
 */
private class FollowAnchor {

    // ---------- 发布给车流层（绘制期读） ----------

    private val pubId = mutableLongStateOf(0L)          // 0 = 没有锚点（车辆 id 从 1 开始）
    private val pubX = mutableFloatStateOf(0f)
    private val pubY = mutableFloatStateOf(0f)

    /** 当前锚定的车辆 id；`0` 表示不锚定，车流层按快照原样画 */
    val anchorId: Long get() = pubId.longValue
    val anchorX: Float get() = pubX.floatValue
    val anchorY: Float get() = pubY.floatValue

    // ---------- 插值窗口（普通字段，不进快照系统，只有跟随循环读写） ----------

    private var active = false
    private var id = 0L

    /** 窗口左端：**上一份**快照的位置 */
    private var p0x = 0.0
    private var p0y = 0.0

    /** 窗口右端：**当前这份**快照的位置 */
    private var p1x = 0.0
    private var p1y = 0.0

    /** 两份快照分别**被看到**的帧时刻；`t0 == 0` 表示还只有一份 */
    private var t0 = 0L
    private var t1 = 0L

    /**
     * 快照间隔的平滑估计（纳秒）＝渲染滞后量；0 = 还没测出来。
     *
     * 用它而不是直接用当前窗口跨度，是为了**少"顿"**：跨度偶尔比上一段长时，
     * 用跨度做滞后会在末尾多停一小会儿（等下一份快照），而平滑值更接近均值，
     * 取样点早就落在区间中部，不容易被顶到 1.0。
     * ⚠️ 使用时必须夹到 `≤ span`（见 [emit]），否则会退回去。
     */
    private var lagNs = 0L

    /** 上一次"位置变了"被观测到的帧时刻（只用来量间隔）；0 = 还没有 */
    private var lastArrivalNs = 0L

    /** 是否已经凑齐两份**带真实时间戳**的快照（凑齐前不做插值，直接跟最新） */
    private var hasPair = false

    /** 本帧插值结果（世界坐标，米），相机与车辆共用 */
    var sampleX = 0.0
        private set
    var sampleY = 0.0
        private set

    /**
     * 起手：直接把锚点钉在第一份快照上（不做插值，否则首帧会从 (0,0) 飞过来）。
     */
    fun seed(vehicleId: Long, x: Double, y: Double) {
        active = true
        id = vehicleId
        p0x = x; p0y = y
        p1x = x; p1y = y
        sampleX = x; sampleY = y
        t0 = 0L
        t1 = 0L
        lagNs = 0L
        lastArrivalNs = 0L
        hasPair = false
        publish()
    }

    /**
     * 观测到一份快照（每帧调用一次；位置没变就直接返回）。
     *
     * 只有**位置真的变了**才把窗口右移：车停住（等红灯）时快照内容不变，
     * 若照样推进，`t1 - t0` 会被拍成 0，插值就退化成硬跳。
     */
    fun push(nowNs: Long, vehicleId: Long, x: Double, y: Double) {
        if (!active || vehicleId != id) {          // 换了车（理论上会重启整个跟随循环）
            seed(vehicleId, x, y)
            return
        }
        if (x == p1x && y == p1y) return           // 车没动 → 窗口不动
        // 整窗右移：原来的右端变成左端，新的这份成为右端
        p0x = p1x; p0y = p1y
        t0 = t1
        p1x = x; p1y = y
        t1 = nowNs
        if (lastArrivalNs != 0L) {
            val d = (nowNs - lastArrivalNs)
                .coerceIn(SNAPSHOT_GAP_MIN_NS, SNAPSHOT_GAP_MAX_NS)
            // 指数平滑（新值权重 1/4）：单次抖动不会改掉整段观感
            lagNs = if (lagNs == 0L) d else (lagNs * 3 + d) / 4
            hasPair = true                          // 这时 t0 / t1 都是真实到达时刻
        }
        lastArrivalNs = nowNs
    }

    /**
     * 算出**本帧**该画在哪儿，并发布给车流层。
     *
     * 取样时刻 = `now − lag`，也就是"把现在往回拨一份快照"。于是：
     *  - 快照到达那一刻取样点落在区间左端附近（接住上一帧，不跳）；
     *  - 下一份快照该来的时候取样点已经走到右端（不落后）；
     *  - 全程**只滞后一份快照**，且不会出现回跳 —— 前提是 `lag ≤ span`：
     *    若 `lag > span`，取样点会被顶出 `[t0, t1]` 夹到 1.0，等窗口右移后又滑回 0，
     *    表现为一次可见的倒退（掉帧时尤其明显）。所以这里强制夹住。
     */
    fun emit(nowNs: Long) {
        if (!active) {
            publish()
            return
        }
        val span = t1 - t0
        if (!hasPair || span <= 0L) {
            // 只有一份快照（刚点上车的那一帧），或两份落在同一帧上：直接跟最新
            sampleX = p1x; sampleY = p1y
        } else {
            val lag = if (lagNs > 0L && lagNs < span) lagNs else span
            val k = ((nowNs - lag - t0).toDouble() / span).coerceIn(0.0, 1.0)
            sampleX = p0x + (p1x - p0x) * k
            sampleY = p0y + (p1y - p0y) * k
        }
        publish()
    }

    /** 退出跟随：立刻解除锚定，车流层恢复按快照画（否则车会永远停在锚点上） */
    fun clear() {
        active = false
        id = 0L
        publish()
    }

    private fun publish() {
        pubId.longValue = id
        pubX.floatValue = sampleX.toFloat()
        pubY.floatValue = sampleY.toFloat()
    }
}

/**
 * **全体车辆的帧时钟插值器**。
 *
 * ## 为什么需要它（"其它车辆位置跳变"的根因）
 * 仿真快照是**事件驱动**的：`SimViewModel` 的循环 `delay(16)` 产出一次，与显示刷新不对齐。
 * 而屏幕是 120Hz（8.3ms 一帧），于是每两份快照之间要画 2~3 帧 —— 其中只有一帧拿到新位置，
 * 其余帧重复上一帧。画面上就是"走一格、停一格、走一格"。
 *
 * 更糟的是倍速：5x 时一份快照代表 80ms 的仿真时间，一辆 80km/h 的车一次前进 **1.8 米**
 * （约 10 像素），跳变肉眼可见 —— 这正是用户报告的"5 倍速下最明显"。
 *
 * ## 做法
 * 和 [FollowAnchor] 完全同一套时钟：手里握着相邻两份快照 `P0(t0)`、`P1(t1)`，
 * 每帧按 `k = (now − lag − t0) / (t1 − t0)` 在两者之间取中间值。
 * 取样点永远落在**已知两端**之间，所以：
 *  - 新快照到达那一刻 `k ≈ 0`，画出来的正好是上一帧的位置（接得住，不跳）；
 *  - 两份快照之间 `k` 线性推进，位移**每帧恒定**；
 *  - `lag` 夹到 `≤ span`，取样点不会被顶出区间再滑回来（那会表现为倒退）。
 *
 * 相机仍然走 [FollowAnchor]（它更"贵"：要跟车钉在屏幕同一点，滞后量必须和车流严格一致），
 * 这里只负责**其余所有车**的位置连续性，两者互不干扰。
 *
 * ## 为什么 `k` 用 State 发布
 * 车流层在**绘制期**读它（`mixer.k`），于是写入只让绘制失效、不触发重组 ——
 * 与"每帧都变的状态只在绘制期读"这条铁律一致。
 */
private class FrameMixer {

    private var prev: RenderFrame? = null
    private var curr: RenderFrame? = null

    /** 上一份快照里的车辆，按 id 索引；每份快照重建一次（约一百条，可忽略） */
    private var prevById: Map<Long, VehicleRender> = emptyMap()

    private var t0 = 0L
    private var t1 = 0L
    private var lagNs = 0L
    private var lastArrivalNs = 0L

    private val pubK = mutableFloatStateOf(1f)

    /** 本帧的插值比例：0 = 完全取上一份快照，1 = 完全取当前快照 */
    val k: Float get() = pubK.floatValue

    /** 上一份快照的车辆表（绘制期按 id 查，查不到就当"这辆车刚出现"，直接用当前值） */
    val prevVehicles: Map<Long, VehicleRender> get() = prevById

    /**
     * 观测到一份快照。同一份（引用相同）直接返回 —— 循环每帧都会调它，
     * 而快照只有 60Hz，不能每帧都把窗口往前推。
     */
    fun push(nowNs: Long, f: RenderFrame) {
        if (f === curr) return
        prev = curr
        curr = f
        prevById = prev?.vehicles?.associateBy { it.id } ?: emptyMap()
        t0 = t1
        t1 = nowNs
        if (lastArrivalNs != 0L) {
            val d = (nowNs - lastArrivalNs)
                .coerceIn(SNAPSHOT_GAP_MIN_NS, SNAPSHOT_GAP_MAX_NS)
            lagNs = if (lagNs == 0L) d else (lagNs * 3 + d) / 4
        }
        lastArrivalNs = nowNs
    }

    /** 算出本帧的插值比例并发布 */
    fun advance(nowNs: Long) {
        if (curr == null) {
            pubK.floatValue = 1f
            return
        }
        val span = t1 - t0
        val kk = if (prev == null || span <= 0L) {
            1f
        } else {
            // lag 必须夹到 span 以内，否则取样点会被顶出区间（见 [FollowAnchor] 的说明）
            val lag = if (lagNs > 0L && lagNs < span) lagNs else span
            ((nowNs - lag - t0).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
        }
        pubK.floatValue = kk
    }

    /** 仿真暂停 / 重置：丢掉窗口，回到"直接按快照画" */
    fun reset() {
        prev = null
        curr = null
        prevById = emptyMap()
        t0 = 0L
        t1 = 0L
        lagNs = 0L
        lastArrivalNs = 0L
        pubK.floatValue = 1f
    }
}

/**
 * 两个角度之间的**最短弧**插值（弧度）。
 *
 * 车头朝向在 `poseOf` 里是连续的，但 ±π 附近会从 +3.14 跳到 -3.14；
 * 直接线性插值会让车在那一刻原地转一整圈。取最短带符号差即可。
 */
private fun lerpAngle(a: Double, b: Double, t: Double): Double {
    var d = (b - a) % (2.0 * Math.PI)
    if (d > Math.PI) d -= 2.0 * Math.PI
    if (d < -Math.PI) d += 2.0 * Math.PI
    return a + d * t
}

/**
 * 画布的**稳定参数边界**。
 *
 * ## 为什么需要这一层壳
 *
 * Compose 的「跳过重组」要求一个 composable 的**所有参数都稳定且相等**。
 * `SimulatorViewModel` 和 `CameraState` 都是普通 class（不是 data class、没标 `@Stable`），
 * 编译器一律当作不稳定 —— 于是 [SimCanvas] **永远不可跳过**：
 * 父层（`MainScreen`）每重组一次，画布就跟着重组一次。
 *
 * 而画布一重组，十层就得全部重录。原因在每层的参数里都带着 `camera: CameraState`（不稳定），
 * 每一层都不可跳过；绘制 lambda 里又捕获了 `scenery` / `nightLevel` 这些值，
 * 每次重组都是**新的 lambda 实例** → `drawBehind` 的节点判定"绘制内容变了" → 重录。
 * 十层里最贵的是地面层（上限 900 栋楼）和植被层（1600 棵树），一次几十毫秒。
 *
 * 于是「进入 / 退出编辑模式」这种**跟画布毫无关系**的操作（它只改了一个枚举），
 * 却要让整个街区重新录一遍 —— 这就是按钮弹出时那一顿。
 *
 * ## 这个壳做了什么
 *
 * 它只是**两个 val 的容器**，构造之后永不改变，`@Stable` 的承诺**完全成立**。
 * 外面用 `remember(vm, camera)` 建一次，之后每次重组传进来的都是**同一个实例**
 * （`equals` 走引用比较，必然相等）→ [SimCanvas] 满足跳过条件 → 画布一动不动。
 *
 * ⚠️ 这不等于"画布不再刷新"。画布的所有输入都是 `StateFlow.collectAsState()` 拿到的
 * `State` 对象，**订阅关系与参数稳定性无关** —— 天亮了、选中的路变了、车动了，
 * collectAsState 照样会触发对应那一层重组。壳挡住的只是"与我无关的父层重组"。
 */
@Stable
class CanvasHost(
    val vm: SimulatorViewModel,
    val camera: CameraState,
)

/**
 * 主画布（v4）：**十层独立 Canvas**。
 *
 * ## 为什么要拆层
 * 旧实现是**一张** Canvas，它在一个绘制 lambda 里同时读三类"每帧都在变"的状态：
 * `frame`（仿真快照，16ms 一次）、`networkVersion`（拖动端点时每帧 +1）、`camera.offset`（平移）。
 * Compose 的绘制失效是**按节点**算的 —— 一张画布上任何一处状态变了，
 * 整张画布那四五千条绘制指令就得全部重录，哪怕楼、树、路灯一动没动。
 *
 * 拆开之后，每层只登记自己真正用到的状态，于是：
 *  - **动画在跑**（车在动、信号灯在跳）：只有「车流层 / 标记层」重录；
 *    网格 / 地面 / 路面 / 影子 / 植被五层**一次都不重录**（它们根本不读 `frame`）；
 *  - **拖动端点**：只有「路面层」重录；地面层因为街景冻结，更是零重录；
 *  - **平移**：**全部零重录** —— 平移量交给各层的 `graphicsLayer`，只改变换矩阵（P8）；
 *  - **缩放**：世界类图层也**零重录** —— 几何按"参考缩放"画，真实缩放作为
 *    `graphicsLayer` 的 `scaleX/scaleY` 乘上去（P12，见 [ZOOM_COMMIT_SETTLE_MS]）；
 *    只有「车流 / 标记」两层还在跟着缩放重录，它们本来就每帧重录，无所谓。
 *
 * ## 五条实现铁律
 * 1. **每帧都变的状态只在绘制期读。** `collectAsState()` 只取 State 对象、组合期不读 `.value`，
 *    再在 Canvas 的绘制 lambda 里读 —— 于是状态变化只让**绘制**失效，不触发**重组**。
 *    这条必须守住：重组会重建 lambda、连带让整层重录，拆层就白拆了。
 * 2. **每层必须带 `Modifier.graphicsLayer`。** 这不是装饰：Compose 只有在该节点拥有
 *    **自己的 RenderNode** 时才有"这一层的已录制指令可以复用"这回事。`Canvas` 默认只是
 *    把绘制指令录进**同一个** display list —— 不加 `graphicsLayer`，十层依旧是一张清单，
 *    任何一处失效都要整棵树重录，拆层等于没拆。
 *    （空 lambda = 不裁剪、alpha 为 1、无变换，GPU 直接合成，不会产生离屏缓冲。）
 * 3. **层序严格照抄旧代码**（见每层注释）。拆层只允许「切开」，不允许「调序」：
 *    楼体必须在路下、楼影必须在路上、树必须在车上、灯必须在最后 ——
 *    顺序错一层就是"影子伸到马路上被路面盖掉"这类肉眼可见的 bug。
 * 4. **世界类图层用 `worldToLocal` + [worldLayer]，界面类用 `worldToScreen`。**
 *    前者把平移与缩放都交给 RenderNode 的变换矩阵（两者都零重录），
 *    后者本来就每帧重录、何必多绕一层。
 *    两者的像素结果**逐位相同**（都是先 `x*scale` 再 `+offset`，只是加法发生的时刻不同）。
 *    ⚠️ 用了 `worldToLocal` 就**不能**再在绘制里自己 translate offset 或自己乘缩放
 *    （位移会翻倍、尺寸会平方），也**不能**再用 `size.width/height` 做屏幕外剔除
 *    （那个矩形在局部坐标系里位置是错的）。
 * 5. **世界类图层里只许读 `camera.renderScale`，绝不读 `camera.scalePxPerM`。**
 *    读后者等于把"真实缩放"变回几何输入，P12 立刻失效、回到每帧重录。
 *    需要"当前看起来多大"时用 [CameraState.renderZoom] 想清楚再动手。
 */
@Composable
fun SimCanvas(
    host: CanvasHost,
    modifier: Modifier = Modifier,
    /**
     * 界面整体可见度（本轮需求 4「去UI」）：1 = 正常，0 = 只剩画面。
     *
     * 画布里那些**属于界面**的标记（路口圆圈符号、路口编号、信号相位卡、堵塞警示圈）
     * 跟着它一起淡出；路面、街景、车、路灯这些属于"世界"的东西不受影响。
     *
     * ⚠️ 传 `State<Float>` 而不是 `Float`（第 57 轮）：这个值在 380ms 的淡出里每帧都变，
     * 当普通参数传进来就等于"淡出期间画布每帧重组" —— 又是十层重录。
     * 传 State 之后，只有用到它的 `MarkerLayer` 在自己的绘制 lambda 里 `.value`，
     * 其余九层一次都不动。
     */
    uiAlpha: State<Float>,
) {
    // 从壳里取出来当局部变量：下面几百行代码一字不用改，也仍然拿得到这两个对象。
    val vm = host.vm
    val camera = host.camera
    val haptics = LocalHapticFeedback.current
    // 拖动触觉（档位切换 / 连续滑动）：实例稳定，可以安全地被 pointerInput 长期持有。
    // 放在这里而不是 Gestures 内部 —— 那里是 suspend 函数，拿不到 CompositionLocal。
    val dragHaptics = rememberDragHaptics()
    val tapTracker = remember { TapTracker() }
    val messages = LocalMessages.current
    val textMeasurer = rememberTextMeasurer()
    val labelCache = remember { HashMap<String, TextLayoutResult>() }
    // 相位卡上"还剩 8 秒 · 第 2/4 段"那一行：文案每秒都换，不能进 [labelCache]（键会长到几千条）。
    // 但它一秒内被重复排版 60~120 次 —— 用一个"只记最近一条"的槽位兜住（P10）。
    val phaseText = remember { PhaseTextCache() }

    // ---------- 低频状态：只有它们变化才会让整张画布重组 ----------
    val dayMode by vm.dayMode.collectAsState()
    // ⚠️ 这个函数里**故意不读 `vm.uiMode`**（第 57 轮）。
    // 以前它读在最外层，于是"进入 / 退出编辑模式"会让整张画布重组 —— 而画布一重组，
    // 十层全部重录（每层都带着不稳定的 `camera` 参数，谁也跳不过）。改模式明明只影响
    // 两处：底色（白 / 浅灰）和编辑标记。现在这两处各自订阅（见 [BackdropLayer] / [MarkerLayer]），
    // 一次换模式只重录两个廉价的层，街区的 900 栋楼和 1600 棵树一次都不动。
    //
    // 同理：外面的 `MainScreen` 也不再在根部读 `uiMode`（底色下沉到 `StageBackdrop`），
    // 加上 [CanvasHost] 这层稳定边界，UI 侧的任何状态变化都不会再拖着画布重录。
    // 夜色按 1/128 量化后下发：色板、地面、路面、网格都只用这一份，
    // 于是"天亮了一点"不会把三层拖成每帧重录。
    //
    // ⚠️ 这几行**必须写在这里**、不能抽成一个返回 Float 的小 composable：
    // 父层读子函数的**返回值**并不建立重组依赖（子函数自己重组时，新值传不回父层），
    // 抽出去的结果就是"昼夜补间跑了、颜色不变"。状态必须在**使用它的作用域里**读。
    val autoNight by vm.dayNight.collectAsState()
    val blend by animateFloatAsState(
        targetValue = autoNight,
        animationSpec = tween(durationMillis = 340),
        label = "nightBlend",
    )
    // 自动模式：跟随仿真时间，量化到 1/128 之后才更新（否则每帧都变 → 四层每帧重录）
    //
    // 刻意**不加 `dayMode` 作 key、也不在此提前 return**：一旦加了 key，
    // 每次切进自动模式都会重建这个 State（值回到初值 0），画面会先闪一帧大白天、
    // 下一帧才跳回真实夜色。让它一直在后台算，代价只是每帧一次 `lightAt`，
    // 而手动模式下没人读它，所以写进去也不会引起任何重组。
    val autoLevel by produceState(0f) {
        vm.frame.collect { f ->
            val q = quantizeNight(DayCycle.lightAt(DayCycle.phaseAt(f?.simTime ?: 0.0)).night)
            if (q != value) value = q
        }
    }
    // ---------- P10：自动模式的「整份光照」，量化后下发给影子 / 植被 / 暖色三层 ----------
    //
    // 夜色量化（[autoLevel]）只管颜色，这三层要的是**影子方向、长度、浓度、暖色**，
    // 以前它们各自 `collectAsState(vm.frame)` 再在绘制期算 lightAt —— 于是一帧一录。
    // 现在改由这里算一次、量化到 1/[LIGHT_STEPS]，三层只订阅这一份 State。
    //
    // ⚠️ 这个 State **绝不能**在组合期读（不能写 `by`）：它每秒变几次，
    // 一读就会把 SimCanvas 连着七层世界图层一起重组、全部重录。
    // 它只以 `State<DayLight>` 的形式往下传，由各层的绘制 lambda 去 `.value`。
    val autoLight = produceState(DayCycle.lightAt(DayCycle.phaseAt(0.0))) {
        vm.frame.collect { f ->
            val l = DayCycle.lightAt(quantizePhase(DayCycle.phaseAt(f?.simTime ?: 0.0)))
            if (l != value) value = l
        }
    }
    // 两个分支各自只在自己的模式下读一个状态：自动模式不读 `blend`，
    // 手动模式不读 `autoLevel` —— 于是两边都不会被对方的更新拖着重算。
    val nightLevel = if (dayMode == DayMode.AUTO) autoLevel else quantizeNight(blend)
    // 色板按量化后的值 remember：天色是个慢变量，没必要每次颜色变一点就重建 20 多个 Color
    val palette = remember(nightLevel) { SimPalette(nightLevel) }
    // 手动模式（白天 / 夜间）的固定光照：方向立刻定型，长度 / 浓度随 340ms 补间插值。
    // 自动模式不用这一份 —— 它按仿真时间逐帧算，影子才会连续地转（见各层的 `light` 分支）。
    val fixedLight = remember(dayMode, nightLevel) {
        DayCycle.withNight(
            if (dayMode == DayMode.NIGHT) DayCycle.NIGHT_LIGHT else DayCycle.DAY_LIGHT,
            nightLevel,
        )
    }
    // 「简化渲染」阈值：做成**只在跨越阈值时**才变的 State（详见 [rememberSimpleRender]）。
    // 旧代码每帧读 `frame.vehicles.size` 来算这个布尔值，地面 / 路面 / 车流三层跟着每帧重录；
    // 而默认阈值 800 实际根本到不了，等于纯亏。
    val simpleRender = rememberSimpleRender(vm)

    // ---------- 街景（低频）----------
    // 建筑/树/路灯的位置由 edge.id 做种子**确定性生成**，所以拖动画面、缩放、开关夜间
    // 都不会让它们"换位置"；只有路网真的变了才重排。
    //
    // 为什么要 120ms 防抖：拖动道路端点时 `networkVersion` 会**每帧** +1
    // （见 SimulatorViewModel.dragEdgeEndpoint），若直接跟着重建，等于在拖拽的每一帧里
    // 重排整片街区。这里等手停下来再算 —— 拖动过程中街区先"冻住"，松手 120ms 后一次性重排。
    var scenery by remember { mutableStateOf(SceneryGenerator.generate(vm.network)) }
    val settledVersion by vm.networkVersionSettled.collectAsState()
    LaunchedEffect(settledVersion) {
        delay(120)
        // 生成一次要扫全部道路（道路距离场 + 建筑避让查询），是几十毫秒级的活。
        // 放在主线程 = 松手后必然顿一下；改成先做快照、丢到后台算，算完再切回来赋值。
        // 首帧那次仍然同步生成 —— 背景还没有街区就开画会闪一下空白。
        val snapshot = vm.networkSnapshot()
        scenery = withContext(Dispatchers.Default) { SceneryGenerator.generate(snapshot) }
    }

    // ---------- 各层共用的几何 / 绘制缓存 ----------
    // 提到这里 remember，是为了让图层重组（例如昼夜切换、选中变化）时缓存不被丢掉。
    val gridCache = remember { GridDotCache() }
    val geometry = remember { DragSafeGeometry() }
    val roadCache = remember { RoadRenderCache() }
    val cornerCache = remember { CornerPathCache() }

    // ---------- 聚焦光圈的"呼吸"相位 ----------
    // 这是界面时钟驱动的无限动画。**注意绝不能在组合期读它** ——
    // 旧代码把 `focusPulse` 直接读在函数体里，于是那个 760ms 的循环让 `SimCanvas`
    // 每帧都重组、整张画布每帧全量重录（哪怕仿真暂停、一辆车都没被聚焦）。
    // 现在它只以 `State` 的形式传给车流层，并且在"确实有聚焦车辆"的分支里才读。
    val focusPulse = rememberInfiniteTransition(label = "focusRing").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "focusRingPulse",
    )

    // ---------- 缩放提交器（P12）----------
    // 世界类图层的几何按 `camera.renderScale` 生成，真实缩放只作为 graphicsLayer 的倍数，
    // 于是捏合过程中这七层**一次都不重录**。问题只剩一个：谁来把参考缩放推上去。
    //
    // 两个时机，缺一不可：
    //  1. **比值跑出允许区间**（[CameraState.RENDER_ZOOM_MIN] ~ [CameraState.RENDER_ZOOM_MAX]）→ 立刻提交。
    //     这一条管"视觉正确"：以像素为单位的量（1.8px 标线、虚线相位、最小线宽）
    //     不会因为矩阵缩放而明显变粗变细。
    //  2. **停下 90ms** → 提交。这一条管"最终状态正确"：细节层级（缩放 >= 1.1 才画窗光/屋顶设备）、
    //     网格自适应档位、路宽的最小值夹取，都会在松手后回到精确值。
    //
    // 两个时机都只在**真正需要**时才让那七层重录一次：一次 4 倍的捏合约 6 次，
    // 而旧实现是每帧一次（60fps 下 4 倍捏合按 1 秒算就是 60 次）。
    LaunchedEffect(camera) {
        snapshotFlow { camera.scalePxPerM }.collectLatest { s ->
            val zoom = s / camera.renderScale
            if (zoom < CameraState.RENDER_ZOOM_MIN || zoom > CameraState.RENDER_ZOOM_MAX) {
                camera.commitRenderScale(s)
                return@collectLatest
            }
            delay(ZOOM_COMMIT_SETTLE_MS)
            camera.commitRenderScale(camera.scalePxPerM)
        }
    }

    // ---------- 需求 4：锁定跟随 ----------
    // 每帧把车放到画面中央；用户手动缩放 / 拖动后 followLocked 变 false。
    //
    // 把原来的两个 effect（一次性 focusOnWorld + 每帧 centerOnWorld）合并成一个：
    //  · key 里带 focusEpoch → **切换车辆**时会重启循环，并重新记下"起点视图"，
    //    于是镜头会从 A 车平滑飞到 B 车（约 0.28s），明确表达"锁定目标变了"；
    //  · 首次点车也同样走这段飞行（从用户当前的视角推近到车上），比瞬移自然；
    //  · 车辆到站消失（focus 为 null）→ 多等几帧后退出，等下一次点击重新启动。
    val followLocked by vm.followLocked.collectAsState()
    val focusEpoch by vm.focusEpoch.collectAsState()
    // 跟随锚点：相机与「被聚焦那辆车」共用的绘制位置（注释见 [FollowAnchor]）
    val followAnchor = remember { FollowAnchor() }
    LaunchedEffect(followLocked, focusEpoch) {
        if (!followLocked) {
            followAnchor.clear()
            return@LaunchedEffect
        }
        // 聚焦快照由仿真循环产出，点车那一瞬间可能还是**上一帧**（focus 为 null）。
        // 等最多 20 帧（≈0.3 秒），否则会出现"点了车但镜头不动"。
        var first: FocusRender? = null
        for (i in 0 until 20) {
            first = vm.frame.value?.focus
            if (first != null) break
            withFrameNanos { }
        }
        val start = first
        if (start == null) {
            followAnchor.clear()
            return@LaunchedEffect
        }

        camera.beginFocusTransition()      // 以当前视图为起点，开始飞向目标车
        followAnchor.seed(start.id, start.x, start.y)   // 首帧直接钉住，不从 (0,0) 飞过来
        var miss = 0
        while (true) {
            // ⚠️ 必须写成 `withFrameNanos { it }`：空 lambda 的返回类型是 Unit，
            // 拿不到帧时间戳（写成 `{ }` 会报 Unit 与 Long 类型不匹配）。
            val nowNs = withFrameNanos { it }
            val f = vm.frame.value?.focus
            if (f == null) {
                // 车刚被点中时快照还没跟上 → 多等几帧；真的没了（到站 / 被清空）才退出
                if (++miss > 30) break
                continue
            }
            miss = 0
            // ① 观测：这份快照（约 60Hz、不锁 vsync）代表"车现在到哪儿了"
            followAnchor.push(nowNs, f.id, f.x, f.y)
            // ② 插值：按**帧时钟**在"上次画到哪儿"和"这份快照"之间取一个中间位置
            followAnchor.emit(nowNs)
            // ③ 相机与车**用同一个位置**：车严格钉在屏幕上，背景匀速流动，不会互相错位
            camera.followStep(followAnchor.sampleX, followAnchor.sampleY, FOCUS_SCALE_PX_PER_M, nowNs)
        }
        followAnchor.clear()
    }

    // 画布按 z 序自下而上切成十层，**每层都是独立 Canvas + 独立 RenderNode**
    // （`Modifier.graphicsLayer`，见铁律 2）。谁的依赖变了就只重录谁。
    // 手势与尺寸监听挂在**容器**上，与层数无关。
    //
    // ## P8 平移 / P12 缩放：都写进 graphicsLayer，而不是写进每个坐标里
    // `graphicsLayer { translationX = camera.offsetX; scaleX = camera.renderZoom }` 只是改
    // RenderNode 的**变换矩阵**，已经录好的绘制指令原样复用；旧写法把 offset 与 scale 加进了
    // 每一个坐标，于是相机一动，整层指令全部过期 —— 平移/缩放等于把所有层全部重录一遍。
    //
    // 因此分两类：
    //  · **世界类**（网格点 / 地面 / 路面 / 影子 / 植被）：坐标用 `camera.worldToLocal`
    //    （world × **renderScale**，既不含平移也不含真实缩放），平移与缩放都交给 graphicsLayer
    //    → 平移、捏合期间**零重录**（缩放只在"提交"那几次重录，见 [ZOOM_COMMIT_SETTLE_MS]）；
    //  · **界面类**（车流 / 标记 / 黄昏暖色）：本来就每帧变（或只是一条全屏矩形），
    //    继续用 `worldToScreen` 屏幕坐标、不参与矩阵变换，省掉一层坐标换算。
    //    （它们跟着真实缩放重录，与矩阵缩放后的世界**逐像素对齐**，因为两条路径的
    //     最终公式完全一样：`world × renderScale × zoom + offset` == `world × scale + offset`。）
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { camera.centerOrigin(it) }
            .pointerInput(Unit) {
                keepRollingGestures(camera, vm, tapTracker, dragHaptics) {
                    // 长按就绪：轻震动，让用户明确知道"可以开始滑了"
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
    ) {
        // 绘制顺序（从下到上，与拆层前**逐条对应**）：
        //   底色 → 网格副点 → 网格主点 → ② 街景地面层（草 / 楼体）→ ③ 道路 + 标线 + 圆角 + 路口铺装
        //   → ④ 建筑影子 → ⑤ 停止线 / 路线虚线 / 聚焦光圈 / 车
        //   → ⑥ 树 / 路灯光晕 → ⑥' 黄昏暖色 → ⑦ 编辑标记 / 路口符号 / 手柄 / 预览 / 信号灯 / 相位卡
        //
        // 层叠的几条规矩（每一条都是踩过坑的）：
        //  · 路灯光晕必须在道路**之后**，光才洒在路面上；
        //  · 路口那个"补接缝"的铺装圆点必须在路灯光晕**之前** ——
        //    它是**路面的一部分**，得被灯照亮；反过来盖在光上面，夜里每个十字路口
        //    就会留下一块照不到的浅色圆盘；
        //  · 建筑**本体**在道路**之前**（否则盖住路口铺装），但它**影子**必须单独排在道路
        //    **之后** —— 不然落在路面上的那半边被路面盖掉，楼一伸到马路上影子就没了；
        //  · **树在车之后**：树冠伸到车道上方时，现实里是树把车挡住；
        //  · 停止线属于路面标线，跟着车一起排在树下面（否则白线会画在树冠上）。
        BackdropLayer(vm, palette)
        GridLayer(camera, gridCache, palette)
        GridMajorLayer(camera, gridCache, palette)
        GroundLayer(camera, scenery, nightLevel, simpleRender)
        RoadLayer(vm, camera, geometry, roadCache, cornerCache, palette, simpleRender)
        ShadowLayer(camera, scenery, dayMode, fixedLight, autoLight, simpleRender)
        TrafficLayer(vm, camera, palette, dayMode, fixedLight, simpleRender, focusPulse, followAnchor)
        FoliageLayer(vm, camera, scenery, dayMode, fixedLight, autoLight, simpleRender)
        WarmthLayer(dayMode, fixedLight, autoLight)
        MarkerLayer(vm, camera, palette, messages, textMeasurer, labelCache, phaseText, uiAlpha)
    }
}

// ==================== 图层 ====================

/**
 * 「世界类」图层的 Modifier：铺满 + 自带 RenderNode + **把相机的平移与缩放都作为变换矩阵**。
 *
 * ## 为什么平移能放在这里
 * `graphicsLayer` 的 block 在**绘制/合成阶段**执行，读 `camera.offsetX` 只会更新
 * RenderNode 的 translation，**不会让已经录好的绘制指令失效**。
 * 于是"画面挪了一屏"从"重算并重录几千条指令"变成"改两个 float"。
 *
 * ## 为什么缩放也能放在这里（P12）
 * 缩放是同一类东西：`x × scale` 里的 `scale` 完全可以最后乘。图层的缩放基准必须
 * 设在 **(0, 0)**（世界原点）—— 所以 `transformOrigin = TransformOrigin(0f, 0f)`，
 * 而不是默认的中心点。设错的表现很直观：缩放时整个画面朝屏幕中心"吸"过去。
 *
 * 变换的合成顺序是 **先缩放、再平移**（Android 的 RenderNode 就是这样定义的：
 * `点 → 平移 + 缩放 × 点`），正好等于 `world × renderScale × zoom + offset`，
 * 与 `worldToScreen` 的 `world × scalePxPerM + offset` 逐位等价。
 *
 * ## 为什么必须每个世界层都用它
 * 这些层的绘制内容用的是 `camera.worldToLocal`（world × renderScale，不含平移与真实缩放）。
 * 少了这个 Modifier，整层会画在世界原点上 —— 也就是屏幕左上角，而且尺寸是"参考缩放"那一档。
 * 反过来说：**用了它就不能再在绘制里自己 translate offset 或乘缩放**，否则翻倍/平方。
 */
private fun Modifier.worldLayer(camera: CameraState): Modifier =
    fillMaxSize().graphicsLayer {
        val zoom = camera.renderZoom
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = zoom
        scaleY = zoom
        translationX = camera.offsetX
        translationY = camera.offsetY
    }


// ==================== 图层（自下而上）====================

/**
 * 夜色量化档数：色板与各静态图层共用同一个粒度。
 *
 * 取值理由：整条昼夜渐变的色差约 200 个 RGB 单位，1/128 档每档不到 2 个单位 ——
 * 哪怕在大面积纯色楼顶上盯着看，也看不出"一档一档跳"。
 * 而它把"自动昼夜模式下的每帧重录"降成每秒 1~3 次（随倍速变化，最高也就每秒十几次）。
 *
 * 别为了省那几次重录把它调粗（比如 1/48）：每档色差会涨到 4 个单位，
 * 在平涂的楼顶上可能被看成轻微的"脉动"。
 */
private const val NIGHT_STEPS = 128f

/** 把夜色量化到 1/[NIGHT_STEPS]（0 = 白天，1 = 夜晚） */
private fun quantizeNight(n: Float): Float =
    (n.coerceIn(0f, 1f) * NIGHT_STEPS).roundToInt() / NIGHT_STEPS

/**
 * 自动模式下**整份光照**（影子方向 / 长度 / 浓度 / 暖色）的量化档数（P10）。
 *
 * ## 为什么夜色量化了、影子却还在每帧重录
 * [NIGHT_STEPS] 只管"颜色"，可影子层与植被层读的是**整份 [DayLight]** ——
 * 自动昼夜时影子方向随仿真时间连续转，这两层于是每帧重录一遍：
 * 最坏情况（900 栋楼 / 1600 棵树 / 320 盏灯）单帧就是上万条绘制指令。
 * 而这些东西一分钟才转几度，肉眼根本分不出 0.7°。
 *
 * 512 档 = 每档 **0.70°**，而重录频率从"每帧 120 次/秒"降到 1x 倍速约 5 次/秒、
 * 5x 倍速约 27 次/秒。影子端点每步的位移：16m 长的影子在 1px/m 时 **0.20px**、
 * 在 3px/m（放大看）时 **0.59px** —— 影子本身只有 10~20% 的浓度，
 * 半像素级的横向台阶看不见（实测数值见"自检"一节）。
 *
 * ⚠️ 别调到 128 以下：那样每档 2.8°，16m 影子在 3px/m 下会一次跳 2.4px，能看出"一跳一跳"。
 */
private const val LIGHT_STEPS = 512f

/** 把昼夜相位量化到 1/[LIGHT_STEPS]（相位 0~1，一天转半圈影子） */
private fun quantizePhase(p: Float): Float =
    (p.coerceIn(0f, 1f) * LIGHT_STEPS).roundToInt() / LIGHT_STEPS

/**
 * **屏幕空间剔除**（P9 的落地形式，界面类图层专用）。
 *
 * ## 为什么只给「车流 / 标记」用，不给「世界类」图层用
 * 世界类图层的坐标是局部坐标，而它们的平移/缩放交给 `graphicsLayer` —— 也就是说
 * **平移时这些层根本不重录**。剔除结果是在"录制那一刻"算出来的，一旦录好就不再更新：
 * 把镜头移过去，被剔掉的楼不会自己回来（这正是铁律 22 说的"别用 size 硬裁"）。
 *
 * 界面类图层（车流 / 标记 / 黄昏暖色）本来就每帧重录，剔除当场生效，
 * 而且它们的坐标就是屏幕坐标，`size` 是可信的。
 *
 * @param margin 元素的"外扩半径"：车身长、灯箱高、编号标签宽……凡是可能伸出
 *   中心点之外的部分都要算进来，算小了就会出现"东西还在屏幕里、人却不见了"。
 */
private fun DrawScope.onScreen(c: Offset, margin: Float): Boolean =
    c.x >= -margin && c.y >= -margin &&
        c.x <= size.width + margin && c.y <= size.height + margin

/**
 * 「简化渲染」是否生效（车辆数 > `simpleRenderThreshold`）。
 *
 * 做成一个**只在跨越阈值时**才变的 State，是为了让地面 / 路面 / 车流三层的绘制
 * 不必每帧都去读车辆数 —— 那个读取本身就会让它们每帧重录。
 * 默认阈值 800、实际车流到不了，所以这个 State 全程只有一次初值。
 *
 * ⚠️ 它同时补齐了审计里指出的"从未生效的阀门"：阈值仍可调，
 * 但现在它的判定不再有每帧重录的代价 —— 真把阈值调低到车流规模时，
 * 简化渲染会立刻生效，而不是白白拖慢帧率。
 */
@Composable
private fun rememberSimpleRender(vm: SimulatorViewModel): State<Boolean> =
    produceState(false) {
        vm.frame.collect { f ->
            val simple = (f?.vehicles?.size ?: 0) > vm.settings.simpleRenderThreshold
            if (simple != value) value = simple
        }
    }

/**
 * ① 底色（最底层，铺满整屏、不透明）。
 *
 * 单独一层、**不参与平移**：全屏矩形没必要跟着世界动，拆出来还能让"编辑 / 普通模式"
 * 切换时只重录这一条指令。
 *
 * ⚠️ `uiMode` 在**这里**订阅，不在 [SimCanvas] 的根部（第 57 轮）：
 * 订阅写在根部的代价是"换个模式 → 整张画布重组 → 十层全部重录"。
 * 写在这里，换模式只让这一个全屏矩形重录 —— 一条 `drawRect`，成本可以忽略。
 */
@Composable
private fun BackdropLayer(vm: SimulatorViewModel, palette: SimPalette) {
    val uiMode by vm.uiMode.collectAsState()
    val editMode = uiMode == SimulatorViewModel.UiMode.EDIT
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        drawRect(if (editMode) palette.editBg else palette.canvasBg)
    }
}

/**
 * ①' 网格**副点**（每 cell 一个）。
 *
 * ## 平移为什么用 `wrap(offset, cell)` 而不是直接 `offset`
 * 点阵是**周期性**的：整体平移一个 cell 与不动完全等价。所以只把"余数"交给
 * `graphicsLayer` —— 平移多少都不影响画面，点集却能一直复用。
 *
 * 关键在于余数是在 **graphicsLayer 的 block 里**算的：那里读 `camera.offsetX`
 * 只会更新 RenderNode 的变换矩阵，**不会重录点集**。如果写在绘制 lambda 里，
 * 每帧都会重录一次几千个点的 `drawPoints`（这正是 P8 之前的行为）。
 *
 * ## 缩放走 P12 同一套机制，但周期要换算到屏幕
 * 点集按 `renderScale` 生成、由 `scaleX/scaleY` 乘上真实缩放，所以**屏幕上的**
 * 周期是 `cell × zoom`，`wrap` 必须按这个周期取余（按 `cell` 取会让点阵在缩放时一格一格地跳）。
 * 点集还要多做 30% 的富余，原因见 [GRID_OVERSCAN]。
 */
@Composable
private fun GridLayer(
    camera: CameraState,
    gridCache: GridDotCache,
    palette: SimPalette,
) {
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            val zoom = camera.renderZoom
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = zoom
            scaleY = zoom
            val period = gridCellPx(camera.renderScale) * zoom
            translationX = wrap(camera.offsetX, period) - period
            translationY = wrap(camera.offsetY, period) - period
        }
    ) {
        val scale = camera.renderScale
        if (!prepareGrid(scale, size.width, size.height, gridCache)) return@Canvas
        drawPoints(
            gridCache.minor, PointMode.Points, palette.gridDot,
            strokeWidth = gridDotRadius(scale) * 2f, cap = StrokeCap.Round,
        )
    }
}

/**
 * ①'' 网格**主点**（每 5 格一个，更大更深，作为方向参照）。
 *
 * 必须单独一层：它的周期是 `cell × 5`，和副点不同 —— 若跟副点挤在一个
 * `graphicsLayer` 里，两者的 `wrap` 余数无法同时成立。
 */
@Composable
private fun GridMajorLayer(
    camera: CameraState,
    gridCache: GridDotCache,
    palette: SimPalette,
) {
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            val zoom = camera.renderZoom
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = zoom
            scaleY = zoom
            val period = gridCellPx(camera.renderScale) * 5f * zoom
            translationX = wrap(camera.offsetX, period) - period
            translationY = wrap(camera.offsetY, period) - period
        }
    ) {
        val scale = camera.renderScale
        if (!prepareGrid(scale, size.width, size.height, gridCache)) return@Canvas
        drawPoints(
            gridCache.major, PointMode.Points, palette.gridMajor,
            strokeWidth = gridDotRadius(scale) * 3.2f, cap = StrokeCap.Round,
        )
    }
}

/**
 * ② 地面层：草坪 + 楼**体**（压在道路之下）。
 *
 * 楼体必须在路面**之下**（否则楼会盖住路口铺装和车道线），所以它是独立一层，
 * 而且必须排在 [RoadLayer] 之前。
 *
 * 依赖：街景对象（路网稳定后才换）、夜色、缩放。**不读 `frame`** → 动画期零重录。
 */
@Composable
private fun GroundLayer(
    camera: CameraState,
    scenery: Scenery,
    night: Float,
    simpleRender: State<Boolean>,
) {
    Canvas(Modifier.worldLayer(camera)) {
        val scale = camera.renderScale
        // 细节等级：简化渲染 或 缩得很远时，街景只画色块（不画投影 / 窗光 / 屋顶设备）
        drawScenery(scenery, camera, night, !simpleRender.value && scale >= 1.1f)
    }
}

/**
 * ③ 路面层：道路本体 + 车道标线 + 路口圆角 + 路口铺装圆点。
 *
 * 这是**拖动路网时唯一需要重录的层**：端点移动 → 只有相接的 2~4 条边几何重建（P1），
 * 其余层（网格 / 地面 / 植被 / 标记）完全不参与。
 *
 * 三个高频状态全部在**绘制期**读：
 *  - `networkVersion`：拖动时每帧 +1，是"路面要跟着动"的唯一信号 → 只失效绘制，不触发重组；
 *  - `draggingNetwork`：拖动期冻结几何 + 标线降级到 8m 采样；
 *  - `selectedRoadId`：选中变化才变，同样走绘制期，省掉一次全画布重组。
 */
@Composable
private fun RoadLayer(
    vm: SimulatorViewModel,
    camera: CameraState,
    geometry: DragSafeGeometry,
    roadCache: RoadRenderCache,
    cornerCache: CornerPathCache,
    palette: SimPalette,
    simpleRender: State<Boolean>,
) {
    val versionState = vm.networkVersion.collectAsState()
    val draggingState = vm.draggingNetwork.collectAsState()
    val selectedRoadState = vm.selectedRoadId.collectAsState()

    Canvas(Modifier.worldLayer(camera)) {
        // ⚠️ 这一行必须**无条件**读：拖动时正是它每帧 +1，才让路面跟着手重画。
        // 写成 `if (!dragging) geometry.update(..., versionState.value)` 会把手拖的路
        // 冻在原地（拖动期不读版本号 → 不失效 → 画面不更新）。
        val version = versionState.value
        val dragging = draggingState.value
        // 拖动端点时冻结车道截断量与路口圆角：前者是 O(E·deg)（还要 new 一堆 RoadPath）、
        // 后者是每路口 3 轮不动点迭代 —— 每帧重算就是拖动掉帧的最大一块。
        // 松手后（dragging 变 false）一次性算完。
        if (!dragging) geometry.update(vm.network, version)

        drawRoads(
            vm.network, camera, selectedRoadState.value, simpleRender.value,
            geometry.insets, geometry.version, geometry.corners,
            roadCache, cornerCache, palette, dragging,
        )
        // 路口中心"补接缝"的铺装圆点：属于**路面**，必须画在道路/圆角之后、
        // 路灯之前（夜里要能被灯照亮）。拖动端点时几何是冻结的旧值，会和移动中的
        // 路面错开，所以拖动期先不画 —— 跟圆角同一个处理。
        if (!dragging) drawJunctionPavement(vm.network, camera, palette)
    }
}

/**
 * ④ 建筑影子层（画在路面**之上**）。
 *
 * 楼体在路下、影子在路上 —— 这两件事同时成立只能拆成两层（详见 SceneryRender.kt 文件头）。
 *
 * ## 自动昼夜：读的是**量化后的**光照（P10）
 * 以前这一层直接 `collectAsState(vm.frame)` 再在绘制期算 `lightAt` ——
 * 影子方向每一帧都变一点点，于是**每一帧都要把最多 900 栋楼的影子重录一遍**。
 * 现在改读 [lightState]（相位量化到 1/[LIGHT_STEPS]，每秒只变几次），
 * 重录频率降两个数量级，而影子每步只转 0.7°，看不出来。
 *
 * 手动模式走组合期算好的 [fixed]，**完全不读任何 State** → 动画期零重录。
 */
@Composable
private fun ShadowLayer(
    camera: CameraState,
    scenery: Scenery,
    dayMode: DayMode,
    fixed: DayLight,
    lightState: State<DayLight>,
    simpleRender: State<Boolean>,
) {
    Canvas(Modifier.worldLayer(camera)) {
        // ⚠️ 只在这个分支里读 State：手动模式一旦读了，就又会每帧重录
        val light = if (dayMode == DayMode.AUTO) lightState.value else fixed
        val scale = camera.renderScale
        drawBuildingShadows(scenery, camera, light, !simpleRender.value && scale >= 1.1f)
    }
}

/**
 * ⑤ 车流层：停止线 → 前方路线虚线 → 聚焦光圈 → 车辆。
 *
 * 这是动画期的主力层（每帧都要重录），但它只包含"真的在动"的东西：
 * 一百多辆车、几十条停止线、几段路线箭头。楼、树、路、网格都不在这里。
 *
 * 层序（照抄旧代码）：停止线 → 路线 → 光圈 → 车。光圈必须在车**之前** ——
 * 车身把光圈中间盖住，只留四周一圈呼吸的光环。
 *
 * ## [anchor] 的作用
 * 锁定跟随时，被聚焦那辆车的绘制位置由 [FollowAnchor] 给出（帧时钟插值后的连续值），
 * 而不是快照里那个**每 16ms 才更新一次**的位置 —— 否则它会和相机的连续移动错开半个快照，
 * 表现为"车偶尔闪一下"。其余车辆一律按快照画，行为与以前完全一致。
 */
@Composable
private fun TrafficLayer(
    vm: SimulatorViewModel,
    camera: CameraState,
    palette: SimPalette,
    dayMode: DayMode,
    fixed: DayLight,
    simpleRender: State<Boolean>,
    focusPulse: State<Float>,
    anchor: FollowAnchor,
) {
    val frameState = vm.frame.collectAsState()
    // 车灯光束的可复用 Path（P7）：一帧里被复用 4×车辆数 次，不再每束光新建一个
    val beamPath = remember { Path() }

    // ---------- 帧时钟插值：让**所有**车都按帧间时间差连续移动 ----------
    // 快照是 60Hz 且不锁 vsync，屏幕是 120Hz —— 不插值就是"走一格停一格"，
    // 5x 倍速下一次跳 1.8 米（约 10 像素），非常显眼。详见 [FrameMixer]。
    val mixer = remember { FrameMixer() }
    val simState by vm.simState.collectAsState()
    LaunchedEffect(simState) {
        if (simState != SimulatorViewModel.SimState.RUNNING) {
            // 暂停 / 崩溃 / 未开始：没有新快照，插值窗口必须丢掉，
            // 否则恢复运行的第一帧会从"很久以前的位置"插过来（一次横穿马路）。
            mixer.reset()
            return@LaunchedEffect
        }
        while (true) {
            // ⚠️ 必须写成 `withFrameNanos { it }`：空 lambda 的返回类型是 Unit，拿不到帧时间戳
            val nowNs = withFrameNanos { it }
            vm.frame.value?.let { mixer.push(nowNs, it) }
            mixer.advance(nowNs)
        }
    }

    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val f = frameState.value ?: return@Canvas
        val light = if (dayMode == DayMode.AUTO) {
            DayCycle.lightAt(DayCycle.phaseAt(f.simTime))
        } else {
            fixed
        }
        // 绘制期读插值比例（State，不进组合期）与上一份快照的车辆表
        val kf = mixer.k
        val prevVehicles = mixer.prevVehicles

        // 停止线（画在路面之上，直观展示"车停在线上、不越线"）
        for (sl in f.stopLines) drawStopLine(sl, camera, palette)

        // 需求 4：聚焦车辆前方**已规划的路**（向前箭头组成的虚线）。画在车之前，
        // 于是箭头永远被车身压住一截 —— 视觉上"从车头伸出去"。
        f.focus?.let { fo ->
            // 虚线的起点就是车身中心，所以整条折线要跟着**车的插值位置**一起平移。
            // 不平移的话：车在平滑滑动，虚线却每 16ms 按快照跳一格 —— 5x 倍速下
            // 箭头会一顿一顿地闪，看着像"路径在抖"。
            val t = kf.toDouble()
            val pv = prevVehicles[fo.id]
            val fx: Double
            val fy: Double
            if (anchor.anchorId == fo.id) {
                // 跟随中：车是按锚点画的，虚线必须用同一个位置，否则"车头与第一个箭头分家"
                fx = anchor.anchorX.toDouble()
                fy = anchor.anchorY.toDouble()
            } else if (pv != null) {
                fx = pv.x + (fo.x - pv.x) * t
                fy = pv.y + (fo.y - pv.y) * t
            } else {
                fx = fo.x
                fy = fo.y
            }
            drawRouteGuide(fo.path, camera, fx - fo.x, fy - fo.y)
            // 需求 2：车底闪烁光圈。呼吸相位**只在确实有聚焦车辆时才读** ——
            // 没有聚焦时它连一次绘制依赖都不会登记，那个无限动画也就不产生任何重绘。
            // 光圈跟着锚点画：它必须包住车，车在哪儿它就在哪儿。
            drawFocusRing(fo, camera, focusPulse.value, anchor, fx - fo.x, fy - fo.y)
        }

        // 车辆：夜间在这里先画地面光斑 / 投影、再画车身、最后点尾灯
        drawVehicles(
            f.vehicles, prevVehicles, kf,
            camera, simpleRender.value, palette, light, anchor, beamPath,
        )
    }
}

/**
 * ⑥ 植被 / 灯光层：树 → 路灯光晕（都画在车**之上**）。
 *
 * 为什么必须在车之上：树冠伸到车道上方时，现实里是树把车挡住；
 * 路灯光要洒在路面和树冠上，画早了会被它们盖掉。
 *
 * 自动昼夜读 [lightState]（量化后的光照，见 [ShadowLayer] 的说明）；
 * 手动模式走 [fixed]、不读任何 State → 动画期零重录。
 */
@Composable
private fun FoliageLayer(
    vm: SimulatorViewModel,
    camera: CameraState,
    scenery: Scenery,
    dayMode: DayMode,
    fixed: DayLight,
    lightState: State<DayLight>,
    simpleRender: State<Boolean>,
) {
    // 拖动路网（每帧 +1 的版本号）时才需要临时降一档细节，这个 State 变化很稀疏
    val draggingState = vm.draggingNetwork.collectAsState()
    Canvas(Modifier.worldLayer(camera)) {
        val light = if (dayMode == DayMode.AUTO) lightState.value else fixed
        val scale = camera.renderScale
        val detail = !simpleRender.value && scale >= 1.1f
        // 拖动路网时再降一档，只关"楼体/树冠内部"的碎细节（高光、窗光）：
        // 整片街区都在跟着手移动，这些 1~2px 的东西根本看不见，却要占掉上千条绘制指令。
        // 注意**影子不受这档影响** —— 影子是大块几何，忽然消失比模糊更扎眼。
        drawTrees(scenery, camera, light, detail && !draggingState.value)
        drawStreetLights(scenery, camera, light.night)
    }
}

/**
 * ⑥' 黄昏 / 黎明暖色（整屏叠加一层淡橙）。
 *
 * 为什么单独一层：它铺满整屏、**与世界坐标无关**。若跟 [FoliageLayer] 一起放在
 * 带平移的 `graphicsLayer` 里，矩形会跟着世界跑，松手后屏幕上就会缺一条边。
 * 单独一层后它就永远盖满画面，成本只有一条 `drawRect`。
 */
@Composable
private fun WarmthLayer(
    dayMode: DayMode,
    fixed: DayLight,
    lightState: State<DayLight>,
) {
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val light = if (dayMode == DayMode.AUTO) lightState.value else fixed
        if (light.warmth > 0.01f) {
            drawRect(Color(0xFFFF9A5C).copy(alpha = 0.085f * light.warmth))
        }
    }
}

/**
 * ⑦ 标记层：编辑灯位标记 → 路口符号与编号 → 拖拽手柄 → 画路预览 → 曲率手柄
 *            → 信号灯 → 相位卡 → 堵塞警示圈。
 *
 * 这些是**界面**而不是路面：路口符号与相位卡随「去UI」（[uiAlpha]）一起淡出。
 * 层序全部照抄旧代码 —— 例如相位卡必须画在信号灯之后，否则会被灯头和车身挡住。
 */
@Composable
private fun MarkerLayer(
    vm: SimulatorViewModel,
    camera: CameraState,
    palette: SimPalette,
    messages: Messages,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
    phaseText: PhaseTextCache,
    uiAlphaState: State<Float>,
) {
    // 与 [BackdropLayer] 同一个道理：`uiMode` 在**用到它的这一层**订阅，
    // 而不是拉到 [SimCanvas] 根部 —— 换模式只让这一层（本来就每帧重录）重录。
    val uiMode by vm.uiMode.collectAsState()
    val editMode = uiMode == SimulatorViewModel.UiMode.EDIT
    val frameState = vm.frame.collectAsState()
    val selectedNodeState = vm.selectedNodeId.collectAsState()
    val selectedRoadState = vm.selectedRoadId.collectAsState()
    val drawStartState = vm.drawStart.collectAsState()
    val drawCurrentState = vm.drawCurrent.collectAsState()
    val drawReadyState = vm.drawReady.collectAsState()
    val drawToolState = vm.drawTool.collectAsState()
    val simState by vm.simState.collectAsState()

    // 崩溃后：堵塞路口以「闪烁」方式提示（不依赖已冻结的 simTime，用独立时钟驱动）
    val crashed = simState == SimulatorViewModel.SimState.CRASHED
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(crashed) {
        if (crashed) {
            while (true) {
                blinkOn = !blinkOn
                delay(480.milliseconds)
            }
        } else {
            blinkOn = true
        }
    }

    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val f = frameState.value
        // 「去UI」的可见度在**绘制期**读：这一层本来就每帧重录，读它不额外付钱；
        // 而读在组合期的话，淡出的 380ms 里整张画布会每帧重组一次。
        val uiAlpha = uiAlphaState.value
        val selectedRoad = selectedRoadState.value
        val selectedNode = selectedNodeState.value

        // 编辑模式下，给"有红绿灯的路口"画一个小灯位标记，让灯的存在一直可见
        if (editMode) drawSignalMarkers(vm, camera, selectedNode)

        // 路口符号 + 编号（需求 4：只属于界面，随「去UI」一起淡出）
        if (uiAlpha > 0.01f) {
            val focusNow = f?.focus
            drawNodeSymbols(
                vm, camera, selectedNode, textMeasurer, labelCache, messages, palette,
                // 需求 3：路口名称只在**锁定车辆**时显示，平时不打扰画面。
                // 判据用「有没有聚焦车辆」（= 信息框在不在），不用 followLocked ——
                // 后者在用户手动拖动一下画面后就会变 false，可信息框还开着，
                // 于是出现"车锁定着、名字却全没了"的割裂感。
                labelsVisible = focusNow != null,
                focusOrigin = focusNow?.originJunction ?: -1L,
                focusTarget = focusNow?.targetJunction ?: -1L,
                alpha = uiAlpha,
            )
        }

        // 选中道路 → 两端画可拖拽手柄（需求 2：拖动端点即可移动道路）
        if (editMode) drawEdgeHandles(vm, camera, selectedRoad)
        drawPreview(
            vm.network, drawStartState.value, drawCurrentState.value,
            drawToolState.value, drawReadyState.value, camera,
        )
        drawCurveHandle(vm, camera, selectedRoad)

        if (f != null) {
            // 路口信号灯（十字 / 丁字路口自动配置）
            for (sig in f.signals) drawSignalLight(sig, camera)
            // 相位条：选中信号路口时，告诉用户"现在放行谁、还剩几秒"。
            // 画在灯之后（最上层），不然会被车和灯头挡住。属于界面元素 → 随「去UI」淡出。
            val phase = f.phase
            if (phase != null && uiAlpha > 0.01f) {
                val node = selectedNode?.let { vm.network.node(it) }
                if (node != null) {
                    drawPhaseCard(
                        node.x, node.y, camera, phase, textMeasurer, labelCache, phaseText,
                        messages, palette, uiAlpha,
                    )
                }
            }
            if (uiAlpha > 0.01f) {
                for ((junctionId, _) in f.stats.blockedJunctions) {
                    val node = vm.network.node(junctionId) ?: continue
                    drawBlockedHalo(node.x, node.y, camera, f.simTime, crashed, blinkOn, uiAlpha)
                }
            }
        }
    }
}

// ==================== 网格（点阵） ====================

/**
 * 背景网格改为**点阵**渲染：在每个网格交点画一个小圆点。
 *
 * 为什么不用线：线条在缩放时会出现粗细跳变 / 闪烁 / 错位（offsetX % step 的取整误差会累积）；
 * 而圆点只改变间距、不会改变线宽，缩放全程视觉稳定。
 *
 * 自适应层级：缩得太小时只画"主网格点"（隔 N 个点画一次），避免点糊成一团变成一片灰。
 */
/**
 * 网格点间距（像素），含自适应档位：缩得越小、格距越大（只保留稀疏的"主点"）。
 *
 * 抽成独立函数是因为**两个地方必须算出完全相同的值**：
 *  · 绘制时用它生成点集（[prepareGrid]）；
 *  · `graphicsLayer` 的 block 里用它算平移余数。
 * 两处只要有一位偏差，平移时点阵就会一格一格地"跳"。
 */
private fun gridCellPx(scale: Float): Float {
    val step = RoadNetwork.GRID_M.toFloat() * scale
    val level = when {
        step >= 26f -> 1f
        step >= 14f -> 2f
        step >= 7f -> 5f
        else -> 10f
    }
    return step * level
}

/** 基础点的半径（×2 即直径；主点在此基础上再 ×3.2） */
private fun gridDotRadius(scale: Float): Float =
    if (RoadNetwork.GRID_M.toFloat() * scale >= 26f) 1.6f else 1.2f

/**
 * 网格点集的**富余倍数**（P12）。
 *
 * 点集是按 `renderScale` 铺满"屏幕尺寸"的，真实缩放靠 `graphicsLayer` 乘上去 ——
 * 于是缩放比小于 1 的那一刻（捏合缩小），整片点阵在屏幕上**同步缩小**，
 * 右边缘 / 下边缘就会露出一条没有点的空白。
 *
 * 缩放的允许比值下限是 [CameraState.RENDER_ZOOM_MIN] = 0.8，所以点集只要铺到
 * 屏幕的 1/0.8 = 1.25 倍就够；取 1.3 留一点余量（浮点 ceil 与 wrap 余数都在边界上）。
 * 代价是点数多约 70%（最密时 3900 → 6600），而这些点只在**提交那几次**才生成，
 * 捏合过程中一次都不重建 —— 用一次性开销换掉每帧开销，很划算。
 */
private const val GRID_OVERSCAN = 1.3f

/**
 * 按当前缩放与画布尺寸把点集准备好；返回 `false` 表示这帧不该画网格
 * （缩得太小、或点密到会画出海量圆点）。
 *
 * 注意它**不读 camera.offset** —— 这是平移能零重录的前提。
 */
private fun prepareGrid(scale: Float, w: Float, h: Float, cache: GridDotCache): Boolean {
    val step = RoadNetwork.GRID_M.toFloat() * scale
    if (step < 3f) return false
    val cell = gridCellPx(scale)
    val cols = ceil(w * GRID_OVERSCAN / cell).toInt() + 1
    val rows = ceil(h * GRID_OVERSCAN / cell).toInt() + 1
    // 安全上限：极端情况下不画出海量圆点
    if (cols > 300 || rows > 300) return false
    cache.ensure(cell, cols, rows)
    return true
}

/** `v` 对 `period` 取**正**余数：Kotlin 的 `%` 会保留符号，负 offset 会算到 -period..0 */
private fun wrap(v: Float, period: Float): Float {
    val m = v % period
    return if (m < 0f) m + period else m
}

/**
 * 点阵网格的**缓存点集**（局部坐标，与相机无关）。
 *
 * 旧实现每帧对每个交点调一次 `drawCircle`：1080×2340 的屏在常规缩放下约 3800~6500 个点，
 * 也就是说每帧要往绘制列表里塞几千条指令 —— 平移、缩放、拖动路网时这些指令全都要重录一遍。
 * 现在点集只在**缩放或画布尺寸变化**时重建，平移靠一次画布变换完成，绘制指令恒定两条。
 *
 * 点集用局部坐标还有一个好处：平移时不需要重新计算任何点的世界坐标。
 */
private class GridDotCache {
    private var cell = -1f
    private var cols = -1
    private var rows = -1

    var minor: List<Offset> = emptyList()
        private set
    var major: List<Offset> = emptyList()
        private set

    fun ensure(cell: Float, cols: Int, rows: Int) {
        if (cell == this.cell && cols == this.cols && rows == this.rows) return
        this.cell = cell
        this.cols = cols
        this.rows = rows

        val all = ArrayList<Offset>(cols * rows)
        for (i in 0..cols) {
            val x = i * cell
            for (j in 0..rows) all.add(Offset(x, j * cell))
        }
        minor = all

        val step5 = cell * 5f
        val c5 = cols / 5 + 2
        val r5 = rows / 5 + 2
        val maj = ArrayList<Offset>(c5 * r5)
        for (i in 0..c5) {
            val x = i * step5
            for (j in 0..r5) maj.add(Offset(x, j * step5))
        }
        major = maj
    }
}

// ==================== 道路 ====================

/**
 * 每条道路两端的车道标线截断量：edgeId → [from 端, to 端]（米）。只在路网变化时重算。
 *
 * **本轮需求 2：统一截断规则。** 用 [RoadNetwork.junctionBoundaryM]，它取
 * 「对方半宽 / |sinθ|」与「本路口最大半宽」的较大者 —— 于是同一个路口的**所有方向**
 * （不论几条车道、几度斜交）标线都停在同一条边界上，与停止线也严格对齐。
 * 旧版只用 junctionInsetM，三车道 × 单车道的路口会出现"三车道那边中心线穿进路口、
 * 单车道那边早早截断"的错位。
 */
private fun laneInsetsOf(network: RoadNetwork): Map<Long, DoubleArray> =
    network.edges.associate {
        it.id to doubleArrayOf(
            network.junctionBoundaryM(it, it.from),
            network.junctionBoundaryM(it, it.to),
        )
    }

/**
 * 画布用的**拖动安全几何缓存**：车道标线截断量 + 路口圆角。
 *
 * 这两份结构都不便宜：
 *  - `laneInsetsOf` 对每条路算 `junctionBoundaryM`，里面还要对每个相邻路口求切向与夹角，
 *    而 `pathOf` 每次调用都新建一个 `RoadPath`（O(E·deg) 次分配）；
 *  - `JunctionGeometry.allCorners` 对每个路口做 3 轮不动点迭代。
 *
 * 拖动端点时 `networkVersion` 是**每帧** +1，如果直接 `remember(networkVersion)`，
 * 上面两坨就会在拖动的每一帧重跑一遍 —— 这正是"拖动掉帧"的主因。
 * 这里把版本号记在自己身上，只在 `dragging == false` 时推进；
 * 拖动期间沿用按下那一刻的结果（同期标线走 8m 粗采样、圆角不画）。
 *
 * 它是普通可变对象而不是 `State`：本身不触发重组，驱动重组的是 `networkVersion`。
 */
private class DragSafeGeometry {
    /** 本轮几何对应的版本号；车道标线缓存的失效判断要用它（见 [RoadRenderCache]） */
    var version = Int.MIN_VALUE
        private set

    var insets: Map<Long, DoubleArray> = emptyMap()
        private set
    var corners: List<CornerArc> = emptyList()
        private set

    fun update(network: RoadNetwork, version: Int) {
        if (version == this.version) return
        this.version = version
        insets = laneInsetsOf(network)
        corners = JunctionGeometry.allCorners(network)
    }
}

/** 车道标线的正负两个方向（提为常量，避免在内层循环里 `intArrayOf` 每轮新建数组） */
private val LANE_SIGNS = doubleArrayOf(1.0, -1.0)

/**
 * 一条道路的绘制几何（[RoadRenderCache] 的缓存单元）。
 *
 * 为什么提到文件级、并且用 `internal` 而不是 `private`：它会被 `RoadRenderCache.entry()`
 * 当作返回值交给绘制函数用。Kotlin 对"从成员签名里漏出更小可见度的类型"有可见性检查，
 * 把类型放成 `internal`（≥ 私有类的有效可见度）就不会踩到，也不需要把它写成内部类。
 */
internal class RoadEdgeGeometry {
    var bodySig = Long.MIN_VALUE
    var lineSig = Long.MIN_VALUE
    var body: Path? = null
    var center: Path? = null
    var dashes: List<Path>? = null
    var path: RoadPath? = null
    var length = 0.0
}

/**
 * 每条道路的**绘制几何缓存**（P1：拖动期增量重建）。
 *
 * 为什么可以缓存：路面 Path 只依赖「两端坐标 + 曲率 + 缩放」，车道标线再多两个量
 * （路口截断量、采样步长）—— 它们全都与帧号、相机偏移无关。
 * 所以 Path 一律按**局部坐标**（world × scale，**不含 offset**）构建，
 * 绘制时用一次 `translate` 平移到位：矩阵平移不重采样，像素级零差异（不会发糊）。
 *
 * 于是三类交互各自变成：
 *  - **拖动端点**：只有与该节点相接的 2~4 条边签名变了 → 只重建这 2~4 条，其余 36 条原样复用；
 *  - **平移画面**：一个签名都不变 → 几何重建量为 **0**（旧实现每帧重建全部 40 条）；
 *  - **缩放（捏合）**：传进来的是 `renderScale` 而不是真实缩放，捏合期间它不变 →
 *    几何重建量也是 **0**，只在缩放提交那几次整体重建一次（P12）；
 *  - **动画运行**：世界是静止的 → 几何重建量为 **0**。
 *
 * 缓存键是**几何签名**而不是版本号：`networkVersion` 在拖动时每帧 +1，拿它当键等于没缓存。
 * 签名相同 ⇒ 形状逐位相同 ⇒ 复用上一帧的 Path 必然正确。
 *
 * 用 `LongSparseArray` 而不是 `HashMap<Long, …>`：后者的每次 `get(id)` 都要把 long
 * 装箱成 Long（40 条边 × 每帧 = 40 个临时对象），前者零装箱。
 */
private class RoadRenderCache {

    private val map = LongSparseArray<RoadEdgeGeometry>()

    /** 这份缓存属于哪个路网对象（见 [bind]） */
    private var owner: RoadNetwork? = null

    /**
     * 绑定路网实例：**换了整个路网对象就整体作废**。
     *
     * 载入存档 / 撤销 / 重做 / 清空都是 `network = <新的 RoadNetwork>`，
     * 新对象的边 id 又从 1 开始编，会和旧缓存撞号；而"几何签名"虽然能挡住形状不同的误用，
     * 却挡不住"签名恰好相同但属于另一张图"的滞留条目，也没法回收它们。
     * 一次引用比较就能把这类问题一次性关掉，成本为 0。
     */
    fun bind(network: RoadNetwork) {
        if (owner === network) return
        owner = network
        map.clear()
    }

    /**
     * 取（必要时重建）一条道路的绘制几何。
     *
     * @param insetVersion 车道截断量对应的版本号（[DragSafeGeometry.version]）
     * @param scale        **参考缩放**（`camera.renderScale`）：捏合期间它是常量，所以这里不失效；
     *                     真实缩放由图层的 `graphicsLayer` 乘上去。传真实缩放的后果是
     *                     每帧重建全部 40 条边（Path + 上千个采样点），缩放掉帧的主因。
     * @param stepM        标线采样步长（拖动时 8m / 平时 2m）
     * @param wantLines    是否需要车道标线（简化渲染时为 false）
     * @return null = 这条路的端点节点不存在（调用方跳过它）
     */
    fun entry(
        e: RoadEdge,
        network: RoadNetwork,
        insets: Map<Long, DoubleArray>,
        insetVersion: Int,
        scale: Float,
        stepM: Double,
        wantLines: Boolean,
    ): RoadEdgeGeometry? {
        val a = network.nodes[e.from] ?: return null
        val b = network.nodes[e.to] ?: return null
        val ent = map.get(e.id) ?: RoadEdgeGeometry().also { map.put(e.id, it) }

        // ---- ① 路面：签名只含"真正决定形状"的量 ----
        // 拖动一个端点时，只有相接的那 2~4 条边签名会变，其余原样命中缓存。
        var sig = a.x.toRawBits()
        sig = sig * 1_000_003L + a.y.toRawBits()
        sig = sig * 1_000_003L + b.x.toRawBits()
        sig = sig * 1_000_003L + b.y.toRawBits()
        sig = sig * 1_000_003L + e.curvature.toRawBits()
        sig = sig * 1_000_003L + scale.toRawBits()

        if (ent.bodySig != sig) {
            val path = network.pathOf(e) ?: return null
            ent.path = path
            ent.length = path.length
            ent.body = polylinePath(network.samplePolyline(path), scale)
            ent.bodySig = sig
            ent.lineSig = Long.MIN_VALUE          // 路面形状变了 → 标线必须跟着重建
        }

        // ---- ② 车道标线 ----
        if (!wantLines) {
            ent.center = null
            ent.dashes = null
            ent.lineSig = Long.MIN_VALUE
            return ent
        }
        var lineSig = sig * 31L + insetVersion.toLong()
        lineSig = lineSig * 1_000_003L + stepM.toRawBits()
        // ⚠️ 车道数必须进签名：它不改变路面轮廓（中线不变），只改变**虚线条数**。
        // 少了这一项，把一条路从双车道改成三车道后，缓存命中会继续画两条虚线。
        lineSig = lineSig * 1_000_003L + e.lanes
        if (ent.lineSig == lineSig) return ent
        ent.lineSig = lineSig

        val path = ent.path ?: return ent
        val ins = insets[e.id]
        val s0 = ins?.get(0) ?: 0.0
        val s1 = ent.length - (ins?.get(1) ?: 0.0)
        if (s1 - s0 < 0.6) {                     // 路太短 / 几乎整段都是路口 → 不画标线
            ent.center = null
            ent.dashes = null
            return ent
        }

        // ① 中央分隔线（偏移 0，分隔相反行驶方向）：始终**黄色实线**
        ent.center = laneLinePath(path, 0.0, s0, s1, scale, stepM)

        // ② 同向车道分隔线（偏移 ±LANE_WIDTH_M, ±2·LANE_WIDTH_M …）：**白色虚线**
        // 刻意**不合并**成一条 Path：合并后虚线相位会跨子轮廓累积，
        // 第二条之后的虚线起始位置会整体挪一截 —— 这是肉眼能看出来的，宁可多几条指令。
        val lanes = e.lanes
        if (lanes < 2) {
            ent.dashes = null
        } else {
            val list = ArrayList<Path>(2 * (lanes - 1))
            for (k in 1 until lanes) {
                for (sgn in LANE_SIGNS) {
                    laneLinePath(path, RoadNetwork.LANE_WIDTH_M * k * sgn, s0, s1, scale, stepM)
                        ?.let { list.add(it) }
                }
            }
            ent.dashes = if (list.isEmpty()) null else list
        }
        return ent
    }

    /**
     * 丢掉已删除道路的缓存。
     *
     * 为什么必须做：条目里握着 `android.graphics.Path`（原生内存 + 托管堆引用），
     * 只增不减的话，反复"删路 / 画路"会让缓存无限长大。
     * 道路 id 单调递增、**永不重用**，所以按当前边集合过滤是安全的（不会误删有效条目）。
     * 拖动期 `size` 相等 → 这里每帧只是一次 Int 比较，零开销。
     */
    fun prune(edges: List<RoadEdge>) {
        if (map.size() == edges.size) return
        val keep = HashSet<Long>(edges.size * 2 + 4)
        for (e in edges) keep.add(e.id)
        for (i in map.size() - 1 downTo 0) {
            if (!keep.contains(map.keyAt(i))) map.removeAt(i)
        }
    }
}

/**
 * **路口圆角的合并 Path 缓存**。
 *
 * 旧实现每帧为 40 个圆角各新建一个 `Path`、各画两遍（填充 + 同色描边），
 * 即每帧 40 个原生 Path 对象、80 条绘制指令。
 *
 * 为什么可以合并成一条：这些圆角**同为 `palette.road` 且不透明**，又分属不同路口
 * （彼此不重叠）—— 把 40 个闭合轮廓放进同一个 Path 一次填充，与逐个填充在像素上等价；
 * 描边（用来盖住圆角与道路折线之间的亚像素缝隙）也逐轮廓生效，效果不变。
 *
 * 失效条件只有两个：圆角几何变了（[version]）、缩放变了（[scale]）。
 * **拖动端点时圆角几何是冻结的**（见 [DragSafeGeometry]），所以拖动期这里也是零重建。
 */
private class CornerPathCache {
    private var version = Int.MIN_VALUE
    private var scale = Float.NaN

    var path: Path? = null
        private set

    fun ensure(corners: List<CornerArc>, scale: Float, version: Int) {
        if (version == this.version && scale == this.scale) return
        this.version = version
        this.scale = scale
        val p = Path()
        var any = false
        for (c in corners) {
            if (c.fill.size < 4) continue
            any = true
            val pts = c.fill
            for (i in pts.indices) {
                val q = pts[i]
                val x = q.x.toFloat() * scale
                val y = q.y.toFloat() * scale
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
        }
        path = if (any) p else null
    }
}

private fun DrawScope.drawRoads(
    network: RoadNetwork,
    camera: CameraState,
    selectedRoadId: Long?,
    simple: Boolean,
    laneInsets: Map<Long, DoubleArray>,
    insetVersion: Int,
    corners: List<CornerArc>,
    roadCache: RoadRenderCache,
    cornerCache: CornerPathCache,
    palette: SimPalette,
    dragging: Boolean,
) {
    val scale = camera.renderScale
    // 拖动路网时把标线采样放粗（2m → 8m）：采样点少 3/4，画面几乎看不出差别
    // （整条路都在手里移动），省下的正是拖动时最吃紧的那部分 CPU。
    val stepM = if (dragging) LANE_LINE_STEP_DRAG_M else LANE_LINE_STEP_M
    roadCache.bind(network)

    // ⚠️ 这里所有 Path 都是**局部坐标**（world × **renderScale**，既不含 offset 也不含真实缩放）。
    // 平移与缩放由 RoadLayer 的 Modifier.worldLayer(camera) 在 RenderNode 的变换矩阵上完成 ——
    // 所以这里**绝不能再 translate(offset)、也不能再乘缩放**：那等于把相机又写回绘制内容，
    // 平移 / 捏合时整层重录（这正是 P8 / P12 之前的行为）。

    // 道路本体（直线与曲线统一：把路径采样成折线再画）；宽度按**每方向车道数**决定
    for (e in network.edges) {
        val ent = roadCache.entry(e, network, laneInsets, insetVersion, scale, stepM, !simple)
            ?: continue
        val body = ent.body ?: continue
        val widthPx = (e.widthM * scale).toFloat().coerceAtLeast(3f)
        val selected = e.id == selectedRoadId

        // 选中时先铺一层更宽的半透明高亮，短路段也能一眼看出选中了哪条
        if (selected) {
            drawPath(
                body, palette.roadSelected.copy(alpha = 0.30f),
                style = Stroke(width = widthPx * 2.0f, cap = StrokeCap.Round),
            )
        }
        drawPath(
            body, if (selected) palette.roadSelected else palette.road,
            style = Stroke(width = if (selected) widthPx * 1.06f else widthPx, cap = StrokeCap.Round),
        )

        // ---- 标线（简化渲染时省略）----
        // 规则一（颜色/线型）：正中间那条（分隔相反行驶方向）黄色实线，其余同向车道之间白色虚线。
        // 规则二（截断，本轮修正）：标线**不得侵入路口**。路口区域是两条道路行车带相交形成的
        //   四边形，标线应恰好止于这条四边形面向自己的那一边 —— 由 RoadNetwork.junctionBoundaryM
        //   按「对方半宽 / |sinθ|」算出两端各自的内缩量，于是任何相交角度、任何车道数下
        //   每个方向的标线都停在同一条边界上（不再有的超出、有的偏短）。
        // 标线几何已在缓存里按 (insetVersion, stepM) 建好，这里只负责画。
        if (simple) continue

        ent.center?.let { drawPath(it, palette.centerLine, style = Stroke(width = 1.8f)) }
        // 同向车道分隔线（虚线）：几条就几条，逐个画（合并会改变虚线相位，见缓存类的说明）
        ent.dashes?.forEach {
            drawPath(it, palette.laneLine, style = Stroke(width = 1.8f, pathEffect = LANE_DASH))
        }
    }

    // 路口圆角（需求 1）：画在路面之后 —— 它只补在两条路缘边线之间的死角里，
    // 与车辆过路口用的是**同一个** JunctionGeometry 结果 → 所见即所行。
    //
    // 拖动端点时这份几何是**冻结的旧值**，画出来会和移动中的路面错开一大截 →
    // 先不画，松手后几何重算，下一帧就回来了。
    if (!dragging && scale >= 0.3f) {
        cornerCache.ensure(corners, scale, insetVersion)
        cornerCache.path?.let {
            drawPath(it, palette.road)
            // 同色细描边：曲线路上圆角边界与道路折线之间可能有亚像素级缝隙，
            // 描一圈同色边把它盖住（同色叠加，肉眼完全看不出来）。
            drawPath(it, palette.road, style = Stroke(width = 2f, join = StrokeJoin.Round))
        }
    }

    // 清掉已删除道路的缓存（拖动期 size 相等 → 一次 Int 比较就返回）
    roadCache.prune(network.edges)
}

// ==================== 路口圆角（需求 1）====================

/*
 * **路口圆角路面**：把每个夹角补成一段圆弧铺装。（实现见 [CornerPathCache]）
 *
 * 需求原文：「取消右转专用道的自动生成……将四个夹角改为圆角过渡，使所有右转车辆沿该弧线
 * 车道行驶。圆角定义为：**在不增加道路宽度的前提下**，由道路最右侧边线分支出一条弧形边线，
 * 该弧形边线连接到交汇道路的最右侧边线，并与两条最右侧边线同时相切。」
 *
 * 所以：
 *  - **不加宽任何道路**（原来的"右转专用道加宽带子"整段删掉了）；
 *  - 只把两条路缘边线之间那块**原本没有铺装的死角**填成圆角；
 *  - 填充边界由 [CornerArc.fill] 给出：两侧贴着**真实边线**（弧形道路也是采样出来的曲线），
 *    中间是路缘弧，因此圆角与路边严丝合缝，不会留缝、也不会多出一块三角形。
 *
 * 与车辆用的是**同一个 [CornerArc]** —— 车跑的那条弧（半径 = 路缘半径 + 半个车道宽）
 * 就是这块圆角的同心弧，所以"画出来的路"和"车走的路"必然重合，不可能跑到白区里。
 */

/**
 * 沿 [path] 画一条**侧向偏移 [off] 米**、只覆盖里程 [s0]~[s1] 的线（车道标线专用）。
 *
 * 为什么不用"整条折线整体平移"：平移只能用折线的弦向法线，曲线段上会与真实法线有偏差，
 * 且端点无法精确截在路口边界上。这里直接按 `pointAt(s) + tangentAt(s).perp() · off`
 * 逐点求值 —— 与车辆行驶用的是同一套几何，因此标线始终贴合车道、端点严格落在边界。
 *
 * 两个实现细节（P1/P3）：
 *  1. 输出的是**局部坐标**（world × scale，不含 offset），由调用方统一 translate；
 *  2. 侧向偏移按分量算，不构造中间向量 —— 旧写法 `pointAt + tangentAt.perp() * off`
 *     会为**每个采样点**白造 3 个 `Vector2`，一条三车道的路就是几百个临时对象。
 */
private fun laneLinePath(
    path: RoadPath,
    off: Double,
    s0: Double,
    s1: Double,
    scale: Float,
    stepM: Double = LANE_LINE_STEP_M,
): Path? {
    val len = s1 - s0
    if (len < 0.3) return null
    // 每 [stepM] 米采样一个点，弯道上也足够平滑；上限 96 段防止长路开销过大
    val steps = ceil(len / stepM).toInt().coerceIn(2, 96)
    val p = Path()
    for (i in 0..steps) {
        val s = s0 + len * i / steps
        val pt = path.pointAt(s)
        val tan = path.tangentAt(s)
        // perp(t) = (-ty, tx) → 偏移向量 = (-ty·off, tx·off)
        val x = (pt.x + -tan.y * off).toFloat() * scale
        val y = (pt.y + tan.x * off).toFloat() * scale
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    return p
}

/** 世界坐标折线 → **局部坐标** Path（world × scale，不含 offset；绘制时统一 translate） */
private fun polylinePath(poly: List<Vector2>, scale: Float): Path {
    val path = Path()
    for (i in poly.indices) {
        val p = poly[i]
        val x = p.x.toFloat() * scale
        val y = p.y.toFloat() * scale
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

// ==================== 节点：铺装（世界层）+ 符号（界面层）====================

/**
 * **路口铺装圆点**：普通路口中心那块"和路面同色"的圆，用来盖住几条道路交汇处的接缝。
 *
 * 它属于**路面**，所以画在道路之后、树与路灯之前 ——
 * 夜里路灯光要能照到它（需求 1），不能反过来盖在光上面，
 * 否则每个十字路口都会出现一块照不亮的浅色圆盘。
 */
private fun DrawScope.drawJunctionPavement(
    network: RoadNetwork,
    camera: CameraState,
    palette: SimPalette,
) {
    val scale = camera.renderScale
    for (node in network.nodes.values) {
        if (node.type == NodeType.OPEN) continue
        // 相接道路只取一次：断头路判定、最大半宽都从这一个列表来。
        // （edgesAt 现在走邻接表缓存，O(1) 且零分配；旧实现每次调用都要扫全部边并新建 List）
        val incident = network.edgesAt(node.id)
        if (incident.size <= 1) continue                  // 断头路（= isDeadEnd）
        // 半径取相接道路里最大的半宽，兼容多车道
        val half = incident.maxOfOrNull { it.halfWidthM } ?: RoadNetwork.LANE_WIDTH_M
        // 局部坐标（不含平移）：这一层由 RoadLayer 的 graphicsLayer 统一平移
        val c = camera.worldToLocal(node.x, node.y)
        drawCircle(palette.road, radius = (half * scale).toFloat().coerceAtLeast(3f), center = c)
    }
}

/**
 * 所有路口的**符号**（断头路停止标 / 开放路口双向箭头 / 路口编号）。
 *
 * 这些是**界面标记**，不是路面 —— 所以画在最上层，并且随「去UI」（[alpha]）一起淡出。
 *
 * **需求 3**：路口编号（「路口1」「路口2」……）只在 `labelsVisible` 为 true 时画，
 * 当前定义是"锁定跟随某辆车时"才显示 —— 平时不占画面。
 * 编号由 [RoadNetwork.assignJunctionNumber] 按开放顺序永久分配，随存档/撤销一起走，
 * 所以同一个路口任何时候都是同一个号。
 *
 * 编号标签用 [TextMeasurer] 预排版并按字符串缓存：每帧重复 measure 会把帧时间吃掉一大截。
 * 颜色在 drawText 时按昼夜覆写（见 [drawJunctionNumber]），所以缓存不必区分昼夜。
 */
private fun DrawScope.drawNodeSymbols(
    vm: SimulatorViewModel,
    camera: CameraState,
    selectedNodeId: Long?,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
    messages: Messages,
    palette: SimPalette,
    labelsVisible: Boolean,
    /** 锁定车辆的起始 / 目标路口 id（-1 = 无）：只用来给这两个名字加强调色 */
    focusOrigin: Long,
    focusTarget: Long,
    alpha: Float,
) {
    val network = vm.network
    val a = alpha.coerceIn(0f, 1f)

    for (node in network.nodes.values) {
        val c = camera.worldToScreen(node.x, node.y)
        // P9：屏幕外的路口不参与（这一层每帧重录，剔除当场生效）。
        // 余量 140dp 要同时容下符号半径（最大 30px）与右上角那枚编号标签。
        if (!onScreen(c, 140f * density)) continue
        val selected = node.id == selectedNodeId
        // 本节点相接的道路：只取**一次**，度数与最大半宽都从这里来
        // （每个节点都 filter 一遍边表，取三次就白跑三趟 O(E)）
        val incident = network.edgesAt(node.id)
        val degree = incident.size
        // 与 RoadNetwork.isDeadEnd 同义：未开放且只连一条路 → 画圆形停止符号
        val isDeadEnd = node.type == NodeType.NORMAL && degree <= 1
        // 编号贴图的基准半径（屏幕像素）：断头路 / 开放路口各有自己的符号半径，
        // 普通路口没有符号，就用"铺装圆的半径"。三种情况统一走一个变量，
        // 编号的位置逻辑只需要写一次。
        var symbolR: Float

        if (isDeadEnd) {
            // 断头路：同心圆停止符号（视觉半径 10~26px，更好点中）
            val r = (4.4 * camera.scalePxPerM).toFloat().coerceIn(10f, 26f)
            // 底色跟昼夜走：夜里用卡片深色，不能是纯白（会在深色路面上炸开一个白盘）
            drawCircle(palette.cardBg.copy(alpha = a), radius = r, center = c)
            drawCircle(DEAD_END_COLOR.copy(alpha = a), radius = r, center = c, style = Stroke(width = 2.8f))
            drawCircle(DEAD_END_COLOR.copy(alpha = a), radius = r * 0.38f, center = c)
            if (selected) {
                drawCircle(ACCENT.copy(alpha = a), radius = r + 6f, center = c, style = Stroke(width = 2.2f))
            }
            symbolR = r
        } else if (node.type == NodeType.OPEN) {
            // 开放路口：双向箭头符号（视觉半径 12~30px，更好点中）
            val r = (4.8 * camera.scalePxPerM).toFloat().coerceIn(12f, 30f)
            drawCircle(OPEN_COLOR, radius = r * 1.5f, center = c, alpha = 0.16f * a)
            drawCircle(OPEN_COLOR.copy(alpha = a), radius = r * 1.25f, center = c, style = Stroke(width = 2.2f))
            val axis = nodeAxisDirection(vm, node.id)
            val angle = Math.toDegrees(atan2(axis.y, axis.x)).toFloat()
            rotate(angle, c) {
                // 背对背两个三角 = 双向箭头（表示车辆可进可出）
                drawTriangle(Offset(c.x + r * 0.62f, c.y), r * 0.66f, OPEN_COLOR.copy(alpha = a), flip = false)
                drawTriangle(Offset(c.x - r * 0.62f, c.y), r * 0.66f, OPEN_COLOR.copy(alpha = a), flip = true)
            }
            if (selected) {
                drawCircle(ACCENT.copy(alpha = a), radius = r * 1.55f, center = c, style = Stroke(width = 2.2f))
            }
            symbolR = r * 1.25f
        } else {
            // 普通路口：符号已经在路面层（drawJunctionPavement）画过了，这里不重复，
            // 只取它的半径做编号定位基准。
            val half = incident.maxOfOrNull { it.halfWidthM } ?: RoadNetwork.LANE_WIDTH_M
            symbolR = (half * camera.scalePxPerM).toFloat().coerceAtLeast(6f)
        }

        // 需求 3：路口名称只在锁定车辆时显示 —— 而且**只有开放路口才有名字**。
        //
        // 普通十字 / 丁字路口是画路时自动 stitch 出来的交叉点，从来没有编号，也不该有：
        // 它们只是路的交汇处。有名字的是编辑模式里被"开放"的断头路（车辆出入口），
        // 名字在开放那一刻由 assignJunctionNumber 分发，永久不变。
        if (labelsVisible && node.type == NodeType.OPEN) {
            // 这辆车是从哪个路口出发、要去哪个路口 —— 给这两个名字加一点强调色，
            // 于是"锁定一辆车"时，名字里哪两个跟它有关一眼可辨。
            val isEnds = node.id == focusOrigin || node.id == focusTarget
            drawJunctionNumber(
                network, node.id, c, symbolR, camera, textMeasurer, labelCache, messages, palette, a, isEnds,
            )
        }
    }
}

/**
 * 开放路口的编号标签（需求 6）：白底小圆角胶囊 + 深绿文字，贴在符号的右上方。
 *
 * **只有开放路口有名字** —— 普通十字 / 丁字路口是画路时自动生成的交叉点，不带名字。
 *
 * @param emphasized 是不是"锁定车辆的起点 / 终点路口"：是的话文字与描边换成强调色，
 *   这样一辆车锁定后，名字里哪两个跟它有关一眼就能挑出来。
 *
 * 为什么贴在右上而不是正上方：正上方在竖直道路上会压住路面，右上角是路口外侧，
 * 四个方向的路都不会被遮住；同时与符号本身留 8% 半径的间隙，不粘在一起。
 */
private fun DrawScope.drawJunctionNumber(
    network: RoadNetwork,
    nodeId: Long,
    center: Offset,
    symbolR: Float,
    camera: CameraState,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
    messages: Messages,
    palette: SimPalette,
    alpha: Float,
    emphasized: Boolean = false,
) {
    val num = network.junctionNumberOf(nodeId) ?: return
    if (camera.scalePxPerM < 0.5f) return            // 缩得太小：文字会糊成一团，索性不画

    val text = messages.s(StrKey.LABEL_JUNCTION, num)
    // 缓存键只含文案：颜色在 drawText 时按昼夜覆写，所以昼夜切换不需要重排
    val key = "jn|$text"
    val layout = labelCache.getOrPut(key) {
        textMeasurer.measure(
            text = text,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = JUNCTION_NUM_COLOR,
            ),
        )
    }
    val tw = layout.size.width.toFloat()
    val th = layout.size.height.toFloat()
    val x = center.x + symbolR * 0.9f
    val y = center.y - symbolR * 1.25f - th
    val radius = CornerRadius((th + 5f) * 0.5f)

    drawRoundRect(
        palette.cardBg.copy(alpha = 0.92f * alpha),
        topLeft = Offset(x - 4f, y - 2.5f),
        size = Size(tw + 8f, th + 5f),
        cornerRadius = radius,
    )
    if (emphasized) {
        // 起终点路口：描一圈强调色边框，显眼但不改变字号（免得挤到别的标签）
        drawRoundRect(
            palette.accent.copy(alpha = 0.85f * alpha),
            topLeft = Offset(x - 4f, y - 2.5f),
            size = Size(tw + 8f, th + 5f),
            cornerRadius = radius,
            style = Stroke(width = 1.4f),
        )
    }
    drawText(
        textLayoutResult = layout,
        color = (if (emphasized) palette.accent else palette.junctionLabel).copy(alpha = alpha),
        topLeft = Offset(x, y),
    )
}

/** 取节点处第一条连接道路的方向（用于箭头符号朝向） */
private fun nodeAxisDirection(vm: SimulatorViewModel, nodeId: Long): Vector2 {
    val e = vm.network.edgesAt(nodeId).firstOrNull() ?: return Vector2(1.0, 0.0)
    val path = vm.network.pathOf(e) ?: return Vector2(1.0, 0.0)
    return if (e.from == nodeId) path.tangentAt(0.0) else path.tangentAt(path.length)
}

// ==================== 信号相位条 ====================

private val PHASE_TEXT_MAIN = Color(0xFF26215C)
private val PHASE_TEXT_SUB = Color(0xFF6B6A78)
private val PHASE_GREEN = Color(0xFF1D9E75)
private val PHASE_AMBER = Color(0xFFEF9F27)

/**
 * 相位卡副行的**单条**文本排版缓存（P10）。
 *
 * 这一行是"还剩 8 秒 · 第 2/4 段"，秒数一变文案就变 —— 不能塞进 [labelCache]：
 * 那个表的键是永久的，开一晚上会攒出几千条、每条挂一个 [TextLayoutResult]。
 *
 * 但同一秒里它会被重复排版 60~120 次（标记层每帧重录，卡片每帧重画）。
 * 文本排版是这套渲染里最贵的单次操作之一（0.1~0.4ms），于是用一个**只有一格**的
 * 槽位兜住：命中就复用，文案变了才重排。**同时只会画一张相位卡**，一格就够。
 */
private class PhaseTextCache {
    var key: String? = null
    var layout: TextLayoutResult? = null
}

/**
 * **信号相位卡**：选中某盏信号灯时，在路口右上方浮一张小卡片，回答两个问题 ——
 * 「现在放行的是哪个方向、什么转向？」「这一段还剩几秒？」
 *
 * 为什么要有它：配时（规则一/二/三）以前完全是个黑盒，用户只能看灯的颜色猜。
 * 有了这张卡，配时是不是合理（比如"直行 20 秒够不够放空队伍"）就能直接读出来。
 *
 * 为什么画在路口右上而不放屏幕顶栏：卡片要**指向具体那个路口**。顶栏固定位置在
 * 路口多的时候会分不清是哪盏灯的相位；贴着路口画，指向性一目了然。
 */
private fun DrawScope.drawPhaseCard(
    nodeX: Double,
    nodeY: Double,
    camera: CameraState,
    phase: SignalPhaseRender,
    textMeasurer: TextMeasurer,
    labelCache: HashMap<String, TextLayoutResult>,
    phaseText: PhaseTextCache,
    messages: Messages,
    palette: SimPalette,
    alpha: Float,
) {
    val axis = when (phase.axis) {
        0 -> messages.s(StrKey.PHASE_AXIS_UD)
        1 -> messages.s(StrKey.PHASE_AXIS_LR)
        else -> messages.s(StrKey.PHASE_AXIS_MIXED)
    }
    val turn = when (phase.turn) {
        0 -> messages.s(StrKey.PHASE_TURN_STRAIGHT)
        1 -> messages.s(StrKey.PHASE_TURN_LEFT)
        else -> messages.s(StrKey.PHASE_TURN_BOTH)
    }
    val head = "$axis · $turn"
    val remain = phase.remainingSec.roundToInt().coerceAtLeast(0)
    val stateText = if (phase.isYellow) {
        messages.s(StrKey.PHASE_YELLOW)
    } else {
        messages.s(StrKey.PHASE_REMAIN_FMT, remain)
    }
    val sub = stateText + " · " + messages.s(StrKey.PHASE_INDEX_FMT, phase.index, phase.count)

    // 只缓存**不变**的相位名（绿色/黄灯 + 轴向/转向，最多十几种组合）。
    // 下面那行倒计时每秒都换一个字符串，绝不能进这个永久缓存 ——
    // 否则键越攒越多、每键挂一个 TextLayoutResult，开一晚上就是几千条。
    // 它改走 [PhaseTextCache]（只有一格、按文案命中），于是同一秒内只排版一次。
    val headLayout = labelCache.getOrPut("ph|$head") {
        textMeasurer.measure(
            text = head,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PHASE_TEXT_MAIN),
        )
    }
    // 命中判定用"文案相同"，不是"引用相同"：`sub` 是每帧新拼的字符串，
    // 但只要秒数没变，内容就一样，排版结果可以直接复用（颜色在 drawText 时覆写，不进缓存）。
    val cachedSub = phaseText.layout
    val subLayout = if (phaseText.key == sub && cachedSub != null) {
        cachedSub
    } else {
        val measured = textMeasurer.measure(
            text = sub,
            style = TextStyle(fontSize = 10.sp, color = PHASE_TEXT_SUB),
        )
        phaseText.key = sub
        phaseText.layout = measured
        measured
    }

    val pad = 9f * density
    val gap = 2f * density
    val barH = 3.5f * density
    val contentW = max(headLayout.size.width, subLayout.size.width).toFloat()
    val w = (contentW + pad * 2f).coerceAtLeast(104f * density)
    val h = pad + headLayout.size.height + gap + subLayout.size.height + gap + barH + pad

    // 贴在路口右上方；越界就夹回屏幕内（路口在屏幕边缘时卡片不会跑出去）
    val c = camera.worldToScreen(nodeX, nodeY)
    val x = (c.x + 24f * density).coerceIn(8f * density, (size.width - w - 8f * density).coerceAtLeast(8f * density))
    val y = (c.y - h - 20f * density).coerceIn(8f * density, (size.height - h - 8f * density).coerceAtLeast(8f * density))

    drawRoundRect(
        palette.cardShadow.copy(alpha = palette.cardShadow.alpha * alpha),
        topLeft = Offset(x + 1.5f, y + 2.5f),
        size = Size(w, h),
        cornerRadius = CornerRadius(11f * density),
    )
    drawRoundRect(
        palette.cardBg.copy(alpha = 0.97f * alpha),
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(11f * density),
    )
    drawRoundRect(
        ACCENT.copy(alpha = 0.30f * alpha),
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(11f * density),
        style = Stroke(width = 1.2f),
    )
    drawText(
        textLayoutResult = headLayout,
        color = palette.cardInk.copy(alpha = alpha),
        topLeft = Offset(x + pad, y + pad),
    )
    drawText(
        textLayoutResult = subLayout,
        color = palette.cardSub.copy(alpha = alpha),
        topLeft = Offset(x + pad, y + pad + headLayout.size.height + gap),
    )

    // 进度条：整条 = 本级总时长，剩下的那截用绿（黄灯期用琥珀）——
    // 于是"条在缩短"就是倒计时，不用读数字也能感觉到还有多久。
    val barY = h + y - pad - barH
    val barW = w - pad * 2f
    val radius = CornerRadius(barH / 2f)
    drawRoundRect(
        palette.cardTrack.copy(alpha = alpha),
        topLeft = Offset(x + pad, barY),
        size = Size(barW, barH),
        cornerRadius = radius,
    )
    val frac = if (phase.segSec <= 0.0) 0f else (phase.remainingSec / phase.segSec).toFloat().coerceIn(0f, 1f)
    if (frac > 0.01f) {
        drawRoundRect(
            (if (phase.isYellow) PHASE_AMBER else PHASE_GREEN).copy(alpha = alpha),
            topLeft = Offset(x + pad, barY),
            size = Size(barW * frac, barH),
            cornerRadius = radius,
        )
    }
}

/** 画一个指向 +x（flip=false）或 -x（flip=true）的三角形，朝向由外层 rotate 控制 */
private fun DrawScope.drawTriangle(center: Offset, size: Float, color: Color, flip: Boolean) {
    val dir = if (flip) -1f else 1f
    val path = Path().apply {
        moveTo(center.x + size * 0.75f * dir, center.y)
        lineTo(center.x - size * 0.45f * dir, center.y - size * 0.62f)
        lineTo(center.x - size * 0.45f * dir, center.y + size * 0.62f)
        close()
    }
    drawPath(path, color)
}

// ==================== 路口信号灯 ====================

/**
 * 一盏信号灯：垂直于**来车方向**摆放的灯箱 + 灯色。
 * [SignalLightRender.angleRad] 是该进口的行驶方向，rotate 之后沿局部 y 画灯箱 →
 * 灯箱永远横在车头正前方，任何相交角度（含大角度斜交）朝向都正确（需求 7）。
 * [SignalLightRender.state]：0 = 绿、1 = 黄、2 = 红。
 */
private fun DrawScope.drawSignalLight(sig: SignalLightRender, camera: CameraState) {
    val c = camera.worldToScreen(sig.x, sig.y)
    val r = (1.15 * camera.scalePxPerM).toFloat().coerceIn(2.6f, 7.5f)
    // P9：屏幕外的灯不画（灯箱半高 r*1.9 再加灯圈）
    if (!onScreen(c, r * 3f)) return
    val color = when (sig.state) {
        0 -> SIGNAL_GREEN
        1 -> SIGNAL_YELLOW
        else -> SIGNAL_RED
    }
    val deg = Math.toDegrees(sig.angleRad).toFloat()
    rotate(deg, c) {
        // 灯箱：一小段深色横条，垂直于行驶方向（local y = 横向）
        val halfLen = r * 1.9f
        drawLine(
            Color(0xFF3A3A38),
            Offset(c.x, c.y - halfLen),
            Offset(c.x, c.y + halfLen),
            strokeWidth = r * 2.1f,
            cap = StrokeCap.Round,
        )
        drawCircle(Color.White, radius = r + 0.9f, center = c)
        drawCircle(color, radius = r, center = c)
    }
}

// ==================== 停止线 ====================

/**
 * 一条停止线：白色粗横线，垂直于该进口的行驶方向，画在路口净空半径处。
 * 让"红灯 / 让行的车停在线上、绝不越线"这件事肉眼可见（需求 1）。
 */
private fun DrawScope.drawStopLine(sl: StopLineRender, camera: CameraState, palette: SimPalette) {
    val c = camera.worldToScreen(sl.x, sl.y)
    val half = (sl.halfWidthM * camera.scalePxPerM).toFloat()
    if (half < 3f) return                       // 太小就不画，避免糊成一片
    // P9：屏幕外的停止线直接跳过（half 就是它的长度）
    if (!onScreen(c, half + 8f)) return
    val deg = Math.toDegrees(sl.angleRad).toFloat()
    val w = (0.5 * camera.scalePxPerM).toFloat().coerceIn(1.5f, 4.5f)
    rotate(deg, c) {
        // rotate 后局部 +x 是行驶方向、局部 +y 是"来车侧"（即本车的车道所在半幅），
        // 因此停止线从道路中线横跨到本侧路缘，只覆盖**进口方向**的车道（不是整条路）
        drawLine(
            palette.stopLine,
            Offset(c.x, c.y),
            Offset(c.x, c.y + half),
            strokeWidth = w,
            cap = StrokeCap.Butt,
        )
    }
}

// ==================== 编辑模式的路口红绿灯标记 ====================

/**
 * 编辑模式下，在"当前有红绿灯"的路口旁画一个小小的三灯图标。
 *
 * **位置（需求 7）**：不再固定放在路口右上角（斜交时会压在路面上），而是取
 * [RoadNetwork.signalMarkerPos]——沿"最空扇区"方向放到路口外侧，任何相交角度都不压路面；
 * 命中检测用的是同一个函数，所以所见即所点（需求 6）。
 *
 * **尺寸（需求 5）**：灯箱尺寸按**世界尺寸 × 相机缩放**计算（不是固定像素）→ 放大画面时
 * 图标一起变大、缩小画面时一起变小，与路网**同步缩放**；同时整体比原来放大约 1.3 倍，
 * 更好看清、也更好点中。外移距离同样用世界常量 [SimulatorViewModel.SIGNAL_GAP_M]。
 */
private fun DrawScope.drawSignalMarkers(vm: SimulatorViewModel, camera: CameraState, selectedNodeId: Long?) {
    val gapM = SimulatorViewModel.SIGNAL_GAP_M
    // 灯箱外高 6.0m：既"随路网缩放"，又给上下限兜底（缩到极小不至于看不见、放到极大不至于糊屏）
    val h = (6.0 * camera.scalePxPerM).toFloat().coerceIn(14f, 48f)
    val w = h * 0.42f
    for (node in vm.network.nodes.values) {
        if (!vm.hasSignal(node.id)) continue
        val mp = vm.network.signalMarkerPos(node.id, gapM) ?: continue
        val c = camera.worldToScreen(mp.x, mp.y)
        // P9：灯箱高度 h 就是它的外扩半径（箱体本身 + 白色描边 + 选中圈）
        if (!onScreen(c, h)) continue
        if (node.id == selectedNodeId) {
            drawCircle(SIGNAL_GREEN, radius = h * 0.78f, center = c, style = Stroke(width = 2f))
        }
        drawRoundRect(
            Color.White,
            topLeft = Offset(c.x - w / 2f - 1.6f, c.y - h / 2f - 1.6f),
            size = Size(w + 3.2f, h + 3.2f),
            cornerRadius = CornerRadius(h * 0.16f),
        )
        drawRoundRect(
            Color(0xFF3A3A38),
            topLeft = Offset(c.x - w / 2f, c.y - h / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(h * 0.12f),
        )
        val r = w * 0.30f
        drawCircle(SIGNAL_RED, radius = r, center = Offset(c.x, c.y - h * 0.28f))
        drawCircle(SIGNAL_YELLOW, radius = r, center = Offset(c.x, c.y))
        drawCircle(SIGNAL_GREEN, radius = r, center = Offset(c.x, c.y + h * 0.28f))
    }
}

// ==================== 选中道路的端点拖拽手柄（需求 2）====================

/**
 * 选中一条道路时，在它**两端**各画一个可拖拽手柄（白底 + 紫色描边圆点）。
 * 按住手柄拖动 → 该端点跟着走，相接的所有道路自动跟随（等于"移动道路的位置"）。
 * 手柄半径固定 13px（屏幕尺寸）——它是**操作点**而不是路网元素，不随缩放变化反而更好按。
 */
private fun DrawScope.drawEdgeHandles(vm: SimulatorViewModel, camera: CameraState, selectedRoadId: Long?) {
    if (selectedRoadId == null) return
    val e = vm.network.edge(selectedRoadId) ?: return
    for (nid in longArrayOf(e.from, e.to)) {
        val n = vm.network.node(nid) ?: continue
        val c = camera.worldToScreen(n.x, n.y)
        drawCircle(Color.White, radius = 13f, center = c)
        drawCircle(ACCENT, radius = 13f, center = c, style = Stroke(width = 2.6f))
        drawCircle(ACCENT, radius = 4.6f, center = c)
    }
}

// ==================== 画路预览 ====================

private fun DrawScope.drawPreview(
    network: RoadNetwork,
    start: Vector2?,
    current: Vector2?,
    drawTool: SimulatorViewModel.DrawTool,
    ready: Boolean,
    camera: CameraState,
) {
    if (start == null) return
    val curvature = if (drawTool.curve) SimulatorViewModel.DEFAULT_CURVE else 0.0
    val end = current ?: start

    // 预览道路：虚线 + 半透明（与最终道路明显区分）；宽度反映所选车道数。
    // 路径由 RoadNetwork.roadPathFor 生成——与抬手后的实际生成**同一套规则**（含曲线匝道相切），
    // 因此拖拽看到的形状就是最终生成的形状。
    if (end.distanceTo(start) > 1.0) {
        val preview = network.roadPathFor(start, end, curvature)
        val poly = preview.sample(if (preview is RoadPath.Straight) 4 else 24)
        val path = Path()
        for (i in poly.indices) {
            val p = poly[i]
            val s = camera.worldToScreen(p.x, p.y)
            if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
        }
        val lanes = drawTool.lanes.coerceAtLeast(1)
        drawPath(
            path, ACCENT,
            style = Stroke(
                width = (2.0 * RoadNetwork.LANE_WIDTH_M * lanes * camera.scalePxPerM * 0.7f).toFloat().coerceAtLeast(3f),
                cap = StrokeCap.Round,
                // 提为模块常量：`PathEffect` 不可变，一份就够全项目共用。
                // 旧写法在这里每帧新建一个 PathEffect + 一个 FloatArray（还带原生句柄）。
                pathEffect = PREVIEW_DASH,
            ),
            alpha = 0.55f,
        )
    }

    // 起点 / 临时终点：同心圆（略放大，起终点看得更清楚）
    concentric(camera.worldToScreen(start.x, start.y), 13f, 6.5f, ACCENT)
    if (ready) concentric(camera.worldToScreen(end.x, end.y), 13f, 6.5f, ACCENT.copy(alpha = 0.75f))
}

private fun DrawScope.concentric(center: Offset, outer: Float, inner: Float, color: Color) {
    drawCircle(color, radius = outer, center = center, style = Stroke(width = 2f))
    drawCircle(color, radius = inner, center = center)
}

// ==================== 曲率手柄 ====================

private fun DrawScope.drawCurveHandle(vm: SimulatorViewModel, camera: CameraState, selectedRoadId: Long?) {
    if (selectedRoadId == null) return
    val e = vm.network.edge(selectedRoadId) ?: return
    val path = vm.network.pathOf(e) ?: return
    if (e.curvature == 0.0) return
    val handle = path.chordMid + path.chordNormal * path.sagitta
    val hs = camera.worldToScreen(handle.x, handle.y)
    val ms = camera.worldToScreen(path.chordMid.x, path.chordMid.y)
    drawLine(ACCENT.copy(alpha = 0.35f), ms, hs, strokeWidth = 1.5f)
    drawCircle(Color.White, radius = 12f, center = hs)
    drawCircle(ACCENT, radius = 12f, center = hs, style = Stroke(width = 2.5f))
    drawCircle(ACCENT, radius = 4.5f, center = hs)
}

// ==================== 聚焦车辆的前方路线（需求 4）====================

/**
 * 把「已规划的前方路线」画成一串**向前箭头构成的虚线**。
 *
 * 为什么用箭头而不是普通虚线：箭头自带方向，一眼就能读出"这辆车接下来要往哪拐"；
 * 而普通虚线拐弯时看不出左右转。
 *
 * 三条硬要求（都对应需求原文）：
 *  1. **只在车前方**：[path] 由 `TrafficSimulator.pathAhead` 生成，从车头位置往后串，
 *     车后面一个点都没有 —— 所以箭头永远不会出现在车尾之后；
 *  2. **随手变道 / 换路更新**：路径每帧按当前车道与最新路由重算（见 `pathAhead`），
 *     车身一变道，箭头立刻跟着平移，不需要任何额外刷新逻辑；
 *  3. **画在车之下**：调用方在 `drawVehicles` 之前调用，箭头被车身压住一截，
 *     视觉上就是"从车头伸出去的路"。
 *
 * 箭头按**屏幕弧长**等距排布（不按世界坐标），所以缩小画面时箭头不会挤成一片、
 * 放大时也不会稀疏到看不出是一条线。
 *
 * **本轮需求 4 修的两处**：
 *  1. 间距 `len × 1.55` → `len × 2.6`。原来箭头几乎首尾相接，看起来像一条毛毛虫；
 *  2. 箭头朝向改为"以该点为中心取一小段弧"的整体走向（见下面的 `win`），
 *     以前是逐段取弦向 —— 弯道上每一段的弦向都不一样，箭头就会东一个西一个，
 *     连起来像是"杂乱的折线"。改用窗口方向后，同一段弯道上的箭头会连续转过去，是一条弧。
 */
private fun DrawScope.drawRouteGuide(
    path: List<Vector2>,
    camera: CameraState,
    /**
     * 整条折线的**世界坐标**平移量（米）。
     *
     * 折线是从车身当前快照位置开始采样的，所以它必须跟着"车的插值位置"一起挪：
     * 差值只有几十分之一米，肉眼完全看不出来，却能把"每 16ms 跳一格"变成逐帧滑动。
     * 由于采样起点就是车的位置、步长恒定，整条折线是**刚性平移**的，
     * 平移之后形状与采样相位都不变（不会出现"箭头沿路抖动"）。
     */
    offX: Double = 0.0,
    offY: Double = 0.0,
) {
    if (path.size < 2) return

    // ---- ① 折线 → 屏幕坐标 + 累计弧长（顺手丢掉重合点）----
    // 重合点会让"单位方向"变成 NaN / 乱指，是箭头乱飞的头号元凶，直接在这里滤掉。
    val xs = ArrayList<Float>(path.size)
    val ys = ArrayList<Float>(path.size)
    val acc = ArrayList<Float>(path.size)
    var total = 0f
    for (p in path) {
        val sp = camera.worldToScreen(p.x + offX, p.y + offY)
        if (xs.isNotEmpty()) {
            val ddx = sp.x - xs[xs.size - 1]
            val ddy = sp.y - ys[ys.size - 1]
            val d = sqrt(ddx * ddx + ddy * ddy)
            if (d < 0.35f) continue
            total += d
        }
        xs.add(sp.x)
        ys.add(sp.y)
        acc.add(total)
    }
    if (xs.size < 2 || total < 1f) return

    // ---- ② 按弧长取点 ----
    fun indexAt(d: Float): Int {
        var lo = 0
        var hi = xs.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (acc[mid] < d) lo = mid + 1 else hi = mid
        }
        return lo
    }
    fun pointAt(d: Float): Offset {
        val i = indexAt(d)
        if (i <= 0) return Offset(xs[0], ys[0])
        val d0 = acc[i - 1]
        val d1 = acc[i]
        val t = if (d1 - d0 < 0.0001f) 0f else ((d - d0) / (d1 - d0)).coerceIn(0f, 1f)
        return Offset(xs[i - 1] + (xs[i] - xs[i - 1]) * t, ys[i - 1] + (ys[i] - ys[i - 1]) * t)
    }

    // 箭头尺寸跟着缩放走，但有上下限：太小学不清、太大糊满屏
    val len = (2.4 * camera.scalePxPerM).toFloat().coerceIn(9f, 20f)
    val halfW = len * 0.44f
    val spacing = len * 2.6f         // 箭头中心间距（变大 → 更稀疏、更像"虚线"）
    // 方向窗口 = 半个间距：小了等于没平滑，大了弯道会被抹成一根弦
    val win = spacing * 0.5f

    // 虚线会一直画到目标路口（可能几公里），屏幕外的箭头直接跳过 ——
    // 不做剔除的话，长路线每帧要发上千次 drawPath，白白拖慢帧率。
    val margin = len * 2f
    val w = size.width
    val h = size.height
    // 复用同一个 Path：一条路线上有几十个箭头，每帧各建一个就是几十次 Skia 对象分配
    val arrowPath = Path()
    var d = len * 0.6f               // 第一个箭头从车头前方一点点开始（不压在车头上）
    while (d < total) {
        val p = pointAt(d)
        if (p.x >= -margin && p.y >= -margin && p.x <= w + margin && p.y <= h + margin) {
            val a = pointAt((d - win).coerceAtLeast(0f))
            val b = pointAt((d + win).coerceAtMost(total))
            val vx = b.x - a.x
            val vy = b.y - a.y
            val vl = sqrt(vx * vx + vy * vy)
            if (vl > 0.001f) drawGuideArrow(p.x, p.y, vx / vl, vy / vl, len, halfW, arrowPath)
        }
        d += spacing
    }
}

/**
 * 单个方向箭头：一个"V"字形（两条后掠的边 + 内凹的尾部），指向 ([dx],[dy])。
 * 先用白色描边打底再填色 —— 路面是灰色，单色箭头在灰度接近时会糊掉看不见。
 *
 * [path] 由调用方复用（`rewind()` 后重录）；Skia 会在路径被改写时把已录制的内容拷出去，
 * 所以与每帧新建一个 Path 的画面完全一致。
 */
private fun DrawScope.drawGuideArrow(
    x: Float, y: Float, dx: Float, dy: Float, len: Float, halfW: Float, path: Path,
) {
    val nx = -dy
    val ny = dx
    val tipX = x + dx * len * 0.5f
    val tipY = y + dy * len * 0.5f
    val backX = x - dx * len * 0.5f
    val backY = y - dy * len * 0.5f
    val notchX = x - dx * len * 0.16f
    val notchY = y - dy * len * 0.16f
    path.rewind()
    path.moveTo(tipX, tipY)
    path.lineTo(backX + nx * halfW, backY + ny * halfW)
    path.lineTo(notchX, notchY)
    path.lineTo(backX - nx * halfW, backY - ny * halfW)
    path.close()
    drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(width = 2.6f, join = StrokeJoin.Round))
    drawPath(path, ROUTE_GUIDE_COLOR)
}

// ==================== 车辆 ====================

/** 拥堵染色的两档目标色：轻堵偏琥珀、重堵偏红（都刻意压低了饱和度，避免整屏刺眼） */
private val JAM_WARN = Color(0xFFE0A03C)
private val JAM_HEAVY = Color(0xFFD0564C)

/**
 * 车身颜色 = **车型本色 → 琥珀 → 红** 的两段插值（需求：一眼看出堵在哪）。
 *
 * 为什么分段而不是一条连续渐变：真实感知里"还能慢慢走"和"完全停死"是两种不同状态，
 * 用两个锚点（0.6 处切到警示红）能让轻微排队与死锁在颜色上明显区分。
 *
 * 混合强度上限 0.9（不是 1.0）：全部染成一个颜色就分不清车型了，
 * 留一成本色既保留辨识度，又不影响"红 = 堵"的直觉。
 */
private fun jamTint(colorHex: Long, congestion: Float, alpha: Float): Color {
    val base = Color(colorHex)
    val c = congestion.coerceIn(0f, 1f)
    if (c < 0.03f) return base.copy(alpha = alpha)
    val target: Color
    val k: Float
    if (c < 0.6f) {
        target = JAM_WARN
        k = c / 0.6f
    } else {
        target = JAM_HEAVY
        k = (c - 0.6f) / 0.4f
    }
    return lerp(base, target, k * 0.9f).copy(alpha = alpha)
}


/**
 * **需求 2：聚焦车辆底部的闪烁光圈**。
 *
 * 三条要求逐一对应：
 *  1. **在车底**：调用方在 `drawVehicles` 之前调用，所以车身会盖住光圈中心 ——
 *     看到的是"车压在一个发光环上"，而不是被一个框套住；
 *  2. **闪烁**：[pulse] 由界面时钟以 760ms 为一拍往复（见 `rememberInfiniteTransition`），
 *     叠出"半径轻微呼吸 + 亮度明显明暗"两层动效，走到哪儿都一眼能找到；
 *  3. **大小合适**：半径按**车长**取（`车长 × 0.78`，最小 3.2m），小车大车都刚好被整圈包住。
 *
 * 光圈是纯视觉元素，不参与任何仿真判定。
 *
 * [anchor] 非空且正好锚着这辆车时，画在锚点上而不是快照位置上：
 * 锁定跟随的相机是按锚点居中的，光圈若用快照位置会与车错开半个快照（约 2~3px，
 * 在大光圈上表现为"边缘轻微游移"）。
 */
private fun DrawScope.drawFocusRing(
    f: FocusRender,
    camera: CameraState,
    pulse: Float,
    anchor: FollowAnchor? = null,
    /** 与 [drawRouteGuide] 同源的插值平移量（米）：光圈必须套在**画出来的那辆车**上 */
    offX: Double = 0.0,
    offY: Double = 0.0,
) {
    val c = if (anchor != null && anchor.anchorId == f.id) {
        camera.worldToScreen(anchor.anchorX.toDouble(), anchor.anchorY.toDouble())
    } else {
        camera.worldToScreen(f.x + offX, f.y + offY)
    }
    val worldR = max(3.2, f.lengthM * 0.78)
    val baseR = (worldR * camera.scalePxPerM).toFloat().coerceIn(16f, 240f)
    val p = pulse.coerceIn(0f, 1f)
    val r = baseR * (0.94f + 0.10f * p)              // 半径 ±5% 的呼吸
    val a = 0.32f + 0.58f * p                        // 亮度 0.32 → 0.90 的明暗
    // ① 外层柔光：让光圈在浅灰路面上也跳出来
    drawCircle(ACCENT, radius = r * 1.26f, center = c, alpha = 0.09f + 0.09f * p)
    // ② 主环（粗）+ 内侧白色高光（细）：像是"发光"而不是"画了个圈"
    drawCircle(ACCENT, radius = r, center = c, alpha = a, style = Stroke(width = 4.2f))
    drawCircle(Color.White, radius = r, center = c, alpha = 0.50f * p, style = Stroke(width = 1.5f))
}


/**
 * 画所有车辆。
 *
 * 夜景按"地面投影 → 前灯光斑 → 车身 → 尾灯"的次序画，
 * 于是光斑落在地面上、车身压住光束根部、尾灯最后点在车尾 —— 和真实观感一致。
 * 白天（[DayLight.night] ≈ 0）时车灯相关的几次绘制都会在内部直接返回，只多一次判断。
 *
 * **投影**（本轮需求 1）用和楼、树同一份 [DayLight]：方向一致、长度一起变，
 * 所以傍晚整条街的影子是"一起倒向同一边"的；夜里影子浓度趋近于 0，不会拖一地黑块。
 *
 * ## [anchor]：只影响一辆车
 * 锁定跟随时，被聚焦那辆车改用 [FollowAnchor] 的插值位置（与相机完全同源），
 * 于是它牢牢钉在屏幕固定点；**其余车辆一律保持原样**按快照位置画，不引入任何行为变化。
 * 锚点未激活（`anchorId == 0`）时这段判断恒为假，开销可以忽略。
 */
private fun DrawScope.drawVehicles(
    vehicles: List<VehicleRender>,
    /**
     * 上一份快照的车辆表（按 id 索引）。与 [k] 一起把"每 16ms 跳一格"变成逐帧滑动。
     * 查不到（新车刚生成 / 旧车已到站）就直接用当前值 —— 那本来就该是"出现/消失"。
     */
    prev: Map<Long, VehicleRender>,
    /** 插值比例：[FrameMixer.k]，0 = 上一份快照，1 = 当前快照 */
    k: Float,
    camera: CameraState,
    simple: Boolean,
    palette: SimPalette,
    light: DayLight,
    anchor: FollowAnchor? = null,
    /**
     * 车灯光束用的**可复用 Path**（P7）。调用方 `remember` 一份传进来即可：
     * 以前每辆车每帧都要 `Path()` 四次（左右两侧 × 内外两层），
     * 一百辆车 120fps 就是每秒近 5 万个 Skia Path 对象 —— 全是 GC 的活。
     * 复用后同一个 Path 每次 `rewind()` 再录，Skia 的录制器会在路径被改写时自己拷贝，
     * 所以画面逐像素不变。
     */
    beamPath: Path,
) {
    val scale = camera.scalePxPerM
    val beamPx = (BEAM_M * scale).toFloat()
    val night = light.night
    val nightOn = night > 0.03f
    // 车辆投影的偏移：车高约 1.6m，投影长度按它折算（再乘上当天影子的倍数）
    val shadowOffM = 1.6 * light.shadowLen
    val shadowOff = Offset(
        (shadowOffM * light.shadowDx * scale).toFloat(),
        (shadowOffM * light.shadowDy * scale).toFloat(),
    )
    val shadowAlpha = 0.16f * light.shadowAlpha
    // 每帧只读一次锚点（两个 Float 状态 + 一个 Long 状态，命中就一辆车）
    val anchorId = if (anchor != null) anchor.anchorId else 0L
    val anchorX = if (anchor != null) anchor.anchorX else 0f
    val anchorY = if (anchor != null) anchor.anchorY else 0f
    // 插值参数每帧只取一次；没有上一份快照（刚开始跑 / 刚恢复）时 t 恒为 1 → 直接用当前值
    val t = if (k >= 1f) 1.0 else k.toDouble()
    val interpolate = t < 1.0 && prev.isNotEmpty()
    for (v in vehicles) {
        // ---- 帧时钟插值：位置、朝向、透明度都取中间值 ----
        // 只插值"两份快照都有的车"：新车第一帧直接出现在原地（不该从屏幕外滑进来）
        val p = if (interpolate) prev[v.id] else null
        val vx = if (p != null) p.x + (v.x - p.x) * t else v.x
        val vy = if (p != null) p.y + (v.y - p.y) * t else v.y
        val vAng = if (p != null) lerpAngle(p.angleRad, v.angleRad, t) else v.angleRad
        val vAlpha = if (p != null) {
            p.alpha + (v.alpha - p.alpha) * k
        } else {
            v.alpha
        }

        val w = (VehicleKind.WIDTH_M * scale).toFloat().coerceAtLeast(2.5f)   // 车宽 → 局部 y
        val l = (v.lengthM * scale).toFloat().coerceAtLeast(4f)                // 车长 → 局部 x
        val center = if (v.id == anchorId) {
            camera.worldToScreen(anchorX.toDouble(), anchorY.toDouble())
        } else {
            camera.worldToScreen(vx, vy)
        }
        // P9：屏幕外的车连它的投影 / 光斑 / 尾灯一起跳过。余量要算上**车灯光束的长度**，
        // 否则会出现"车还在屏幕外、光却打进来了"（光斑长 8m，比车身长得多）。
        if (!onScreen(center, l + beamPx)) continue
        val alpha = vAlpha.coerceIn(0f, 1f)
        val deg = Math.toDegrees(vAng).toFloat()
        // 车身颜色 = 车型本色 → 拥堵染色，再按夜色整体压暗一点（夜间车身不该比路灯还亮）
        val base = jamTint(v.colorHex, v.congestion, alpha)
        val color = if (palette.vehicleDim > 0f) lerp(base, Color(0xFF232830), palette.vehicleDim) else base

        // ① 地面投影：比车身更先画（车身压住它的大半，只露一圈）
        if (!simple && shadowAlpha > 0.01f) {
            drawVehicleShadow(center, l, w, deg, shadowOff, shadowAlpha * alpha)
        }

        // ② 地面光斑：画在车身之前，被车头压住根部
        if (nightOn && !simple) drawHeadBeam(center, l, w, deg, beamPx, night, beamPath)

        // 车长沿局部 x 轴 = 行驶方向（rotate 把局部 +x 对齐切线），这样车头才朝着前进方向
        rotate(deg, center) {
            if (simple) {
                // 简化渲染：平角矩形，省性能
                drawRect(
                    color,
                    topLeft = Offset(center.x - l / 2f, center.y - w / 2f),
                    size = Size(l, w),
                )
            } else {
                // 车型细节：轿车 / 中巴 / 大货各画各的（座舱玻璃、侧窗、货箱棱线……）
                // 见 VehicleDraw.kt —— 尺寸全按车长车宽的比例算，任何缩放下比例都对。
                drawVehicleBody(v.kind, center, l, w, color, alpha, night)
            }
        }

        // ④ 尾灯：画在车身之后，车尾两个红点
        if (nightOn && !simple) drawTailLights(center, l, w, deg, night)
    }
}

/**
 * 堵塞路口警示：
 *  - 尚未崩溃（只是暂时堵住）：红色**脉动**光圈，跟随仿真时间呼吸；
 *  - 已崩溃：切换为**闪烁**（亮/暗交替），且不依赖冻结的仿真时间，由界面时钟驱动。
 */
private fun DrawScope.drawBlockedHalo(
    nodeX: Double,
    nodeY: Double,
    camera: CameraState,
    simTime: Double,
    crashed: Boolean,
    blinkOn: Boolean,
    alphaMul: Float,
) {
    val m = alphaMul.coerceIn(0f, 1f)
    val c = camera.worldToScreen(nodeX, nodeY)
    val radius = max(16f, (5.0 * camera.scalePxPerM).toFloat())
    // P9：屏幕外的警示圈不画（半径最大会呼吸到 1.1 倍，再留一点描边余量）
    if (!onScreen(c, radius * 1.6f)) return
    if (crashed) {
        // 闪烁：亮时清晰、暗时只留一点余晖，做到"一眼看到是哪个路口"
        val a = (if (blinkOn) 1f else 0.15f) * m
        drawCircle(DANGER_COLOR, radius = radius * 1.1f, center = c, alpha = 0.22f * a)
        drawCircle(
            DANGER_COLOR, radius = radius, center = c,
            style = Stroke(width = if (blinkOn) 3.2f else 1.6f), alpha = a,
        )
    } else {
        val pulse = (0.5 + 0.5 * sin(simTime * 4.0)).toFloat()
        val r = radius * (0.85f + 0.25f * pulse)
        drawCircle(DANGER_COLOR, radius = r, center = c, alpha = (0.18f + 0.14f * pulse) * m)
        drawCircle(DANGER_COLOR, radius = r, center = c, style = Stroke(width = 2f), alpha = 0.8f * m)
    }
}
