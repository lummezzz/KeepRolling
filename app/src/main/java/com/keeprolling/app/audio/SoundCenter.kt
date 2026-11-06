package com.keeprolling.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 全局音频中心：**一个 [SoundPool] 播 63 个短音效 + 两条 [MediaPlayer] 长音轨**。
 *
 * ## 三条通道
 * ```
 *  SFX   SoundPool（最多 4 路并发，超了丢弃新的，绝不排队）
 *  BGM   MediaPlayer ×2（current + outgoing，用于交叉淡化），isLooping = true
 *  AMB   MediaPlayer ×2（同上），环境底噪，音量固定为 BGM 的 40%
 * ```
 * 后两条各自**同一时刻只有一个"真实发声主体"**：切换时旧的那条退到 outgoing、音量降到 0
 * 就立刻 `release()`。所以不会出现"切了三次场景，三首音乐叠在一起"这种事故 ——
 * 这是"循环播放不冲突"的硬保证。
 *
 * ## 为什么淡化不用"一个切歌协程 + cancel 上一个"
 * 快速连点昼夜按钮时，取消会把 MediaPlayer 停在"半淡出"的音量上，然后新的协程又去抢
 * 同一个引用，很容易出现"淡到一半被 release"或"音量卡在 0.3 再也不动"。
 * 这里改成一个常驻的**混音循环**（[MIXER_TICK_MS] 一跳）：它只做一件事 ——
 * 把每条通道的音量朝**目标值**挪一小步，挪到 0 的 outgoing 就地释放。
 * 于是"切歌"退化成"改一个目标值"，任何时序下都自洽，也不可能有两首同时满音量。
 *
 * ## 限流（防止机关枪）
 *  - 同一音效 [SAME_SFX_THROTTLE_MS] 内只播一次（拖端点会以 240Hz 产生事件）；
 *  - `SoundPool` 本身 `maxStreams = 4`，第 5 个直接丢；
 *  - 目标音量 ≤ 0 时**提前返回**，不占通道。
 *
 * ## 线程
 * 音效可以在任意线程播（`SoundPool.play` 是线程安全的，仿真循环里的事件也走它）；
 * 音乐的状态切换一律 `launch` 到主线程串行执行（`Dispatchers.Main.immediate`）。
 * 所以 `sfxEnabled` / `sfxGain` 用 `@Volatile`，音乐侧的字段只在主线程读写。
 *
 * ## 「按下 → 听见」这条链上的四段延迟（按影响从大到小）
 * ```
 *  ① 音频通路     USAGE_GAME → 普通混音通路，缓冲大：~80~150ms
 *                 ＋ [FLAG_LOW_LATENCY]（API 29+）→ 命中快车道：~20~40ms
 *  ② 首条 AudioTrack 第一次 play() 才现场建轨道：+10~40ms（只影响第一击）
 *                  → [warmUpOnce] 在启动阶段悄悄建好（音量 0，无声）
 *  ③ 素材音头     个别 wav 前面垫了 3~40ms 静音 → SoundPool 从 0 采样播，白等
 *                  → 已由 tools/trim_sfx_preroll.py 统一切到 ~1ms
 *  ④ 调用时机     play 排在 rebuildTopology / JSON 反序列化后面：+10~50ms
 *                  → ViewModel 侧统一改成"先出声、再干活"
 * ```
 * 四段叠起来就是"点了等一下才响"。任何一段单独优化都只能省一部分，所以四处都要做。
 * **1x 播放速度下这个差别最刺耳**：音效本来就是给"手指反馈"用的，超过 ~60ms 就不像自己按的。
 */
class SoundCenter(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 混音循环跑在主线程：只改音量，几十微秒一跳，不占仿真线程 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ==================== 音效 ====================

    private val sfxList = Sfx.values()

    /**
     * 音效的音频属性 —— **出声延迟的大头就在这三行**。
     *
     * `USAGE_GAME` 会把声音送到媒体通路上，那条路为了省电和防断续，缓冲开得比较厚，
     * 从 `play()` 到真的从扬声器出来常有 80~150ms。`FLAG_LOW_LATENCY`（API 29 起）
     * 是系统专门给按键音 / 点击音留的**快车道**：命中后这条链会缩到 20~40ms。
     *
     * - 为什么不用 `USAGE_ASSISTANCE_SONIFICATION`：它会走**系统音量**流，
     *   手机静音 / 免打扰时整片音效都会哑掉。这个 App 的音效是核心反馈，
     *   不能因为用户调了静音就全没 —— 宁可慢一点也要保证"按了有声音"。
     * - 拿不到快车道也不影响功能：系统会静默回落到普通通路，只是没那么快。
     */
    private val sfxAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .apply {
            // ⚠️ `AudioAttributes.Builder` 只有**覆盖式**的 `setFlags(int)`，
            //    没有 `addFlags`（那是 `AudioTrack.Builder` / `Bundle` 的写法）。
            //    这里本来就没设过别的 flag，直接覆盖 ≡ "只带这一个"。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            }
        }
        .build()

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(SFX_MAX_STREAMS)
        .setAudioAttributes(sfxAttributes)
        .build()

    /** `ordinal` → SoundPool 的 sampleId */
    private val sampleIds = IntArray(sfxList.size)

    /** `ordinal` → 是否已解码完成（没加载完就 play 是静默的，也白占一次限流） */
    private val loaded = BooleanArray(sfxList.size)

    /** `ordinal` → 上次播放的 `uptimeMillis`，用于同音效限流 */
    private val lastPlayAt = LongArray(sfxList.size)

    /** sampleId → `ordinal`（`onLoadComplete` 只给 sampleId） */
    private val sampleToIndex = HashMap<Int, Int>(sfxList.size * 2)

    /**
     * 是否已经做过一次"空播预热"。
     *
     * `@Volatile`：`onLoadComplete` 由 SoundPool 的解码线程回调，热身在那个线程上发起
     * （`SoundPool.play` 本身线程安全），而 [release] 可能在主线程同时跑。
     */
    @Volatile
    private var warmedUp: Boolean = false

    @Volatile
    private var sfxEnabled: Boolean = true

    @Volatile
    private var sfxGain: Float = DEFAULT_SFX_GAIN

    /** 画路底噪那条循环的 streamId；0 = 没有在循环 */
    @Volatile
    private var loopStreamId: Int = 0

    // ==================== 音乐 / 环境 ====================

    @Volatile
    private var musicGain: Float = DEFAULT_BGM_GAIN

    private var musicEnabled: Boolean = true

    /** 应用是否在前台（`onStop` / `onStart` 维护）：后台一律不发声 */
    private var foreground: Boolean = true

    /** 被别的 App 抢了音频焦点（通话 / 视频）：暂停，等回到前台再续 */
    private var focusLost: Boolean = false

    /** 临时"压低"（对话框 / 崩溃 / 存档时让路），只影响音量、不暂停 */
    private var ducked: Boolean = false

    // ---- 目标曲目（"我想听哪首"）----
    private var bgmWanted: BgmTrack? = null
    private var ambWanted: BgmTrack? = null

    /**
     * 当前 BGM 快放完时**回调一次**（主线程），由 ViewModel 决定"下一首是什么"。
     *
     * 为什么放在引擎里而不是让 ViewModel 自己起个定时器：只有引擎知道
     * `MediaPlayer.currentPosition` 和 `duration`，也只有在同一线程里改目标曲目，
     * 才不会和混音循环抢状态（见文件头的线程模型）。
     *
     * 触发时机是**曲尾前 [TRACK_ADVANCE_LEAD_MS]** 而不是"播完之后"：
     * 那时切歌，旧曲还在响、新曲开始淡入 → 是一个交叉淡化，不是一个空档。
     */
    var onTrackFinished: (() -> Unit)? = null

    /** 一轮只触发一次；换曲（或被拖回开头）后重新武装 */
    private var advanceArmed: Boolean = true

    /** 当前 BGM 的总时长（建播放器时读**一次**就够，见 [checkTrackProgress]） */
    private var bgmDurationMs: Int = 0

    /** 曲尾检测的节拍计数，用来把"每跳都查"降成"每 5 跳查一次" */
    private var progressTick: Int = 0

    // ---- 实际发声的两条通道（current 发声，outgoing 正在淡出）----
    private var bgmCurrent: MediaPlayer? = null
    private var bgmCurrentTrack: BgmTrack? = null
    private var bgmCurrentVol: Float = 0f
    private var bgmOutgoing: MediaPlayer? = null
    private var bgmOutgoingVol: Float = 0f

    private var ambCurrent: MediaPlayer? = null
    private var ambCurrentTrack: BgmTrack? = null
    private var ambCurrentVol: Float = 0f
    private var ambOutgoing: MediaPlayer? = null
    private var ambOutgoingVol: Float = 0f

    private var mixerJob: Job? = null

    // ==================== 音频焦点 ====================

    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusHandler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onFocusChange(change)
    }

    // ==================== 初始化 ====================

    init {
        // 监听器必须先挂上再 load，否则先加载完的那几个收不到回调
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            val idx = sampleToIndex[sampleId] ?: return@setOnLoadCompleteListener
            if (idx in loaded.indices) loaded[idx] = true
            // 第一个样本一就绪就顺手把音频通道建起来（见 warmUpOnce）
            warmUpOnce(idx)
        }
        // 一次性把 63 个全部解码进内存：总共约 2MB PCM，换来"任何交互都零延迟"
        for (i in sfxList.indices) {
            val id = runCatching { pool.load(appContext, sfxList[i].res, 1) }.getOrDefault(0)
            sampleIds[i] = id
            if (id != 0) sampleToIndex[id] = i
        }
    }

    /**
     * 预热音频通道：在用户第一次点按钮**之前**，用音量 0 悄悄播一下。
     *
     * `SoundPool.play()` 第一次调用时才现场创建底层 `AudioTrack`（JNI + 注册到
     * AudioFlinger，视机型 10~40ms）。那一击就会明显"慢半拍"，之后所有 play 复用
     * 同一条轨道就都是即时的 —— 典型的"只有第一次慢"的怪现象。
     *
     * 这里让那 10~40ms 发生在启动阶段（用户还在看画面），而不是发生在第一次点击上：
     *  - 音量 0 → 完全无声，也不影响系统音量；
     *  - `loop = 0` → 播完自己回收，不需要 `stop()`；最多占一个并发槽几百毫秒。
     */
    private fun warmUpOnce(idx: Int) {
        if (warmedUp) return
        warmedUp = true
        val id = if (idx in sampleIds.indices) sampleIds[idx] else 0
        if (id == 0) return
        runCatching { pool.play(id, 0f, 0f, 0, 0, 1f) }
    }

    // ==================== 音效：播放 ====================

    /**
     * 播一个音效。
     *
     * @param gain 相对音量（1 = 正常）。相位灯 / 到达这类"背景事件"会传 0.2~0.3 压下去，
     *             否则和主操作音一样响，会抢戏。
     *
     * 未加载完成、音效已关、音量过低、或 120ms 内重复 —— 都直接返回，不占通道。
     */
    fun play(sfx: Sfx, gain: Float = 1f) {
        if (!sfxEnabled) return
        val idx = sfx.ordinal
        if (idx !in loaded.indices || !loaded[idx]) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayAt[idx] < SAME_SFX_THROTTLE_MS) return
        val v = (sfxGain * gain).coerceIn(0f, 1f)
        if (v <= MIN_AUDIBLE_GAIN) return
        lastPlayAt[idx] = now
        runCatching { pool.play(sampleIds[idx], v, v, 1, 0, 1f) }
    }

    /**
     * 起一条**循环**音效（目前只有画路底噪 `sfx_draw_loop` 用）。
     *
     * 与 [play] 分开是因为它要返回 streamId 才能停：`SoundPool.play` 的 `loop = -1`
     * 会一直循环，必须用 `stop(streamId)` 收。
     */
    fun startLoop(sfx: Sfx, gain: Float = 1f) {
        if (!sfxEnabled) return
        val idx = sfx.ordinal
        if (idx !in loaded.indices || !loaded[idx]) return
        stopLoop()
        val v = (sfxGain * gain).coerceIn(0f, 1f)
        if (v <= MIN_AUDIBLE_GAIN) return
        loopStreamId = runCatching { pool.play(sampleIds[idx], v, v, 0, -1, 1f) }.getOrDefault(0)
    }

    /** 停循环音效（抬手 / 取消画路 / 关音效时调；没在播时是空操作） */
    fun stopLoop() {
        val s = loopStreamId
        loopStreamId = 0
        if (s != 0) runCatching { pool.stop(s) }
    }

    // ==================== 音效 / 音乐：开关与音量 ====================

    fun setSfxEnabled(on: Boolean) {
        if (sfxEnabled == on) return
        sfxEnabled = on
        if (!on) stopLoop()     // 关掉时立刻掐断画路底噪，不能让它"循环到天荒地老"
    }

    fun isSfxEnabled(): Boolean = sfxEnabled

    fun setMusicEnabled(on: Boolean) {
        onMain {
            if (musicEnabled == on) return@onMain
            musicEnabled = on
            if (on) {
                resumePlayers()
                ensureMixer()
            } else {
                pausePlayers()
            }
        }
    }

    fun isMusicEnabled(): Boolean = musicEnabled

    /**
     * 音量（0~1）。存进设置，重启后保持；两个开关是"静音"，这里是"多大声"。
     * 环境层不单独给用户调，它固定跟着音乐走（见 [ambTarget]）。
     */
    fun setVolumes(music: Float, sfx: Float) {
        sfxGain = sfx.coerceIn(0f, 1f)             // 音效音量可在任意线程改（@Volatile）
        onMain {
            musicGain = music.coerceIn(0f, 1f)
            ensureMixer()
        }
    }

    // ==================== 场景：放哪首 ====================

    /**
     * 指定背景音乐。**同一个值重复调用是空操作**（不会重头播）。
     *
     * 真正的切换发生在混音循环里：旧曲退成 outgoing 淡出，新曲从 0 淡入 ——
     * 于是"编辑 ↔ 观看 / 白天 ↔ 夜间 / 崩溃"全都是平滑过渡，没有咔哒声。
     */
    fun setBgm(track: BgmTrack?) {
        onMain {
            if (bgmWanted == track) return@onMain
            bgmWanted = track
            ensureMixer()
        }
    }

    /** 指定环境底噪；`null` = 这场景不需要（编辑模式 / 未运行 / 已崩溃） */
    fun setAmbience(track: BgmTrack?) {
        onMain {
            if (ambWanted == track) return@onMain
            ambWanted = track
            ensureMixer()
        }
    }

    /**
     * 让音乐临时让路（对话框打开、崩溃、存档/加载/清空时）。
     *
     * 只降 6dB（[DUCK_RATIO]）而不是静音 —— 音乐"退后半步"，音效和对白位置让出来。
     */
    fun setDucked(on: Boolean) {
        onMain {
            if (ducked == on) return@onMain
            ducked = on
            ensureMixer()
        }
    }

    /**
     * 把一段"只能发生在主线程"的操作送过去。
     *
     * 音乐侧的状态（`bgmWanted` / `musicEnabled` / 各播放器引用）**只有主线程读写**，
     * 于是混音循环不需要任何锁。调用方大多本来就在主线程（`Main.immediate` 会就地执行，
     * 不多一次调度），只有仿真循环里的"崩溃切歌"是后台线程 —— 它会排队到主线程。
     */
    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    // ==================== 生命周期 ====================

    /** 回到前台：从**暂停点**续播（不重头），并重新申请音频焦点 */
    fun onForeground() {
        if (foreground) return
        foreground = true
        requestFocus()
        resumePlayers()
        ensureMixer()
    }

    /** 切到后台：立刻停（省电、也不打扰用户），位置保留在 MediaPlayer 里 */
    fun onBackground() {
        if (!foreground) return
        foreground = false
        pausePlayers()
    }

    /** ViewModel 销毁时一次性收干净 */
    fun release() {
        mixerJob?.cancel()
        mixerJob = null
        releaseAllPlayers()
        focusRequest?.let { req ->
            runCatching { audioManager?.abandonAudioFocusRequest(req) }
        }
        focusRequest = null
        runCatching { pool.release() }
        runCatching { scope.cancel() }
    }

    // ==================== 内部：混音循环 ====================

    private fun ensureMixer() {
        if (mixerJob?.isActive == true) return
        mixerJob = scope.launch {
            while (true) {
                syncBgm()
                syncAmbience()
                stepVolumes()
                checkTrackProgress()
                val busy = bgmCurrent != null || bgmOutgoing != null ||
                    ambCurrent != null || ambOutgoing != null
                if (!busy) break
                delay(MIXER_TICK_MS)
            }
        }
    }

    /** 目标音量：音乐开关 × 前台 × 焦点 × 让路，四道闸门串联 */
    private fun bgmTarget(): Float {
        if (!musicEnabled) return 0f
        if (!foreground || focusLost) return 0f
        return if (ducked) musicGain * DUCK_RATIO else musicGain
    }

    /** 环境层固定为音乐的 40%：给人"空间感"，但永远压不住音乐 */
    private fun ambTarget(): Float = bgmTarget() * AMB_RATIO

    /** 把"想听哪首"变成"实际在播哪首"：旧的退成 outgoing，新的从 0 开始 */
    private fun syncBgm() {
        val want = bgmWanted
        if (want == bgmCurrentTrack) return

        // 换曲了：下一轮"快放完了"重新算一次
        advanceArmed = true

        // 连续切换时，先把上一条还没淡完的旧曲彻底扔掉，保证最多两路
        releaseOutgoing(isBgm = true)

        bgmCurrent?.let { old ->
            bgmOutgoing = old
            bgmOutgoingVol = bgmCurrentVol
        }
        bgmCurrent = null
        bgmCurrentTrack = null
        bgmCurrentVol = 0f
        bgmDurationMs = 0

        if (want == null) return
        // 后台 / 丢焦点时不建新播放器：只是记下"想听什么"，回前台由 resume + mixer 补上
        if (!foreground || focusLost) return
        val p = createLoopPlayer(want)
        if (p != null) {
            bgmCurrent = p
            bgmCurrentTrack = want
            bgmCurrentVol = 0f
            // 时长在这里读一次并缓存：`duration` 是跨 JNI 的查询，没必要每跳问一遍
            bgmDurationMs = runCatching { p.duration }.getOrDefault(0)
            progressTick = 0
            runCatching { p.setVolume(0f, 0f); p.start() }
        } else {
            // 创建失败（资源异常）：把目标标记为"已满足"，避免每个 tick 都重试
            bgmCurrentTrack = want
        }
    }

    private fun syncAmbience() {
        val want = ambWanted
        if (want == ambCurrentTrack) return

        releaseOutgoing(isBgm = false)

        ambCurrent?.let { old ->
            ambOutgoing = old
            ambOutgoingVol = ambCurrentVol
        }
        ambCurrent = null
        ambCurrentTrack = null
        ambCurrentVol = 0f

        if (want == null) return
        if (!foreground || focusLost) return
        val p = createLoopPlayer(want)
        if (p != null) {
            ambCurrent = p
            ambCurrentTrack = want
            ambCurrentVol = 0f
            runCatching { p.setVolume(0f, 0f); p.start() }
        } else {
            ambCurrentTrack = want
        }
    }

    /** 每个 tick 把 4 个音量朝目标挪一小步；淡出到底的就地释放 */
    private fun stepVolumes() {
        bgmCurrent?.let { p ->
            bgmCurrentVol = ramp(bgmCurrentVol, bgmTarget())
            runCatching { p.setVolume(bgmCurrentVol, bgmCurrentVol) }
        }
        bgmOutgoing?.let { p ->
            bgmOutgoingVol = ramp(bgmOutgoingVol, 0f)
            runCatching { p.setVolume(bgmOutgoingVol, bgmOutgoingVol) }
            if (bgmOutgoingVol <= MIN_AUDIBLE_GAIN) releaseOutgoing(isBgm = true)
        }
        ambCurrent?.let { p ->
            ambCurrentVol = ramp(ambCurrentVol, ambTarget())
            runCatching { p.setVolume(ambCurrentVol, ambCurrentVol) }
        }
        ambOutgoing?.let { p ->
            ambOutgoingVol = ramp(ambOutgoingVol, 0f)
            runCatching { p.setVolume(ambOutgoingVol, ambOutgoingVol) }
            if (ambOutgoingVol <= MIN_AUDIBLE_GAIN) releaseOutgoing(isBgm = false)
        }
    }

    /**
     * 曲尾检测：把"该换下一首了"交给 [onTrackFinished]（由 ViewModel 决定换成哪首）。
     *
     * 三条纪律，缺一条就会出怪事：
     *  1. **提前 [TRACK_ADVANCE_LEAD_MS] 报**，不是播完再报 —— 那时旧曲还在响、新曲开始淡入，
     *     是一个交叉淡化；播完再报会先静一小段，听得出"换歌的缝"。
     *  2. **一轮只报一次**（[advanceArmed]）。回调里 ViewModel 会调 `setBgm(下一首)`，
     *     但真正的换曲发生在下一个 tick 的 [syncBgm]；这中间若再报一次，就会连跳两首。
     *  3. **绕回开头要重新武装**。单曲循环的场景（崩溃余音）里没人接手，`currentPosition`
     *     会在 `isLooping` 下回到 0 —— 不重新武装的话，之后再进多曲场景就永远不换歌了。
     *
     * 音乐关掉 / 后台 / 丢焦点时直接返回：那时用户听不见，没必要让播放列表空转。
     *
     * 节拍：混音循环跑 40ms 一跳，但这里**每 [PROGRESS_CHECK_TICKS] 跳（200ms）才查一次**。
     * 提前量有 1.2 秒，200ms 的粒度完全够；而 `currentPosition` 是一次跨 JNI 的读，
     * 混音循环又跑在主线程上 —— 每秒少 20 多次 native 调用，点击事件的排队抖动就小一点。
     * 时长 [bgmDurationMs] 在建播放器时读过一次，这里直接用缓存，不再问播放器。
     */
    private fun checkTrackProgress() {
        val cb = onTrackFinished ?: return
        val p = bgmCurrent ?: return
        if (!musicEnabled || !foreground || focusLost) return

        // 每 PROGRESS_CHECK_TICKS 跳才真查一次（见上面的"节拍"说明）
        progressTick++
        if (progressTick < PROGRESS_CHECK_TICKS) return
        progressTick = 0

        val dur = bgmDurationMs
        val pos = runCatching { p.currentPosition }.getOrDefault(0)

        if (!advanceArmed) {
            // 已经报过：只有真的绕回开头（单曲循环）才重新武装
            if (dur > 0 && pos < dur / 2) advanceArmed = true
            return
        }
        if (bgmWanted != bgmCurrentTrack) return       // 正在切换，别叠加第二次
        if (dur <= TRACK_ADVANCE_LEAD_MS) return       // 时长未知 / 太短：不掺和
        if (pos < dur - TRACK_ADVANCE_LEAD_MS) return
        advanceArmed = false
        cb()
    }

    /**
     * 指数逼近：每跳把差距吃掉 [MIX_RAMP]。
     * 比线性更"像耳朵"——开头快、尾巴软，40ms 一跳下约 0.8 秒到 95%，听不出台阶。
     */
    private fun ramp(cur: Float, target: Float): Float {
        val d = target - cur
        return if (abs(d) <= VOLUME_EPSILON) target else cur + d * MIX_RAMP
    }

    private fun createLoopPlayer(track: BgmTrack): MediaPlayer? {
        val p = runCatching { MediaPlayer.create(appContext, track.res) }.getOrNull() ?: return null
        runCatching {
            p.isLooping = true               // 素材首尾零交叉，循环接缝无爆音
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setVolume(0f, 0f)
        }
        return p
    }

    private fun releaseOutgoing(isBgm: Boolean) {
        val p = if (isBgm) bgmOutgoing else ambOutgoing
        if (isBgm) {
            bgmOutgoing = null
            bgmOutgoingVol = 0f
        } else {
            ambOutgoing = null
            ambOutgoingVol = 0f
        }
        if (p == null) return
        runCatching { p.stop() }
        runCatching { p.release() }
    }

    private fun releaseAllPlayers() {
        for (p in arrayOf(bgmCurrent, bgmOutgoing, ambCurrent, ambOutgoing)) {
            val pl = p ?: continue
            runCatching { pl.stop() }
            runCatching { pl.release() }
        }
        bgmCurrent = null
        bgmOutgoing = null
        ambCurrent = null
        ambOutgoing = null
        bgmCurrentTrack = null
        ambCurrentTrack = null
        bgmDurationMs = 0
        progressTick = 0
        bgmCurrentVol = 0f
        bgmOutgoingVol = 0f
        ambCurrentVol = 0f
        ambOutgoingVol = 0f
    }

    /** 暂停但**保留播放位置**：回前台接着放，不从 0 重来 */
    private fun pausePlayers() {
        mixerJob?.cancel()
        mixerJob = null
        for (p in arrayOf(bgmCurrent, bgmOutgoing, ambCurrent, ambOutgoing)) {
            val pl = p ?: continue
            runCatching { if (pl.isPlaying) pl.pause() }
        }
        bgmCurrentVol = 0f
        ambCurrentVol = 0f
        bgmCurrent?.let { runCatching { it.setVolume(0f, 0f) } }
        ambCurrent?.let { runCatching { it.setVolume(0f, 0f) } }
    }

    private fun resumePlayers() {
        if (!musicEnabled || !foreground || focusLost) return
        for (p in arrayOf(bgmCurrent, ambCurrent)) {
            val pl = p ?: continue
            runCatching { if (!pl.isPlaying) pl.start() }
        }
    }

    // ==================== 内部：音频焦点 ====================

    /**
     * 申请音频焦点：让系统知道"我在放背景音"，来电 / 别的 App 播视频时自动让我们让路。
     * 拿不到焦点也照常播 —— 这个 App 的声音是辅料，不值得为它阻断用户操作。
     */
    private fun requestFocus() {
        val am = audioManager ?: return
        if (focusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = runCatching {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusListener, focusHandler)
                .build()
        }.getOrNull() ?: return
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusLost = true
                focusRequest?.let { req -> runCatching { audioManager?.abandonAudioFocusRequest(req) } }
                focusRequest = null
                pausePlayers()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusLost = true
                pausePlayers()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                ensureMixer()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                focusLost = false
                ducked = false
                resumePlayers()
                ensureMixer()
            }
        }
    }

    companion object {
        /** 同时发声上限：4 路。第 5 个直接丢弃（不排队，排队只会让声音越来越晚） */
        private const val SFX_MAX_STREAMS = 4

        /** 同一音效的最小间隔。拖端点以 240Hz 出事件，不限流会变成机关枪 */
        private const val SAME_SFX_THROTTLE_MS = 120L

        /** 混音循环的节拍：40ms。只改几个 float，开销可忽略 */
        private const val MIXER_TICK_MS = 40L

        /** 曲尾检测每几跳查一次（5 × 40ms = 200ms）。提前量 1.2s，够用 */
        private const val PROGRESS_CHECK_TICKS = 5

        /** 每跳吃掉多少差距（0~1）。0.16 → 约 0.8 秒到 95% */
        private const val MIX_RAMP = 0.16f

        /** 低于这个音量就算"听不见"：不再写 setVolume，也允许释放 */
        private const val MIN_AUDIBLE_GAIN = 0.004f
        private const val VOLUME_EPSILON = 0.002f

        /** 默认音量：音乐压得比音效低一档，音效才听得清（用户可在设置里改） */
        private const val DEFAULT_BGM_GAIN = 0.55f
        private const val DEFAULT_SFX_GAIN = 0.85f

        /** 环境层固定为音乐的 40%。再高就会盖住音乐、抢走"空间感" */
        private const val AMB_RATIO = 0.40f

        /** 让路时降 6dB ≈ ×0.5 */
        private const val DUCK_RATIO = 0.50f

        /**
         * 曲尾提前多久通知"该换下一首"。
         *
         * 1200ms：混音循环的淡入淡出约 0.8s 到 95%，提前 1.2s 报能留出一点余量，
         * 于是换歌是一次交叉淡化而不是"淡出 → 静 → 淡入"。
         * 别再调大：提前太多会在乐句还没走完时就开始切，听上去像"跳着放"。
         */
        private const val TRACK_ADVANCE_LEAD_MS = 1200
    }
}
