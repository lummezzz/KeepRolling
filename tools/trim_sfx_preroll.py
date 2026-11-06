# -*- coding: utf-8 -*-
"""
切掉音效文件**音头前的空白**（preroll）。

## 为什么必须切
`SoundPool` 是从第 0 个采样开始播的。如果文件前面垫了 30ms 静音，用户按下去到听见
就白白多 30ms —— 这跟"音频通路慢"是两回事，但耳朵分不出来，只会觉得"点了有点迟钝"。

`gen_audio.py` 的 `_fit()` 只在**尾部**补零，音头空白来自各音效素材本身
（滤波器/混响的预延迟、`_at()` 把短音放在 0.04s 处等等）。
所以渲染阶段已经在 `render_sfx()` 里补了 `_trim_lead()`；本脚本是给
**已经渲染好的 wav** 做同一件事，避免为了 63 个文件重跑整条 20 分钟的合成流程。

## 规则
- 阈值 = 峰值 × 2%（低于这个就是听不见的底噪）；
- 切到"第一个可闻采样"再往前留 `KEEP_MS` 的余量（给 `fade_io` 的 2ms 起振留位置，
  也避免把软起音削出咔哒声）；
- **只切不补**，其余采样原样保留；文件格式（48k / 单声道 / PCM16）不变；
- 空白 < 2ms 的文件不动（切了也没意义，反而多一层风险）。

用法：
    python tools/trim_sfx_preroll.py            # 预演，只打印
    python tools/trim_sfx_preroll.py --write    # 真的写回
"""
import array
import glob
import os
import sys
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "..", "app", "src", "main", "res", "raw")

KEEP_MS = 1.0        # 保留在第一个可闻采样之前的余量
THRESH = 0.02        # 相对峰值的可闻阈值
MIN_TRIM_MS = 2.0    # 小于这个就不动


def lead_silence_ms(samples, channels, rate):
    peak = max((abs(v) for v in samples), default=0)
    if peak == 0:
        return 0.0, 0
    thr = max(1, int(peak * THRESH))
    for i, v in enumerate(samples):
        if abs(v) > thr:
            return i / channels / rate * 1000.0, i
    return 0.0, 0


def main():
    write = "--write" in sys.argv
    total_before = 0.0
    total_after = 0.0
    changed = 0

    for path in sorted(glob.glob(os.path.join(RAW, "sfx_*.wav"))):
        with wave.open(path, "rb") as w:
            n, rate, ch, sw = w.getnframes(), w.getframerate(), w.getnchannels(), w.getsampwidth()
            raw = w.readframes(n)
        if sw != 2:
            print("  skip (not PCM16): %s" % os.path.basename(path))
            continue

        a = array.array("h")
        a.frombytes(raw)
        lead_ms, first = lead_silence_ms(a, ch, rate)
        total_before += lead_ms
        if lead_ms < MIN_TRIM_MS:
            continue

        keep = int(KEEP_MS * rate / 1000) * ch
        cut = max(0, first - keep)
        cut -= cut % ch                      # 对齐到帧边界
        if cut <= 0:
            continue

        trimmed = a[cut:]
        total_after += lead_ms
        changed += 1
        print("  %-24s %6.2f ms -> %5.2f ms  (cut %d frames)"
              % (os.path.basename(path), lead_ms,
                 lead_silence_ms(trimmed, ch, rate)[0], cut // ch))

        if write:
            with wave.open(path, "wb") as w:
                w.setnchannels(ch)
                w.setsampwidth(sw)
                w.setframerate(rate)
                w.writeframes(trimmed.tobytes())

    print("\n%d 个文件%s，累计音头空白 %.1f ms"
          % (changed, "已处理" if write else "待处理（加 --write 才落盘）", total_after))


if __name__ == "__main__":
    main()
