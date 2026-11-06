# -*- coding: utf-8 -*-
"""
把用户提供的 9 首素材做成**可无缝循环**的 OGG，写进 res/raw。

## 为什么不能直接转换了事
这些素材和上一批一样，是"完整乐句 + 尾音自然衰减"的结构：
  - 有的尾部衰减到近乎静音（night2 / edit2 等）→ 直接循环，每首歌结束都会**静音一下**；
  - 有的尾部还有能量（day2 / night3 / edit1）→ 直接循环会在乐句中间**硬切**，一声"咔"。

## 循环处理：环绕式等功率交叉淡化（wrap-around crossfade）
不是"把尾巴淡出、开头淡入"（那样接缝处仍然是从 x[tc] 跳回 x[0]），而是
**把多出来的尾巴卷回开头混进去**，然后让文件在 "纯尾巴" 与 "纯开头" 的接缝处收尾：

    y = x[0 : E-TC]
    y[0 : TC] = x[0 : TC] * sin(θ) + x[E-TC : E] * cos(θ)      θ: 0 → π/2

于是：
    y[-1] = x[E-TC-1]（纯原信号，cos≈0 那侧已消失）
    y[0]  = x[E-TC]  （sin(0)=0，cos(π/2)=0 → 只剩尾巴那一路）
两者在原始波形里本来就是**相邻采样** → 接缝逐采样连续，理论上零跳变。

用等功率（sin/cos）而不是线性：线性在中点会掉 −3dB，循环处能听出"凹一下"。

## 响度统一
9 首来自不同生成批次，峰值 0.89~1.09、RMS 0.15~0.18。随机连播时音量跳变很明显，
所以统一归一到 RMS 0.16，并把峰值压到 0.95 以内（原曲有几首已经超过 1.0，会削波）。

## 已知环境限制
本机 `sf.write()` 一次性写整段长音频会被环境杀掉（exit 127，只剩 4333 字节的文件头），
必须用 `sf.SoundFile(...)` **分块写**（每块 4 秒）。

用法：python build_bgm_tracks.py
"""

import os

import numpy as np
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # Desktop/Keep Rolling
RAW = os.path.join(ROOT, "KeepRolling", "app", "src", "main", "res", "raw")

# 输出文件名 → 素材文件名（输出名必须是小写字母/数字/下划线，否则 R.raw 不认）
TRACKS = [
    ("bgm_day_view.ogg",   "BGM-day-view1.wav"),       # 覆盖上一轮已入库的那首（同源，重新按统一口径处理）
    ("bgm_day_view_2.ogg", "BGM-day-view2.mp3"),
    ("bgm_day_view_3.ogg", "BGM-day-view3.mp3"),
    ("bgm_day_view_4.ogg", "BGM-day-view4.mp3"),
    ("bgm_night_view_1.ogg", "BGM-night-view1.wav"),
    ("bgm_night_view_2.ogg", "BGM-night-view2.wav"),
    ("bgm_night_view_3.ogg", "BGM-night-view3.mp3"),
    ("bgm_night_view_4.ogg", "BGM-night-view4.mp3"),
    ("bgm_edit_1.ogg",     "BGM-edit1.mp3"),
    ("bgm_edit_2.ogg",     "BGM-edit2.mp3"),
]

WRAP_SEC = 0.6          # 交叉淡化长度
TARGET_RMS = 0.16       # 统一响度
PEAK_CAP = 0.95         # 峰值上限（避免编码后削波）
WRITE_CHUNK_SEC = 4.0   # 分块写入的块长（见文件头"已知环境限制"）


def find_end(mono, sr):
    """尾部有声内容到哪里结束（秒）。整体都很响的曲子原样保留。"""
    win = int(0.05 * sr)
    n = len(mono) // win
    env = np.array([
        float(np.sqrt((mono[i * win:(i + 1) * win] ** 2).mean())) for i in range(n)
    ])
    rms = float(np.sqrt((mono ** 2).mean()))
    th = max(0.02, rms * 0.25)                 # 相对阈值：安静的曲子不会被误判成"整首无声"
    i = n - 1
    while i > 0 and env[i] < th:
        i -= 1
    end = (i + 1) * 0.05 + 0.10                # 留 100ms 余量，别切掉尾音的余韵
    return min(end, len(mono) / sr)


def wrap_loop(x, sr, end_sec, wrap_sec):
    """环绕式等功率交叉淡化（见文件头）。返回处理后的立体声数组。"""
    end = int(end_sec * sr)
    tc = min(int(wrap_sec * sr), end // 4)     # 极短的曲子也别把整首都吃掉
    if tc < 32:
        return x[:end].copy()

    y = x[:end - tc].copy()
    theta = np.linspace(0.0, np.pi / 2.0, tc)[:, None]
    head = x[:tc]
    tail = x[end - tc:end]
    y[:tc] = head * np.sin(theta) + tail * np.cos(theta)
    return y


def normalize(y, target_rms, peak_cap):
    rms = float(np.sqrt((y ** 2).mean()))
    gain = target_rms / rms if rms > 1e-6 else 1.0
    peak = float(np.max(np.abs(y))) * gain
    if peak > peak_cap:
        gain *= peak_cap / peak
    return (y * gain).astype(np.float32), gain


def write_ogg(path, y, sr):
    with sf.SoundFile(
        path, "w", samplerate=sr, channels=y.shape[1],
        format="OGG", subtype="VORBIS", compression_level=0.45,
    ) as f:
        step = int(WRITE_CHUNK_SEC * sr)
        for i in range(0, len(y), step):
            f.write(np.ascontiguousarray(y[i:i + step]))


def main():
    print("%-24s %7s %7s %7s %7s %6s %7s" % (
        "output", "src_dur", "loop_dur", "seam", "rms", "peak", "MB"))
    total = 0
    for out_name, src_name in TRACKS:
        src = os.path.join(ROOT, src_name)
        if not os.path.exists(src):
            print("!! 缺素材", src_name)
            continue
        x, sr = sf.read(src, dtype="float32", always_2d=True)
        if x.shape[1] == 1:                    # 万一有单声道素材，补成立体声（播放器统一按立体声处理）
            x = np.repeat(x, 2, axis=1)

        end_sec = find_end(x.mean(axis=1), sr)
        y = wrap_loop(x, sr, end_sec, WRAP_SEC)
        y, gain = normalize(y, TARGET_RMS, PEAK_CAP)

        out = os.path.join(RAW, out_name)
        write_ogg(out, y, sr)

        seam = float(np.max(np.abs(y[0] - y[-1])))
        size_mb = os.path.getsize(out) / 1048576
        total += size_mb
        print("%-24s %6.2fs %6.2fs %7.4f %.3f %.3f %6.2f" % (
            out_name, len(x) / sr, len(y) / sr, seam,
            float(np.sqrt((y ** 2).mean())), float(np.max(np.abs(y))), size_mb))
    print("合计 %.1f MB" % total)


if __name__ == "__main__":
    main()
