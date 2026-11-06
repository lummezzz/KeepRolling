package com.keeprolling.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.keeprolling.app.model.RoadNetwork
import com.keeprolling.app.model.Vector2
import kotlin.math.max
import kotlin.math.min

/**
 * 相机：世界坐标（米）与屏幕坐标（像素）互转 + 手势变换。
 * 参数用 Compose 状态保存，改动即自动重绘。
 */
class CameraState(
    /** 每米多少像素 */
    scalePxPerM: Float = 3.5f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
) {
    companion object {
        /**
         * 切换锁定车辆时镜头飞行的时长（秒）。
         *
         * 0.28 秒：比普通转场（0.3~0.4s）略快 —— 用户的意图是"我要看那辆车"，
         * 太久会显得拖沓；再短（<0.2s）又会退化成瞬移，看不出"切换"这件事。
         */
        const val FOCUS_FLY_SEC = 0.28

        /**
         * 参考缩放与真实缩放允许的**比值区间**（P12）。
         *
         * 为什么需要上下界而不是「捏合结束才提交」：
         * 以像素为单位的量（1.8px 的车道标线、11/9px 的虚线相位、3px 的最小线宽）
         * 被矩阵缩放时不会像以米为单位的量那样严格等比 —— 比值 2 就会让标线粗一倍，
         * 捏合过程中一闪、松手又缩回去，很扎眼。
         *
         * 取 ±25%：1.8px 的线最粗 2.25px、最细 1.35px，都在"同一条线"的观感以内；
         * 同时一次 4 倍的捏合只会触发约 6 次提交（而不是每帧一次）。
         */
        const val RENDER_ZOOM_MIN = 0.8f
        const val RENDER_ZOOM_MAX = 1.25f
    }

    var scalePxPerM by mutableFloatStateOf(scalePxPerM)
    var offsetX by mutableFloatStateOf(offsetX)
    var offsetY by mutableFloatStateOf(offsetY)

    // ---------------- 缩放：绘制参考尺度（P12）----------------

    /** [renderScale] 的状态载体（`mutableFloatStateOf` 才能让绘制层只失效、不重组） */
    private val renderScaleState = mutableFloatStateOf(scalePxPerM)

    /**
     * 世界类图层**生成几何时使用的参考缩放**（像素/米）。
     *
     * ## 为什么不能直接用 [scalePxPerM]
     * 双指捏合时 [scalePxPerM] 每帧都在变。而它是**几何的输入**：
     * 路面 Path、楼体矩形、车道虚线的采样点全都是 `world × scale` 算出来的，
     * 只要它一动，这些层就得整层重录 —— 缩放比平移贵一个数量级
     * （平移只是改坐标里的加法，缩放要重算上千个点、重建几十条 Path）。
     *
     * 现在几何一律按**这个**值画，真实缩放与它的比值交给
     * `Modifier.graphicsLayer { scaleX/scaleY }` 去乘 —— 那只是改 RenderNode 的变换矩阵，
     * 已录好的指令原样复用，与平移同一套机制（见 `SimCanvas.worldLayer`）。
     *
     * ## 谁负责推进它
     * 只有 `SimCanvas` 里那个「缩放提交器」会调用 [commitRenderScale]，
     * 而且只在两种时机：捏合停下 90ms 之后，或者比值跑出 [RENDER_ZOOM_MIN]~[RENDER_ZOOM_MAX]。
     * 于是捏合过程中这里**保持不变** → 世界类图层一次都不重录。
     */
    val renderScale: Float get() = renderScaleState.floatValue

    /**
     * 当前真实缩放 / 参考缩放：世界类图层 `graphicsLayer` 的缩放倍数。
     *
     * 恒在 [RENDER_ZOOM_MIN]~[RENDER_ZOOM_MAX] 之间（由缩放提交器保证），
     * 所以"以像素为单位的量"（1.8px 的标线、虚线相位、最小线宽）最多只会偏 25%，
     * 肉眼看不出来；而"以米为单位的量"（路宽、楼体、车道）是**严格等比**的，零误差。
     */
    val renderZoom: Float get() = if (renderScale <= 0f) 1f else scalePxPerM / renderScale

    /**
     * 把参考缩放推进到 [scale]（默认当前真实缩放）。
     *
     * 同值直接返回 —— 提交是"让七个世界类图层重录一次"的开关，能省就必须省。
     */
    fun commitRenderScale(scale: Float = scalePxPerM) {
        if (scale == renderScaleState.floatValue) return
        renderScaleState.floatValue = scale
    }

    /** 画布视口尺寸（像素）：跟随车辆居中时要它 */
    var viewW = 0f
        private set
    var viewH = 0f
        private set

    /** 是否已完成首次居中（布局完成后按画布尺寸居中） */
    var initialized = false

    /**
     * 世界坐标（米）→ 屏幕坐标（像素），**不需要先造一个 [Vector2]**。
     *
     * 为什么专门开这个重载：绘制路径上绝大多数调用点的原始数据本身就是两个 double
     * （`SimNode.x/y`、`VehicleRender.x/y`、`BuildingLot.cx/cy` …），
     * 走 `worldToScreen(Vector2(x, y))` 等于每帧白送约 700 个临时对象给 GC ——
     * 拖动时的卡顿毛刺有一半来自这里。这个重载零分配。
     *
     * 用 double 入参而不是 float：`x.toFloat() * scale` 与原实现逐位相同，
     * 换成 float 入参会先损失精度，长距离平移到屏幕边缘时可能出现 1px 的抖动。
     */
    fun worldToScreen(x: Double, y: Double): Offset =
        Offset(x.toFloat() * scalePxPerM + offsetX, y.toFloat() * scalePxPerM + offsetY)

    fun worldToScreen(w: Vector2): Offset = worldToScreen(w.x, w.y)

    /**
     * 世界坐标（米）→ **画布局部坐标**（像素，`world × renderScale`，**不含平移、也不含真实缩放**）。
     *
     * ## 为什么要有它（P8 平移 / P12 缩放）
     * 平移和缩放都是"整个世界的刚体变换"，几何形状一点没变。
     * 但旧实现把 `offset` 和 `scale` 都写进了**每一条绘制指令**（`x * scale + offsetX`），
     * 于是相机一动 → 该层 display list 里的每个坐标都过期 → 整层重录。
     *
     * 现在静态图层改用这个函数出坐标，平移与缩放交给
     * `Modifier.graphicsLayer { translationX/Y + scaleX/Y }`：
     * 那只是改 RenderNode 的变换矩阵，**已录好的指令原样复用**，一个字节都不用重算。
     *
     * ## 三个必须知道的约束
     * 1. **用了它的图层，必须自己在 `graphicsLayer` 里补上同一个平移与缩放**
     *    （见 `SimCanvas.worldLayer`），否则画面会跑到左上角、尺寸也不对
     *    （局部坐标系的原点在世界原点，缩放基准也是世界原点）。
     * 2. 它给出的坐标可能**远在屏幕之外**（世界很大），所以用了它的绘制函数
     *    **不能再用 `size.width/height` 做屏幕外剔除** —— 那个矩形在局部坐标系里是错的位置。
     *    剔除改由 GPU 按 layer 边界自动裁剪（多提交几条指令，比重录整层便宜得多）。
     * 3. 每帧都在变的东西（车、信号灯、相位卡）**继续用 [worldToScreen]** ——
     *    它们本来就每帧重录，改用局部坐标只会白增复杂度。
     */
    fun worldToLocal(x: Double, y: Double): Offset =
        Offset(x.toFloat() * renderScale, y.toFloat() * renderScale)

    fun worldToLocal(w: Vector2): Offset = worldToLocal(w.x, w.y)

    fun screenToWorld(s: Offset): Vector2 =
        Vector2((s.x - offsetX) / scalePxPerM.toDouble(), (s.y - offsetY) / scalePxPerM.toDouble())

    /**
     * 把世界坐标 [w] 放到画面中央（略偏上，让车前方的路看得更多）。
     *
     * 用于"锁定跟随"：每帧调用一次，车就永远在画面中央不动、由背景向后流动。
     * 直接改 offset 而不动缩放 —— 缩放由用户（或聚焦时的一次性设定）控制。
     */
    fun centerOnWorld(w: Vector2) {
        if (viewW <= 0f || viewH <= 0f) return
        offsetX = viewW / 2f - w.x.toFloat() * scalePxPerM
        offsetY = viewH * 0.45f - w.y.toFloat() * scalePxPerM
    }

    /** 聚焦车辆：一次性设定缩放，并把该点居中 */
    fun focusOnWorld(w: Vector2, scale: Float) {
        scalePxPerM = scale.coerceIn(0.8f, 40f)
        centerOnWorld(w)
    }

    // ---------------- 锁定跟随时的平滑过渡 ----------------

    /** 平滑飞行的起点（切换锁定车辆的那一瞬间，画面在哪儿） */
    private var transFromX = 0f
    private var transFromY = 0f
    private var transFromScale = 0f
    private var transStartNs = 0L
    private var transActive = false

    /**
     * 开始一段**镜头飞行**：以当前视图为起点，`[FOCUS_FLY_SEC]` 秒内平滑到达目标车辆。
     *
     * 为什么需要它：锁定 A 车时点 B 车，如果直接把画面"瞬移"到 B，用户会觉得是画面跳了一下、
     * 而不是"我换了一辆车"。加一段短促的位移动画（约 0.28 秒），就能明确表达
     * "视角转移到了另一辆车"这件事 —— 位移方向本身就是最好的说明。
     */
    fun beginFocusTransition() {
        transFromX = offsetX
        transFromY = offsetY
        transFromScale = scalePxPerM
        // 起点时间留到第一帧再定（0 = 未定）：这样计时与 [followStep] 收到的 nowNs
        // 一定是同一个时钟基准，不会因为 System.nanoTime() 与帧时钟的差异导致动画瞬间跳完。
        transStartNs = 0L
        transActive = true
    }

    /**
     * 锁定跟随的一帧：把车辆放到画面 45% 高度处（略偏上，让车前方的路看得更多）。
     *
     * 分两个阶段：
     *  1. 飞行中（刚切换车辆）→ 从起点按 easeOutCubic 插值，同时把缩放过渡到 [targetScale]；
     *  2. 飞行结束 → 严格跟随（每帧直接对齐），保证车永远钉在屏幕固定位置、不抖不飘。
     *
     * ⚠️ 插值过程中的"目标 offset"必须用**当前**缩放重算：缩放本身也在变，
     * 用最终缩放算出来的目标位置在中途是错的，会导致车的落点一路漂移。
     *
     * ## 为什么入参是 `Double` 而不是 `Vector2`
     * 调用方（[com.keeprolling.app.ui.FollowAnchor] 的插值结果）本来就是两个 double，
     * 走 `Vector2` 等于每帧白送一个临时对象给 GC；直接收两个坐标还省掉一次装箱。
     *
     * @param nowNs 当前帧时间（`withFrameNanos` 给的值），用帧时钟而不是墙钟，
     *              这样动画和绘制严格同帧，不会出现"位移比画面快半帧"的抖动。
     */
    fun followStep(x: Double, y: Double, targetScale: Float, nowNs: Long) {
        if (viewW <= 0f || viewH <= 0f) return
        val scale = targetScale.coerceIn(0.8f, 40f)
        if (!transActive) {
            scalePxPerM = scale
            offsetX = viewW / 2f - x.toFloat() * scale
            offsetY = viewH * 0.45f - y.toFloat() * scale
            return
        }
        if (transStartNs == 0L) transStartNs = nowNs      // 第一帧：定下起点时刻
        val elapsedSec = (nowNs - transStartNs) / 1_000_000_000.0
        val k = (elapsedSec / FOCUS_FLY_SEC).coerceIn(0.0, 1.0)
        if (k >= 1.0) {
            transActive = false
            scalePxPerM = scale
            offsetX = viewW / 2f - x.toFloat() * scale
            offsetY = viewH * 0.45f - y.toFloat() * scale
            return
        }
        // easeOutCubic：一开始快（"快速移动"）、末尾缓（稳稳停住不顿）
        val inv = 1.0 - k
        val e = (1.0 - inv * inv * inv).toFloat()
        scalePxPerM = transFromScale + (scale - transFromScale) * e
        val tx = viewW / 2f - x.toFloat() * scalePxPerM
        val ty = viewH * 0.45f - y.toFloat() * scalePxPerM
        offsetX = transFromX + (tx - transFromX) * e
        offsetY = transFromY + (ty - transFromY) * e
    }

    /** 像素长度 → 世界米数（命中容差换算） */
    fun pxToMeters(px: Float): Double = (px / scalePxPerM).toDouble()

    /** 缩放：保持指尖处的世界坐标不动 */
    fun applyZoom(zoom: Float, centroid: Offset) {
        if (zoom == 1f) return
        val worldBefore = screenToWorld(centroid)
        scalePxPerM = (scalePxPerM * zoom).coerceIn(0.8f, 40f)
        val after = worldToScreen(worldBefore)
        offsetX += centroid.x - after.x
        offsetY += centroid.y - after.y
    }

    fun pan(delta: Offset) {
        offsetX += delta.x
        offsetY += delta.y
    }

    /** 首次布局：世界原点居中 */
    fun centerOrigin(viewport: IntSize) {
        // 视口尺寸每次都记下来（旋转屏幕 / 分屏后跟随车辆仍能居中）
        if (viewport.width > 0) { viewW = viewport.width.toFloat(); viewH = viewport.height.toFloat() }
        if (initialized || viewport.width == 0) return
        offsetX = viewport.width / 2f
        offsetY = viewport.height / 2f
        initialized = true
    }

    /** 双击复位：缩放并居中到路网包围盒 */
    fun resetToNetwork(network: RoadNetwork, viewport: IntSize) {
        val nodes = network.nodes.values
        if (nodes.isEmpty()) {
            scalePxPerM = 3.5f
            offsetX = viewport.width / 2f
            offsetY = viewport.height / 2f
            return
        }
        val minX = nodes.minOf { it.x }
        val maxX = nodes.maxOf { it.x }
        val minY = nodes.minOf { it.y }
        val maxY = nodes.maxOf { it.y }
        val w = max(maxX - minX, 60.0)
        val h = max(maxY - minY, 60.0)
        val margin = 60f
        val sx = (viewport.width - margin * 2) / w
        val sy = (viewport.height - margin * 2) / h
        scalePxPerM = min(sx, sy).toFloat().coerceIn(0.8f, 20f)
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        offsetX = viewport.width / 2f - (cx * scalePxPerM).toFloat()
        offsetY = viewport.height / 2f - (cy * scalePxPerM).toFloat()
        initialized = true
    }
}
