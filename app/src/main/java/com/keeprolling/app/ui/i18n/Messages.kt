package com.keeprolling.app.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** 界面语言（需求 5）：中文 / 英文 */
enum class Lang { ZH, EN }

/** 全部界面文案的键。新增文案时在这里加一个键，并在 [Messages] 的中英映射里各补一条。 */
enum class StrKey {
    // ---- 主界面提示 ----
    HINT_EDIT_NO_ROADS,
    HINT_EDIT_DRAWING,
    HINT_EDIT_ROAD_SELECTED,
    HINT_EDIT_SIGNAL,
    HINT_EDIT_NO_SIGNAL,
    HINT_EDIT_DEFAULT,
    HINT_VIEW_NO_ROADS,

    // ---- 红绿灯设置对话框（本轮）----
    SIGNAL_RULE_TITLE,
    SIGNAL_RULE_1_NAME,
    SIGNAL_RULE_1_DESC,
    SIGNAL_RULE_2_NAME,
    SIGNAL_RULE_2_DESC,
    SIGNAL_RULE_3_NAME,
    SIGNAL_RULE_3_DESC,
    TOAST_SIGNAL_RULE,

    // ---- 统计面板 ----
    STATS_RUNTIME,
    STATS_VEHICLES,
    STATS_ARRIVED,
    STATS_REROUTES,
    STATS_LANECHANGES,
    /** 实时帧率（只在统计面板展开时采样，见 MainScreen.rememberFps） */
    STATS_FPS,

    // ---- 崩溃横幅 ----
    CRASH_BANNER,
    DESC_BACK_EDIT,

    // ---- 出入口设置对话框 ----
    JUNCTION_TITLE,
    SECTION_VEHICLE_SPAWN,
    SPAWN_INTERVAL,
    VEHICLE_RATIO,
    RATIO_CAR,
    RATIO_BUS,
    RATIO_TRUCK,
    SPEED_CURVE_TITLE,
    SPEED_CURVE_HINT,
    SECTION_HEAT,
    HEAT_LABEL,
    HEAT_HINT,
    DESC_APPLY,
    DESC_DISCARD,
    PROB_ONLY_ONE,
    PROB,

    // ---- 保存 / 清空 / 加载对话框 ----
    SAVE_TITLE,
    SAVE_NAME,
    /** 保存弹窗里预填的默认名字（%s = 当前时间） */
    SAVE_DEFAULT_NAME,
    /** 路网一条路都没有时，缩略图上那行占位字 */
    SAVE_EMPTY_THUMB,
    DESC_SAVE,
    DESC_CANCEL,
    CLEAR_TITLE,
    CLEAR_BODY,
    CLEAR_CONFIRM,
    LOAD_TITLE,
    LOAD_EMPTY,
    /** 加载确认面板最下方那行深红加粗的警告 */
    LOAD_WARN,
    DESC_LOAD,
    /** 删除单个存档的二次确认 */
    DELETE_SAVE_TITLE,
    DELETE_SAVE_BODY,
    DESC_DELETE,
    DESC_CLOSE,

    // ---- 设置面板 ----
    SETTINGS_TITLE,
    SETTING_SPEED,
    SETTING_REACTION,
    SETTING_CRASH,
    SETTING_GRID_SNAP,
    SETTING_LIGHTS,
    SETTING_LIGHTS_HINT,
    SECTION_REROUTE,
    REROUTE_HINT,
    REROUTE_ENABLE,
    REROUTE_WINDOW,
    REROUTE_THRESHOLD,
    REROUTE_COOLDOWN,
    SECTION_STATS,
    STATS_NONE,
    STATS_BODY,
    ACTION_RESET,
    ACTION_CLEAR,

    // ---- 气泡提示 / 标签 ----
    TOAST_TOO_SHORT,
    TOAST_ROAD_FAILED,
    TOAST_LANES_1,
    TOAST_LANES_2,
    TOAST_LANES_3,
    TOAST_SIGNAL_REMOVED,
    TOAST_SIGNAL_ONLY_JUNCTION,
    TOAST_SIGNAL_ADDED_ON,
    TOAST_SIGNAL_ADDED,
    TOAST_ROAD_DELETED,
    TOAST_NODE_CLOSED,
    LABEL_JUNCTION_UNNUMBERED,
    LABEL_JUNCTION,
    TOAST_CRASHED,
    TOAST_NEED_ROADS,
    TOAST_NEED_OPEN,
    TOAST_NEED_TWO_OPEN,
    TOAST_SPEED,
    TOAST_REROUTE_PRESET,
    TOAST_NO_UNDO,
    TOAST_UNDO_FAILED,
    TOAST_UNDONE,
    TOAST_NO_REDO,
    TOAST_REDO_FAILED,
    TOAST_REDONE,
    TOAST_NAME_EMPTY,
    TOAST_SAVED,
    TOAST_SAVE_FAILED,
    TOAST_SAVE_MISSING,
    TOAST_LOADED,
    TOAST_DELETED_SAVE,
    TOAST_CLEARED,
    TOAST_LANG_ZH,
    TOAST_LANG_EN,

    // ---- 聚焦车辆的信息框（需求 4）----
    VEHICLE_INFO_TITLE,
    VEHICLE_INFO_SPEED,
    VEHICLE_INFO_DESIRED,
    VEHICLE_INFO_FROM,
    VEHICLE_INFO_TO,
    VEHICLE_INFO_CLOSE,
    VEHICLE_INFO_EMPTY,

    // ---- 自主规划预设名 ----
    PRESET_OFF,
    PRESET_SENSITIVE,
    PRESET_STANDARD,
    PRESET_CONSERVATIVE,
    // 同一批预设的**圆钮短标签**：右侧展开的那一排只有 44dp，
    // 塞得下上面那种「灵敏（2 秒 · 20% · 5 秒冷却）」的长句吗？显然不行。
    // 完整说明留给选中后的 toast，按钮上只放 1~2 个字。
    PRESET_OFF_SHORT,
    PRESET_SENSITIVE_SHORT,
    PRESET_STANDARD_SHORT,
    PRESET_CONSERVATIVE_SHORT,

    // ---- 信号相位条（选中路口时显示"现在放行谁、还剩几秒"）----
    PHASE_AXIS_UD,
    PHASE_AXIS_LR,
    PHASE_AXIS_MIXED,
    PHASE_TURN_STRAIGHT,
    PHASE_TURN_LEFT,
    PHASE_TURN_BOTH,
    PHASE_INDEX_FMT,
    PHASE_REMAIN_FMT,
    PHASE_YELLOW,

    // ---- 昼夜模式（需求 1：白天 / 夜间 / 自动循环）----
    TOAST_DAY,
    TOAST_NIGHT,
    TOAST_DAY_AUTO,
}

/**
 * 界面文案解析（需求 5）。
 *
 * 用「键 → 模板」的映射 + [String.format] 承载占位符（`%d` / `%s` / `%%`），
 * 中英文共用同一套模板，切语言时只换映射。
 */
class Messages private constructor(private val lang: Lang) {

    /** 取文案；[args] 会按模板里的 `%d` / `%s` 填充（无参数则原样返回） */
    fun s(key: StrKey, vararg args: Any?): String {
        val table = if (lang == Lang.EN) EN else ZH
        val raw = table[key] ?: ZH[key] ?: key.name
        return if (args.isEmpty()) raw else String.format(raw, *args)
    }

    companion object {
        fun of(lang: Lang): Messages = Messages(lang)

        private val ZH: Map<StrKey, String> = mapOf(
            StrKey.HINT_EDIT_NO_ROADS to "点「道路」选一种路型（直线 / 曲线 · 双车道 · 三车道）\n单指按住 0.4 秒后滑动 = 画路",
            StrKey.HINT_EDIT_DRAWING to "按住 0.4 秒不动 → 开始画路\n起点贴近道路会自动吸附交汇",
            StrKey.HINT_EDIT_ROAD_SELECTED to "拖拽两端的圆点 = 移动这条道路\n左下方按钮 = 改车道数 / 删除",
            StrKey.HINT_EDIT_SIGNAL to "上方按钮 = 红绿灯设置（选运行规则）\n下方按钮 = 删除该路口的红绿灯",
            StrKey.HINT_EDIT_NO_SIGNAL to "左下方按钮 = 为该路口添加红绿灯",
            // 本轮需求 5：不再用「·」把几件事串成一段，改用 \n 一条一行
            // （四项操作彼此独立，横着串成一句读起来像流水账，还容易错行）
            StrKey.HINT_EDIT_DEFAULT to "点道路可选中（拖端点可移动）\n点端点圆圈可开放路口\n点路口可增删红绿灯\n点空白处收起左侧按钮",
            StrKey.HINT_VIEW_NO_ROADS to "进入编辑模式，搭一个属于你的路网",

            // 需求 5：标题只写"红绿灯设置"，**不显示路口信息**（路口编号在画布上已经标出来了）
            StrKey.SIGNAL_RULE_TITLE to "红绿灯设置",
            StrKey.SIGNAL_RULE_1_NAME to "规则一 · 直行与转弯同时放行",
            StrKey.SIGNAL_RULE_1_DESC to
                "① 上下方向：直行 + 左转全绿（35 秒），左右方向全红\n" +
                "② 左右方向：直行 + 左转全绿（35 秒），上下方向全红\n" +
                "两种状态交替循环 · 右转随时可走，无需等灯",
            StrKey.SIGNAL_RULE_2_NAME to "规则二 · 直行与转弯分开放行",
            StrKey.SIGNAL_RULE_2_DESC to
                "① 上下直行绿（20 秒）· 其余全红\n" +
                "② 上下左转绿（20 秒）· 其余全红\n" +
                "③ 左右直行绿（20 秒）· 其余全红\n" +
                "④ 左右左转绿（20 秒）· 其余全红\n" +
                "四个状态轮流循环 · 右转随时可走，无需等灯",
            StrKey.SIGNAL_RULE_3_NAME to "规则三 · 四方向轮流通行",
            StrKey.SIGNAL_RULE_3_DESC to
                "① 上方向直行 + 左转绿（20 秒），其余全红\n" +
                "② 下方向直行 + 左转绿（20 秒），其余全红\n" +
                "③ 左方向直行 + 左转绿（20 秒），其余全红\n" +
                "④ 右方向直行 + 左转绿（20 秒），其余全红\n" +
                "四个状态轮流循环 · 右转随时可走，无需等灯",
            StrKey.TOAST_SIGNAL_RULE to "已切换为%s",

            StrKey.STATS_RUNTIME to "运行时间 %s",
            StrKey.STATS_VEHICLES to "运行车辆数 %d",
            StrKey.STATS_ARRIVED to "到达车辆数 %d",
            StrKey.STATS_FPS to "帧率 %d fps",
            StrKey.STATS_REROUTES to "累计换路 %d",
            StrKey.STATS_LANECHANGES to "累计变道 %d",

            StrKey.CRASH_BANNER to "%s 持续堵塞，网络已崩溃 · 点这里返回编辑",
            StrKey.DESC_BACK_EDIT to "返回编辑",

            StrKey.JUNCTION_TITLE to "设置 · %s",
            StrKey.SECTION_VEHICLE_SPAWN to "车辆生成",
            StrKey.SPAWN_INTERVAL to "生成间隔：%d 秒/辆",
            StrKey.VEHICLE_RATIO to "车型比例（拖动两个滑块切三段）",
            StrKey.RATIO_CAR to "小型车 %d%%",
            StrKey.RATIO_BUS to "中型车 %d%%",
            StrKey.RATIO_TRUCK to "大型车 %d%%",
            StrKey.SPEED_CURVE_TITLE to "速度分布（拖动圆点调节各速度的概率）",
            StrKey.SPEED_CURVE_HINT to "横轴 40~120 km/h：上下拖动圆点改变概率，相邻点自动平滑连接",
            StrKey.SECTION_HEAT to "路口热度",
            StrKey.HEAT_LABEL to "热度 %d / 10",
            StrKey.HEAT_HINT to "热度越高，越容易被其他路口的车辆选为目的地",
            StrKey.DESC_APPLY to "保存并应用",
            StrKey.DESC_DISCARD to "放弃并关闭",
            StrKey.PROB_ONLY_ONE to "唯一开放路口 · 无其他目的地",
            StrKey.PROB to "目的地概率 %s%%",

            StrKey.SAVE_TITLE to "保存路网",
            StrKey.SAVE_NAME to "路网名称",
            StrKey.SAVE_DEFAULT_NAME to "路网 %s",
            StrKey.SAVE_EMPTY_THUMB to "空白路网",
            StrKey.DESC_SAVE to "保存",
            StrKey.DESC_CANCEL to "取消",
            StrKey.CLEAR_TITLE to "清空路网？",
            StrKey.CLEAR_BODY to "将删除画布上所有道路、路口与出入口设置。清空后仍可用「撤销」找回。",
            StrKey.CLEAR_CONFIRM to "清空",
            StrKey.LOAD_TITLE to "加载路网（%d）",
            StrKey.LOAD_EMPTY to "还没有存档，先用保存按钮存一个吧。",
            StrKey.LOAD_WARN to "加载会放弃目前正在使用的路网，确认吗",
            StrKey.DESC_LOAD to "加载",
            StrKey.DELETE_SAVE_TITLE to "删除这个存档？",
            StrKey.DELETE_SAVE_BODY to "「%s」将被永久删除，无法恢复。",
            StrKey.DESC_DELETE to "删除",
            StrKey.DESC_CLOSE to "关闭",

            StrKey.SETTINGS_TITLE to "模拟设置",
            StrKey.SETTING_SPEED to "倍速",
            StrKey.SETTING_REACTION to "反应时间基准：%s 秒",
            StrKey.SETTING_CRASH to "崩溃判定：路口持续堵塞 %d 秒",
            StrKey.SETTING_GRID_SNAP to "画路吸附网格交点",
            StrKey.SETTING_LIGHTS to "十字 / 丁字路口自动红绿灯",
            StrKey.SETTING_LIGHTS_HINT to "开启后，度≥3 的路口会自动配信号灯；每个路口可在编辑模式里单独选运行规则（规则一 35 秒 / 规则二·三 20 秒，另加 2 秒黄灯）。",
            StrKey.SECTION_REROUTE to "自主规划",
            StrKey.REROUTE_HINT to "车辆被堵住时自动绕行：最近一段时间平均速度掉到初始速度的阈值以下，就重新找更快路径。",
            StrKey.REROUTE_ENABLE to "启用自动改道",
            StrKey.REROUTE_WINDOW to "观察窗口：最近 %s 秒的平均速度",
            StrKey.REROUTE_THRESHOLD to "触发阈值：均速低于初始速度的 %d%%",
            StrKey.REROUTE_COOLDOWN to "改道冷却：切换后 %s 秒内不再切换",
            StrKey.SECTION_STATS to "实时统计",
            StrKey.STATS_NONE to "尚未开始模拟",
            StrKey.STATS_BODY to "车流 %d 辆 · 均速 %d km/h\n累计生成 %d · 累计到达 %d\n累计换路 %d · 累计变道 %d",
            StrKey.ACTION_RESET to "重置模拟",
            StrKey.ACTION_CLEAR to "清空画布",

            StrKey.TOAST_TOO_SHORT to "太短了：至少要跨过一个网格",
            StrKey.TOAST_ROAD_FAILED to "这段路没能生成，换个位置试试",
            StrKey.TOAST_LANES_1 to "已改为单车道",
            StrKey.TOAST_LANES_2 to "已改为双车道",
            StrKey.TOAST_LANES_3 to "已改为三车道",
            StrKey.TOAST_SIGNAL_REMOVED to "已删除该路口的红绿灯（其它路口不受影响）",
            StrKey.TOAST_SIGNAL_ONLY_JUNCTION to "只有丁字 / 十字路口才需要红绿灯",
            StrKey.TOAST_SIGNAL_ADDED_ON to "已添加红绿灯（并打开了总开关）",
            StrKey.TOAST_SIGNAL_ADDED to "已为该路口添加红绿灯",
            StrKey.TOAST_ROAD_DELETED to "已删除该道路",
            StrKey.TOAST_NODE_CLOSED to "已封闭：该路口不再生成或接收车辆（名字已为它保留）",
            StrKey.LABEL_JUNCTION_UNNUMBERED to "路口（未编号）",
            StrKey.LABEL_JUNCTION to "路口%d",
            StrKey.TOAST_CRASHED to "网络已崩溃：请回到编辑模式改造路网",
            StrKey.TOAST_NEED_ROADS to "先进入编辑模式画几条路吧",
            StrKey.TOAST_NEED_OPEN to "至少需要一个开放路口（点断头路的圆圈 → 开放）",
            StrKey.TOAST_NEED_TWO_OPEN to "只有一个开放路口时没有目的地，再开放一个吧",
            StrKey.TOAST_SPEED to "倍速 %s",
            StrKey.TOAST_REROUTE_PRESET to "自主规划：%s",
            StrKey.TOAST_NO_UNDO to "没有可撤销的操作",
            StrKey.TOAST_UNDO_FAILED to "撤销失败",
            StrKey.TOAST_UNDONE to "已撤销上一步",
            StrKey.TOAST_NO_REDO to "没有可重做的操作",
            StrKey.TOAST_REDO_FAILED to "重做失败",
            StrKey.TOAST_REDONE to "已重做",
            StrKey.TOAST_NAME_EMPTY to "名字不能为空",
            StrKey.TOAST_SAVED to "已保存「%s」",
            StrKey.TOAST_SAVE_FAILED to "保存失败：%s",
            StrKey.TOAST_SAVE_MISSING to "存档不存在",
            StrKey.TOAST_LOADED to "已加载「%s」",
            StrKey.TOAST_DELETED_SAVE to "已删除「%s」",
            StrKey.TOAST_CLEARED to "画布已清空",
            StrKey.TOAST_LANG_ZH to "已切换为中文",
            StrKey.TOAST_LANG_EN to "已切换为英文",

            StrKey.VEHICLE_INFO_TITLE to "车辆信息",
            StrKey.VEHICLE_INFO_SPEED to "实时速度",
            StrKey.VEHICLE_INFO_DESIRED to "预设速度",
            StrKey.VEHICLE_INFO_FROM to "起始路口",
            StrKey.VEHICLE_INFO_TO to "目标路口",
            StrKey.VEHICLE_INFO_CLOSE to "关闭信息框",
            StrKey.VEHICLE_INFO_EMPTY to "—",

            StrKey.PRESET_OFF to "关闭",
            StrKey.PRESET_SENSITIVE to "灵敏（2 秒 · 20% · 5 秒冷却）",
            StrKey.PRESET_STANDARD to "标准（5 秒 · 30% · 10 秒冷却）",
            StrKey.PRESET_CONSERVATIVE to "保守（10 秒 · 50% · 20 秒冷却）",
            // 圆钮上的短标签：中文两个汉字刚好铺满 44dp 的圆，再多就顶边了
            StrKey.PRESET_OFF_SHORT to "关",
            StrKey.PRESET_SENSITIVE_SHORT to "灵敏",
            StrKey.PRESET_STANDARD_SHORT to "标准",
            StrKey.PRESET_CONSERVATIVE_SHORT to "保守",

            // 相位条
            StrKey.PHASE_AXIS_UD to "上下",
            StrKey.PHASE_AXIS_LR to "左右",
            StrKey.PHASE_AXIS_MIXED to "混合",
            StrKey.PHASE_TURN_STRAIGHT to "直行",
            StrKey.PHASE_TURN_LEFT to "左转",
            StrKey.PHASE_TURN_BOTH to "直行+左转",
            StrKey.PHASE_INDEX_FMT to "第 %d/%d 段",
            StrKey.PHASE_REMAIN_FMT to "剩 %d 秒",
            StrKey.PHASE_YELLOW to "黄灯",

            StrKey.TOAST_DAY to "已切换到白天模式",
            StrKey.TOAST_NIGHT to "已切换到夜间模式",
            StrKey.TOAST_DAY_AUTO to "昼夜自动循环 · 天色随仿真时间变化",
        )

        private val EN: Map<StrKey, String> = mapOf(
            StrKey.HINT_EDIT_NO_ROADS to "Tap \"Road\" to pick a type (straight / curve · 2-lane · 3-lane)\nPress one finger for 0.4 s, then drag = draw",
            StrKey.HINT_EDIT_DRAWING to "Hold 0.4 s without moving → start drawing\nA start point near a road snaps and joins it",
            StrKey.HINT_EDIT_ROAD_SELECTED to "Drag either end dot = move this road\nBottom-left buttons = change lanes / delete",
            StrKey.HINT_EDIT_SIGNAL to "Upper button = traffic light settings (pick a rule)\nLower button = remove this junction's light",
            StrKey.HINT_EDIT_NO_SIGNAL to "Bottom-left button = add a traffic light here",
            StrKey.HINT_EDIT_DEFAULT to "Tap a road to select it (drag its ends to move)\nTap an end circle to open a junction\nTap a junction to add or remove its traffic light\nTap empty space to hide the left buttons",
            StrKey.HINT_VIEW_NO_ROADS to "Enter edit mode and build your own road network",

            StrKey.SIGNAL_RULE_TITLE to "Traffic light settings",
            StrKey.SIGNAL_RULE_1_NAME to "Rule 1 · Straight and left together",
            StrKey.SIGNAL_RULE_1_DESC to
                "① Up/down: straight + left green (35 s), left/right all red\n" +
                "② Left/right: straight + left green (35 s), up/down all red\n" +
                "The two states alternate · right turns never wait",
            StrKey.SIGNAL_RULE_2_NAME to "Rule 2 · Straight and left separated",
            StrKey.SIGNAL_RULE_2_DESC to
                "① Up/down straight green (20 s), everything else red\n" +
                "② Up/down left green (20 s), everything else red\n" +
                "③ Left/right straight green (20 s), everything else red\n" +
                "④ Left/right left green (20 s), everything else red\n" +
                "Four states cycle · right turns never wait",
            StrKey.SIGNAL_RULE_3_NAME to "Rule 3 · One direction at a time",
            StrKey.SIGNAL_RULE_3_DESC to
                "① Up direction straight + left green (20 s), rest red\n" +
                "② Down direction straight + left green (20 s), rest red\n" +
                "③ Left direction straight + left green (20 s), rest red\n" +
                "④ Right direction straight + left green (20 s), rest red\n" +
                "Four states cycle · right turns never wait",
            StrKey.TOAST_SIGNAL_RULE to "Switched to %s",

            StrKey.STATS_RUNTIME to "Time %s",
            StrKey.STATS_VEHICLES to "Running %d",
            StrKey.STATS_ARRIVED to "Arrived %d",
            StrKey.STATS_FPS to "%d fps",
            StrKey.STATS_REROUTES to "Reroutes %d",
            StrKey.STATS_LANECHANGES to "Lane changes %d",

            StrKey.CRASH_BANNER to "%s is jammed — network collapsed · tap to go back to editing",
            StrKey.DESC_BACK_EDIT to "Back to editing",

            StrKey.JUNCTION_TITLE to "Settings · %s",
            StrKey.SECTION_VEHICLE_SPAWN to "Vehicle spawn",
            StrKey.SPAWN_INTERVAL to "Spawn interval: %d s/vehicle",
            StrKey.VEHICLE_RATIO to "Vehicle mix (drag two handles to split into three)",
            StrKey.RATIO_CAR to "Small %d%%",
            StrKey.RATIO_BUS to "Medium %d%%",
            StrKey.RATIO_TRUCK to "Large %d%%",
            StrKey.SPEED_CURVE_TITLE to "Speed distribution (drag the dots to set each speed's probability)",
            StrKey.SPEED_CURVE_HINT to "X axis 40–120 km/h: drag a dot up/down to change its probability; neighbours connect smoothly",
            StrKey.SECTION_HEAT to "Junction heat",
            StrKey.HEAT_LABEL to "Heat %d / 10",
            StrKey.HEAT_HINT to "Higher heat makes it a more likely destination for vehicles from other junctions",
            StrKey.DESC_APPLY to "Save and apply",
            StrKey.DESC_DISCARD to "Discard and close",
            StrKey.PROB_ONLY_ONE to "Only open junction · no other destinations",
            StrKey.PROB to "Destination probability %s%%",

            StrKey.SAVE_TITLE to "Save network",
            StrKey.SAVE_NAME to "Network name",
            StrKey.SAVE_DEFAULT_NAME to "Network %s",
            StrKey.SAVE_EMPTY_THUMB to "Empty network",
            StrKey.DESC_SAVE to "Save",
            StrKey.DESC_CANCEL to "Cancel",
            StrKey.CLEAR_TITLE to "Clear the network?",
            StrKey.CLEAR_BODY to "This removes every road, junction and entrance setting on the canvas. You can still get them back with Undo.",
            StrKey.CLEAR_CONFIRM to "Clear",
            StrKey.LOAD_TITLE to "Load network (%d)",
            StrKey.LOAD_EMPTY to "No saves yet — use the save button first.",
            StrKey.LOAD_WARN to "Loading will discard the network in use. Continue?",
            StrKey.DESC_LOAD to "Load",
            StrKey.DELETE_SAVE_TITLE to "Delete this save?",
            StrKey.DELETE_SAVE_BODY to "\u201C%s\u201D will be deleted permanently. This cannot be undone.",
            StrKey.DESC_DELETE to "Delete",
            StrKey.DESC_CLOSE to "Close",

            StrKey.SETTINGS_TITLE to "Simulation settings",
            StrKey.SETTING_SPEED to "Speed",
            StrKey.SETTING_REACTION to "Reaction time base: %s s",
            StrKey.SETTING_CRASH to "Collapse rule: a junction blocked for %d s",
            StrKey.SETTING_GRID_SNAP to "Snap drawing to grid",
            StrKey.SETTING_LIGHTS to "Auto traffic lights at crossings / T-junctions",
            StrKey.SETTING_LIGHTS_HINT to "When on, junctions with ≥3 roads get automatic signals; each junction's rule can be picked in edit mode (35 s for rule 1, 20 s for rules 2/3, plus 2 s yellow).",
            StrKey.SECTION_REROUTE to "Auto rerouting",
            StrKey.REROUTE_HINT to "Vehicles detour when jammed: if the recent average speed drops below a fraction of the desired speed, they look for a faster path.",
            StrKey.REROUTE_ENABLE to "Enable auto rerouting",
            StrKey.REROUTE_WINDOW to "Window: average speed over the last %s s",
            StrKey.REROUTE_THRESHOLD to "Trigger: average speed below %d%% of desired",
            StrKey.REROUTE_COOLDOWN to "Cooldown: no re-route within %s s after switching",
            StrKey.SECTION_STATS to "Live stats",
            StrKey.STATS_NONE to "Simulation not started",
            StrKey.STATS_BODY to "Traffic %d · avg %d km/h\nSpawned %d · arrived %d\nReroutes %d · lane changes %d",
            StrKey.ACTION_RESET to "Reset simulation",
            StrKey.ACTION_CLEAR to "Clear canvas",

            StrKey.TOAST_TOO_SHORT to "Too short: it must span at least one grid cell",
            StrKey.TOAST_ROAD_FAILED to "Couldn't create this road — try another spot",
            StrKey.TOAST_LANES_1 to "Changed to 1 lane",
            StrKey.TOAST_LANES_2 to "Changed to 2 lanes",
            StrKey.TOAST_LANES_3 to "Changed to 3 lanes",
            StrKey.TOAST_SIGNAL_REMOVED to "Removed this junction's traffic light (others unaffected)",
            StrKey.TOAST_SIGNAL_ONLY_JUNCTION to "Traffic lights only apply to T-junctions / crossings",
            StrKey.TOAST_SIGNAL_ADDED_ON to "Traffic light added (master switch turned on)",
            StrKey.TOAST_SIGNAL_ADDED to "Traffic light added to this junction",
            StrKey.TOAST_ROAD_DELETED to "Road deleted",
            StrKey.TOAST_NODE_CLOSED to "Closed: no more vehicles spawn here or target it (its name is kept)",
            StrKey.LABEL_JUNCTION_UNNUMBERED to "Junction (unnumbered)",
            StrKey.LABEL_JUNCTION to "Junction %d",
            StrKey.TOAST_CRASHED to "Network collapsed: go back to edit mode and rework the roads",
            StrKey.TOAST_NEED_ROADS to "Enter edit mode and draw some roads first",
            StrKey.TOAST_NEED_OPEN to "At least one open junction is needed (tap a dead-end circle → open)",
            StrKey.TOAST_NEED_TWO_OPEN to "One open junction has no destination — open another one",
            StrKey.TOAST_SPEED to "Speed %s",
            StrKey.TOAST_REROUTE_PRESET to "Auto rerouting: %s",
            StrKey.TOAST_NO_UNDO to "Nothing to undo",
            StrKey.TOAST_UNDO_FAILED to "Undo failed",
            StrKey.TOAST_UNDONE to "Undone",
            StrKey.TOAST_NO_REDO to "Nothing to redo",
            StrKey.TOAST_REDO_FAILED to "Redo failed",
            StrKey.TOAST_REDONE to "Redone",
            StrKey.TOAST_NAME_EMPTY to "Name can't be empty",
            StrKey.TOAST_SAVED to "Saved \"%s\"",
            StrKey.TOAST_SAVE_FAILED to "Save failed: %s",
            StrKey.TOAST_SAVE_MISSING to "Save not found",
            StrKey.TOAST_LOADED to "Loaded \"%s\"",
            StrKey.TOAST_DELETED_SAVE to "Deleted \"%s\"",
            StrKey.TOAST_CLEARED to "Canvas cleared",
            StrKey.TOAST_LANG_ZH to "Switched to Chinese",
            StrKey.TOAST_LANG_EN to "Switched to English",

            StrKey.VEHICLE_INFO_TITLE to "Vehicle",
            StrKey.VEHICLE_INFO_SPEED to "Speed",
            StrKey.VEHICLE_INFO_DESIRED to "Target speed",
            StrKey.VEHICLE_INFO_FROM to "From",
            StrKey.VEHICLE_INFO_TO to "To",
            StrKey.VEHICLE_INFO_CLOSE to "Close this panel",
            StrKey.VEHICLE_INFO_EMPTY to "—",

            StrKey.PRESET_OFF to "Off",
            StrKey.PRESET_SENSITIVE to "Sensitive (2 s · 20% · 5 s cooldown)",
            StrKey.PRESET_STANDARD to "Standard (5 s · 30% · 10 s cooldown)",
            StrKey.PRESET_CONSERVATIVE to "Conservative (10 s · 50% · 20 s cooldown)",
            // Short labels for the round buttons: Hi / Mid / Lo = how eager a car is
            // to change lanes. The full wording still shows up in the toast.
            StrKey.PRESET_OFF_SHORT to "Off",
            StrKey.PRESET_SENSITIVE_SHORT to "Hi",
            StrKey.PRESET_STANDARD_SHORT to "Mid",
            StrKey.PRESET_CONSERVATIVE_SHORT to "Lo",

            // Phase card
            StrKey.PHASE_AXIS_UD to "N–S",
            StrKey.PHASE_AXIS_LR to "E–W",
            StrKey.PHASE_AXIS_MIXED to "Mixed",
            StrKey.PHASE_TURN_STRAIGHT to "Straight",
            StrKey.PHASE_TURN_LEFT to "Left",
            StrKey.PHASE_TURN_BOTH to "Straight+Left",
            StrKey.PHASE_INDEX_FMT to "%d/%d",
            StrKey.PHASE_REMAIN_FMT to "%ds left",
            StrKey.PHASE_YELLOW to "Amber",

            StrKey.TOAST_DAY to "Day mode",
            StrKey.TOAST_NIGHT to "Night mode",
            StrKey.TOAST_DAY_AUTO to "Auto day-night cycle · sky follows simulation time",
        )
    }
}

/** 当前语言对应的文案表；由 [androidx.compose.runtime.CompositionLocalProvider] 在根部注入 */
val LocalMessages = staticCompositionLocalOf { Messages.of(Lang.ZH) }
