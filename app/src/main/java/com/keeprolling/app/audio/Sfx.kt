package com.keeprolling.app.audio

import com.keeprolling.app.R

/**
 * 全部 63 个音效（`res/raw/sfx_*.wav`）。
 *
 * ## 为什么做成 enum
 * 三个理由，都是「少出错」：
 *  1. **引用即校验**：写成 `R.raw.sfx_xxx` 时文件名打错要到编译才知道；写成枚举成员，
 *     每个成员旁边就摆着它对应的资源，改资源名时 IDE 会一起提示。
 *  2. **限流表按 `ordinal` 索引**：同一音效 120ms 内只播一次（见 [SoundCenter.play]）。
 *     用 `IntArray(values().size)` 按下标存时间戳，零分配、常数时间；
 *     前提是**声明顺序与 `values()` 的顺序一致** —— enum 本身就是这个顺序，不需要额外维护。
 *  3. **调用点自解释**：`sound.play(Sfx.ROAD_CREATED)` 比 `sound.play(R.raw.sfx_road_created)` 好读，
 *     也不会把"资源 id"这种实现细节散落到 ViewModel 里。
 *
 * ## 分组的含义（与 `AUDIO_ASSETS.md` 的 A~E 组一致）
 *  - **界面通用**：点击、开关、菜单、胶囊、气泡、对话框；
 *  - **画路与编辑**：落笔、成路、车道、端点、路口、红绿灯；
 *  - **仿真与观看**：开始/暂停/重置、倍速、锁车、崩溃、相位、到达；
 *  - **模式与场景**：进出编辑、去UI、昼夜、语言、自动循环；
 *  - **存档与撤销**：保存/加载/删除/清空/撤销/重做/空操作。
 *
 * ⚠️ **绝不重新生成或替换这些资源**。素材已经按 48kHz / 单声道 / 16bit / 峰值 −10 dBFS 渲染好，
 * 换文件会改变听感平衡；代码只负责"什么时候播、播多响"。
 *
 * 唯一允许的改动是 `tools/trim_sfx_preroll.py` 做的**音头静音裁剪**（把每个文件前面的
 * 0~40ms 空白切到 ~1ms，音色与内容完全不动）。原因见 [SoundCenter] 文件头的"延迟四段"：
 * `SoundPool` 从第 0 采样开始播，前面垫的空白就是实打实的"按下去到听见"的延迟。
 */
enum class Sfx(val res: Int) {

    // ---------- A 组 · 界面通用 ----------

    /** 主按钮（底栏、对话框「确定」、菜单项确认） */
    TAP_PRIMARY(R.raw.sfx_tap_primary),

    /** 次按钮（返回 / 取消 / 中性操作） */
    TAP_SECONDARY(R.raw.sfx_tap_secondary),

    /** 画布点选（只点在空白处、什么都没选中时才响，避免与"选中反馈"叠在一起） */
    TAP_CANVAS(R.raw.sfx_tap_canvas),

    /** 开关打开（布尔设置项由关变开） */
    TOGGLE_ON(R.raw.sfx_toggle_on),

    /** 开关关闭 */
    TOGGLE_OFF(R.raw.sfx_toggle_off),

    /** 右侧浮层展开（菜单 / 滑块 / 卡片） */
    MENU_OPEN(R.raw.sfx_menu_open),

    /** 浮层收起 */
    MENU_CLOSE(R.raw.sfx_menu_close),

    /** 选中元素后左侧胶囊弹入 */
    CAPSULE_IN(R.raw.sfx_capsule_in),

    /** 胶囊弹回屏幕左侧 */
    CAPSULE_OUT(R.raw.sfx_capsule_out),

    /** 轻提示气泡出现 */
    TOAST_IN(R.raw.sfx_toast_in),

    /** 对话框弹入 */
    DIALOG_OPEN(R.raw.sfx_dialog_open),

    /** 对话框关闭 */
    DIALOG_CLOSE(R.raw.sfx_dialog_close),

    // ---------- B 组 · 画路与编辑 ----------

    /** 长按判定"可以开始画路"的那一刻 */
    DRAW_ARM(R.raw.sfx_draw_arm),

    /** 拖拽画路期间的持续底噪（**循环播放**，抬手/取消时停） */
    DRAW_LOOP(R.raw.sfx_draw_loop),

    /** 道路落成 */
    ROAD_CREATED(R.raw.sfx_road_created),

    /** 无效操作（太短 / 生成失败 / 缺路 / 缺出入口） */
    ROAD_REJECTED(R.raw.sfx_road_rejected),

    /** 删除道路 */
    ROAD_DELETED(R.raw.sfx_road_deleted),

    /** 改为单车道（与 2/3 同音色、递增音高，闭眼也能听出当前车道数） */
    LANE_1(R.raw.sfx_lane_1),
    LANE_2(R.raw.sfx_lane_2),
    LANE_3(R.raw.sfx_lane_3),

    /** 抓住端点手柄 */
    ENDPOINT_GRAB(R.raw.sfx_endpoint_grab),

    /** 松手落位 */
    ENDPOINT_DROP(R.raw.sfx_endpoint_drop),

    /** 抓住曲率手柄 */
    CURVE_GRAB(R.raw.sfx_curve_grab),

    /** 开放路口（"这个路口活了"） */
    NODE_OPEN(R.raw.sfx_node_open),

    /** 封闭路口 */
    NODE_CLOSE(R.raw.sfx_node_close),

    /** 路口设置确认 */
    JUNCTION_APPLY(R.raw.sfx_junction_apply),

    /** 路口设置放弃 */
    JUNCTION_DISCARD(R.raw.sfx_junction_discard),

    /** 添加红绿灯 */
    SIGNAL_ADD(R.raw.sfx_signal_add),

    /** 删除红绿灯 */
    SIGNAL_REMOVE(R.raw.sfx_signal_remove),

    /** 切换红绿灯运行规则 */
    SIGNAL_RULE(R.raw.sfx_signal_rule),

    // ---------- C 组 · 仿真与观看 ----------

    /** 开始仿真（城市苏醒） */
    SIM_START(R.raw.sfx_sim_start),

    /** 暂停 */
    SIM_PAUSE(R.raw.sfx_sim_pause),

    /** 重置仿真 */
    SIM_RESET(R.raw.sfx_sim_reset),

    /** 0.5x（六档同音色、递增音高） */
    SPEED_05(R.raw.sfx_speed_05),
    SPEED_10(R.raw.sfx_speed_10),
    SPEED_15(R.raw.sfx_speed_15),
    SPEED_20(R.raw.sfx_speed_20),
    SPEED_30(R.raw.sfx_speed_30),
    SPEED_50(R.raw.sfx_speed_50),

    /** 锁定车辆（镜头对焦） */
    VEHICLE_LOCK(R.raw.sfx_vehicle_lock),

    /** 解除锁定 */
    VEHICLE_UNLOCK(R.raw.sfx_vehicle_unlock),

    /** 网络崩溃（沉重但不恐怖） */
    CRASH(R.raw.sfx_crash),

    /** 崩溃横幅落下 */
    CRASH_BANNER(R.raw.sfx_crash_banner),

    /** 相位转绿（**只对被选中的那个路口播**，全城一起响会疯） */
    PHASE_GREEN(R.raw.sfx_phase_green),
    PHASE_YELLOW(R.raw.sfx_phase_yellow),
    PHASE_RED(R.raw.sfx_phase_red),

    /** 车辆到达（**计数限流**，绝不逐车响） */
    ARRIVAL(R.raw.sfx_arrival),

    // ---------- D 组 · 模式与场景 ----------

    /** 进入编辑模式（下沉、工具感） */
    MODE_TO_EDIT(R.raw.sfx_mode_to_edit),

    /** 进入观看模式（上浮、开阔） */
    MODE_TO_VIEW(R.raw.sfx_mode_to_view),

    /** 界面淡出（去UI） */
    UI_HIDE(R.raw.sfx_ui_hide),

    /** 界面渐显 */
    UI_SHOW(R.raw.sfx_ui_show),

    /** 转夜 */
    TO_NIGHT(R.raw.sfx_to_night),

    /** 转昼 */
    TO_DAY(R.raw.sfx_to_day),

    /** 切换语言 */
    LANG_SWITCH(R.raw.sfx_lang_switch),

    /** 开启昼夜自动循环 */
    AUTO_CYCLE_ON(R.raw.sfx_auto_cycle_on),

    // ---------- E 组 · 存档与撤销 ----------

    /** 保存成功（自动暂存**不播**） */
    SAVE_OK(R.raw.sfx_save_ok),

    /** 保存失败 / 名字为空 */
    SAVE_FAIL(R.raw.sfx_save_fail),

    /** 加载成功 */
    LOAD_OK(R.raw.sfx_load_ok),

    /** 删除存档 */
    DELETE_SAVE(R.raw.sfx_delete_save),

    /** 清空画布 */
    CLEAR_CANVAS(R.raw.sfx_clear_canvas),

    /** 撤销 */
    UNDO(R.raw.sfx_undo),

    /** 重做 */
    REDO(R.raw.sfx_redo),

    /** 空操作（没有可撤销的 / 不可用的动作），比耳鸣还轻 */
    NOOP_SOFT(R.raw.sfx_noop_soft);

    companion object {
        /**
         * 倍速档位 → 音效。
         *
         * 六档是**递增音高**的同一个音色，拖倍速滑块时连起来就是一串上行音阶 ——
         * 用户不看屏幕也能听出"现在比刚才快了几档"。
         * 档位数值与 [com.keeprolling.app.model.SimSettings.SPEED_MULTIPLIERS] 一一对应；
         * 万一以后加档位，这里用 `else` 兜到最近一档，不会崩。
         */
        fun ofSpeed(multiplier: Double): Sfx = when {
            multiplier <= 0.5 -> SPEED_05
            multiplier <= 1.0 -> SPEED_10
            multiplier <= 1.5 -> SPEED_15
            multiplier <= 2.0 -> SPEED_20
            multiplier <= 3.0 -> SPEED_30
            else -> SPEED_50
        }

        /** 车道数 → 音效（1/2/3，其它值按 1 处理） */
        fun ofLanes(lanes: Int): Sfx = when (lanes) {
            2 -> LANE_2
            3 -> LANE_3
            else -> LANE_1
        }
    }
}
