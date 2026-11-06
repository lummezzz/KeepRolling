package com.keeprolling.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.keeprolling.app.audio.BgmTrack
import com.keeprolling.app.audio.Sfx
import com.keeprolling.app.audio.SoundCenter
import com.keeprolling.app.model.FocusRender
import com.keeprolling.app.model.JunctionConfig
import com.keeprolling.app.model.NodeType
import com.keeprolling.app.model.RoadNetwork
import com.keeprolling.app.model.RoadPath
import com.keeprolling.app.model.SimSettings
import com.keeprolling.app.model.SimSnapshot
import com.keeprolling.app.model.SignalLightRender
import com.keeprolling.app.model.SignalPhaseRender
import com.keeprolling.app.model.SimStats
import com.keeprolling.app.model.StopLineRender
import com.keeprolling.app.model.TrafficSimulator
import com.keeprolling.app.model.Vector2
import com.keeprolling.app.model.VehicleRender
import com.keeprolling.app.storage.NetworkRepository
import com.keeprolling.app.storage.SavedNetwork
import com.keeprolling.app.ui.DayCycle
import com.keeprolling.app.ui.DayMode
import com.keeprolling.app.ui.i18n.Lang
import com.keeprolling.app.ui.i18n.Messages
import com.keeprolling.app.ui.i18n.StrKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 每帧渲染快照 */
data class RenderFrame(
    val vehicles: List<VehicleRender>,
    val stats: SimStats,
    val simTime: Double,
    /** 路口信号灯灯位（十字 / 丁字路口自动配置；无信号灯时为空） */
    val signals: List<SignalLightRender> = emptyList(),
    /** 路口进口的停止线（画白色横线，直观展示"车停在线上、不越线"） */
    val stopLines: List<StopLineRender> = emptyList(),
    /** 正在聚焦的车辆（信息框 + 前方虚线路径）；未聚焦为 null */
    val focus: FocusRender? = null,
    /**
     * 正在编辑的那盏信号灯的**当前相位**（选中 SIGNAL 态路口时才有值）。
     * 与车辆位置、灯色同一帧产出，界面上那条相位条不会和灯的实际颜色打架。
     */
    val phase: SignalPhaseRender? = null,
)

/**
 * 主 ViewModel（v2）。
 *
 * 界面状态机：
 *   VIEW（主界面）：底部 开始/暂停 · 编辑 · 设置；可实时点开放路口调参
 *   EDIT（编辑模式）：浅灰底，底部 道路/返回；画路、删除、开放/封闭、调曲率
 *
 * 仿真状态机（VIEW 内）：
 *   IDLE → RUNNING ⇄ PAUSED；任一路口堵塞超阈值 → CRASHED（非遮挡横幅）
 */
class SimulatorViewModel(app: Application) : AndroidViewModel(app) {

    enum class UiMode { VIEW, EDIT }
    enum class SimState { IDLE, RUNNING, PAUSED, CRASHED }

    /**
     * 画路工具（本轮新增多车道）：
     *  [curve] 是否曲线；[lanes] 每方向车道数（1/2/3）。
     *  NONE 表示未选择任何道路工具。
     */
    enum class DrawTool(val curve: Boolean, val lanes: Int) {
        NONE(false, 1),
        LINE(false, 1),
        CURVE(true, 1),
        LINE2(false, 2),
        CURVE2(true, 2),
        LINE3(false, 3),
        CURVE3(true, 3);

        /** 是否已选中具体道路类型（用于 canDraw 判定） */
        val isRoad: Boolean get() = this != NONE
    }

    /**
     * 编辑模式的选中对象：
     *  NONE 未选中 · ROAD 道路 · DEAD_END 断头路（封闭口）· OPEN 开放路口
     *  · SIGNAL 有红绿灯的路口（点灯位/路口 → 出现「删除红绿灯」）
     *  · NO_SIGNAL 无红绿灯的十字/丁字路口（→ 出现「添加红绿灯」）
     */
    enum class Selection { NONE, ROAD, DEAD_END, OPEN, SIGNAL, NO_SIGNAL }

    /** 曲线默认曲率（用户拖出手柄后调整；画布预览也用它，保证预览即所得） */
    private val defaultCurve = DEFAULT_CURVE
    private val undoLimit = 40

    // ==================== 数据 ====================

    var network: RoadNetwork = RoadNetwork()
        private set
    val junctionConfigs = mutableMapOf<Long, JunctionConfig>()
    var settings: SimSettings = SimSettings()
        private set

    // ==================== UI 状态 ====================

    private val _uiMode = MutableStateFlow(UiMode.VIEW)
    val uiMode: StateFlow<UiMode> = _uiMode

    private val _simState = MutableStateFlow(SimState.IDLE)
    val simState: StateFlow<SimState> = _simState

    private val _drawTool = MutableStateFlow(DrawTool.NONE)
    val drawTool: StateFlow<DrawTool> = _drawTool

    private val _frame = MutableStateFlow<RenderFrame?>(null)
    val frame: StateFlow<RenderFrame?> = _frame

    private val _networkVersion = MutableStateFlow(0)
    val networkVersion: StateFlow<Int> = _networkVersion

    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val _selection = MutableStateFlow(Selection.NONE)
    val selection: StateFlow<Selection> = _selection

    private val _selectedRoadId = MutableStateFlow<Long?>(null)
    val selectedRoadId: StateFlow<Long?> = _selectedRoadId

    private val _selectedNodeId = MutableStateFlow<Long?>(null)
    val selectedNodeId: StateFlow<Long?> = _selectedNodeId

    // 画路过程：起点（长按就绪）→ 预览（跟手）→ 抬手生成
    private val _drawReady = MutableStateFlow(false)
    val drawReady: StateFlow<Boolean> = _drawReady
    private val _drawStart = MutableStateFlow<Vector2?>(null)
    val drawStart: StateFlow<Vector2?> = _drawStart
    private val _drawCurrent = MutableStateFlow<Vector2?>(null)
    val drawCurrent: StateFlow<Vector2?> = _drawCurrent

    // 各浮层
    private val _showSettingsRow = MutableStateFlow(false)
    val showSettingsRow: StateFlow<Boolean> = _showSettingsRow
    private val _showRoadSubmenu = MutableStateFlow(false)
    val showRoadSubmenu: StateFlow<Boolean> = _showRoadSubmenu
    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet
    private val _showSaveDialog = MutableStateFlow(false)
    val showSaveDialog: StateFlow<Boolean> = _showSaveDialog
    private val _showLoadDialog = MutableStateFlow(false)
    val showLoadDialog: StateFlow<Boolean> = _showLoadDialog
    private val _junctionDialogNodeId = MutableStateFlow<Long?>(null)
    val junctionDialogNodeId: StateFlow<Long?> = _junctionDialogNodeId

    /** 「红绿灯设置」对话框正在编辑的路口（null = 未打开） */
    private val _signalRuleDialogNodeId = MutableStateFlow<Long?>(null)
    val signalRuleDialogNodeId: StateFlow<Long?> = _signalRuleDialogNodeId

    // ==================== 车辆聚焦（本轮需求 4）====================

    /** 正在聚焦的车辆 id（null = 没有信息框） */
    private val _focusedVehicleId = MutableStateFlow<Long?>(null)
    val focusedVehicleId: StateFlow<Long?> = _focusedVehicleId

    /** 是否**锁定跟随**（相机每帧居中到该车）；手动缩放 / 拖动后自动解除，但信息框保留 */
    private val _followLocked = MutableStateFlow(false)
    val followLocked: StateFlow<Boolean> = _followLocked

    /**
     * 聚焦请求序号：每次「点车辆」或「点信息框」都 +1。
     * 画布监听它的变化来执行一次"缩放 + 居中"，避免每帧重复设置相机。
     */
    private val _focusEpoch = MutableStateFlow(0)
    val focusEpoch: StateFlow<Int> = _focusEpoch

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    /** 重做是否可用（撤销之后才可重做） */
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    /** 右上角统计面板开关（运行时间 + 车辆数） */
    private val _showStats = MutableStateFlow(false)
    val showStats: StateFlow<Boolean> = _showStats

    /** 清空确认对话框 */
    private val _showClearConfirm = MutableStateFlow(false)
    val showClearConfirm: StateFlow<Boolean> = _showClearConfirm

    /** 倍速横向圆形滑块是否展开（点倍速按钮打开，点屏幕任意处确认收起） */
    private val _speedDialOpen = MutableStateFlow(false)
    val speedDialOpen: StateFlow<Boolean> = _speedDialOpen

    /** 自主规划预设菜单是否展开（出现在按钮右侧的白色卡片） */
    private val _rerouteMenuOpen = MutableStateFlow(false)
    val rerouteMenuOpen: StateFlow<Boolean> = _rerouteMenuOpen

    /** 界面语言（需求 5）：随设置持久化，默认中文 */
    private val _lang = MutableStateFlow(Lang.ZH)
    val lang: StateFlow<Lang> = _lang

    /**
     * 语言二选一菜单是否展开（本轮需求 2）。
     *
     * 交互与昼夜按钮完全一致：点一下在按钮**右侧**展开「中文 / English」两个并排圆钮，
     * 选中任一个即收起。所以它需要单独一个开关，不能只靠 [lang] —— 语言没变时
     * （重复点同一个）也要能收起菜单。
     */
    private val _langMenuOpen = MutableStateFlow(false)
    val langMenuOpen: StateFlow<Boolean> = _langMenuOpen

    /**
     * **夜间模式**（本轮需求 1 升级为三态：白天 / 夜间 / 自动循环）。
     *
     * 它是一个 `StateFlow` 而不是直接读 [settings]，因为 Compose 需要被它驱动重组；
     * `settings.dayMode` 负责存盘 / 进存档 / 跨重启恢复，两者永远在 [setDayMode] 里一起更新。
     *
     * 与旧版 `nightMode: Boolean` 的关系：旧存档没有 `dayMode` 字段，读取时按 `nightMode`
     * 推断一次（见 [resolveDayMode]），所以老用户升级后状态不会变。
     */
    private val _dayMode = MutableStateFlow(DayMode.DAY)
    val dayMode: StateFlow<DayMode> = _dayMode

    /** 「白天 / 夜间 / 自动循环」三选一菜单是否展开（点那个按钮 → 在它右侧展开三个按钮） */
    private val _dayMenuOpen = MutableStateFlow(false)
    val dayMenuOpen: StateFlow<Boolean> = _dayMenuOpen

    /**
     * 「音频」按钮右侧那两个开关（音乐 / 音效）是否展开。
     *
     * 与昼夜、语言那套完全同构：同一时间只允许一个右侧浮层展开，
     * 因为三个 Popup 都锚在同一列按钮上，叠着会互相压住。
     *
     * 两个开关本身的状态不放这里 —— 它们就是 [SimSettings.musicEnabled] /
     * [SimSettings.sfxEnabled]（要持久化），界面靠 [configVersion] 感知变化。
     */
    private val _audioMenuOpen = MutableStateFlow(false)
    val audioMenuOpen: StateFlow<Boolean> = _audioMenuOpen

    /** 音乐开关的当前值（界面直接读这一份，避免到处 `settings.musicEnabled` 的长链） */
    val musicEnabled: Boolean get() = settings.musicEnabled

    /** 音效开关的当前值 */
    val sfxEnabled: Boolean get() = settings.sfxEnabled

    /**
     * **触觉反馈开关**的 StateFlow 版本。
     *
     * 为什么不直接让界面读 `settings.hapticEnabled`（音乐 / 音效那两个就是那么干的）：
     * 那两个只在**音频浮层**里用，靠 `configVersion` 驱动重组就够；
     * 而触觉要注入到**整棵按钮树**（CompositionLocal），必须有个能单独通知的源 ——
     * 否则每次改别的设置都会把所有按钮重组一遍。
     */
    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled


    /**
     * 界面层用的"夜晚程度" 0~1。
     *
     *  - 手动模式：恒为 0 / 1，靠界面那边 340ms 补间过去；
     *  - 自动模式：按仿真时间推算，由仿真循环**节流**推送（每变化 0.01 才发一次）。
     *
     * 为什么不直接让界面读每一帧的 `frame`：那会把整层界面拖进 60Hz 重组
     * （上一轮刚为此把帧订阅下沉），而天色本来就是个慢变量，8 次/秒绰绰有余。
     */
    private val _dayNight = MutableStateFlow(0f)
    val dayNight: StateFlow<Float> = _dayNight

    /**
     * **「去UI」模式**（本轮需求 4）：true = 所有界面元素淡出，只剩画面。
     *
     * 只影响"看得见什么、点得着什么"，不碰仿真与相机：
     *  - 单指拖动照样平移、双指照样缩放（手势在画布上，本来就不依赖 UI）；
     *  - 但**点车锁定**被禁用（[focusVehicleAt] 直接返回 false）——
     *    连信息框都藏起来了，锁定跟随就失去了意义；
     *  - 单击画布任意位置 → 重新唤回全部界面（见 [setHideUi]）。
     */
    private val _hideUi = MutableStateFlow(false)
    val hideUi: StateFlow<Boolean> = _hideUi

    // ==================== 内部 ====================

    private var simulator: TrafficSimulator? = null
    private var simJob: Job? = null
    private val repository = NetworkRepository(app)

    /**
     * 全局音频中心（63 个音效 + 4 条音乐 + 2 条环境层，全部来自 `res/raw` 的现成素材）。
     *
     * 为什么挂在 ViewModel 上而不是做成全局单例：它的生命周期正好等于"这个界面还活着"，
     * 在 [onCleared] 里一次性释放最干净；也不需要 Context 泄漏的顾虑（内部只拿 applicationContext）。
     * 界面侧直接 `vm.sound` 就能播（左侧胶囊弹入/弹出那两个音效在 composable 里触发）。
     */
    val sound = SoundCenter(app)

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    // ⚠️ 整个类**只能有一个** companion object（Kotlin 语法限制）。
    // 全部常量统一放在文件末尾那唯一的 companion 里，不要再在这里新建。
    // 曾经这里也写了一个 companion（只放 MAX_STEPS_PER_FRAME），导致末尾那个失效，
    // 于是其余成员在别的文件里全部报 "Unresolved reference"。

    /** 自动暂存的防抖任务（频繁改动只落一次盘） */
    private var autoSaveJob: Job? = null

    /**
     * 「推迟切歌」的任务（见 [refreshAudioSceneDeferred]）。
     *
     * 为什么需要它：切 BGM 场景会在主线程**同步**建一个 `MediaPlayer`（5~40ms）。
     * 进入 / 退出编辑模式那一帧同时还背着整棵树的重组，两个重活叠在同一帧就是那一下明显的卡顿。
     * 把它挪到按钮换班演完之后，画面就顺了。
     */
    private var audioSceneJob: Job? = null

    /** 正在被拖拽的道路端点节点（需求 2）；null = 没有拖拽 */
    private var draggingNodeId: Long? = null

    /**
     * 是否正在拖动道路端点（供画布切换"拖动期低细节"渲染）。
     *
     * 拖动时每个指针事件都会让整片路网位移。画布若照常：
     *  · 重算车道标线截断量与路口圆角（O(E·deg) + 每路口 3 轮迭代，还带大量对象分配）；
     *  · 给每条路按 2m 采样重建全部标线路径（几十条路 × 7 条线 × 上百个点）；
     *  · 给每栋楼每棵树画窗光、屋顶设备、树冠高光（上千条绘制指令）；
     * 一帧就是几千次几何计算 —— 这是"拖动明显掉帧"的主因。
     * 拖动期间这几项全部降级（冻结几何 / 8m 采样 / 只画色块），松手立即恢复。
     * 详见 [com.keeprolling.app.ui.SimCanvas] 的 DragSafeGeometry。
     */
    private val _draggingNetwork = MutableStateFlow(false)
    val draggingNetwork: StateFlow<Boolean> = _draggingNetwork

    /**
     * "稳定版"路网版本号：**拖动期间保持不动**，松手才跳到最新值。
     *
     * 左侧按钮组、顶部操作提示这些界面在拖动过程中内容根本不会变
     * （选中对象没变、车道数没变），却因为订阅了 [networkVersion] 而每帧重组一次。
     * 画布仍然用原始的 [networkVersion]（它必须每帧重绘），
     * 其余"只在路网真正定下来才需要更新"的地方一律用这个。
     */
    val networkVersionSettled: StateFlow<Int> =
        combine(_networkVersion, _draggingNetwork) { v, drag -> if (drag) null else v }
            .filterNotNull()
            .stateIn(viewModelScope, SharingStarted.Eagerly, _networkVersion.value)

    /** 本次拖拽是否已经真正移动过（用于"首次移动才记撤销"） */
    private var endpointDragDirty = false

    /** 拖拽起点：节点原坐标 + 手指原世界坐标（按位移增量移动，起手不跳） */
    private var dragAnchorNodePos: Vector2 = Vector2(0.0, 0.0)
    private var dragAnchorWorld: Vector2 = Vector2(0.0, 0.0)

    /** 正在被调节曲率的道路 id（拖动曲率手柄时）；null = 没有在调曲率 */
    private var curvatureDragEdgeId: Long? = null

    /**
     * 上一次 [refreshAudioScene] 时的"白天/夜间"判断结果。
     *
     * 自动昼夜模式下夜色是**连续**变化的，音乐却只能离散切换。用它把"跨越 0.55"这一刻
     * 挑出来：只有真的从白天变到夜里才切歌，而不是每帧都去比较。
     */
    private var lastAudioNight: Boolean = false

    // ---------- BGM 播放列表（第 51 轮：每个场景多首随机循环）----------

    /** 当前播放列表属于哪个场景；`null` = 崩溃余波（单曲，不进列表）或还没开始 */
    private var bgmScene: BgmScene? = null

    /** 这一"袋"曲目的播放顺序（洗过牌）；场景一变就换一袋 */
    private var bgmBag: List<BgmTrack> = emptyList()

    /** 正在播 [bgmBag] 里的第几首 */
    private var bgmIndex: Int = 0

    /**
     * 上一声"车辆到达"响起时的累计到达数。
     *
     * 存的是**计数**而不是时间戳：到达音效要的是"每满 N 辆响一次"，
     * 用时间做间隔会让"车多时更密" —— 那正好是最该安静的时候。
     */
    private var lastArrivedForSfx: Int = 0

    /** 上一帧选中路口的灯色（[PHASE_KEY_GREEN] / [PHASE_KEY_YELLOW] / [PHASE_KEY_RED] / [PHASE_KEY_NONE]） */
    private var lastPhaseKey: Int = PHASE_KEY_NONE

    /** 本次调曲率是否真的改变过曲率（用于"首次改变才记撤销"、以及抬手时决定要不要补路口） */
    private var curvatureDragDirty = false

    /** 曲率手柄最近一次所在的世界坐标（曲线被打断后据此重新选中最近的子路） */
    private var curvatureDragWorld: Vector2 = Vector2(0.0, 0.0)

    // ==================== 自动暂存（需求 2）====================

    init {
        // 启动即恢复上次的路网 / 配置 / 设置——即使上次是被强制关闭的，也能从最后状态接着玩
        val saved = runCatching { repository.loadAuto() }.getOrNull()
        if (saved != null) {
            network = saved.network
            junctionConfigs.clear()
            junctionConfigs.putAll(saved.configs)
            settings = saved.settings
            _showStats.value = settings.showStats
            _networkVersion.value += 1
            _configVersion.value += 1
        }
        // 需求 5：语言随设置恢复（默认中文）
        _lang.value = if (settings.language == "EN") Lang.EN else Lang.ZH
        // 本轮需求 1：昼夜模式随设置恢复（旧存档按 nightMode 推断）
        syncDayModeFromSettings()
        ensureJunctionNumbers()

        // ---------- 声音（本轮新增）----------
        // 旧存档里没有这 4 个字段，`SimSettings` 的默认值会补上（音乐/音效默认都开），
        // 所以老用户升级后第一次启动就在响，不需要额外迁移。
        sound.setVolumes(settings.bgmVolume.toFloat(), settings.sfxVolume.toFloat())
        sound.setSfxEnabled(settings.sfxEnabled)
        sound.setMusicEnabled(settings.musicEnabled)
        // 触觉随设置恢复（默认开；旧存档没有这个字段，读出来就是默认值）
        _hapticEnabled.value = settings.hapticEnabled
        // 按"启动时的模式 + 昼夜"定好第一首，避免先放错了再切
        refreshAudioScene()
        // 一首放完 → 由播放列表接下一首（引擎只在曲尾前 1.2s 报一次，见 SoundCenter）
        sound.onTrackFinished = { autoAdvanceBgm() }
    }

    /**
     * 给"有名字的路口"补发编号。
     *
     * **只有开放路口（`NodeType.OPEN`）有名字** —— 也就是编辑模式里被"开放"的断头路
     * （车辆出入口）。普通十字 / 丁字路口是画路时自动生成的交叉点，**从来不带名字**，
     * 也不该带：它们只是路的交汇处，不是可配置的出入口。
     *
     * 这个函数只负责兼容旧存档：已开放但还没编号的，按 id 顺序补发。
     * 新开放的路口由 [openSelectedNode] 当场分配，不需要等这里。
     */
    private fun ensureJunctionNumbers() {
        network.openNodes().sortedBy { it.id }.forEach { network.assignJunctionNumber(it.id) }
    }

    /**
     * 路网的**独立快照**：节点逐个 `copy()`，边本身不可变直接复用列表引用。
     *
     * 两个用途都需要"读的时候不会被主线程改"：
     *  · 自动暂存：防抖 300ms 后才落盘，期间用户可能继续拖端点；
     *  · 街景后台生成：放到 [kotlinx.coroutines.Dispatchers.Default] 上算，
     *    主线程同时可能在改路网。
     *
     * 只复制表是不够的 —— `SimNode.type` 是 `var`，拖端点时节点会被整只替换，
     * 共享引用会让落盘 / 生成读到"拖到一半"的状态。
     */
    fun networkSnapshot(): RoadNetwork = RoadNetwork(
        nodes = network.nodes.mapValuesTo(LinkedHashMap()) { (_, node) -> node.copy() },
        edges = ArrayList(network.edges),
        nextNodeId = network.nextNodeId,
        nextEdgeId = network.nextEdgeId,
        nextJunctionNumber = network.nextJunctionNumber,
        junctionNumbers = LinkedHashMap(network.junctionNumbers),
        signalOverride = LinkedHashMap(network.signalOverride),
        signalRule = LinkedHashMap(network.signalRule),
    )

    /**
     * 安排一次自动暂存（防抖 300ms）。
     *
     * 为避开"主线程继续编辑 / IO 线程正在序列化"的竞态，先在主线程复制所有可变对象
     * （见 [networkSnapshot]），再交给 IO 线程落盘。
     *
     * ⚠️ 拖动端点过程中**不会**调到这里（每帧一次深拷贝纯属浪费），
     * 而是等松手时由 [endEdgeEndpointDrag] 存一次。
     */
    private fun scheduleAutoSave() {
        val netSnap = networkSnapshot()
        val cfgSnap = junctionConfigs.mapValues { (_, cfg) ->
            cfg.copy(speedCurve = cfg.speedCurve.toList())
        }
        val setSnap = settings.copy()
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            runCatching { repository.saveAuto(netSnap, cfgSnap, setSnap) }
        }
    }

    // ==================== 模式切换 ====================

    fun enterEditMode() {
        // ★ 先出声：resetSimulation 会取消仿真协程 + 清帧，closeOverlays 会连带一串状态，
        //   这些都在"换模式"的同一帧里发生，声音排在后面会被一起推迟
        sound.play(Sfx.MODE_TO_EDIT)
        resetSimulation()             // 按确认结果：进编辑清空车流
        _uiMode.value = UiMode.EDIT
        _drawTool.value = DrawTool.NONE
        clearSelection()
        _showSettingsRow.value = false
        _showRoadSubmenu.value = false
        closeOverlays()
        // 编辑模式换"编辑"那一袋，环境层撤掉 —— 但**别在这一帧切**（见 [refreshAudioSceneDeferred]）
        refreshAudioSceneDeferred()
    }

    fun exitEditMode() {
        _drawTool.value = DrawTool.NONE
        clearSelection()
        cancelDraw()
        _showRoadSubmenu.value = false
        _uiMode.value = UiMode.VIEW
        closeOverlays()
        sound.play(Sfx.MODE_TO_VIEW)
        refreshAudioSceneDeferred()
    }

    /**
     * 「等按钮换班演完，再动音频」的 [refreshAudioScene]。
     *
     * ## 为什么不能就地调
     * 换模式 = 换 BGM 那一袋，`SoundCenter` 会**同步**建一个 `MediaPlayer`
     * （打开 raw 资源 + 解析音频头 + `start()`，实测 5~40ms，低端机更久）。
     * 而这一帧同时还背着"模式切换的整棵树重组"，两个重活叠在一起就是用户看到的
     * "点了编辑，画面先僵住一下，然后按钮才动"。
     *
     * 推迟到 [AUDIO_SCENE_DEFER_MS] 之后：那时按钮已经逐个就位、弹簧也快停稳，
     * 偶尔掉一帧谁也看不出来；而音乐本来就是淡入淡出（40ms 一跳、约 0.8 秒到 95%），
     * 晚 0.4 秒才开始换曲，耳朵完全听不出差别。
     *
     * ⚠️ 必须 `cancel` 上一个：快速"进编辑 → 立刻退出"时，两次请求要合并成最后一次
     * （[refreshAudioScene] 本身幂等，跑一次就够，跑两次只是白建播放器）。
     */
    private fun refreshAudioSceneDeferred() {
        audioSceneJob?.cancel()
        audioSceneJob = viewModelScope.launch {
            delay(AUDIO_SCENE_DEFER_MS)
            refreshAudioScene()
        }
    }

    fun setDrawTool(t: DrawTool) {
        _drawTool.value = t
        // 需求 1：点左侧道路工具列表里的**任一按钮**后，左侧按钮**立即全部消失**
        //（底部「道路」按钮会显示当前选中的路型，再点它可重新展开列表）
        _showRoadSubmenu.value = false
        clearSelection()
    }

    /** 点「道路」：展开/收起 道路工具菜单（直线 / 双车道直线 / 三车道直线 / 曲线 / …） */
    fun toggleRoadSubmenu() {
        _showRoadSubmenu.value = !_showRoadSubmenu.value
        if (_showRoadSubmenu.value) clearSelection()
        sound.play(if (_showRoadSubmenu.value) Sfx.MENU_OPEN else Sfx.MENU_CLOSE)
    }

    /** 左侧菜单（道路工具 / 设置行）是否有任意一个展开 */
    fun leftMenuOpen(): Boolean = _showRoadSubmenu.value || _showSettingsRow.value

    /**
     * 点画布 → 收起左侧菜单（需求 4：点屏幕其它任意位置，左侧按钮全部隐藏）。
     * @return 收起之前是否有菜单是展开的（供手势决定本次是否跳过命中检测）
     */
    fun dismissLeftMenus(): Boolean {
        val was = leftMenuOpen()
        _showRoadSubmenu.value = false
        _showSettingsRow.value = false
        return was
    }

    fun toggleSettingsRow() {
        _showSettingsRow.value = !_showSettingsRow.value
        if (!_showSettingsRow.value) closeOverlays()   // 收起设置行时同步收起倍速滑块/自主规划菜单
        sound.play(if (_showSettingsRow.value) Sfx.MENU_OPEN else Sfx.MENU_CLOSE)
    }

    // ==================== 画布手势 ====================

    /** 当前是否允许画路：编辑模式 + 已选直线/曲线（含多车道）工具 */
    fun canDraw(): Boolean = _uiMode.value == UiMode.EDIT && _drawTool.value.isRoad

    /**
     * 画路端点的吸附（需求 3）：**既有节点**优先（→ 与道路端点相接成转角）→
     * **既有道路内部**（→ 落在路面上、交汇成丁字路口）→ 网格。
     * 与 [RoadNetwork.addRoad] 内部用的是同一套规则，因此"预览看到什么，抬手就生成什么"，
     * 拖拽过程即是道路从吸附点**生长**到当前位置。
     */
    private fun snapIfNeeded(p: Vector2): Vector2 =
        network.snapDrawPoint(p, settings.gridSnap)

    /** 长按 0.4s 就绪：确定起点（起点会按节点 → 道路 → 网格的顺序吸附） */
    fun beginDraw(world: Vector2) {
        if (!canDraw()) return
        val p = snapIfNeeded(world)
        _drawStart.value = p
        _drawCurrent.value = p
        _drawReady.value = true
        // "笔尖搭到纸上"——画路体验里最重要的一次反馈
        sound.play(Sfx.DRAW_ARM)
        // 之后的持续底噪：抬手 / 取消时由 finishDraw / cancelDraw 停掉。
        // 音量压到 0.45，它是"背景质感"，不能盖过落成那一声
        sound.startLoop(Sfx.DRAW_LOOP, 0.45f)
    }

    /** 跟手更新预览终点 */
    fun updateDraw(world: Vector2) {
        if (!_drawReady.value) return
        _drawCurrent.value = snapIfNeeded(world)
    }

    /** 抬手：生成最终道路 */
    fun finishDraw(world: Vector2) {
        val start = _drawStart.value
        _drawReady.value = false
        _drawStart.value = null
        _drawCurrent.value = null
        sound.stopLoop()                       // 先收底噪，再落成/否定，两段不叠
        if (start == null) return
        val end = snapIfNeeded(world)
        if (start.distanceTo(end) < RoadNetwork.MIN_SEGMENT_M) {
            // 否定的那一声**代替**通用气泡音（两者同帧响会打架）
            toast(str(StrKey.TOAST_TOO_SHORT), Sfx.ROAD_REJECTED)
            return
        }
        val curvature = if (_drawTool.value.curve) defaultCurve else 0.0
        pushUndo()
        val ok = network.addRoad(start, end, curvature, _drawTool.value.lanes, settings.gridSnap)
        if (!ok) {
            undoStack.removeLastOrNull()
            syncUndoFlag()
            toast(str(StrKey.TOAST_ROAD_FAILED), Sfx.ROAD_REJECTED)
        } else {
            _networkVersion.value += 1
            scheduleAutoSave()
            sound.play(Sfx.ROAD_CREATED)
        }
    }

    fun cancelDraw() {
        _drawReady.value = false
        _drawStart.value = null
        _drawCurrent.value = null
        sound.stopLoop()                       // 画到一半被打断，底噪不能留着循环
    }

    /**
     * 单击命中（需求 2 / 6）：
     *  ① **道路端点标记**（开放路口 / 封闭口）优先；
     *  ② 编辑模式下点到**十字 / 丁字路口** → 红绿灯增删（需求 6）；
     *  ③ 否则命中**道路**（仅编辑模式）；
     *  ④ 都不中 → 取消选择。
     *
     * **端点与道路怎么区分**：道路折线一定包含端点，所以"到道路的距离"恒 ≤ "到端点的距离"，
     * 单纯比大小的话端点永远选不中。因此给端点一个偏置：距离相差不超过端点半径的 25%
     * （编辑模式 48px × 25% ≈ 12px）就算端点。
     *
     * **路口为什么单独给一个更大的容差（需求 6）**：路口中心**就在所有相接道路上**，
     * 于是"到道路的距离"≈ 0，端点的偏置判据会把路口的有效命中半径压到只有 12px 左右，
     * 极难点中。所以度 ≥ 3 的路口改用独立的 [junctionTolM]（60px）——只要落在路口附近即选中，
     * 不再和道路比距离。
     *
     * @param nodeTolM 端点命中半径（米，UI 按像素换算，编辑模式 48px）
     * @param roadTolM 道路命中半径（米；30px）
     * @param junctionTolM 十字 / 丁字路口命中半径（米；60px，比端点更大更好点）
     */
    fun onTap(world: Vector2, nodeTolM: Double, roadTolM: Double, junctionTolM: Double) {
        val edit = _uiMode.value == UiMode.EDIT
        val node = network.nearestNode(world, maxOf(nodeTolM, junctionTolM))
        val nodeDist = node?.pos?.distanceTo(world) ?: Double.MAX_VALUE
        val road = if (edit) network.hitRoad(world, roadTolM) else null
        val roadDist = road?.third ?: Double.MAX_VALUE

        if (node != null) {
            // ① 端点（开放路口 / 封闭口）——最高优先级
            val isEndpoint = node.type == NodeType.OPEN || network.isDeadEnd(node.id)
            if (isEndpoint && nodeDist <= nodeTolM && nodeDist <= roadDist + nodeTolM * 0.25) {
                if (!edit) {
                    if (node.type == NodeType.OPEN) openJunctionDialog(node.id)
                    return
                }
                _selectedNodeId.value = node.id
                _selectedRoadId.value = null
                _selection.value = if (node.type == NodeType.OPEN) Selection.OPEN else Selection.DEAD_END
                return
            }
            // ② 十字 / 丁字路口 → 红绿灯增删（需求 6：独立大容差，不再和道路比距离）
            if (edit && network.degree(node.id) >= 3 && nodeDist <= junctionTolM) {
                _selectedNodeId.value = node.id
                _selectedRoadId.value = null
                _selection.value = if (hasSignal(node.id)) Selection.SIGNAL else Selection.NO_SIGNAL
                return
            }
        }

        // ③ 道路
        if (road != null) {
            _selectedRoadId.value = road.first.id
            _selectedNodeId.value = null
            _selection.value = Selection.ROAD
            return
        }
        // 什么都没点到（或取消了选择）：只给一声"触到纸面"的极轻反馈。
        // 选中道路 / 路口时不播它 —— 那些有胶囊弹入、对话框弹入这类更明确的反馈，
        // 两个一起响只会糊成一团。
        sound.play(Sfx.TAP_CANVAS)
        clearSelection()
    }

    // ==================== 选中道路：端点拖拽 + 车道数（需求 2 / 7）====================

    /** 选中的道路是不是曲线（决定"变双/三车道"按钮用直线图标还是曲线图标） */
    fun selectedRoadIsCurve(): Boolean {
        val id = _selectedRoadId.value ?: return false
        return (network.edge(id)?.curvature ?: 0.0) != 0.0
    }

    /** 选中道路当前的每方向车道数（用于把"已是该车道数"的按钮置灰） */
    fun selectedRoadLanes(): Int {
        val id = _selectedRoadId.value ?: return 1
        return network.edge(id)?.lanes ?: 1
    }

    /** 把选中道路改为双车道 / 三车道（需求 7） */
    fun setSelectedRoadLanes(lanes: Int) {
        val id = _selectedRoadId.value ?: return
        val e = network.edge(id) ?: return
        val target = lanes.coerceIn(1, RoadNetwork.MAX_LANES)
        if (e.lanes == target) return
        pushUndo()
        network.setLanes(id, target)
        simulator?.rebuildTopology()
        _networkVersion.value += 1
        scheduleAutoSave()
        val key = when (target) {
            1 -> StrKey.TOAST_LANES_1
            2 -> StrKey.TOAST_LANES_2
            else -> StrKey.TOAST_LANES_3
        }
        // 三个文件同音色、递增音高（C5 / E5 / G5）：闭眼也能听出"现在是几条车道"。
        // 直接当成这次气泡的提示音 —— **一次操作只响一声**，别让"车道音 + 通用气泡音"叠着播
        toast(str(key), Sfx.ofLanes(target))
    }

    /** 命中"选中道路"两端的**拖拽手柄**（需求 2），返回要拖拽的节点 id；没打中返回 null */
    fun edgeHandleAt(world: Vector2, tolM: Double): Long? {
        val id = _selectedRoadId.value ?: return null
        val e = network.edge(id) ?: return null
        val a = network.node(e.from) ?: return null
        val b = network.node(e.to) ?: return null
        val da = a.pos.distanceTo(world)
        val db = b.pos.distanceTo(world)
        val nid = if (da <= db) a.id else b.id
        val d = minOf(da, db)
        return if (d <= tolM) nid else null
    }

    /**
     * 开始拖拽端点（需求 2）。**不立刻记撤销**——等真正移动了再记（见 [dragEdgeEndpoint]）。
     *
     * 用**位移增量**驱动：记住按下时的节点坐标与手指世界坐标，之后按手指位移平移该节点。
     * 这样即便"按下 → 判定为拖拽"之间有十几像素的容差，起手也不会让道路跳一下。
     *
     * @return 该节点是否存在（不存在返回 false，调用方就不会进入拖拽模式）
     */
    fun beginEdgeEndpointDrag(nodeId: Long, world: Vector2): Boolean {
        val n = network.node(nodeId) ?: return false
        draggingNodeId = nodeId
        dragAnchorNodePos = n.pos
        dragAnchorWorld = world
        endpointDragDirty = false
        _draggingNetwork.value = true
        sound.play(Sfx.ENDPOINT_GRAB)      // 抓住手柄的那一下"磁吸"
        return true
    }

    /**
     * 拖拽端点：按手指位移平移该节点 → 所有相接道路自动跟随（需求 2）。
     * 吸附只认**其它节点 / 网格**（见 [RoadNetwork.snapDragPoint]），避免被"吸"在自己所属的道路上。
     */
    fun dragEdgeEndpoint(world: Vector2) {
        val id = draggingNodeId ?: return
        val raw = dragAnchorNodePos + (world - dragAnchorWorld)
        val target = network.snapDragPoint(raw, settings.gridSnap, id)
        // 防塌缩：移动后不能把任何相接道路压得比最小路段还短
        val ok = network.edgesAt(id).all { e ->
            val other = network.node(network.otherEnd(e, id))
            other == null || other.pos.distanceTo(target) >= RoadNetwork.MIN_SEGMENT_M
        }
        if (!ok) return
        val n = network.node(id) ?: return
        if (n.pos.distanceTo(target) < 1e-9) return          // 没真正移动，不记撤销
        if (!endpointDragDirty) { pushUndo(); endpointDragDirty = true }  // 首次真移动才记一次撤销
        network.moveNode(id, target)
        _networkVersion.value += 1
        // ⚠️ 这里**不**调 scheduleAutoSave()：拖动时指针事件最高 240Hz，
        // 每来一个事件就取消并重建一次 300ms 防抖任务 + 深拷贝全部节点，
        // 纯属白烧 CPU。松手那一刻统一存一次（见 endEdgeEndpointDrag）。
    }

    /**
     * 结束拖拽端点：
     *  ① 需求 3：把拖动后**新产生的交叉**补成路口（移动节点本身不会新建交点节点，
     *     所以两路即使压在一起也不会自动成路口）——这里用 [RoadNetwork.stitchCrossingsAt] 补做；
     *     随后 [TrafficSimulator.rebuildTopology] 会让度 ≥ 3 的新路口按自动配灯规则带上红绿灯
     *     （不动红绿灯总开关：总开关关着时它就先不亮，开着时立即亮）。
     *  ② 重建拓扑（车道偏移 / 停止线等按新几何刷新）。
     */
    fun endEdgeEndpointDrag() {
        _draggingNetwork.value = false
        val id = draggingNodeId ?: return
        draggingNodeId = null
        sound.play(Sfx.ENDPOINT_DROP)      // 松手落位
        val moved = endpointDragDirty
        endpointDragDirty = false
        if (moved && network.stitchCrossingsAt(id)) {
            _networkVersion.value += 1
        }
        simulator?.rebuildTopology()
        scheduleAutoSave()
    }

    // ==================== 红绿灯增删（需求 6）====================

    /**
     * 该路口当前**有没有红绿灯**：度 ≥ 3（丁字 / 十字）、全局开关打开、且没有被手工关闭。
     * 编辑模式画的小灯图标、能否"添加红绿灯"都以此为准。
     */
    fun hasSignal(nodeId: Long): Boolean =
        settings.trafficLightsEnabled &&
            network.degree(nodeId) >= 3 &&
            network.signalEnabled(nodeId)

    /**
     * 命中某个路口的**红绿灯小图标**（编辑模式）：返回路口 id，没打中返回 null。
     * 图标位置与画布绘制用的是同一个 [RoadNetwork.signalMarkerPos]，所见即所点。
     */
    fun hitSignalMarker(world: Vector2, gapM: Double, tolM: Double): Long? {
        var best: Long? = null
        var bestD = tolM
        for (node in network.nodes.values) {
            if (!hasSignal(node.id)) continue
            val mp = network.signalMarkerPos(node.id, gapM) ?: continue
            val d = mp.distanceTo(world)
            if (d <= bestD) {
                bestD = d
                best = node.id
            }
        }
        return best
    }

    /** 选中某个路口的红绿灯（点灯位或点路口本身都走这里） */
    fun selectSignalNode(id: Long) {
        _selectedNodeId.value = id
        _selectedRoadId.value = null
        _selection.value = Selection.SIGNAL
        // 暂停状态下循环没在跑 → 手动补一帧，相位条立刻就有数据
        refreshSnapshotIfIdle()
    }

    /** 删除该路口的红绿灯（需求 6）：只关这一个路口，不影响其它路口 */
    fun removeSignalAtSelected() {
        val id = _selectedNodeId.value ?: return
        if (network.degree(id) < 3) return
        pushUndo()
        network.setSignalEnabled(id, false)
        simulator?.rebuildTopology()
        _networkVersion.value += 1
        _selection.value = Selection.NO_SIGNAL
        refreshSnapshotIfIdle()
        scheduleAutoSave()
        toast(str(StrKey.TOAST_SIGNAL_REMOVED), Sfx.SIGNAL_REMOVE)
    }

    /** 给该路口添加红绿灯（需求 6）；若全局开关是关的，顺手打开 */
    fun addSignalAtSelected() {
        val id = _selectedNodeId.value ?: return
        if (network.degree(id) < 3) {
            // "嗯，这里不能加" —— 极轻的否定，不是报错音
            toast(str(StrKey.TOAST_SIGNAL_ONLY_JUNCTION), Sfx.NOOP_SOFT)
            return
        }
        pushUndo()
        network.setSignalEnabled(id, true)
        val globalWasOff = !settings.trafficLightsEnabled
        settings.trafficLightsEnabled = true
        simulator?.rebuildTopology()
        _networkVersion.value += 1
        _configVersion.value += 1
        _selection.value = Selection.SIGNAL
        refreshSnapshotIfIdle()
        scheduleAutoSave()
        toast(
            str(if (globalWasOff) StrKey.TOAST_SIGNAL_ADDED_ON else StrKey.TOAST_SIGNAL_ADDED),
            Sfx.SIGNAL_ADD,
        )
    }

    fun clearSelection() {
        _selection.value = Selection.NONE
        _selectedRoadId.value = null
        _selectedNodeId.value = null
    }

    // ==================== 红绿灯规则（本轮需求）====================

    /** 该路口当前使用的规则（1 / 2 / 3） */
    fun signalRuleOf(nodeId: Long): Int = network.signalRuleOf(nodeId)

    /**
     * 打开「红绿灯设置」对话框。
     *
     * 入口是编辑模式里"点中某个路口的红绿灯"之后，左侧**删除按钮上方**新出现的那个按钮
     * （见 [com.keeprolling.app.ui.components.ContextButtons]）。
     */
    fun openSignalRuleDialog(nodeId: Long) {
        if (network.degree(nodeId) < 3) {
            toast(str(StrKey.TOAST_SIGNAL_ONLY_JUNCTION), Sfx.NOOP_SOFT)
            return
        }
        _signalRuleDialogNodeId.value = nodeId
        sound.play(Sfx.DIALOG_OPEN)
        syncDuck()
    }

    fun closeSignalRuleDialog() {
        val wasOpen = _signalRuleDialogNodeId.value != null
        _signalRuleDialogNodeId.value = null
        if (wasOpen) {
            sound.play(Sfx.DIALOG_CLOSE)
            syncDuck()
        }
    }

    /**
     * 音乐让路（ducking）：只要有**任何一个弹层在场**，就把音乐压低 6dB。
     *
     * 为什么每次都"重新算一遍"而不是"打开就压、关闭就抬"：
     * 后者只要漏掉一条关闭路径（比如对话框被系统返回键关掉），
     * 音乐就会**永久卡在压低状态** —— 用户看不出原因，只觉得"音乐怎么变小了"。
     * 重算是幂等的，漏调一次也能在下一次任意弹层动作里纠正回来。
     */
    private fun syncDuck() {
        val anyDialog = _showSettingsSheet.value ||
            _showSaveDialog.value ||
            _showLoadDialog.value ||
            _showClearConfirm.value ||
            _junctionDialogNodeId.value != null ||
            _signalRuleDialogNodeId.value != null
        sound.setDucked(anyDialog)
    }

    /**
     * 应用某个路口的红绿灯规则（对话框点「勾」时调用）。
     *
     * 规则写进 [RoadNetwork.signalRule] → 随后 [TrafficSimulator.rebuildTopology] 会按新规则
     * 重新生成状态表（相位从 0 起算，所以切换后会立刻从"状态①"开始走一遍，观感最清楚）。
     */
    fun applySignalRule(nodeId: Long, rule: Int) {
        val target = rule.coerceIn(1, RoadNetwork.SIGNAL_RULE_COUNT)
        if (network.signalRuleOf(nodeId) == target) {
            _signalRuleDialogNodeId.value = null
            return
        }
        pushUndo()
        network.setSignalRule(nodeId, target)
        simulator?.rebuildTopology()
        _networkVersion.value += 1
        _signalRuleDialogNodeId.value = null
        syncDuck()                    // 对话框直接被"确认"关掉，别把音乐留在压低状态
        refreshSnapshotIfIdle()
        scheduleAutoSave()
        toast(str(StrKey.TOAST_SIGNAL_RULE, str(ruleNameKey(target))), Sfx.SIGNAL_RULE)
    }

    /** 规则序号 → 规则名字的文案键（提示与对话框共用） */
    private fun ruleNameKey(rule: Int): StrKey = when (rule) {
        2 -> StrKey.SIGNAL_RULE_2_NAME
        3 -> StrKey.SIGNAL_RULE_3_NAME
        else -> StrKey.SIGNAL_RULE_1_NAME
    }

    /** 命中曲率手柄（选中曲线道路后，手柄在弦法线方向的弧顶处） */
    fun curvatureHandlePos(): Vector2? {
        val id = _selectedRoadId.value ?: return null
        val e = network.edge(id) ?: return null
        if (e.curvature == 0.0) return null
        val path = network.pathOf(e) ?: return null
        val mid = path.chordMid
        return mid + path.chordNormal * path.sagitta
    }

    fun hitCurvatureHandle(world: Vector2, toleranceM: Double): Boolean {
        val h = curvatureHandlePos() ?: return false
        return h.distanceTo(world) <= toleranceM
    }

    /**
     * 开始调节曲率（拖曲率手柄）。**不立刻记撤销**——等曲率真的变了再记（见 [dragCurvature]），
     * 这样"只是点了一下手柄、没拖动"不会在撤销栈里留一步空操作。
     */
    fun beginCurvatureDrag() {
        val id = _selectedRoadId.value ?: return
        if (network.edge(id) == null) return
        curvatureDragEdgeId = id
        curvatureDragDirty = false
        curvatureDragWorld = curvatureHandlePos() ?: curvatureDragWorld
        sound.play(Sfx.CURVE_GRAB)
    }

    fun dragCurvature(world: Vector2) {
        val id = curvatureDragEdgeId ?: return
        val e = network.edge(id) ?: return
        val a = network.node(e.from)?.pos ?: return
        val b = network.node(e.to)?.pos ?: return
        val c = RoadPath.curvatureFromHandle(a, b, world)
        curvatureDragWorld = world
        if (c == e.curvature) return                                          // 没真正改变，不记撤销
        if (!curvatureDragDirty) { pushUndo(); curvatureDragDirty = true }    // 首次真改变才记一次
        network.setCurvature(id, c)
        _networkVersion.value += 1
        scheduleAutoSave()
    }

    /**
     * 结束调节曲率：与"拖端点"同款收尾（对照 [endEdgeEndpointDrag]）。
     *
     * 曲线鼓出 / 收拢后，弧身可能**扫过别的道路**——原来这条交互只管改曲率、不补路口，
     * 于是画面上看着相交、实际既没有路口也没有灯。这里补做：
     *  ① [RoadNetwork.stitchCrossingsOfEdge] 把这条曲线新产生的交叉打断成路口；
     *     随后 [TrafficSimulator.rebuildTopology] 让度 ≥ 3 的新路口按自动配灯规则带上红绿灯
     *     （**不动红绿灯总开关**：总开关开着就立即亮、关着就先不亮——与"拖端点"行为一致）。
     *  ② 曲线被打断后原 edge id 会失效 → 重新选中离手柄最近的那条子路，保证曲率手柄仍可继续调。
     */
    fun endCurvatureDrag() {
        val id = curvatureDragEdgeId ?: return
        curvatureDragEdgeId = null
        val changed = curvatureDragDirty
        curvatureDragDirty = false
        if (!changed) return                                  // 只是点了一下手柄、没拖动 → 什么都不用做
        if (network.stitchCrossingsOfEdge(id)) {
            _networkVersion.value += 1
            val sel = _selectedRoadId.value
            if (sel == null || network.edge(sel) == null) {
                val near = network.edgeNearestTo(curvatureDragWorld)
                if (near != null) {
                    _selectedRoadId.value = near
                    _selectedNodeId.value = null
                    _selection.value = Selection.ROAD
                } else {
                    clearSelection()
                }
            }
        }
        simulator?.rebuildTopology()
        scheduleAutoSave()
    }

    // ==================== 编辑操作（上下文按钮） ====================

    fun deleteSelectedRoad() {
        val id = _selectedRoadId.value ?: return
        pushUndo()
        network.removeEdge(id)
        clearSelection()
        _networkVersion.value += 1
        scheduleAutoSave()
        toast(str(StrKey.TOAST_ROAD_DELETED), Sfx.ROAD_DELETED)
    }

    /** 断头路 → 开放路口。**本轮需求 4 改过一次**，见下面的说明。 */
    fun openSelectedNode() {
        val id = _selectedNodeId.value ?: return
        if (network.node(id) == null) return
        // ⚠️ 这里**不再**改 node.type。
        // 以前是"当场开放 → 再弹出入口设置"：用户弹出框后点「叉」想反悔，
        // 路口其实已经开了，必须再手动点一次「封闭」才能退回原样。
        // 现在只记下"待开放的路口"并把设置框弹出来 —— 路口仍然是封闭的，路面没有任何变化。
        //   · 点「勾」→ confirmJunctionDialog() 才真正开放并写回配置；
        //   · 点「叉」/ 点外部 / 返回键 → closeJunctionDialog() 把这次意图整个丢掉，路口保持封闭。
        junctionConfigs.getOrPut(id) { JunctionConfig() }
        pendingOpenNodeId = id
        _junctionDialogNodeId.value = id
        sound.play(Sfx.DIALOG_OPEN)
        syncDuck()
    }

    /**
     * 开放路口 → 封闭（变回断头路，不再进出车辆）。
     * 注意：**编号保留不释放**——这样它再次被开放时还是原来的名字。
     */
    fun closeSelectedNode() {
        val id = _selectedNodeId.value ?: return
        val node = network.node(id) ?: return
        pushUndo()
        node.type = NodeType.NORMAL
        simulator?.rebuildTopology()
        _networkVersion.value += 1
        _selection.value = Selection.DEAD_END
        scheduleAutoSave()
        toast(str(StrKey.TOAST_NODE_CLOSED), Sfx.NODE_CLOSE)
    }

    // ==================== 出入口设置对话框 ====================

    /**
     * 本次对话框是不是「开放路口」流程的一部分（由 [openSelectedNode] 设置）。
     * 点「叉」时据此决定要不要把这次开放意图丢掉。
     */
    private var pendingOpenNodeId: Long? = null

    fun openJunctionDialog(nodeId: Long) {
        // 从「设置」按钮进来的永远是"给已开放的路口改配置"，跟开放流程无关 ——
        // 不清掉的话，上一次没走完的 pending 会误伤这一次的「叉」。
        pendingOpenNodeId = null
        junctionConfigs.getOrPut(nodeId) { JunctionConfig() }
        _junctionDialogNodeId.value = nodeId
        sound.play(Sfx.DIALOG_OPEN)
        syncDuck()
    }

    /**
     * 对话框「勾」= **确认**（本轮需求 4）。
     *  - 若这次是[openSelectedNode]发起的**开放流程** → 先真正开放（分配编号 + 重建拓扑），
     *    再把草稿配置写回 —— 于是"点勾"这一个动作就完成了"开放 + 保留修改"；
     *  - 若只是给一个**已开放**的路口改设置 → 只写回配置。
     *
     * undo 快照压在这两件事**之前**，所以一次撤销能把"开放 + 这份配置"一起退回。
     */
    fun confirmJunctionDialog(id: Long, cfg: JunctionConfig) {
        val pending = pendingOpenNodeId
        pendingOpenNodeId = null
        val opening = pending == id
        val node = if (opening) network.node(id) else null
        if (opening && node == null) {
            _junctionDialogNodeId.value = null
            return
        }
        // ★ 先出声，再动数据：下面 pushUndo 要把整张路网序列化成 JSON、
        //   rebuildTopology 要重算拓扑，都是几十毫秒的活。
        //   开放是"这个路口活了"的一刻 → 上扬钟声；只改配置 → 中性的确认音
        sound.play(if (opening) Sfx.NODE_OPEN else Sfx.JUNCTION_APPLY)
        if (node != null) {
            pushUndo()
            node.type = NodeType.OPEN
            // 开放时分配编号；已分配过的沿用原编号（封闭不释放）
            network.assignJunctionNumber(id)
            junctionConfigs.getOrPut(id) { JunctionConfig() }
            simulator?.rebuildTopology()
            _networkVersion.value += 1
            _selection.value = Selection.OPEN
        }
        applyJunctionConfig(id, cfg)
        _junctionDialogNodeId.value = null
        syncDuck()
    }

    /**
     * 关闭对话框 = **取消**（点「叉」/ 点外部 / 返回键）。
     *
     * 需求 4：如果这次是"开放路口"流程，路口**保持封闭**，草稿配置一并丢弃 ——
     * 也就是这个对话框等于没打开过。已经开放的路口不受影响（它本来就是开的）。
     */
    fun closeJunctionDialog() {
        val wasOpen = _junctionDialogNodeId.value != null
        pendingOpenNodeId = null
        _junctionDialogNodeId.value = null
        // 中性退出：不要有"你错了"的暗示；本来就没开过就不响
        if (wasOpen) sound.play(Sfx.JUNCTION_DISCARD)
        syncDuck()
    }

    /**
     * 应用出入口设置对话框里「勾」按钮确认的**整份草稿配置**。
     * 对话框内部用草稿副本编辑，点「勾」才一次性写回（点「叉」则丢弃）。
     */
    private fun applyJunctionConfig(id: Long, cfg: JunctionConfig) {
        junctionConfigs[id] = cfg
        _configVersion.value += 1
        scheduleAutoSave()
    }

    /**
     * 路口显示名：**开放时分配的编号**（路口1、路口2、路口3…）。
     *
     * 编号规则（需求 4）：
     *  - 所有道路端点默认都是**封闭路口且无名字**；
     *  - 用户把某个封闭路口「开放」时，才分配下一个编号；
     *  - 编号**一经分配即被占用保留**：封闭「路口3」后编号 3 不释放，
     *    新开放的路口会继续往后编（路口7、路口8…）；
     *  - 原来那个路口再次开放时，恢复它自己的编号（仍是路口3）。
     */
    fun junctionLabel(id: Long): String {
        val n = network.junctionNumberOf(id) ?: return str(StrKey.LABEL_JUNCTION_UNNUMBERED)
        return str(StrKey.LABEL_JUNCTION, n)
    }

    /**
     * 目的地概率说明文本：对每个可能的出发路口，按实际抽样公式计算后取平均。
     * 单个固定概率无法表达所有出发情形，因此这里明确展示各出发路口等权时的期望值。
     * @param heatOverride 对话框的草稿热度；待开放路口也会临时纳入预览集合。
     */
    fun junctionProbabilityText(id: Long, heatOverride: Int? = null): String {
        val ids = network.openNodes().mapTo(mutableListOf()) { it.id }
        if (id !in ids && junctionConfigs.containsKey(id)) ids += id
        if (ids.size <= 1) return str(StrKey.PROB_ONLY_ONE)

        fun heatOf(nodeId: Long): Int =
            (if (nodeId == id) heatOverride else null)
                ?.coerceIn(1, 10)
                ?: (junctionConfigs[nodeId]?.heat ?: 5).coerceIn(1, 10)

        val pct = ids.filter { it != id }.map { originId ->
            val candidates = ids.filter { it != originId }
            val total = candidates.sumOf(::heatOf).coerceAtLeast(1)
            100.0 * heatOf(id) / total
        }.average()
        return str(StrKey.PROB, (pct * 10).roundToInt() / 10.0)
    }

    // ==================== 车辆聚焦（需求 4）====================

    /**
     * 点中画面上的某辆车 → 聚焦它。
     *
     * 命中用的是**最近一帧渲染快照**里的车心坐标（不可变列表，跨线程安全），
     * 与画面上看到的位置逐像素一致 —— 不会出现"点到了却选不中"的偏差。
     *
     * @return true = 点中了车（调用方不要再把它当成"点路口 / 点道路"处理）
     */
    fun focusVehicleAt(world: Vector2, tolM: Double): Boolean {
        // 需求 4：去UI 模式下不锁定车辆（连信息框都藏起来了，锁定跟随没有意义）
        if (_hideUi.value) return false
        val f = _frame.value ?: return false
        var bestId: Long? = null
        var bestD = tolM
        for (v in f.vehicles) {
            val d = Vector2(v.x, v.y).distanceTo(world)
            if (d <= bestD) { bestD = d; bestId = v.id }
        }
        val id = bestId ?: return false
        _focusedVehicleId.value = id
        _followLocked.value = true
        // 暂停 / 未运行时仿真循环不产帧，必须在这里**同步**补一次聚焦快照，
        // 否则信息框与虚线路径要等下一次启动才出现（点车像没反应）
        refreshSnapshotIfIdle()
        _focusEpoch.value += 1
        sound.play(Sfx.VEHICLE_LOCK)      // 镜头咬住目标的那一下
        return true
    }

    /** 点信息框本体 → 重新缩放并锁定跟随（需求 4："点击信息框可再次缩放并锁定跟随该车"） */
    fun refocusVehicle() {
        if (_focusedVehicleId.value == null) return
        _followLocked.value = true
        refreshSnapshotIfIdle()
        _focusEpoch.value += 1
    }

    /** 信息框右上角的叉 → 关掉信息框，并彻底解除锁定 */
    fun clearFocus() {
        val had = _focusedVehicleId.value != null
        _focusedVehicleId.value = null
        _followLocked.value = false
        refreshSnapshotIfIdle()
        // 本来就没有聚焦（比如重置仿真时顺手调进来）→ 不响，否则每次重置都多一声
        if (had) sound.play(Sfx.VEHICLE_UNLOCK)
    }

    /**
     * 仿真循环没在跑（未开始 / 已暂停 / 已崩溃）时，手动补一帧**只跟界面状态有关**的快照。
     *
     * 目前补两项：聚焦车辆的路径虚线、选中信号路口的相位条。它们都依赖"用户点了什么"，
     * 而暂停时后台循环已经停了 —— 不主动补一次，界面就会一直显示旧值（或干脆没有）。
     *
     * 只在**非 RUNNING** 时动手：RUNNING 下由后台循环每帧产出，主线程插一脚反而会写乱帧序。
     * 非 RUNNING 时循环已取消，`simulator` 的内部状态不会被并发修改，读它是安全的。
     */
    private fun refreshSnapshotIfIdle() {
        if (_simState.value == SimState.RUNNING) return
        val sim = simulator ?: return
        // 冷启动后直接进编辑模式时帧还是 null —— 这里就地造一个"只有界面态"的空帧。
        // 刻意不带 signals / stopLines：那两个属于运行画面，编辑模式本来就不画，
        // 带上会突然多出一堆灯头，反而干扰画路。
        val fr = _frame.value ?: RenderFrame(
            vehicles = emptyList(),
            stats = sim.snapshotStats(),
            simTime = sim.simTime,
        )
        _frame.value = fr.copy(
            focus = sim.renderFocus(_focusedVehicleId.value),
            phase = phaseOfSelected(),
        )
    }

    /** 当前选中的信号路口的相位快照（没选中 / 选中的不是信号路口 → null） */
    private fun phaseOfSelected(): SignalPhaseRender? {
        if (_selection.value != Selection.SIGNAL) return null
        val nodeId = _selectedNodeId.value ?: return null
        return simulator?.signalPhaseInfo(nodeId)
    }

    /**
     * 用户手动缩放 / 拖动地图 → **解除跟随**（需求 4），但信息框保留：
     * 他还能继续看这辆车的速度与路线，只是画面不再被它拽着走。
     */
    fun onManualCameraMove() {
        if (_followLocked.value) _followLocked.value = false
    }

    // ==================== 仿真控制 ====================

    fun toggleSimulation() {
        when (_simState.value) {
            SimState.IDLE -> startSimulation()
            SimState.RUNNING -> pauseSimulation()
            SimState.PAUSED -> resumeSimulation()
            SimState.CRASHED -> toast(str(StrKey.TOAST_CRASHED), Sfx.NOOP_SOFT)
        }
    }

    private fun startSimulation() {
        if (network.edges.isEmpty()) { toast(str(StrKey.TOAST_NEED_ROADS), Sfx.ROAD_REJECTED); return }
        val opens = network.openNodes()
        if (opens.isEmpty()) { toast(str(StrKey.TOAST_NEED_OPEN), Sfx.ROAD_REJECTED); return }
        if (opens.size < 2) { toast(str(StrKey.TOAST_NEED_TWO_OPEN), Sfx.ROAD_REJECTED); return }
        ensureJunctionNumbers()
        opens.forEach { junctionConfigs.getOrPut(it.id) { JunctionConfig() } }
        simulator = TrafficSimulator(network, junctionConfigs, settings)
        _frame.value = null
        _simState.value = SimState.RUNNING
        startLoop()
        sound.play(Sfx.SIM_START)     // "城市苏醒"
        refreshAudioScene()           // 环境层开始铺（白天/夜里各一条）
    }

    private fun pauseSimulation() {
        simJob?.cancel()
        _simState.value = SimState.PAUSED
        sound.play(Sfx.SIM_PAUSE)
        refreshAudioScene()           // 车不动了 → 环境层撤掉，音乐继续
    }

    private fun resumeSimulation() {
        if (simulator == null) { startSimulation(); return }
        _simState.value = SimState.RUNNING
        startLoop()
        sound.play(Sfx.SIM_START)
        refreshAudioScene()
    }

    /** 重置：清空车辆、回到未开始状态（保留路网） */
    fun resetSimulation() {
        simJob?.cancel()
        simulator = null
        _frame.value = null
        _simState.value = SimState.IDLE
        clearFocus()          // 车都没了，信息框与锁定跟随一并撤掉
        refreshAudioScene()   // 回到"未运行"：环境层撤掉
    }

    /**
     * 设置面板里那个「重置」按钮。
     *
     * 为什么不直接在 [resetSimulation] 里出声：那个函数是**内部公共收尾**，
     * 撤销 / 重做 / 读档 / 进编辑都会调它，每次都响就成了噪音。
     * 只有用户真的按下"重置"这一个入口才该有"抹掉重来"的那一声。
     */
    fun resetSimulationFromButton() {
        resetSimulation()
        sound.play(Sfx.SIM_RESET)
    }

    /** 崩溃横幅里的「回到编辑」 */
    fun backToEditAfterCrash() {
        resetSimulation()
        enterEditMode()
    }

    private fun startLoop() {
        simJob?.cancel()
        simJob = viewModelScope.launch(Dispatchers.Default) {
            // ---------- 真实时间累加器 ----------
            // 旧实现是「每轮 delay(16) 就加一份倍速」，等于假设每轮循环恰好 16ms。
            // 但一轮的实际耗时 = 计算 + delay 抖动，大路网下轻松 25~35ms，于是：
            //   1x 实际只有 0.6~0.8x，5x 实际只有 2~3x，且偏多少取决于当前负载 ——
            //   同一档倍速在不同路网上表现不一样，非常不可预期。
            //
            // 现在改用 System.nanoTime() 量出**真实经过的时间**，乘倍速后累加，
            // 再按固定步长 SIM_DT 消耗。好处：
            //   · 倍速与帧率彻底解耦（1x 就是真实 1x，5x 就是真实 5x）；
            //   · 掉帧时自动多跑几个子步把时间补回来，仿真时间不漂移；
            //   · 0.5x 时累加得慢，部分帧不需要推进 —— 那是正确的慢放，不是卡顿。
            var lastNs = System.nanoTime()
            var acc = 0.0
            var first = true
            while (isActive && _simState.value == SimState.RUNNING) {
                val sim = simulator ?: break
                val nowNs = System.nanoTime()
                // 单帧真实间隔夹到 100ms：再长就是被系统挂起了（切后台 / GC 长停顿），
                // 补帧会把画面瞬间"快进"一大段，反而更突兀。
                val realDt = ((nowNs - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.1)
                lastNs = nowNs

                val mult = settings.speedMultiplier.coerceIn(0.1, 8.0)
                acc += realDt * mult
                // 积压上限 = 100ms 真实时间对应的仿真量，防止卡顿后疯狂追帧雪崩
                acc = acc.coerceAtMost(0.1 * mult)

                var steps = 0
                while (acc >= TrafficSimulator.SIM_DT && steps < MAX_STEPS_PER_FRAME) {
                    sim.tick(TrafficSimulator.SIM_DT)
                    acc -= TrafficSimulator.SIM_DT
                    steps++
                }

                // 只有真的推进过才发新快照：0.5x 时约一半的帧不需要更新，
                // 省掉一次全量 RenderFrame 构造和一次 Compose 重组。
                if (steps > 0 || first) {
                    first = false
                    // 统计与相位各算一次：既要写进快照，又要喂给"仿真自带的声音事件"
                    val stats = sim.snapshotStats()
                    val phase = phaseOfSelected()
                    _frame.value = RenderFrame(
                        vehicles = sim.renderPositions(),
                        stats = stats,
                        simTime = sim.simTime,
                        signals = sim.renderSignals(),
                        stopLines = sim.renderStopLines(),
                        // 聚焦车辆的快照与车辆位置同一帧生成：信息框的读数、虚线路径
                        // 与画面上的车永远同步（需求 4）
                        focus = sim.renderFocus(_focusedVehicleId.value),
                        // 相位条只跟"当前选中的那个信号路口"有关，编辑其它东西时不显示
                        phase = phase,
                    )
                    playTrafficSfx(stats, phase)
                }
                // 自动昼夜：天色跟着**仿真时间**走（暂停时 simTime 冻结 → 天色也停住）。
                // 变化超过 0.01 才推一次（黄昏那一小段最密，约 5~6 次/秒）：界面层只需要
                // "大致几点钟"，每帧推送会把整层 UI 拖进 60Hz 重组 —— 上一轮刚把这笔开销摘掉。
                // 0.01 在肉眼上完全看不出（整条夜色曲线才 100 档），但比重组预算便宜得多。
                if (_dayMode.value == DayMode.AUTO) {
                    val n = DayCycle.lightAt(DayCycle.phaseAt(sim.simTime)).night
                    if (abs(n - _dayNight.value) > 0.01f) _dayNight.value = n
                    // 夜色是连续量，音乐只能离散切换：只在**跨过阈值**那一刻换一次曲子，
                    // 否则黄昏那几分钟里每帧都会触发一次判断。
                    if ((n > NIGHT_AUDIO_THRESHOLD) != lastAudioNight) refreshAudioScene()
                }
                if (sim.crashedJunctionId != null) {
                    _simState.value = SimState.CRASHED
                    // 塌陷 + 横幅闷响同时起（两个不同音效，SoundPool 会叠着放），
                    // 随后 refreshAudioScene 把音乐换成 bgm_crash —— 环境层同时撤掉，
                    // "全城停了"才是这个场景该有的听觉。
                    sound.play(Sfx.CRASH)
                    sound.play(Sfx.CRASH_BANNER)
                    refreshAudioScene()
                    break
                }
                delay(16)
            }
        }
    }

    /**
     * 仿真**自己**产生的两种声音：车辆到达、信号相位切换。
     *
     * 它们不在模型层播（`TrafficSimulator` 保持纯逻辑、零音频依赖），而是由这条循环
     * 在"快照已经产出"之后统一决策 —— 于是声音和画面严格同一帧，也不会因为掉帧而漏掉事件。
     *
     * ## 两条都必须限流，理由不同
     *  - **到达**：车流起来后每秒可能到达好几辆，逐车播就是"叮叮叮"的噪音。
     *    改成每满 [ARRIVAL_SFX_EVERY] 辆响一次，音量压到 0.35。
     *  - **相位灯**：`phase` 只对**当前选中的那个信号路口**有值（见 [phaseOfSelected]），
     *    所以全城二十个路口不会每 20 秒一起"叮"。音量 0.30，当背景细节听。
     *
     * 倍速 ≥ 2x 时两条全部静音：那时事件密度翻好几倍，再限流也会变吵。
     */
    private fun playTrafficSfx(stats: SimStats, phase: SignalPhaseRender?) {
        // ---------- 到达 ----------
        if (stats.arrived < lastArrivedForSfx) {
            // 重置仿真 / 读档之后计数会回退，跟着回退就好，别当成"又到了 10 辆"
            lastArrivedForSfx = stats.arrived
        } else if (stats.arrived - lastArrivedForSfx >= ARRIVAL_SFX_EVERY) {
            lastArrivedForSfx = stats.arrived
            if (settings.speedMultiplier < 2.0) sound.play(Sfx.ARRIVAL, ARRIVAL_SFX_GAIN)
        }

        // ---------- 相位灯 ----------
        if (phase == null) {
            lastPhaseKey = PHASE_KEY_NONE       // 取消选中 / 切换路口 → 下次重新判定
            return
        }
        val key = when {
            phase.isYellow -> PHASE_KEY_YELLOW
            phase.index == 0 -> PHASE_KEY_GREEN
            else -> PHASE_KEY_RED
        }
        if (key == lastPhaseKey) return
        lastPhaseKey = key
        if (settings.speedMultiplier >= 2.0) return
        val sfx = when (key) {
            PHASE_KEY_YELLOW -> Sfx.PHASE_YELLOW
            PHASE_KEY_GREEN -> Sfx.PHASE_GREEN
            else -> Sfx.PHASE_RED
        }
        sound.play(sfx, PHASE_SFX_GAIN)
    }

    // ==================== 设置 ====================

    fun openSettingsSheet() {
        _showSettingsSheet.value = true
        sound.play(Sfx.TAP_PRIMARY)
        syncDuck()     // 对话框打开 → 音乐退后半步（−6dB），让文字专心
    }

    fun closeSettingsSheet() {
        if (!_showSettingsSheet.value) return
        _showSettingsSheet.value = false
        sound.play(Sfx.TAP_SECONDARY)
        syncDuck()
    }

    fun updateSettings(block: SimSettings.() -> Unit) {
        // 先把三个布尔项抄下来：设置面板里的滑块每拖动一像素就会调一次这里，
        // `settings.copy()` 虽然也就几个字段，但没必要在拖动路径上分配对象。
        val grid0 = settings.gridSnap
        val lights0 = settings.trafficLightsEnabled
        val reroute0 = settings.autoReroute
        settings.block()
        _configVersion.value += 1
        // 布尔项真的翻转了才响（滑块拖动不会误触发）
        val flipped = settings.gridSnap != grid0 ||
            settings.trafficLightsEnabled != lights0 ||
            settings.autoReroute != reroute0
        if (flipped) {
            // ★ 在进入下面那条"重建拓扑"的重活之前先出声：拖动滑块时手感差别很明显
            val turnedOn = settings.gridSnap && !grid0 ||
                settings.trafficLightsEnabled && !lights0 ||
                settings.autoReroute && !reroute0
            sound.play(if (turnedOn) Sfx.TOGGLE_ON else Sfx.TOGGLE_OFF)
        }
        // 红绿灯开关变化 → 重建拓扑，让信号灯立即生效/撤销
        if (settings.trafficLightsEnabled != lights0) simulator?.rebuildTopology()
        scheduleAutoSave()
    }

    /** 取当前语言下的文案（需求 5） */
    private fun str(key: StrKey, vararg args: Any?): String = Messages.of(_lang.value).s(key, *args)

    /** 点语言按钮：在它右侧展开 / 收起「中文 · English」两个选项（本轮需求 2） */
    fun toggleLangMenu() {
        val open = !_langMenuOpen.value
        if (open) {
            _dayMenuOpen.value = false
            _rerouteMenuOpen.value = false
            _speedDialOpen.value = false
            _audioMenuOpen.value = false
        }
        _langMenuOpen.value = open
        sound.play(if (open) Sfx.MENU_OPEN else Sfx.MENU_CLOSE)
    }

    /** 收起语言菜单（点空白处 / 选中某一项之后） */
    fun closeLangMenu() {
        _langMenuOpen.value = false
    }

    /**
     * 直接选定一种语言（菜单里的「中」/「En」两个按钮）。
     *
     * 与 [setDayMode] 一致：**先收起菜单**再改语言，并写回设置 + 自动暂存。
     * 语言没变时不做任何事（连提示都不弹）—— 点自己当前那一项应该只收起菜单，
     * 让人以为"点了没反应"反而是错的。
     */
    fun setLang(next: Lang) {
        _langMenuOpen.value = false
        if (_lang.value == next) return
        _lang.value = next
        settings.language = next.name
        _configVersion.value += 1
        scheduleAutoSave()
        toast(
            str(if (next == Lang.EN) StrKey.TOAST_LANG_EN else StrKey.TOAST_LANG_ZH),
            Sfx.LANG_SWITCH,
        )
    }

    /**
     * 设置倍速。
     * @param announce 是否弹出气泡提示。倍速滑块的**拖动过程**传 false（避免刷屏），
     *                 收起滑块时再单独调用 [announceSpeed] 提示一次最终值。
     */
    fun setSpeedMultiplier(m: Double, announce: Boolean = true) {
        val before = settings.speedMultiplier
        settings.speedMultiplier = m
        _configVersion.value += 1
        scheduleAutoSave()
        // 滑块拖动时每个指针事件都会调进来，**值没变就不该出声**（否则是哒哒哒的噪音）。
        // 六档是递增音高的同一个音色，拖起来就是一串上行音阶 —— 不看屏幕也知道快了几档。
        val changed = m != before
        val tick = Sfx.ofSpeed(m)
        if (announce) {
            // 要弹气泡时把那一声"顶替"成档位音，别让"档位音 + 通用气泡音"叠着响
            announceSpeed(if (changed) tick else Sfx.TOAST_IN)
        } else if (changed) {
            sound.play(tick)
        }
    }

    /** 用气泡提示当前倍率（倍速滑块收起时调用） */
    fun announceSpeed(sfx: Sfx = Sfx.TOAST_IN) {
        val sp = settings.speedMultiplier
        val label = if (sp == sp.toInt().toDouble()) "${sp.toInt()}x" else "${sp}x"
        toast(str(StrKey.TOAST_SPEED, label), sfx)
    }

    // ==================== 倍速滑块 / 自主规划菜单 浮层 ====================

    /** 点倍速按钮：展开/收起横向圆形滑块（两个浮层互斥） */
    fun toggleSpeedDial() {
        val open = !_speedDialOpen.value
        _speedDialOpen.value = open
        if (open) {
            _rerouteMenuOpen.value = false
            _dayMenuOpen.value = false
            _audioMenuOpen.value = false
        }
        sound.play(if (open) Sfx.MENU_OPEN else Sfx.MENU_CLOSE)
    }

    /** 点屏幕任意位置确认倍速：收起滑块，并提示一次最终选中的倍率 */
    fun closeSpeedDial() {
        if (_speedDialOpen.value) {
            _speedDialOpen.value = false
            announceSpeed()     // 气泡本身带一声，不再额外播"收起"
        }
    }

    /** 点自主规划按钮：展开/收起预设菜单 */
    fun toggleRerouteMenu() {
        val open = !_rerouteMenuOpen.value
        _rerouteMenuOpen.value = open
        if (open) {
            // 同一时间只留一个右侧浮层（五个菜单都锚在同一列按钮上，叠住会互相压）
            _speedDialOpen.value = false
            _dayMenuOpen.value = false
            _langMenuOpen.value = false
            _audioMenuOpen.value = false
        }
        sound.play(if (open) Sfx.MENU_OPEN else Sfx.MENU_CLOSE)
    }

    fun closeRerouteMenu() {
        if (!_rerouteMenuOpen.value) return   // 本来就没开 → 不响（下面四个 closer 是逐个调用的）
        _rerouteMenuOpen.value = false
        sound.play(Sfx.MENU_CLOSE)
    }

    /** 收起所有浮层（切模式/收起设置行时调用；不触发倍速提示） */
    private fun closeOverlays() {
        _speedDialOpen.value = false
        _rerouteMenuOpen.value = false
        _dayMenuOpen.value = false
        _langMenuOpen.value = false
        _audioMenuOpen.value = false
        _signalRuleDialogNodeId.value = null
        syncDuck()      // 信号对话框可能就是这样被顺手关掉的
    }

    /** 一键套用「自主规划（重路由阈值）」预设 */
    fun applyReroutePreset(preset: ReroutePreset) {
        // 与 setDayMode / setLang 一致：**先收起菜单再套用**。
        // 所以"选完自动收起"这件事在 ViewModel 里完成，界面那边只管调用 ——
        // 否则每个调用点都要记得自己补一句 closeRerouteMenu()，漏一次菜单就赖着不走。
        _rerouteMenuOpen.value = false
        settings.autoReroute = preset.enabled
        settings.rerouteWindowSec = preset.window
        settings.rerouteSpeedRatio = preset.ratio
        settings.rerouteCooldownSec = preset.cooldown
        _configVersion.value += 1
        scheduleAutoSave()
        toast(str(StrKey.TOAST_REROUTE_PRESET, str(preset.nameKey)), Sfx.TAP_PRIMARY)
    }

    /** 右上角统计面板开关 */
    fun toggleStats() {
        _showStats.value = !_showStats.value
        settings.showStats = _showStats.value
        _configVersion.value += 1
        scheduleAutoSave()
        sound.play(if (_showStats.value) Sfx.TOGGLE_ON else Sfx.TOGGLE_OFF)
    }

    /**
     * **昼夜模式**（本轮需求 1）：白天 / 夜间 / 自动循环 三选一。
     *
     * 只改渲染：路面配色、车灯、路灯、影子方向全由渲染层按这个值算出来，
     * 仿真逻辑（车速、信号灯、让行）**完全不受影响** —— 换的只是"一套灯"。
     *
     * 选择写入设置并自动暂存，重启后仍是同一个模式。
     * 点完顺手把菜单收起（三个按钮只该在"正在选"的时候在场）。
     */
    fun setDayMode(mode: DayMode) {
        _dayMode.value = mode
        _dayMenuOpen.value = false
        settings.dayMode = mode.name
        // 旧字段同步维护一份，万一被回滚到老版本也能读出个大概
        settings.nightMode = mode == DayMode.NIGHT
        // 界面层的"夜晚程度"立刻切到位：手动模式是 0/1（界面会补间），
        // 自动模式从**当前仿真时刻**接着走（而不是从 0 重来）
        _dayNight.value = when (mode) {
            DayMode.DAY -> 0f
            DayMode.NIGHT -> 1f
            DayMode.AUTO -> DayCycle.lightAt(DayCycle.phaseAt(currentSimTime())).night
        }
        _configVersion.value += 1
        scheduleAutoSave()
        // 昼夜切换的音效**故意比视觉更长**（1.6 秒 vs 340ms 天色补间）：
        // 听觉上"天慢慢变了"，比画面先结束更有氛围。AUTO 用的是"齿轮上弦"。
        toast(
            str(
                when (mode) {
                    DayMode.DAY -> StrKey.TOAST_DAY
                    DayMode.NIGHT -> StrKey.TOAST_NIGHT
                    DayMode.AUTO -> StrKey.TOAST_DAY_AUTO
                }
            ),
            when (mode) {
                DayMode.DAY -> Sfx.TO_DAY
                DayMode.NIGHT -> Sfx.TO_NIGHT
                DayMode.AUTO -> Sfx.AUTO_CYCLE_ON
            },
        )
        refreshAudioScene()     // 夜里换"夜间"那一袋 + 环境层换 amb_night_city
    }

    /** 点那个昼夜按钮：在它右侧展开 / 收起三个选项（白天 · 夜间 · 自动） */
    fun toggleDayMenu() {
        val open = !_dayMenuOpen.value
        // 同一时间只留一个右侧浮层：三个菜单都挂在同一列按钮上，叠在一起会互相压住
        if (open) {
            _langMenuOpen.value = false
            _rerouteMenuOpen.value = false
            _speedDialOpen.value = false
            _audioMenuOpen.value = false
        }
        _dayMenuOpen.value = open
        sound.play(if (open) Sfx.MENU_OPEN else Sfx.MENU_CLOSE)
    }

    fun closeDayMenu() {
        if (!_dayMenuOpen.value) return
        _dayMenuOpen.value = false
        sound.play(Sfx.MENU_CLOSE)
    }

    // ==================== 声音（本轮新增）====================

    /**
     * 点「音频」按钮：在它**右侧**展开 / 收起「上一首 · 下一首 · 音乐 · 音效」四个并排的圆钮。
     *
     * 与昼夜、语言完全同一套交互（也共用"同一时间只开一个"的约定）：
     * 三个 Popup 都锚在同一列按钮上，同时展开会互相叠住。
     */
    fun toggleAudioMenu() {
        val open = !_audioMenuOpen.value
        if (open) {
            _dayMenuOpen.value = false
            _langMenuOpen.value = false
            _rerouteMenuOpen.value = false
            _speedDialOpen.value = false
        }
        _audioMenuOpen.value = open
        sound.play(if (open) Sfx.MENU_OPEN else Sfx.MENU_CLOSE)
    }

    /** 收起音频菜单（点空白处 / 收起设置行时） */
    fun closeAudioMenu() {
        _audioMenuOpen.value = false
    }

    // ---------- 播放列表：上一首 / 下一首 / 自动接续 ----------

    /** 上一首（在当前场景这一袋里往回走；走到头就重洗一袋） */
    fun prevBgm() = stepBgm(-1)

    /** 下一首（同上，往后走） */
    fun nextBgm() = stepBgm(+1)

    /**
     * 切到这一袋里的相邻一首。
     *
     * @param delta +1 = 下一首，-1 = 上一首
     *
     * 三个要点：
     *  1. **音乐关掉时直接返回**。切了也听不见，却会让"下一首"看起来像坏了 ——
     *     按钮在界面上同时被置灰，两边表达一致。
     *  2. 走到一袋的尽头就**重洗一袋**（不是从头按原顺序再来一遍），这才是"随机循环"；
     *     洗牌后若第一首恰好是刚播完的那首，把它转到最后 —— 否则听起来像"点了没反应"。
     *  3. 只改目标曲目，不碰播放器。真正的交叉淡化在 [SoundCenter] 的混音循环里完成。
     */
    private fun stepBgm(delta: Int) {
        if (!settings.musicEnabled) return
        if (bgmBag.isEmpty()) return

        var i = bgmIndex + delta
        if (i !in bgmBag.indices) {
            val justPlayed = bgmBag[bgmIndex]
            val reshuffled = bgmBag.shuffled()
            bgmBag = if (reshuffled.size > 1 && reshuffled[0] == justPlayed) {
                reshuffled.drop(1) + reshuffled[0]
            } else {
                reshuffled
            }
            i = if (delta > 0) 0 else bgmBag.lastIndex
        }
        bgmIndex = i
        sound.setBgm(bgmBag[bgmIndex])
    }

    /**
     * 当前曲目放完了 → 接下一首。
     *
     * 崩溃场景**不接**：那里只有一首 `bgm_crash`，让它自己循环（`isLooping`），
     * 否则崩溃余波会被悄悄换成白天/夜间的音乐，气氛全毁。
     */
    private fun autoAdvanceBgm() {
        if (_simState.value == SimState.CRASHED) return
        if (bgmScene == null) return
        stepBgm(+1)
    }

    /**
     * 换一个场景的播放列表：**洗牌**后从第一首开始。
     *
     * 洗牌本身就实现了"随机选一首开场"，不需要再单独掷一次随机 ——
     * 一个 4 首的列表洗完之后，第一首在 4 首里是等概率的。
     */
    private fun startPlaylist(scene: BgmScene) {
        val bag = scene.tracks.shuffled()
        bgmScene = scene
        bgmBag = bag
        bgmIndex = 0
        sound.setBgm(bag[0])
    }

    /**
     * 按 **模式 × 昼夜 × 崩溃** 决定播哪一组音乐、要不要铺环境层。
     *
     * | 状态 | 音乐 | 环境层 |
     * |---|---|---|
     * | 崩溃 | `bgm_crash`（单曲循环，不接续） | 无（都停了，才叫"余波"） |
     * | 夜间（手动或自动 > 0.55） | 夜间观察 4 首随机循环 | 运行中 → `amb_night_city` |
     * | 白天 · 编辑 | 编辑 2 首随机循环 | 无（没有车，街道底噪会显得假） |
     * | 白天 · 观看 | 白天观察 4 首随机循环 | 运行中 → `amb_day_city` |
     *
     * 这个函数是**幂等**的：同场景重复调用不会重头播，也不会重新洗牌 ——
     * 否则"暂停 → 继续"、"开关一个对话框"都会莫名换一首歌。
     * 所以可以放心在"任何可能改变场景的地方"都调一次。
     */
    private fun refreshAudioScene() {
        val night = isNightNow()
        val crashed = _simState.value == SimState.CRASHED
        val edit = _uiMode.value == UiMode.EDIT
        val running = _simState.value == SimState.RUNNING

        val amb = when {
            crashed || !running || edit -> null
            night -> BgmTrack.AMB_NIGHT
            else -> BgmTrack.AMB_DAY
        }
        sound.setAmbience(amb)

        if (crashed) {
            // 场景标记清空：退出崩溃后重新洗一袋（回到"随机开场"的语义）
            bgmScene = null
            sound.setBgm(BgmTrack.CRASH)
        } else {
            val scene = when {
                night -> BgmScene.NIGHT
                edit -> BgmScene.EDIT
                else -> BgmScene.DAY_VIEW
            }
            if (scene != bgmScene || bgmBag.isEmpty()) {
                startPlaylist(scene)
            } else {
                // 场景没变（只是运行/暂停、开了个面板）：保持当前这首
                sound.setBgm(bgmBag[bgmIndex])
            }
        }
        lastAudioNight = night
    }

    /** 有"多首可随机"的三个场景；每首曲子绑定一组曲目，见 [BgmTrack] 的伴生列表 */
    private enum class BgmScene(val tracks: List<BgmTrack>) {
        DAY_VIEW(BgmTrack.DAY_VIEW_PLAYLIST),
        NIGHT(BgmTrack.NIGHT_PLAYLIST),
        EDIT(BgmTrack.EDIT_PLAYLIST),
    }

    /**
     * 音乐开关。
     *
     * 反馈音用**音效通道**而不是音乐通道 —— 音乐刚被关掉的那一刻，
     * 只有音效还能说明"我确实收到了这一下"。
     */
    fun toggleMusic() {
        val on = !settings.musicEnabled
        settings.musicEnabled = on
        sound.setMusicEnabled(on)
        sound.play(if (on) Sfx.TOGGLE_ON else Sfx.TOGGLE_OFF)
        _configVersion.value += 1
        scheduleAutoSave()
    }

    /**
     * 音效开关。
     *
     * ⚠️ 开关顺序是反的，故意的：
     *  - **开**：先启用引擎、再播音 —— 否则这一声会被"尚未启用"吃掉；
     *  - **关**：先播音、再禁用引擎 —— 否则最后那一声"咔"自己把自己掐断，
     *    用户会以为按钮没响应。
     */
    fun toggleSfx() {
        val on = !settings.sfxEnabled
        settings.sfxEnabled = on
        if (on) {
            sound.setSfxEnabled(true)
            sound.play(Sfx.TOGGLE_ON)
        } else {
            sound.play(Sfx.TOGGLE_OFF)
            sound.setSfxEnabled(false)
        }
        _configVersion.value += 1
        scheduleAutoSave()
    }

    /**
     * 触觉反馈开关（音频浮层最右边那个「轻震」）。
     *
     * 这里**没有** [toggleSfx] 那种"顺序陷阱"：轻震是在**手指按下那一刻**由界面层直接
     * 触发的（不等这次的 onClick），所以关掉它的这一下本身已经震过了 ——
     * 用户一定能感觉到"这个按钮收到了我的点击"，不需要额外补一次。
     *
     * 声音照常给：触觉是**第三通道**，与音乐 / 音效互不影响，关震动不该把声音一起关掉。
     */
    fun toggleHaptic() {
        val on = !settings.hapticEnabled
        settings.hapticEnabled = on
        _hapticEnabled.value = on
        sound.play(if (on) Sfx.TOGGLE_ON else Sfx.TOGGLE_OFF)
        _configVersion.value += 1
        scheduleAutoSave()
    }

    /** 现在算不算"夜里"（手动模式看模式，自动模式看夜晚程度是否越过阈值） */
    private fun isNightNow(): Boolean = when (_dayMode.value) {
        DayMode.DAY -> false
        DayMode.NIGHT -> true
        DayMode.AUTO -> _dayNight.value > NIGHT_AUDIO_THRESHOLD
    }

    /** 当前仿真时刻（没跑过就是 0），用来让"切进自动模式"时从当下这一刻接着走 */
    private fun currentSimTime(): Double = _frame.value?.simTime ?: 0.0

    /** 设置 / 存档里的昼夜模式 → 状态流。空串 = 旧存档，按 nightMode 推断 */
    private fun resolveDayMode(): DayMode = when (settings.dayMode) {
        DayMode.NIGHT.name -> DayMode.NIGHT
        DayMode.AUTO.name -> DayMode.AUTO
        DayMode.DAY.name -> DayMode.DAY
        else -> if (settings.nightMode) DayMode.NIGHT else DayMode.DAY
    }

    /** 读档 / 启动时把设置里的昼夜模式同步到状态流 */
    private fun syncDayModeFromSettings() {
        val mode = resolveDayMode()
        _dayMode.value = mode
        _dayNight.value = when (mode) {
            DayMode.DAY -> 0f
            DayMode.NIGHT -> 1f
            DayMode.AUTO -> DayCycle.lightAt(DayCycle.phaseAt(currentSimTime())).night
        }
    }

    // ==================== 去 UI（沉浸看图）====================

    /**
     * 切换「去UI」（本轮需求 4）。
     *
     * 进入时顺手把**所有浮层**收干净：倍速滑块、自主规划菜单、左侧按钮列、
     * 弹窗状态、以及车辆锁定 —— 否则界面已经淡出，背后还留着一个"看不见但能点"的东西。
     */
    fun toggleHideUi() = setHideUi(!_hideUi.value)

    fun setHideUi(on: Boolean) {
        if (_hideUi.value == on) return
        _hideUi.value = on
        if (on) {
            closeOverlays()
            dismissLeftMenus()
            clearFocus()                 // 需求 4：锁定车辆在去UI模式下不可用
        }
        // 像灯被慢慢关掉 / 打开：500ms 的空气声，正好覆盖界面 380ms 的淡出淡入
        sound.play(if (on) Sfx.UI_HIDE else Sfx.UI_SHOW)
    }

    // ==================== 清空（带确认） ====================

    fun requestClear() {
        _showClearConfirm.value = true
        sound.play(Sfx.DIALOG_OPEN)
        syncDuck()
    }

    fun cancelClear() {
        if (!_showClearConfirm.value) return
        _showClearConfirm.value = false
        sound.play(Sfx.DIALOG_CLOSE)
        syncDuck()
    }

    fun confirmClear() {
        if (!_showClearConfirm.value) return
        _showClearConfirm.value = false
        syncDuck()
        clearAll()
    }

    // ==================== 撤销 ====================

    private fun pushUndo() {
        val snap = SimSnapshot(network, junctionConfigs.toMap())
        val text = runCatching { repository.codec.encodeToString(SimSnapshot.serializer(), snap) }.getOrNull() ?: return
        undoStack.addLast(text)
        while (undoStack.size > undoLimit) undoStack.removeFirst()
        redoStack.clear()          // 产生新的编辑操作 → 旧的“重做”链作废
        syncUndoFlag()
    }

    fun undo() {
        val text = undoStack.removeLastOrNull()
            ?: run {
                // 「没有可撤销的」不是报错，是很轻的一声"嗯，没有"
                toast(str(StrKey.TOAST_NO_UNDO), Sfx.NOOP_SOFT)
                return
            }
        // ★ 先出声再干活：下面要把整份快照反序列化 + 重新压栈（大路网 10~50ms），
        //   排在音效前面就成了"手都抬起来了才响"。撤销/重做是连点的高频操作，最听得出来。
        sound.play(Sfx.UNDO)
        val snap = runCatching { repository.codec.decodeFromString(SimSnapshot.serializer(), text) }.getOrNull()
        // 解码失败极罕见（快照被破坏）：这一声已经响过，只弹文字，不叠第二声
        if (snap == null) { toast(str(StrKey.TOAST_UNDO_FAILED), null); return }
        // 先把"当前状态"压入重做栈，之后就能原样还原回来
        runCatching {
            redoStack.addLast(
                repository.codec.encodeToString(
                    SimSnapshot.serializer(),
                    SimSnapshot(network, junctionConfigs.toMap()),
                )
            )
        }
        resetSimulation()
        network = snap.network
        junctionConfigs.clear()
        junctionConfigs.putAll(snap.configs)
        clearSelection()
        _networkVersion.value += 1
        syncUndoFlag()
        scheduleAutoSave()
        toast(str(StrKey.TOAST_UNDONE), null)      // 音效在函数开头已经播过
    }

    /** 重做：把刚被撤销掉的那一步（元素）重新生成回来 */
    fun redo() {
        val text = redoStack.removeLastOrNull()
            ?: run {
                toast(str(StrKey.TOAST_NO_REDO), Sfx.NOOP_SOFT)
                return
            }
        // 同 undo()：先把声音发出去，再做反序列化 + 把当前状态序列化压栈这两件重活
        sound.play(Sfx.REDO)
        val snap = runCatching { repository.codec.decodeFromString(SimSnapshot.serializer(), text) }.getOrNull()
        if (snap == null) { toast(str(StrKey.TOAST_REDO_FAILED), null); return }
        // 当前状态压回撤销栈，保证撤销/重做可以来回切换
        runCatching {
            undoStack.addLast(
                repository.codec.encodeToString(
                    SimSnapshot.serializer(),
                    SimSnapshot(network, junctionConfigs.toMap()),
                )
            )
            while (undoStack.size > undoLimit) undoStack.removeFirst()
        }
        resetSimulation()
        network = snap.network
        junctionConfigs.clear()
        junctionConfigs.putAll(snap.configs)
        clearSelection()
        _networkVersion.value += 1
        syncUndoFlag()
        scheduleAutoSave()
        toast(str(StrKey.TOAST_REDONE), null)      // 音效在函数开头已经播过
    }

    private fun syncUndoFlag() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    // ==================== 存档 ====================

    fun listSavedNames(): List<String> = repository.list().map { it.name }

    /**
     * 全部存档（**含路网本体**），供"海报式加载面板"画缩略图用。
     *
     * 与 [listSavedNames] 的区别只是要不要把 `network` 一起交出去：
     * 缩略图必须知道每条路怎么弯、几车道，光有名字画不出来。
     * 读盘 + JSON 反序列化是同步的，所以调用方（加载弹窗）只在**打开时算一次**。
     */
    fun listSaved(): List<SavedNetwork> = repository.list()

    fun openSaveDialog() {
        _showSaveDialog.value = true
        sound.play(Sfx.DIALOG_OPEN)
        syncDuck()
    }

    fun openLoadDialog() {
        _showLoadDialog.value = true
        sound.play(Sfx.DIALOG_OPEN)
        syncDuck()
    }

    fun closeDialogs() {
        val wasOpen = _showSaveDialog.value || _showLoadDialog.value
        _showSaveDialog.value = false
        _showLoadDialog.value = false
        if (wasOpen) {
            sound.play(Sfx.DIALOG_CLOSE)
            syncDuck()
        }
    }

    /**
     * 保存当前路网。
     *
     * @return 是否真的存下去了。**名字为空时返回 false** —— 弹窗据此决定要不要关：
     *         旧实现无论成败都关弹窗，用户辛苦起名字发现"弹窗没了、只闪了一条提示"，
     *         还得重新点保存、重新输一遍。留着弹窗，改个字就能重试。
     */
    fun saveCurrent(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) { toast(str(StrKey.TOAST_NAME_EMPTY), Sfx.SAVE_FAIL); return false }
        return runCatching { repository.save(n, network, junctionConfigs, settings) }
            .onSuccess { toast(str(StrKey.TOAST_SAVED, n), Sfx.SAVE_OK) }
            .onFailure { toast(str(StrKey.TOAST_SAVE_FAILED, it.message), Sfx.SAVE_FAIL) }
            .isSuccess
    }

    fun loadNetwork(name: String) {
        val saved = repository.list().firstOrNull { it.name == name }
        if (saved == null) { toast(str(StrKey.TOAST_SAVE_MISSING)); return }
        resetSimulation()
        network = saved.network
        junctionConfigs.clear()
        junctionConfigs.putAll(saved.configs)
        settings = saved.settings
        _showStats.value = settings.showStats
        // 需求 1：昼夜模式也随存档恢复
        syncDayModeFromSettings()
        // 需求 5：存档里的语言也一并恢复
        _lang.value = if (settings.language == "EN") Lang.EN else Lang.ZH
        undoStack.clear()
        redoStack.clear()
        syncUndoFlag()
        clearSelection()
        _networkVersion.value += 1
        _configVersion.value += 1
        ensureJunctionNumbers()
        scheduleAutoSave()
        // 读档会换掉**整份设置**（含昼夜模式），音乐场景跟着重算；音乐开关也一起恢复
        sound.setMusicEnabled(settings.musicEnabled)
        sound.setSfxEnabled(settings.sfxEnabled)
        sound.setVolumes(settings.bgmVolume.toFloat(), settings.sfxVolume.toFloat())
        refreshAudioScene()
        toast(str(StrKey.TOAST_LOADED, name), Sfx.LOAD_OK)
    }

    fun deleteSaved(name: String) {
        repository.delete(name)
        toast(str(StrKey.TOAST_DELETED_SAVE, name), Sfx.DELETE_SAVE)
    }

    fun clearAll() {
        pushUndo()
        resetSimulation()
        network.clear()
        junctionConfigs.clear()
        clearSelection()
        cancelDraw()
        _networkVersion.value += 1
        scheduleAutoSave()
        toast(str(StrKey.TOAST_CLEARED), Sfx.CLEAR_CANVAS)
    }

    // ==================== 杂项 ====================

    /**
     * 弹一条轻提示，并播出它对应的那一声。
     *
     * @param sfx 这次操作**自己的**音效，默认是通用的 `toast_in`。
     *            保存 / 加载 / 撤销 / 改车道 / 昼夜切换这些都有专属音效，
     *            它们会**顶替**掉通用的"叮" —— 一次操作只响一声。
     *            两套都播会听成"两个事件"，反而让人分不清发生了什么。
     */
    /**
     * 弹一条气泡。
     *
     * @param sfx 伴随音效。传 `null` = **不发声** —— 给"音效已经在动作一开始就播过"的
     *            调用点用（撤销 / 重做），否则那一击会响两声。
     */
    private fun toast(msg: String, sfx: Sfx? = Sfx.TOAST_IN) {
        _toast.value = msg
        if (sfx != null) sound.play(sfx)
    }

    fun clearToast() {
        _toast.value = null
    }

    /**
     * ViewModel 回收：把两条 MediaPlayer、那个 SoundPool 和音频焦点一次收干净。
     *
     * 只在这里释放（而不是 Activity 的 `onDestroy`）：屏幕旋转之类的重建不会销毁 ViewModel，
     * 音乐不该被打断；真正的销毁才该收尾。
     */
    override fun onCleared() {
        sound.release()
        super.onCleared()
    }

    // ==================== 预设 ====================

    /**
     * 「自主规划（重路由阈值）」预设：一个预设同时设定 观察窗口 / 触发阈值 / 冷却。
     * 默认「标准」= 5 秒窗口 · 30% 阈值 · 10 秒冷却。
     */
    data class ReroutePreset(
        /** 预设名文案键（需求 5：菜单按当前语言渲染，不再写死中文） */
        val nameKey: StrKey,
        /**
         * 圆钮上的**短标签**文案键。
         *
         * 为什么不能直接用 [nameKey]：`preset.matches` 展开是「保守（10 秒 · 50% · 20 秒冷却）」，
         * 二十来个字符塞进 44dp 的圆钮会糊成一团。按钮上只放 1~2 个字，
         * 完整说明留给选中之后的 toast（`TOAST_REROUTE_PRESET` 里带的就是 [nameKey]）。
         */
        val shortKey: StrKey,
        val enabled: Boolean,
        val window: Double,
        val ratio: Double,
        val cooldown: Double,
    ) {
        /** 当前设置是否正好等于本预设（用于把对应的那个圆钮填成紫色） */
        fun matches(s: SimSettings): Boolean =
            s.autoReroute == enabled &&
                abs(s.rerouteWindowSec - window) < 1e-6 &&
                abs(s.rerouteSpeedRatio - ratio) < 1e-6 &&
                abs(s.rerouteCooldownSec - cooldown) < 1e-6
    }

    companion object {
        /**
         * 单帧最多推进多少个仿真子步。
         *
         * 5x 倍速 + 60fps 时，每帧约需 5 个子步（5 × 16.7ms ≈ 83ms 仿真时间）。
         * 留到 16 步 = 能吞下 5x 下 260ms 的卡顿；再多就说明设备已经跟不上，
         * 这时宁可让仿真慢下来，也不要一次跑几十步（会瞬间"快进"，还会把单帧耗时拖得更长）。
         */
        private const val MAX_STEPS_PER_FRAME = 16

        /**
         * 切换 BGM 场景的推迟量（毫秒）。
         *
         * 底栏"换班"的节奏：旧组 160ms 淡出完，新组 140ms 预热 + 逐个错峰（0/45/90/135/180ms）
         * 起跑，弹簧大约 400ms 收尾。取 380ms = 最后一个按钮也已经开始滑入、旧组早已不在。
         * 这时偶尔掉一帧落在弹簧中途，几乎看不出来；而音乐只晚 0.38 秒开始淡出，听感无差。
         */
        private const val AUDIO_SCENE_DEFER_MS = 380L

        /** 曲线工具的默认曲率参数（0.35 ≈ 一段温和的弧） */
        const val DEFAULT_CURVE = 0.35

        /**
         * 编辑模式红绿灯小图标：在路口净空半径之外**再外移的世界距离**（米）。
         *
         * 用"米"而不是"像素"是为了让图标**随路网一同缩放**（需求 5）——缩放时它始终贴在
         * 路口外侧同样的位置，不会因为放大而压到路面上、缩小而飘远。
         * 画布绘制与点击命中都用这一个常量，所见即所点。
         */
        const val SIGNAL_GAP_M = 2.5

        /** 重路由预设档位（与倍速二级菜单同款交互，点一下直接套用） */
        val REROUTE_PRESETS = listOf(
            ReroutePreset(
                StrKey.PRESET_OFF, StrKey.PRESET_OFF_SHORT,
                enabled = false, window = 5.0, ratio = 0.30, cooldown = 10.0,
            ),
            ReroutePreset(
                StrKey.PRESET_SENSITIVE, StrKey.PRESET_SENSITIVE_SHORT,
                enabled = true, window = 2.0, ratio = 0.20, cooldown = 5.0,
            ),
            ReroutePreset(
                StrKey.PRESET_STANDARD, StrKey.PRESET_STANDARD_SHORT,
                enabled = true, window = 5.0, ratio = 0.30, cooldown = 10.0,
            ),
            ReroutePreset(
                StrKey.PRESET_CONSERVATIVE, StrKey.PRESET_CONSERVATIVE_SHORT,
                enabled = true, window = 10.0, ratio = 0.50, cooldown = 20.0,
            ),
        )

        // ---------- 声音（本轮新增）----------

        /**
         * 自动昼夜模式下"这首曲子该算白天还是夜里"的分界点。
         *
         * 取 0.55 而不是 0.5：黄昏渐暗的过程中，0.5 附近音乐已经该变暖了，
         * 但界面还亮着；0.55 听感与视觉更对得上（也与素材清单里的建议一致）。
         */
        private const val NIGHT_AUDIO_THRESHOLD = 0.55f

        /**
         * 车辆到达音效的**计数间隔**：每满这么多辆响一次。
         *
         * 逐车播会变成"叮叮叮"（车流起来后每秒好几辆）。用计数而不是时间：
         * 车越多越密，而车多正是最该安静的时候。
         */
        private const val ARRIVAL_SFX_EVERY = 10

        /** 到达音效的相对音量（0.35 ≈ −9dB）：是背景细节，不是提示音 */
        private const val ARRIVAL_SFX_GAIN = 0.35f

        /** 相位灯音效的相对音量（0.30）：全城只有选中的那个路口会响，仍然压得很低 */
        private const val PHASE_SFX_GAIN = 0.30f

        /** 相位灯"灯色"编码：用于判断这一帧是不是刚换灯，避免每帧重播 */
        private const val PHASE_KEY_NONE = -1
        private const val PHASE_KEY_GREEN = 0
        private const val PHASE_KEY_YELLOW = 1
        private const val PHASE_KEY_RED = 2
    }
}

