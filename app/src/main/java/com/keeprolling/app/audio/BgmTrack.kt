package com.keeprolling.app.audio

import com.keeprolling.app.R

/**
 * 全部**循环**长音轨：11 条背景音乐 + 2 条环境底噪。
 *
 * ## 分组（第 51 轮改为"多首随机循环"）
 * | 场景 | 曲目 |
 * |---|---|
 * | 白天 · 观看 | [DAY_VIEW_1]（用户上一轮提供的 BGM-01）、[DAY_VIEW_2] ~ [DAY_VIEW_4] |
 * | 夜间 · 观看与编辑 | [NIGHT_VIEW_1] ~ [NIGHT_VIEW_4] |
 * | 白天 · 编辑 | [EDIT_1]、[EDIT_2] |
 * | 崩溃余波 | [CRASH]（只有一首，不进随机列表） |
 * | 环境底噪 | [AMB_DAY]、[AMB_NIGHT] |
 *
 * 每组的"这一袋"见 [DAY_VIEW_PLAYLIST] / [NIGHT_PLAYLIST] / [EDIT_PLAYLIST]，
 * 由 ViewModel 洗牌后一首一首往下放，放完一袋再洗一袋（真正的随机循环）。
 *
 * ## 为什么音乐与环境层共用同一个枚举
 * 它们走的是**同一套播放机制**（MediaPlayer + 循环 + 淡入淡出），只是**音量基准不同**
 * （环境层固定为音乐的 40%，见 [SoundCenter]）。分成两个枚举只会让"切场景"那段
 * 代码变成两套重复逻辑，却没有一点好处。
 *
 * ## 素材来源与处理
 * 第 51 轮起，除 [DAY_VIEW_1]（上一轮已入库）外的 8 首都是**用户新提供的素材**，
 * 全部按同一口径重新处理过（脚本 `tools/build_bgm_tracks.py`，可复现）：
 *  1. **裁掉尾部静音**（乐句尾音衰减到近乎静音的部分）；
 *  2. **环绕式等功率交叉淡化** 0.6s：把多出来的尾巴卷回开头混进去，文件头 0.6s 是
 *     "开头 + 尾巴"的混合，接缝处两个采样在原始波形里本来就是相邻的 → 逐采样连续；
 *  3. **响度统一**到 RMS 0.16、峰值压到 0.95 以内 —— 随机连播时不会一首响一首轻。
 *
 * ⚠️ 与 [Sfx] 一样：**只引用、不重建**。全部是 OGG Vorbis、立体声、首尾可无缝接。
 */
enum class BgmTrack(val res: Int) {

    /** BGM-01 · 白天观看之一（用户提供的 BGM-01.wav，动态最干净的一首） */
    DAY_VIEW_1(R.raw.bgm_day_view),

    /** BGM-02 · 白天观看之二 */
    DAY_VIEW_2(R.raw.bgm_day_view_2),

    /** BGM-03 · 白天观看之三 */
    DAY_VIEW_3(R.raw.bgm_day_view_3),

    /** BGM-04 · 白天观看之四 */
    DAY_VIEW_4(R.raw.bgm_day_view_4),

    /** BGM-05 · 夜间观察之一 */
    NIGHT_VIEW_1(R.raw.bgm_night_view_1),

    /** BGM-06 · 夜间观察之二 */
    NIGHT_VIEW_2(R.raw.bgm_night_view_2),

    /** BGM-07 · 夜间观察之三 */
    NIGHT_VIEW_3(R.raw.bgm_night_view_3),

    /** BGM-08 · 夜间观察之四 */
    NIGHT_VIEW_4(R.raw.bgm_night_view_4),

    /** BGM-09 · 编辑模式之一 */
    EDIT_1(R.raw.bgm_edit_1),

    /** BGM-10 · 编辑模式之二 */
    EDIT_2(R.raw.bgm_edit_2),

    /** BGM-11 · 崩溃余波（无鼓，泄气但不惊悚；单曲循环，不进随机列表） */
    CRASH(R.raw.bgm_crash),

    /** AMB-01 · 白天城市底噪（60 秒无缝循环） */
    AMB_DAY(R.raw.amb_day_city),

    /** AMB-02 · 夜间城市底噪（60 秒无缝循环，整体比白天低 4dB） */
    AMB_NIGHT(R.raw.amb_night_city),
    ;

    companion object {
        /** 白天观看：4 首随机循环 */
        val DAY_VIEW_PLAYLIST: List<BgmTrack> = listOf(
            DAY_VIEW_1, DAY_VIEW_2, DAY_VIEW_3, DAY_VIEW_4,
        )

        /** 夜间观察：4 首随机循环 */
        val NIGHT_PLAYLIST: List<BgmTrack> = listOf(
            NIGHT_VIEW_1, NIGHT_VIEW_2, NIGHT_VIEW_3, NIGHT_VIEW_4,
        )

        /** 白天编辑：2 首随机循环 */
        val EDIT_PLAYLIST: List<BgmTrack> = listOf(EDIT_1, EDIT_2)
    }
}
