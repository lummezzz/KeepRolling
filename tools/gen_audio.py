#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Keep Rolling · 音频素材渲染器
=============================

把 AUDIO_ASSETS.md 里的 69 个素材**用合成的方式**渲染出来，直接写进
app/src/main/res/raw/，文件名与清单完全一致。

为什么是"合成"而不是"AI 生成"：
  · 短 UI 音效（木质+玻璃的点击、扫频、钟声）本来就是合成出来的，
    逐样本合成反而比生成的更干净、更一致，而且零版权、可复现（固定种子）。
  · 4 条 BGM / 2 条环境层是**可用的临时版本**：能听、能循环、能用来调
    混音比例和验证接入；等 AI 版本出来后，同名替换即可。

技术说明（无 scipy 依赖）：
  · 滤波全部走频域：零填充后 rfft → 乘幅度响应 → irfft，得到线性卷积。
  · 稳态噪声床直接在频域给白噪声上色（循环卷积，对噪声听不出来）。
  · 混响用真实 IR 卷积（同样走频域）。
  · 响度按 ITU-R BS.1770 的 K 加权计算（两级 biquad 的解析幅度响应）。

用法：
  python gen_audio.py              # 全部渲染
  python gen_audio.py --only sfx   # 只渲染音效
  python gen_audio.py --only music # 只渲染音乐与环境层
"""

import argparse
import json
import os
import re
import sys

import numpy as np
import soundfile as sf

# ============================================================ 基本常量

SR = 48000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "app", "src", "main", "res", "raw")
OUT = os.path.normpath(OUT)

rng = np.random.default_rng(20261101)          # 固定种子 → 每次渲染结果一致


# ============================================================ 通用 DSP

def tt(n):
    """n 个样本的时间轴（秒）"""
    return np.arange(n) / SR


def colnoise(n, magfn, seed_rng=None):
    """直接在频域给白噪声上色（循环卷积；对稳态噪声床完全听不出来）"""
    g = rng if seed_rng is None else seed_rng
    w = g.standard_normal(n)
    sp = np.fft.rfft(w)
    f = np.fft.rfftfreq(n, 1.0 / SR)
    return np.fft.irfft(sp * magfn(f), n)


def filt(x, magfn, pad=4096):
    """零填充 + 频域乘积 = 线性卷积。用于短素材与单层的 EQ。"""
    n = len(x)
    N = n + pad
    sp = np.fft.rfft(x, N)
    f = np.fft.rfftfreq(N, 1.0 / SR)
    return np.fft.irfft(sp * magfn(f), N)[:n]


def conv(x, ir):
    """线性卷积（混响）"""
    n = len(x)
    N = n + len(ir)
    sp = np.fft.rfft(x, N) * np.fft.rfft(ir, N)
    return np.fft.irfft(sp, N)[:n]


def make_ir(dur, decay=4.0, seed=0):
    """指数衰减噪声脉冲响应（廉价但够用的板式混响）"""
    n = int(dur * SR)
    g = np.random.default_rng(seed)
    ir = g.standard_normal(n) * np.exp(-decay * tt(n) / dur)
    ir[:int(0.004 * SR)] *= 0.15               # 预延迟前压一下直达声
    return ir / np.abs(ir).max()


# ---- 幅度响应 -------------------------------------------------------

def lp_fn(fc, order=2):
    return lambda f: 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order))


def hp_fn(fc, order=2):
    fc = max(fc, 1.0)
    return lambda f: (f / fc) ** order / np.sqrt(1.0 + (f / fc) ** (2 * order))


def bp_fn(f0, q):
    f0 = max(f0, 1.0)
    return lambda f: 1.0 / np.sqrt(1.0 + (q * (f / f0 - f0 / np.maximum(f, 1.0))) ** 2)


def env_perc(n, att=0.002, dec=0.12):
    """打击型包络：极短起音 + 指数衰减，末尾 8ms 收到 0（避免爆音）"""
    e = np.exp(-tt(n) / max(dec, 1e-4))
    a = max(1, int(att * SR))
    e[:a] *= np.linspace(0.0, 1.0, a)
    tail = max(1, int(0.008 * SR))
    e[-tail:] *= np.linspace(1.0, 0.0, tail)
    m = e.max()
    return e / m if m > 0 else e


def env_adsr(n, a=0.01, d=0.1, s=0.7, r=0.2):
    """
    ADSR（用于 pad / 弦乐这类持续音）。

    ⚠️ a/d/r 的单位是**秒**，不是样本数。
    曾经有人（就是我）把 `int(0.25 * SR)` 当成释放时间传进来，
    结果这里按 12000 **秒**去算，一次生成 5.76 亿个样本 —— 单个音效
    渲染 19 秒。所以下面先做总长收缩，把这类单位错误挡在函数内部。
    """
    total_s = a + d + r
    if total_s * SR > n and total_s > 0:      # 包络比素材还长 → 等比收缩
        k = (n * 0.9) / (total_s * SR)
        a, d, r = a * k, d * k, r * k
    a = max(1, int(a * SR)); d = max(1, int(d * SR)); r = max(1, int(r * SR))
    body = max(0, n - a - d - r)
    e = np.concatenate([
        np.linspace(0.0, 1.0, a),
        np.linspace(1.0, s, d),
        np.full(body, s),
        np.linspace(s, 0.0, r),
    ])
    if len(e) < n:
        e = np.concatenate([e, np.zeros(n - len(e))])
    return e[:n]


def fade_io(x, ms_in=3.0, ms_out=8.0):
    n = len(x)
    a = min(n, int(ms_in * SR / 1000)); b = min(n, int(ms_out * SR / 1000))
    x = x.copy()
    if a: x[:a] *= np.linspace(0.0, 1.0, a)
    if b: x[-b:] *= np.linspace(1.0, 0.0, b)
    return x


# ---- 电平 -----------------------------------------------------------

def soft_clip(x, drive=1.2):
    return np.tanh(x * drive) / np.tanh(drive)


def norm_peak(x, dbfs):
    """峰值归一到指定 dBFS"""
    p = np.abs(x).max()
    if p <= 0: return x
    return x * (10 ** (dbfs / 20.0)) / p


# ---- 音高 -----------------------------------------------------------

_NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}


def nf(name):
    """音名 → 频率，A4 = 440Hz。例：nf('C4') nf('A#3') nf('Eb4')"""
    m = re.match(r'^([A-G])([#b]?)(-?\d+)$', name)
    if not m:
        raise ValueError("bad note: " + name)
    acc = 1 if m.group(2) == '#' else (-1 if m.group(2) == 'b' else 0)
    semi = _NAMES[m.group(1)] + acc + (int(m.group(3)) - 4) * 12 - 9
    return 440.0 * 2.0 ** (semi / 12.0)


def chord(names):
    return [nf(x) for x in names]


# ============================================================ 乐器 / 材质

def wood_click(f=260.0, dur=0.09, bright=0.9, seed=None):
    """木质点击：正弦体 + 高频木质瞬态"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    e1 = env_perc(n, 0.001, dur * 0.30)
    e2 = env_perc(n, 0.001, dur * 0.14)
    y = (np.sin(2 * np.pi * f * tt(n)) * e1 * 0.9
         + np.sin(2 * np.pi * f * 1.58 * tt(n)) * e2 * 0.35)
    nb = colnoise(n, bp_fn(f * 3.0, 1.1), g) * env_perc(n, 0.0004, dur * 0.05)
    return y + nb * 0.45 * bright


def glass_tick(f=1800.0, dur=0.09, bright=1.0, seed=None):
    """玻璃叮：非谐分音 + 极短瞬态"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    e = env_perc(n, 0.001, dur * 0.45)
    e2 = env_perc(n, 0.001, dur * 0.18)
    e3 = env_perc(n, 0.001, dur * 0.09)
    y = (np.sin(2 * np.pi * f * tt(n)) * e
         + 0.45 * np.sin(2 * np.pi * f * 2.76 * tt(n)) * e2
         + 0.18 * np.sin(2 * np.pi * f * 5.40 * tt(n)) * e3)
    nb = colnoise(n, hp_fn(4000.0, 2), g) * env_perc(n, 0.0003, dur * 0.03)
    return y + nb * 0.5 * bright


def metal_shing(f=2400.0, dur=0.32, seed=None):
    """金属"唰"（尺子放下）"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    e = env_perc(n, 0.004, dur * 0.40)
    y = sum(a * np.sin(2 * np.pi * f * r * tt(n)) * env_perc(n, 0.003, dur * 0.40 / r)
            for r, a in ((1.0, 1.0), (2.41, 0.5), (3.83, 0.3), (5.17, 0.15)))
    nb = colnoise(n, bp_fn(f * 1.6, 1.6), g) * env_perc(n, 0.002, dur * 0.22)
    return y * e + nb * 0.35


def thump(f0=150.0, f1=55.0, dur=0.30, seed=None):
    """低频落定（沥青落下 / 重物）"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    ph = 2 * np.pi * np.cumsum(np.geomspace(f0, f1, n)) / SR
    e = env_perc(n, 0.002, dur * 0.35)
    y = np.sin(ph) * e
    nb = filt(colnoise(n, lp_fn(700.0, 2), g) * env_perc(n, 0.001, dur * 0.10),
              lp_fn(500.0, 2))
    return y * 0.9 + nb * 0.6


def whoosh(dur, f_start, f_end, bright=1.0, seed=None):
    """空气扫频。**方向由 (起点, 终点) 决定**，与名字里的 lo/hi 无关。"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    a, b = f_start, f_end
    # 噪声的中心频率随时间移动 → 分段拼接
    segs, k = [], 12
    per = n // k
    for i in range(k):
        t01 = i / (k - 1.0)
        f0 = a + (b - a) * t01
        s = colnoise(per, bp_fn(f0, 1.3), g)
        e = env_perc(per, 0.004, per / SR * 0.6)
        segs.append(s * e)
    y = np.concatenate(segs)
    if len(y) < n:
        y = np.concatenate([y, np.zeros(n - len(y))])
    # 整体包络：两头轻、中间重
    w = np.sin(np.pi * np.linspace(0.0, 1.0, n)) ** 1.2
    return y[:n] * w * bright


def sweep_tone(f0, f1, dur, kind='sine', amp_env=None):
    """音高扫频（昼夜切换 / 模式切换的"滤波器关闭"感）"""
    n = int(dur * SR)
    ph = 2 * np.pi * np.cumsum(np.geomspace(max(f0, 1.0), max(f1, 1.0), n)) / SR
    y = np.sin(ph) if kind == 'sine' else np.sign(np.sin(ph)) * 0.4 + np.sin(ph) * 0.6
    e = amp_env if amp_env is not None else env_adsr(n, 0.02, 0.05, 0.85, 0.25)
    return y * e


def zip_sound(dur=0.20, up=True, seed=None):
    """倒带 / 快进的 zip 声"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    a, b = (600.0, 5200.0) if up else (5200.0, 600.0)
    segs, k = [], 16
    per = n // k
    for i in range(k):
        t01 = i / (k - 1.0)
        f0 = a + (b - a) * (t01 if up else 1.0 - t01)
        s = colnoise(per, bp_fn(f0, 3.0), g)
        segs.append(s * env_perc(per, 0.002, per / SR * 0.8))
    y = np.concatenate(segs)
    if len(y) < n:
        y = np.concatenate([y, np.zeros(n - len(y))])
    w = np.sin(np.pi * np.linspace(0.0, 1.0, n))
    # 叠一条扫频音，让它更像"磁带"
    return y[:n] * w * 0.7 + sweep_tone(a * 0.5, b * 0.5, dur) * 0.25


def paper_tear(dur=0.28, seed=None):
    """纸张撕裂 / 刷过"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    nb = colnoise(n, bp_fn(2600.0, 0.7), g)
    # 用一串随机小爆音调制 → 撕裂的颗粒感
    grains = int(dur / 0.012)
    mod = np.ones(n)
    for _ in range(grains):
        p = g.integers(0, max(1, n - 400))
        mod[p:p + 300] *= g.uniform(1.6, 3.2)
    return nb * mod * env_perc(n, 0.006, dur * 0.5) * 0.5


def marimba(f, dur=0.55):
    """马林巴 / 木琴"""
    n = int(dur * SR)
    y = np.zeros(n)
    for r, a, tau in ((1.0, 1.0, 0.28), (3.94, 0.22, 0.11), (9.2, 0.06, 0.05)):
        y += a * np.sin(2 * np.pi * f * r * tt(n)) * env_perc(n, 0.0015, tau)
    nb = colnoise(n, bp_fn(f * 3.0, 1.2)) * env_perc(n, 0.0008, 0.012)
    return y * 0.8 + nb * 0.5


def bell(f, dur=1.2, bright=1.0):
    """钟 / 铃（非谐分音）"""
    n = int(dur * SR)
    y = np.zeros(n)
    for r, a, tau in ((1.0, 1.0, dur * 0.55), (2.0, 0.5, dur * 0.40),
                      (2.76, 0.32, dur * 0.30), (5.40, 0.14, dur * 0.18),
                      (8.93, 0.07, dur * 0.10)):
        y += a * np.sin(2 * np.pi * f * r * tt(n)) * env_perc(n, 0.002, tau)
    return y * bright


def rhodes(f, dur=1.0, bright=0.8):
    """Rhodes 电钢（FM / 相位调制）"""
    n = int(dur * SR)
    t = tt(n)
    idx = 2.0
    mod = np.sin(2 * np.pi * f * idx * t) * (2.2 * bright)
    y = np.sin(2 * np.pi * f * t + mod) * env_perc(n, 0.004, dur * 0.45)
    y += 0.35 * np.sin(2 * np.pi * f * 2 * t) * env_perc(n, 0.004, dur * 0.20)
    return filt(y, lp_fn(3200.0, 2)) * 0.9


def pad(freqs, dur, bright=1.0, seed=None):
    """气垫 pad：轻微失谐的叠加 + 慢速滤波 LFO"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    t = tt(n)
    y = np.zeros(n)
    for i, f in enumerate(freqs):
        det = 1.0 + (i % 2) * 0.0035 - 0.0015
        y += np.sin(2 * np.pi * f * det * t) * 0.55
        y += np.sin(2 * np.pi * f * det * 2 * t) * 0.18 * bright
        y += np.sin(2 * np.pi * f * det * 3 * t) * 0.07 * bright
    e = env_adsr(n, 0.25, 0.30, 0.85, 0.45)
    y *= e
    # 慢速亮度起伏
    lfo = 1.0 + 0.35 * np.sin(2 * np.pi * 0.07 * t)
    return filt(y * lfo, lp_fn(1900.0, 2)) * 0.5


def sub_bass(f, dur=1.0):
    """圆润 sub bass"""
    n = int(dur * SR)
    t = tt(n)
    e = env_adsr(n, 0.02, 0.10, 0.80, 0.12)
    y = np.sin(2 * np.pi * f * t) * e
    y += 0.22 * np.sin(2 * np.pi * f * 2 * t) * e * 0.5
    return filt(soft_clip(y * 1.1), lp_fn(180.0, 2)) * 0.9


def e_bass(f, dur=0.5):
    """圆润电贝斯"""
    n = int(dur * SR)
    t = tt(n)
    e = env_perc(n, 0.006, dur * 0.45)
    y = np.sin(2 * np.pi * f * t) * e
    y += 0.30 * np.sin(2 * np.pi * f * 2 * t) * e
    y += 0.12 * np.sin(2 * np.pi * f * 3 * t) * e
    return filt(y, lp_fn(900.0, 2)) * 0.9


def upright(f, dur=0.7):
    """立式贝斯拨弦"""
    n = int(dur * SR)
    t = tt(n)
    e = env_perc(n, 0.008, dur * 0.30)
    y = np.sin(2 * np.pi * f * t) * e
    y += 0.40 * np.sin(2 * np.pi * f * 2 * t) * env_perc(n, 0.006, dur * 0.15)
    y += 0.15 * np.sin(2 * np.pi * f * 3 * t) * env_perc(n, 0.006, dur * 0.08)
    nb = colnoise(n, bp_fn(f * 4.0, 1.5)) * env_perc(n, 0.001, 0.02)
    return filt(y, lp_fn(700.0, 2)) * 0.9 + nb * 0.3


def kick(dur=0.30):
    """软底鼓"""
    n = int(dur * SR)
    ph = 2 * np.pi * np.cumsum(np.geomspace(130.0, 46.0, n)) / SR
    y = np.sin(ph) * env_perc(n, 0.002, dur * 0.30)
    click = colnoise(n, lp_fn(3000.0, 1)) * env_perc(n, 0.0005, 0.006)
    return filt(y, lp_fn(220.0, 2)) * 0.95 + click * 0.25


def rim(seed=None):
    """rim click"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(0.05 * SR)
    nb = colnoise(n, bp_fn(1900.0, 2.2), g) * env_perc(n, 0.0004, 0.012)
    return nb * 0.7 + wood_click(420.0, 0.05, 0.6, seed) * 0.5


def hat(open_=False, seed=None):
    """踩镲"""
    g = rng if seed is None else np.random.default_rng(seed)
    dur = 0.16 if open_ else 0.045
    n = int(dur * SR)
    nb = colnoise(n, hp_fn(7000.0, 2), g) * env_perc(n, 0.0004, dur * 0.35)
    return nb * (0.5 if open_ else 0.35)


def snap(seed=None):
    """响指"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(0.05 * SR)
    nb = colnoise(n, bp_fn(2400.0, 1.6), g) * env_perc(n, 0.0004, 0.010)
    return nb * 0.6 + wood_click(700.0, 0.05, 1.0, seed) * 0.4


def shaker(accent=False, seed=None):
    """沙锤 / 刷奏"""
    g = rng if seed is None else np.random.default_rng(seed)
    dur = 0.10
    n = int(dur * SR)
    nb = colnoise(n, bp_fn(6500.0, 1.0), g)
    return nb * env_perc(n, 0.004, 0.035) * (0.45 if accent else 0.22)


def vibra(f, dur=1.6):
    """颤音琴"""
    n = int(dur * SR)
    t = tt(n)
    trem = 1.0 + 0.25 * np.sin(2 * np.pi * 5.5 * t)
    y = np.sin(2 * np.pi * f * t) * env_perc(n, 0.004, dur * 0.55) * trem
    y += 0.30 * np.sin(2 * np.pi * f * 4.0 * t) * env_perc(n, 0.004, dur * 0.25) * trem
    return y * 0.8


def detuned_piano(f, dur=2.0):
    """失谐钢琴（崩溃用）"""
    n = int(dur * SR)
    t = tt(n)
    y = np.zeros(n)
    for r, a, dt in ((1.0, 1.0, 0.0), (2.0, 0.35, 1.4), (3.0, 0.14, -1.8), (4.0, 0.06, 2.2)):
        for df in (0.0, 1.1):           # 两条微失谐 → 拍频
            y += a * 0.5 * np.sin(2 * np.pi * (f * r + df) * t) * env_perc(n, 0.002, dur * 0.45 / r)
    return filt(y, lp_fn(2600.0, 2)) * 0.8


def cello(f, dur=8.0, seed=None):
    """大提琴持续音（含揉弦）"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    t = tt(n)
    vib = 1.0 + 0.004 * np.sin(2 * np.pi * 4.7 * t + g.uniform(0, 6))
    y = np.zeros(n)
    for r, a in ((1.0, 1.0), (2.0, 0.42), (3.0, 0.24), (4.0, 0.13), (5.0, 0.07), (6.0, 0.04)):
        ph = 2 * np.pi * f * r * np.cumsum(vib) / SR
        y += a * np.sin(ph)
    e = env_adsr(n, 1.2, 0.8, 0.9, 1.5)
    return filt(y * e, lp_fn(1100.0, 2)) * 0.35


def hum_electrical(dur=6.0, seed=None):
    """电流嗡鸣"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    t = tt(n)
    y = np.zeros(n)
    for f, a in ((50.0, 1.0), (100.0, 0.55), (150.0, 0.28), (250.0, 0.12)):
        y += a * np.sin(2 * np.pi * f * t)
    y *= 1.0 + 0.06 * np.sin(2 * np.pi * 0.6 * t)
    return filt(y, lp_fn(400.0, 2)) * 0.12


def cricket_burst(dur=0.35, seed=None):
    """虫鸣（一串调幅高频）"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    t = tt(n)
    am = (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 55 * t))) ** 2
    nb = colnoise(n, bp_fn(4600.0, 6.0), g)
    return nb * am * env_perc(n, 0.02, dur * 0.5) * 0.35


def bird_chirp(seed=None):
    """鸟鸣（短 FM 扫频）"""
    g = rng if seed is None else np.random.default_rng(seed)
    dur = g.uniform(0.06, 0.14)
    n = int(dur * SR)
    f0 = g.uniform(1900, 2600)
    f1 = f0 * g.uniform(1.3, 1.8)
    t = tt(n)
    ph = 2 * np.pi * np.cumsum(np.linspace(f0, f1, n)) / SR
    y = np.sin(ph + 0.8 * np.sin(2 * np.pi * f0 * 2 * t))
    return y * env_perc(n, 0.01, dur * 0.35) * 0.25


def car_pass(dur=3.0, seed=None):
    """远处驶过的车（含多普勒：中心频率先升后降）"""
    g = rng if seed is None else np.random.default_rng(seed)
    n = int(dur * SR)
    segs, k = [], 24
    per = n // k
    for i in range(k):
        t01 = i / (k - 1.0)
        # 先接近（频率升高）后远离（频率降低）
        f0 = 260.0 + 900.0 * (1.0 - abs(t01 - 0.5) * 2.0) ** 2
        segs.append(colnoise(per, bp_fn(f0, 1.1), g))
    y = np.concatenate(segs)
    if len(y) < n:
        y = np.concatenate([y, np.zeros(n - len(y))])
    # 幅度：先小后大再小
    w = np.sin(np.pi * np.linspace(0.0, 1.0, n)) ** 1.5
    y = y[:n] * w
    y = filt(y, lp_fn(1400.0, 2))
    # 引擎低频
    eng = np.sin(2 * np.pi * 70.0 * tt(n)) * w * 0.25
    return (y + eng) * 0.7


def _biquad_mag(b, a, freqs):
    z = np.exp(-1j * 2 * np.pi * freqs / SR)
    num = b[0] + b[1] * z + b[2] * z * z
    den = a[0] + a[1] * z + a[2] * z * z
    return np.abs(num / den)


# ITU-R BS.1770-4 的 K 加权
_K_HS_B = (1.53512485958697, -2.69169618940638, 1.19839281085285)
_K_HS_A = (1.0, -1.69065929318241, 0.73248077421585)
_K_HP_B = (1.0, -2.0, 1.0)
_K_HP_A = (1.0, -1.99004745483398, 0.99007225036621)


def lufs(x, sr=SR):
    """近似 LUFS（BS.1770 K 加权 + 400ms 分块门限）"""
    x = np.atleast_2d(x)
    if x.shape[0] < x.shape[1]:
        x = x.T
    total, cnt = 0.0, 0
    for ch in range(x.shape[1]):
        mag = _biquad_mag(_K_HS_B, _K_HS_A, np.fft.rfftfreq(len(x), 1.0 / sr))
        y = np.fft.irfft(np.fft.rfft(x[:, ch], len(x)) * mag, len(x))
        mag2 = _biquad_mag(_K_HP_B, _K_HP_A, np.fft.rfftfreq(len(y), 1.0 / sr))
        y = np.fft.irfft(np.fft.rfft(y, len(y)) * mag2, len(y))
        m = y * y
        # 400ms 分块 + 75% 重叠
        blk = int(0.4 * sr); hop = blk // 4
        ms = np.array([np.mean(m[i:i + blk]) for i in range(0, len(m) - blk, hop)])
        ms = ms[ms > 0]
        if len(ms) == 0: continue
        thr = ms.max() * 10 ** (-10.0 / 10)
        ms = ms[ms > thr]
        total += float(np.mean(ms)); cnt += 1
    if cnt == 0 or total <= 0: return -70.0
    return -0.691 + 10.0 * np.log10(total / cnt)


def loudnorm(x, target=-16.0):
    """按 LUFS 归一（一次到位，不再迭代）"""
    cur = lufs(x)
    if cur < -69: return x
    g = 10 ** ((target - cur) / 20.0)
    y = x * g
    # 防止归一后削顶
    p = np.abs(y).max()
    if p > 0.99: y *= 0.99 / p
    return y


# ============================================================ 音效定义

# 每个条目：文件名 → (合成函数, 目标峰值 dBFS 偏移)
# gain_db 是相对 −12 dBFS 基准的偏移，让整套音效在响度上彼此平衡。
def _s(dur, fn, gain=0.0, loop=False, note=""):
    return dict(dur=dur, fn=fn, gain=gain, loop=loop, note=note)


def build_sfx_spec():
    S = {}

    # ---- A 界面通用 ------------------------------------------------
    S["sfx_tap_primary"] = _s(0.09, lambda: (
        wood_click(250.0, 0.075, 1.0) * 1.0
        , glass_tick(1900.0, 0.06) * 0.55), 0.0,
        note="主按钮：木体 + 玻璃边")

    S["sfx_tap_secondary"] = _s(0.06, lambda: (
        wood_click(330.0, 0.05, 0.7) * 1.0
        , glass_tick(2400.0, 0.04) * 0.30), -2.0,
        note="次按钮：更轻更高")

    S["sfx_tap_canvas"] = _s(0.05, lambda: (
        wood_click(420.0, 0.04, 0.35) * 0.8
        , filt(colnoise(int(0.05 * SR), bp_fn(1800.0, 1.0)) * env_perc(int(0.05 * SR), 0.002, 0.02), bp_fn(1500.0, 1.0)) * 0.5), -6.0,
        note="画布点选：几乎只是触到")

    S["sfx_toggle_on"] = _s(0.12, lambda: (
        wood_click(300.0, 0.04, 0.8) * 0.8
        , _blips([(0.035, 523.25), (0.075, 783.99)], 0.12, 0.055) * 0.9), 0.0,
        note="开：咔 + 上行两音")

    S["sfx_toggle_off"] = _s(0.12, lambda: (
        wood_click(300.0, 0.04, 0.6) * 0.8
        , _blips([(0.035, 783.99), (0.075, 523.25)], 0.12, 0.055) * 0.7), -1.0,
        note="关：咔 + 下行两音")

    S["sfx_menu_open"] = _s(0.22, lambda: (
        whoosh(0.22, 300.0, 2400.0) * 0.9
        , _at(wood_click(360.0, 0.05, 0.8), 0.17, 0.22) * 0.7), -1.0,
        note="展开：上扫 + 末尾落定")

    S["sfx_menu_close"] = _s(0.20, lambda: (
        whoosh(0.20, 2400.0, 320.0) * 0.9
        , _at(thump(90.0, 60.0, 0.10), 0.14, 0.20) * 0.5), -2.0,
        note="收起：下扫 + 闷响")

    S["sfx_capsule_in"] = _s(0.26, lambda: (
        _slide(0.26, up=True) * 1.0
        , _at(wood_click(200.0, 0.06, 0.9), 0.205, 0.26) * 0.85), 0.0,
        note="胶囊滑入：橡胶滑动 + 磁吸")

    S["sfx_capsule_out"] = _s(0.22, lambda: (
        _slide(0.22, up=False) * 1.0
        , whoosh(0.10, 900.0, 300.0) * 0.35), -1.0,
        note="胶囊滑出：反向滑动 + 气声")

    S["sfx_toast_in"] = _s(0.14, lambda: (
        marimba(659.25, 0.14) * 0.7
        , marimba(830.61, 0.13) * 0.6), -3.0,
        note="提示：上行大三度两音")

    S["sfx_dialog_open"] = _s(0.20, lambda: (
        sweep_tone(180.0, 520.0, 0.20) * 0.5
        , whoosh(0.20, 200.0, 1200.0) * 0.7), -2.0,
        note="弹入：低频上扫 + 气声")

    S["sfx_dialog_close"] = _s(0.18, lambda: (
        sweep_tone(520.0, 180.0, 0.18) * 0.5
        , whoosh(0.18, 1200.0, 220.0) * 0.55), -3.0,
        note="关闭：下扫")

    # ---- B 画路与编辑 ---------------------------------------------
    S["sfx_draw_arm"] = _s(0.13, lambda: (
        wood_click(520.0, 0.10, 1.3) * 0.9
        , glass_tick(2600.0, 0.09) * 0.6
        , colnoise(int(0.13 * SR), hp_fn(5000.0, 2)) * env_perc(int(0.13 * SR), 0.0004, 0.012) * 0.4), 0.0,
        note="长按就绪：笔尖触玻璃")

    S["sfx_draw_loop"] = _s(2.0, lambda: _draw_loop(), -14.0, loop=True,
                            note="画路持续：石墨在纸上走线（2s 循环）")

    S["sfx_road_created"] = _s(0.35, lambda: (
        thump(140.0, 58.0, 0.30) * 0.9
        , _at(metal_shing(2300.0, 0.30), 0.04, 0.35) * 0.6
        , _at(wood_click(300.0, 0.06, 0.7), 0.20, 0.35) * 0.5), 0.0,
        note="道路落成：落定 + 金属唰")

    S["sfx_road_rejected"] = _s(0.20, lambda: (
        _at(wood_click(200.0, 0.09, 0.4), 0.00, 0.20) * 0.9
        , _at(wood_click(180.0, 0.09, 0.35), 0.09, 0.20) * 0.8), -3.0,
        note="无效：两个闷音小二度下行")

    S["sfx_road_deleted"] = _s(0.28, lambda: (
        whoosh(0.28, 1600.0, 300.0) * 0.8
        , _at(paper_tear(0.20), 0.02, 0.28) * 0.7
        , _at(thump(80.0, 50.0, 0.12), 0.20, 0.28) * 0.7), 0.0,
        note="删除：下扫 + 撕纸 + 闷响")

    for i, (nm, f) in enumerate((("lane_1", 523.25), ("lane_2", 659.25), ("lane_3", 783.99))):
        S["sfx_" + nm] = _s(0.16, lambda f=f: (
            wood_click(f * 0.5, 0.10, 1.0) * 0.9
            , glass_tick(f * 2.0, 0.14) * 0.55), 0.0,
            note="车道 %d：同音色递增音高" % (i + 1))

    S["sfx_endpoint_grab"] = _s(0.08, lambda: (
        wood_click(620.0, 0.05, 1.2) * 0.7
        , glass_tick(3000.0, 0.04) * 0.45), -4.0,
        note="抓端点：磁吸咔")

    S["sfx_endpoint_drop"] = _s(0.18, lambda: (
        wood_click(300.0, 0.05, 1.0) * 0.8
        , _at(thump(120.0, 70.0, 0.09), 0.03, 0.18) * 0.6), -1.0,
        note="松手：咔 + 橡胶回弹")

    S["sfx_curve_grab"] = _s(0.07, lambda: (
        colnoise(int(0.07 * SR), bp_fn(900.0, 1.2)) * env_perc(int(0.07 * SR), 0.003, 0.030) * 0.8), -10.0,
        note="抓曲率手柄：几乎听不见")

    S["sfx_node_open"] = _s(0.45, lambda: (
        bell(659.25, 0.45) * 0.55
        , _at(bell(987.77, 0.40), 0.13, 0.45) * 0.45
        , whoosh(0.45, 400.0, 2000.0) * 0.35), 0.0,
        note="开放路口：两段上行钟声")

    S["sfx_node_close"] = _s(0.40, lambda: (
        bell(783.99, 0.40) * 0.5
        , _at(bell(523.25, 0.40), 0.15, 0.40) * 0.45
        , _at(thump(90.0, 55.0, 0.14), 0.24, 0.40) * 0.6), 0.0,
        note="封闭路口：下行 + 落锁")

    S["sfx_junction_apply"] = _s(0.24, lambda: (
        marimba(783.99, 0.24) * 0.8
        , _at(paper_tear(0.10), 0.08, 0.24) * 0.35), 0.0,
        note="确认：马林巴 + 纸张")

    S["sfx_junction_discard"] = _s(0.20, lambda: (
        whoosh(0.20, 1500.0, 350.0) * 0.8), -3.0,
        note="放弃：中性扫掉")

    S["sfx_signal_add"] = _s(0.32, lambda: (
        glass_tick(2100.0, 0.28) * 0.9
        , metal_shing(1800.0, 0.20) * 0.35
        , _at(wood_click(380.0, 0.04, 0.7), 0.14, 0.32) * 0.6), 0.0,
        note="加灯：玻璃叮 + 旋入")

    S["sfx_signal_remove"] = _s(0.32, lambda: (
        filt(glass_tick(1500.0, 0.24), lp_fn(2600.0, 2)) * 0.8
        , _at(thump(100.0, 60.0, 0.12), 0.18, 0.32) * 0.6), -1.0,
        note="删灯：闷掉的叮 + 落")

    S["sfx_signal_rule"] = _s(0.26, lambda: (
        _blips([(0.02, 587.33), (0.10, 698.46), (0.18, 880.00)], 0.26, 0.10) * 0.9), -1.0,
        note="切规则：上行三连音")

    # ---- C 仿真与观看 ---------------------------------------------
    S["sfx_sim_start"] = _s(0.70, lambda: (
        whoosh(0.70, 250.0, 1800.0) * 0.9
        , sweep_tone(110.0, 330.0, 0.70) * 0.35
        , _at(filt(colnoise(int(0.5 * SR), lp_fn(300.0, 2)) * env_perc(int(0.5 * SR), 0.05, 0.30), lp_fn(260.0, 2)) * 0.5, 0.18, 0.70)), 0.0,
        note="开工：空气推开 + 远处引擎")

    S["sfx_sim_pause"] = _s(0.40, lambda: (
        _filt_sweep_tone(520.0, 120.0, 0.40) * 0.6
        , _at(thump(110.0, 55.0, 0.16), 0.24, 0.40) * 0.7), 0.0,
        note="暂停：低通关闭 + 门合上")

    S["sfx_sim_reset"] = _s(0.55, lambda: (
        _filt_sweep_tone(200.0, 900.0, 0.28) * 0.5       # 反向涌入
        , _at(thump(130.0, 55.0, 0.20), 0.26, 0.55) * 0.8
        , _at(whoosh(0.30, 1800.0, 300.0) * 0.5, 0.25, 0.55)), 0.0,
        note="重置：反向涌入 + 抹掉")

    for nm, f in (("speed_05", 392.00), ("speed_10", 440.00), ("speed_15", 493.88),
                  ("speed_20", 554.37), ("speed_30", 622.25), ("speed_50", 698.46)):
        S["sfx_" + nm] = _s(0.09, lambda f=f: (
            wood_click(f * 0.6, 0.06, 1.1) * 0.9
            , glass_tick(f * 3.0, 0.07) * 0.5), -1.0,
            note="倍速档：6 档全音递增")

    S["sfx_vehicle_lock"] = _s(0.30, lambda: (
        whoosh(0.30, 400.0, 3000.0) * 0.7
        , _at(wood_click(500.0, 0.05, 1.2), 0.22, 0.30) * 0.8), -1.0,
        note="锁定：变焦 + 对焦咔")

    S["sfx_vehicle_unlock"] = _s(0.26, lambda: (
        whoosh(0.26, 2600.0, 350.0) * 0.7
        , _at(wood_click(380.0, 0.05, 0.9), 0.02, 0.26) * 0.6), -2.0,
        note="解锁：反向变焦")

    S["sfx_crash"] = _s(1.40, lambda: _crash(), 2.0,
                        note="崩溃：结构塌落（长黑暗尾）")

    S["sfx_crash_banner"] = _s(0.26, lambda: (
        thump(120.0, 60.0, 0.16) * 0.8
        , _at(paper_tear(0.14), 0.03, 0.26) * 0.5), -1.0,
        note="横幅落下：闷响 + 纸翻")

    for nm, f, br in (("phase_green", 520.0, 0.7), ("phase_yellow", 660.0, 0.9), ("phase_red", 430.0, 0.55)):
        S["sfx_" + nm] = _s(0.12, lambda f=f, br=br: (
            wood_click(f, 0.035, 0.8) * 0.7
            , glass_tick(f * 4.0, 0.10, br) * 0.5), -12.0,
            note="相位灯（仅选中路口）")

    S["sfx_arrival"] = _s(0.20, lambda: bell(880.0, 0.20) * 0.45, -14.0,
                          note="到达（限流使用，勿逐车）")

    # ---- D 模式与场景 ---------------------------------------------
    S["sfx_mode_to_edit"] = _s(0.45, lambda: (
        _filt_sweep_tone(1200.0, 240.0, 0.45) * 0.6
        , _at(wood_click(260.0, 0.07, 0.9), 0.32, 0.45) * 0.85), 0.0,
        note="进编辑：下沉 + 工具锁定")

    S["sfx_mode_to_view"] = _s(0.45, lambda: (
        _filt_sweep_tone(240.0, 1200.0, 0.45) * 0.6
        , whoosh(0.45, 300.0, 2000.0) * 0.5), 0.0,
        note="进观看：上浮 + 开阔")

    S["sfx_ui_hide"] = _s(0.50, lambda: (
        _filt_sweep_tone(1600.0, 300.0, 0.50) * 0.5
        , whoosh(0.50, 2200.0, 300.0) * 0.55), -3.0,
        note="去 UI：慢慢关灯")

    S["sfx_ui_show"] = _s(0.50, lambda: (
        _filt_sweep_tone(300.0, 1600.0, 0.50) * 0.5
        , whoosh(0.50, 300.0, 2200.0) * 0.55), -3.0,
        note="唤回 UI：慢慢开灯")

    S["sfx_to_night"] = _s(1.60, lambda: _sky(True), -2.0,
                           note="入夜：暖转冷 + 虫鸣进入")
    S["sfx_to_day"] = _s(1.60, lambda: _sky(False), -2.0,
                          note="天亮：冷转暖 + 鸟鸣进入")

    S["sfx_lang_switch"] = _s(0.18, lambda: (
        _blips([(0.01, 587.33), (0.09, 880.00)], 0.18, 0.09) * 0.8), -3.0,
        note="语言：中立两音")

    S["sfx_auto_cycle_on"] = _s(0.50, lambda: (
        _clockwork(0.50) * 0.8
        , _at(bell(659.25, 0.35), 0.20, 0.50) * 0.4), -2.0,
        note="自动循环：齿轮上弦")

    # ---- E 存档与撤销 ---------------------------------------------
    S["sfx_save_ok"] = _s(0.40, lambda: (
        _at(bell(587.33, 0.35) * 0.6, 0.00, 0.40)
        , _at(bell(880.00, 0.35) * 0.55, 0.09, 0.40)
        , _at(paper_tear(0.12), 0.16, 0.40) * 0.4), 0.0,
        note="保存成功：纯五度两音")

    S["sfx_save_fail"] = _s(0.35, lambda: (
        _at(wood_click(190.0, 0.12, 0.35), 0.00, 0.35) * 0.9
        , _at(wood_click(165.0, 0.12, 0.30), 0.13, 0.35) * 0.8), 0.0,
        note="保存失败：两个闷音下行")

    S["sfx_load_ok"] = _s(0.45, lambda: (
        whoosh(0.45, 300.0, 2200.0) * 0.6
        , _at(wood_click(340.0, 0.06, 0.9), 0.28, 0.45) * 0.8
        , _at(marimba(659.25, 0.30) * 0.5, 0.06, 0.45)), 0.0,
        note="加载：上扫 + 场景拼装")

    S["sfx_delete_save"] = _s(0.25, lambda: (
        paper_tear(0.18) * 0.7
        , whoosh(0.24, 1600.0, 300.0) * 0.5), -2.0,
        note="删存档：撕纸")

    S["sfx_clear_canvas"] = _s(0.80, lambda: (
        whoosh(0.80, 300.0, 2600.0) * 0.8
        , _filt_sweep_tone(400.0, 90.0, 0.80) * 0.5
        , _at(filt(colnoise(int(0.6 * SR), lp_fn(500.0, 2)) * env_perc(int(0.6 * SR), 0.02, 0.40), lp_fn(400.0, 2)) * 0.6, 0.1, 0.80)), 0.0,
        note="清空：向外扩散 + 低频消散")

    S["sfx_undo"] = _s(0.20, lambda: zip_sound(0.20, up=False) * 0.9, -2.0,
                       note="撤销：倒带")
    S["sfx_redo"] = _s(0.20, lambda: zip_sound(0.20, up=True) * 0.9, -2.0,
                       note="重做：快进")
    S["sfx_noop_soft"] = _s(0.12, lambda: (
        wood_click(150.0, 0.09, 0.25) * 0.8), -10.0,
        note="空操作：比耳语还轻")

    return S


# ---- 音效用的小工具 -------------------------------------------------

def _mixall(parts):
    """把若干长度不一的数组右端补零后相加（音效各层的时长本来就不同）"""
    parts = [np.asarray(x, dtype=np.float64) for x in parts]
    n = max(len(x) for x in parts)
    out = np.zeros(n)
    for x in parts:
        out[:len(x)] += x
    return out


def _fit(x, n):
    """补齐或截断到指定长度（截断后由 fade_io 收尾，不会爆音）"""
    x = np.asarray(x, dtype=np.float64)
    if len(x) == n: return x
    if len(x) > n: return x[:n]
    return np.concatenate([x, np.zeros(n - len(x))])


def _trim_lead(x, keep_ms=1.0, thresh=0.02):
    """切掉音头前的空白。

    `SoundPool` 是从第 0 个采样开始播的：文件前面垫 30ms 静音，用户按下去到听见
    就白白多 30ms。`_fit()` 只在尾部补零，音头空白来自素材本身（滤波预延迟、
    `_at()` 把短音放在 0.04s 处等），所以必须单独裁一刀。

    留 `keep_ms` 的余量给 `fade_io` 的 2ms 起振 —— 直接切到第一个可闻采样会削出咔哒声。
    全静音的文件原样返回。等价实现见 `tools/trim_sfx_preroll.py`（给已渲染的 wav 用）。
    """
    x = np.asarray(x, dtype=np.float64)
    if len(x) == 0: return x
    peak = float(np.max(np.abs(x)))
    if peak <= 0: return x
    idx = np.flatnonzero(np.abs(x) > peak * thresh)
    if len(idx) == 0: return x
    cut = max(0, int(idx[0]) - int(keep_ms * SR / 1000))
    return x[cut:] if cut > 0 else x


def _at(sig, pos, total_len):
    """把一个短音放到 total_len 的 pos 秒处"""
    n = int(total_len * SR)
    out = np.zeros(n)
    i = int(pos * SR)
    j = min(n, i + len(sig))
    out[i:j] += sig[:j - i]
    return out


def _blips(items, total_len, dur_each):
    """[(pos, freq), ...] → 一串短音"""
    out = np.zeros(int(total_len * SR))
    for pos, f in items:
        n = int(dur_each * SR)
        e = env_perc(n, 0.002, dur_each * 0.45)
        y = np.sin(2 * np.pi * f * tt(n)) * e
        y += 0.3 * np.sin(2 * np.pi * f * 2 * tt(n)) * env_perc(n, 0.002, dur_each * 0.2)
        out = _mix_at(out, y, pos)
    return out


def _mix_at(buf, sig, pos):
    """
    把 sig 混进 buf 的 pos 秒处。

    pos 可能因"人性化抖动"变成负数（第一小节第一个八分音符最常见）。
    这里夹到 0 而不是截断波形 —— 截断会把起音的 2ms 切掉，
    信号从 0.87 的振幅突然开始，在循环点上留一声爆音。
    """
    i = max(0, int(pos * SR))
    j = min(len(buf), i + len(sig))
    if j > i:
        buf[i:j] += sig[:j - i]
    return buf


def _slide(dur, up=True):
    """带摩擦感的滑动（胶囊 / 抽屉）"""
    n = int(dur * SR)
    segs, k = [], 10
    per = n // k
    for i in range(k):
        t01 = i / (k - 1.0)
        f0 = (200.0 + 500.0 * t01) if up else (700.0 - 500.0 * t01)
        segs.append(colnoise(per, bp_fn(f0, 1.4)) * env_perc(per, 0.004, per / SR * 0.7))
    y = np.concatenate(segs)
    if len(y) < n:
        y = np.concatenate([y, np.zeros(n - len(y))])
    w = np.sin(np.pi * np.linspace(0.0, 1.0, n)) ** 0.8
    y = y[:n] * w
    # 叠一层低频摩擦
    t = tt(n)
    fr = np.sin(2 * np.pi * ((90.0 if up else 130.0)) * t) * w * 0.3
    return y * 0.9 + fr


def _filt_sweep_tone(f0, f1, dur):
    """扫频音 + 跟随的亮度变化（模拟滤波器扫动）"""
    n = int(dur * SR)
    t = tt(n)
    ph = 2 * np.pi * np.cumsum(np.geomspace(max(f0, 1.0), max(f1, 1.0), n)) / SR
    y = np.sin(ph)
    e = np.sin(np.pi * np.linspace(0.0, 1.0, n)) ** 0.7
    return y * e


def _draw_loop():
    """
    画路持续音：石墨 + 细颗粒（2 秒无缝循环）。
    两个调制频率取 7.0 / 6.5Hz —— 2 秒内正好 14 / 13 个周期，
    波形本身就是周期性的，接缝处连交叉淡化都几乎不需要。
    """
    n = int((2.0 + 0.25) * SR)
    nb = colnoise(n, bp_fn(2200.0, 0.8))
    t = tt(n)
    am = 1.0 + 0.35 * np.sin(2 * np.pi * 7.0 * t) + 0.20 * np.sin(2 * np.pi * 6.5 * t + 1.0)
    y = filt(nb * am, bp_fn(1800.0, 0.9))
    return _wrap_loop(y, 2.0, 0.25) * 0.25


def _clockwork(dur):
    """齿轮上弦：一串等间隔咔哒 + 上紧的吱声"""
    n = int(dur * SR)
    out = np.zeros(n)
    for i, p in enumerate(np.linspace(0.0, 0.30, 9)):
        _mix_at(out, wood_click(700.0 + i * 40, 0.03, 1.0) * 0.5, p)
    t = tt(n)
    creak = np.sin(2 * np.pi * np.linspace(300.0, 520.0, n) * t) * env_perc(n, 0.05, 0.35) * 0.15
    return out + creak


def _sky(to_night):
    """昼夜切换：1.6s 的冷暖扫频 + 环境进出"""
    dur = 1.60
    n = int(dur * SR)
    # 入夜 = 亮→暗（下行）；天亮 = 暗→亮（上行）
    base = _filt_sweep_tone(1400.0, 260.0, dur) * 0.5 if to_night \
        else _filt_sweep_tone(260.0, 1400.0, dur) * 0.5
    # 环境层：虫鸣 or 鸟鸣，放在后段（先响的是画面，后响的是自然界）
    amb = np.zeros(n)
    if to_night:
        for p in (0.55, 0.75, 0.95, 1.15):
            _mix_at(amb, cricket_burst(0.35) * 0.8, p)
    else:
        for p in (0.60, 0.85, 1.10, 1.30):
            _mix_at(amb, bird_chirp() * 1.2, p)
    # 空气：与扫频同向（入夜下行、天亮上行）
    air = whoosh(dur, 900.0, 300.0) * 0.35 if to_night \
        else whoosh(dur, 300.0, 900.0) * 0.35
    return base + amb + air


def _crash():
    """崩溃：低频冲击 + 金属呻吟 + 长黑暗尾"""
    dur = 1.40
    n = int(dur * SR)
    # 1. 低频冲击（扫频 + 失真）
    ph = 2 * np.pi * np.cumsum(np.geomspace(90.0, 32.0, n)) / SR
    impact = np.sin(ph) * env_perc(n, 0.004, 0.55)
    impact = soft_clip(impact * 2.2)
    impact = filt(impact, lp_fn(220.0, 2))
    # 2. 金属呻吟（低频非谐 + 缓慢衰减）
    t = tt(n)
    groan = np.zeros(n)
    for r, a, tau in ((1.0, 1.0, 0.9), (1.41, 0.6, 0.7), (2.13, 0.4, 0.5), (3.07, 0.2, 0.3)):
        groan += a * np.sin(2 * np.pi * 62.0 * r * t + 0.7 * np.sin(2 * np.pi * 3.1 * t)) * env_perc(n, 0.02, tau)
    groan = filt(groan, bp_fn(180.0, 1.2)) * 0.55
    # 3. 结构落定的碎响
    debris = np.zeros(n)
    for p, a in ((0.10, 0.5), (0.22, 0.35), (0.38, 0.25), (0.55, 0.15)):
        _mix_at(debris, thump(120.0, 50.0, 0.18) * a, p)
    y = impact * 1.0 + groan + debris
    # 4. 长混响尾
    ir = make_ir(1.5, decay=3.2, seed=7)
    ir = ir * np.exp(-1.4 * tt(len(ir)) / 1.5)
    wet = conv(y, ir)
    y = y * 0.75 + filt(wet, lp_fn(1600.0, 2)) * 0.55
    return filt(y, lp_fn(3800.0, 2))


# ============================================================ 音乐 / 环境

def _wrap_loop(raw, loop_s, xf=0.40):
    """
    环形交叉淡化：把 [L, L+X] 这段"循环之后本该接的内容"混进开头。
    这样播放到末尾再回到开头时，接缝是连续的。
    """
    L = int(loop_s * SR)
    X = int(xf * SR)
    if len(raw) < L + X:
        raw = np.concatenate([raw, np.zeros(L + X - len(raw))])
    out = raw[:L].copy()
    w = np.linspace(0.0, 1.0, X)
    w_head = np.sqrt(w)                             # 原素材：0 → 1
    w_cont = np.sqrt(1.0 - w)                       # 循环点之后的延续：1 → 0
    # ⚠️ 权重的方向极易搞反。开头必须是"延续"占满（这样 out[0] 才是
    # out[L-1] 的自然下一帧），末尾才落回原素材。写反的话接缝是断开的，
    # 每循环一次就会"啪"一声。
    out[:X] = raw[:X] * w_head + raw[L:L + X] * w_cont
    return out


def _stereo(mono, spread_ms=0.0, k=0.35):
    """
    单声道 → 立体声，用 M/S 加宽而不是 Haas 延时：
        L = m + k·side,  R = m − k·side
    单声道求和后 L+R = 2m，**完全兼容单声道**。
    这点很关键：手机外放是单声道，用 Haas 延时会在求和时产生梳状滤波，
    低频会被梳掉一大块，听感直接塌掉。
    """
    n = len(mono)
    if spread_ms <= 0:
        return np.stack([mono, mono], axis=1)
    d = int(spread_ms * SR / 1000)
    side = np.concatenate([np.zeros(d), mono])[:n]
    side = filt(side, hp_fn(500.0, 2))          # 只让中高频参与加宽，低频保持居中
    l, r = mono + k * side, mono - k * side
    return np.stack([l, r], axis=1) / np.sqrt(1.0 + k * k)


def _master(buf, loop_s, target_lufs=-16.0, lp_hz=13000.0, width_ms=0.0, ir=None):
    """总处理：软削 → 压暗 → 环形循环 → 响度归一 → 立体声"""
    y = soft_clip(buf, 1.25)
    y = filt(y, lp_fn(lp_hz, 2))
    # ⚠️ 混响必须在环形交叉淡化**之前**做。
    # 放之后的话，卷积把循环点当成信号终点，混响尾巴不会绕回开头 ——
    # 每循环一次就在接缝上留一声（实测夜间轨的接缝跳变是其它轨的 5 倍）。
    if ir is not None:
        y = conv(y, ir) * 0.6 + y * 0.7
    y = _wrap_loop(y, loop_s)
    st = _stereo(y, width_ms)
    st = loudnorm(st, target_lufs)
    # 峰值上限 −3.1 dBFS：响度归一有可能把峰值推到 0 附近（实测白天观看轨
    # 到了 −1.4 dBFS），手机外放时和音效叠加必然削顶。宁可牺牲 1~2 dB 响度。
    p = np.abs(st).max()
    if p > 0.70:
        st *= 0.70 / p
    return st


# ---- BGM-01 白天 · 编辑 ---------------------------------------------
def bgm_day_edit(loop_s=93.9):
    bpm = 92.0
    bar = 60.0 / bpm * 4.0
    bars = 36
    prog = [chord(["C3", "E3", "G3", "B3"]), chord(["A2", "C3", "E3", "G3"]),
            chord(["F2", "A2", "C3", "E3"]), chord(["G2", "B2", "D3", "E3"])]
    n = int((bars * bar + 0.6) * SR)
    buf = np.zeros(n)

    for b in range(bars):
        t0 = b * bar
        ch = prog[b % 4]
        # pad：每小节铺一层
        _mix_at(buf, pad([f * 2 for f in ch], bar * 1.05) * 0.5, t0)
        # sub bass：每小节根音
        _mix_at(buf, sub_bass(ch[0], bar * 0.95) * 0.42, t0)
        # 琶音：八分音符
        notes = ch + [ch[1] * 2, ch[2] * 2]
        for i in range(8):
            f = notes[(i * 3 + b) % len(notes)] * 2
            p = t0 + i * bar / 8 + rng.normal(0, 0.006)
            v = rng.uniform(0.62, 1.0)
            _mix_at(buf, marimba(f, 0.55) * 0.30 * v, max(0.0, p))
        # 沙锤：八分，正反拍交替
        for i in range(8):
            p = t0 + i * bar / 8 + rng.normal(0, 0.004)
            _mix_at(buf, shaker(i % 2 == 0) * 0.5, max(0.0, p))
        # 响指：2、4 拍
        for i in (1, 3):
            _mix_at(buf, snap() * 0.35, t0 + i * bar / 4 + rng.normal(0, 0.005))
    return _master(buf, loop_s, -16.0, 12000.0, 9.0)


# ---- BGM-02 白天 · 观看 ---------------------------------------------
def bgm_day_view(loop_s=110.8):
    bpm = 104.0
    bar = 60.0 / bpm * 4.0
    bars = 48
    prog = [chord(["C3", "E3", "G3", "B3", "D4"]), chord(["A2", "C3", "E3", "G3"]),
            chord(["D3", "F3", "A3", "C4"]), chord(["G2", "B2", "D3", "F3"])]
    n = int((bars * bar + 0.6) * SR)
    buf = np.zeros(n)

    for b in range(bars):
        t0 = b * bar
        ch = prog[b % 4]
        # Rhodes 和弦：1 拍 + 3 拍反拍
        for pos, amp in ((0.0, 0.34), (2.5 / 4 * bar, 0.20)):
            for f in ch:
                _mix_at(buf, rhodes(f * 2, bar * 0.9) * amp * 0.5, t0 + pos)
        # 贝斯：1 拍根音 + 3 拍五度
        _mix_at(buf, e_bass(ch[0], bar * 0.42) * 0.55, t0)
        _mix_at(buf, e_bass(ch[2] * 0.5 if len(ch) > 2 else ch[0], bar * 0.35) * 0.40,
                t0 + 2.5 / 4 * bar)
        # 鼓：软底鼓 1、3；rim 2、4
        for i in (0, 2):
            _mix_at(buf, kick(0.30) * 0.7, t0 + i * bar / 4)
        for i in (1, 3):
            _mix_at(buf, rim() * 0.55, t0 + i * bar / 4 + rng.normal(0, 0.004))
        # hi-hat：八分，闭合
        for i in range(8):
            v = 0.30 if i % 2 == 0 else 0.18
            _mix_at(buf, hat(False) * v, t0 + i * bar / 8 + rng.normal(0, 0.003))
        # 钟琴旋律：每 8 小节来一句
        if b % 8 == 4:
            for i, f in enumerate([ch[3] * 4, ch[4] * 4 if len(ch) > 4 else ch[3] * 4]):
                _mix_at(buf, bell(f, 0.9) * 0.22, t0 + i * bar / 2)
    return _master(buf, loop_s, -16.0, 13000.0, 11.0)


# ---- BGM-03 夜间 -----------------------------------------------------
def bgm_night(loop_s=116.8):
    bpm = 74.0
    bar = 60.0 / bpm * 4.0
    bars = 36
    prog = [chord(["F2", "A2", "C3", "E3"]), chord(["E2", "G2", "B2", "D3"]),
            chord(["A2", "C3", "E3", "G3"]), chord(["D2", "F2", "A2", "C3"])]
    n = int((bars * bar + 0.6) * SR)
    buf = np.zeros(n)
    ir = make_ir(1.1, decay=3.0, seed=3)

    for b in range(bars):
        t0 = b * bar
        ch = prog[b % 4]
        for f in ch:
            _mix_at(buf, rhodes(f * 2, bar * 1.2, bright=0.55) * 0.26, t0 + 0.02)
        _mix_at(buf, upright(ch[0], bar * 0.8) * 0.42, t0)
        # 刷鼓：极轻，一小节一次
        _mix_at(buf, shaker(True) * 0.22, t0 + bar * 0.5)
        # 颤音琴：每 4 小节一个音
        if b % 4 == 2:
            _mix_at(buf, vibra(ch[3] * 4, 1.6) * 0.20, t0 + bar * 0.25)
    # 黑胶底噪
    vn = colnoise(n, lp_fn(9000.0, 2)) * 0.012
    for _ in range(int(n / SR * 6)):
        p = rng.integers(0, max(1, n - 200))
        vn[p:p + 120] += rng.normal(0, 0.05, min(120, n - p))
    buf += vn
    return _master(buf, loop_s, -17.0, 9000.0, 13.0, ir=ir)


# ---- BGM-04 崩溃余波 -------------------------------------------------
def bgm_crash(loop_s=40.0):
    n = int((loop_s + 0.6) * SR)
    buf = np.zeros(n)
    # 大提琴持续音（两个音缓慢交替）
    for i, (f, p) in enumerate((("C2", 0.0), ("G1", 20.0))):
        _mix_at(buf, cello(nf(f), 22.0) * 0.9, p)
    # 稀疏失谐钢琴
    for p, nn in ((4.0, "C3"), (11.0, "G2"), (19.0, "Eb3"), (27.0, "C3"), (34.0, "G2")):
        _mix_at(buf, detuned_piano(nf(nn), 3.0) * 0.35, p)
    # 反向混响涌动
    for p in (2.0, 14.0, 26.0, 34.0):
        seg = int(4.0 * SR)
        irseg = make_ir(4.0, decay=2.0, seed=int(p))
        _mix_at(buf, conv(np.sin(2 * np.pi * 55.0 * tt(seg)) * env_perc(seg, 1.6, 1.6), irseg) * 0.5, p)
    # 电流嗡鸣
    buf += hum_electrical(loop_s + 0.6) * 0.9
    return _master(buf, loop_s, -20.0, 2200.0, 7.0)


# ---- AMB-01 白天城市 -------------------------------------------------
def amb_day_city(loop_s=60.0):
    L = int(loop_s * SR)
    X = int(0.5 * SR)
    n = L + X
    g = np.random.default_rng(11)
    # 远处车流：低通噪声 + 缓慢起伏
    bed = colnoise(n, lp_fn(420.0, 2), g)
    t = tt(n)
    bed *= (1.0 + 0.18 * np.sin(2 * np.pi * 0.05 * t) + 0.10 * np.sin(2 * np.pi * 0.13 * t + 1.0))
    # 风
    wind = colnoise(n, bp_fn(500.0, 0.7), g) * (0.4 + 0.3 * np.sin(2 * np.pi * 0.07 * t + 2.0))
    mono = bed * 0.9 + wind * 0.35
    st = np.stack([mono, mono], axis=1)

    # 事件：每个都写两遍（pos 和 pos+循环长度），
    # 第二份负责"跨过循环点的那半截"绕回开头，接缝才连得上。
    events = []
    for _ in range(5):      # 驶过的车：声像从左到右（或反过来）
        p = g.uniform(0, loop_s)
        pan = (0.15, 0.85) if g.random() < 0.5 else (0.85, 0.15)
        events.append((p, car_pass(g.uniform(2.2, 3.4)) * g.uniform(0.5, 1.0), pan))
    for _ in range(7):      # 鸟鸣：固定随机声像
        events.append((g.uniform(0, loop_s), bird_chirp() * g.uniform(0.6, 1.2),
                       g.uniform(0.2, 0.8)))
    for _ in range(3):      # 远处施工：偏中间
        events.append((g.uniform(0, loop_s), thump(70.0, 40.0, 0.25) * 0.35,
                       g.uniform(0.35, 0.65)))
    for pos, ev, pan in events:
        st = _add_stereo(st, ev, pos, pan)
        st = _add_stereo(st, ev, pos + loop_s, pan)
    st = _stereo_wrap(st, L, X)
    return loudnorm(st, -26.0)


# ---- AMB-02 夜间城市 -------------------------------------------------
def amb_night_city(loop_s=60.0):
    L = int(loop_s * SR)
    X = int(0.5 * SR)
    n = L + X
    g = np.random.default_rng(23)
    # 低频交通隆隆
    bed = colnoise(n, lp_fn(220.0, 2), g)
    t = tt(n)
    bed *= (1.0 + 0.15 * np.sin(2 * np.pi * 0.04 * t))
    # 日光灯电流声
    buzz = hum_electrical(loop_s + 0.5) * 0.5
    mono = bed * 1.0 + buzz
    st = np.stack([mono, mono], axis=1)

    # 虫鸣：成串出现
    for _ in range(6):
        p = g.uniform(0, loop_s)
        pan = g.uniform(0.2, 0.8)
        for k in range(int(g.integers(3, 6))):
            ev = cricket_burst(0.35) * g.uniform(0.4, 0.9)
            q = (p + k * 0.42) % loop_s
            st = _add_stereo(st, ev, q, pan)
            st = _add_stereo(st, ev, q + loop_s, pan)
    # 偶尔一辆车（夜间车少）
    for _ in range(2):
        p = g.uniform(0, loop_s)
        ev = car_pass(3.2) * 0.45
        pan = (0.15, 0.85) if g.random() < 0.5 else (0.85, 0.15)
        st = _add_stereo(st, ev, p, pan)
        st = _add_stereo(st, ev, p + loop_s, pan)
    # 远处一声狗叫
    for p in (18.0, 47.0):
        pan = g.uniform(0.2, 0.8)
        for k in range(2):
            ev = filt(colnoise(int(0.12 * SR), bp_fn(700.0, 2.0)) *
                      env_perc(int(0.12 * SR), 0.005, 0.05), lp_fn(1200.0, 2)) * 0.25
            st = _add_stereo(st, ev, p + k * 0.22, pan)
            st = _add_stereo(st, ev, p + k * 0.22 + loop_s, pan)
    st = _stereo_wrap(st, L, X)
    return loudnorm(st, -26.0)


def _add_stereo(st, mono_sig, pos, pan=0.5):
    """
    把单声道事件加进立体声缓冲。
    pan 传数字 = 固定声像（等功率）；传 (起, 止) 元组 = 移动声像（车从左到右）。
    """
    i = int(pos * SR)
    j = min(len(st), i + len(mono_sig))
    if j <= i: return st
    seg = mono_sig[:j - i]
    if isinstance(pan, tuple):
        p = np.linspace(pan[0], pan[1], len(seg))
        l, r = np.cos(p * np.pi / 2), np.sin(p * np.pi / 2)
    else:
        l, r = np.cos(pan * np.pi / 2), np.sin(pan * np.pi / 2)
    st[i:j, 0] += seg * l
    st[i:j, 1] += seg * r
    return st


def _stereo_wrap(st, L, X):
    """立体声环形交叉淡化"""
    out = st[:L].copy()
    w = np.linspace(0.0, 1.0, X)
    w_head, w_cont = np.sqrt(w), np.sqrt(1.0 - w)
    for c in (0, 1):
        out[:X, c] = st[:X, c] * w_head + st[L:L + X, c] * w_cont
    return out


# ============================================================ 主流程

MUSIC = [
    ("bgm_day_edit", bgm_day_edit, 93.9, -16.0),
    ("bgm_day_view", bgm_day_view, 110.8, -16.0),
    ("bgm_night", bgm_night, 116.8, -17.0),
    ("bgm_crash", bgm_crash, 40.0, -20.0),
    ("amb_day_city", amb_day_city, 60.0, -26.0),
    ("amb_night_city", amb_night_city, 60.0, -26.0),
]


def render_sfx(outdir, verbose=True):
    spec = build_sfx_spec()
    made = []
    for name in sorted(spec):
        s = spec[name]
        raw = s["fn"]()
        y = _mixall(raw) if isinstance(raw, tuple) else np.asarray(raw, dtype=np.float64)
        if y.ndim > 1:
            y = y.mean(axis=1)
        y = _fit(y, int(s["dur"] * SR))
        y = _trim_lead(y)          # 音头空白 = 白给的点击延迟，必须裁掉
        y = fade_io(y, 2.0, 6.0)
        y = norm_peak(y, -12.0 + s["gain"])
        p = os.path.join(outdir, name + ".wav")
        sf.write(p, y, SR, subtype="PCM_16")
        made.append(dict(file=name + ".wav", kind="sfx",
                         dur_ms=round(len(y) / SR * 1000),
                         gain_db=s["gain"], loop=s["loop"], note=s["note"]))
        if verbose:
            print("  sfx  %-22s %4d ms  gain %+5.1f dB%s" %
                  (name, len(y) / SR * 1000, s["gain"], "  [loop]" if s["loop"] else ""))
    return made


def write_ogg(path, x, sr, block_s=4.0):
    """
    分块写 OGG。

    不能直接 `sf.write(整条 2 分钟的音频)`：libsndfile 的 Vorbis 编码器
    在一次性拿到超大缓冲时会把进程**静默杀掉**（实测 40 秒以上必崩，
    且没有任何异常，日志里一片空白）。改成 4 秒一块喂进去就完全正常。
    """
    x = np.clip(np.asarray(x, dtype=np.float32), -1.0, 1.0)
    step = int(block_s * sr)
    with sf.SoundFile(path, "w", sr, x.shape[1], format="OGG", subtype="VORBIS") as f:
        for i in range(0, len(x), step):
            f.write(x[i:i + step])


def render_music(outdir, verbose=True):
    made = []
    for name, fn, loop_s, target in MUSIC:
        st = np.clip(fn(), -1.0, 1.0)
        p = os.path.join(outdir, name + ".ogg")
        write_ogg(p, st, SR)
        made.append(dict(file=name + ".ogg", kind="music",
                         dur_s=round(len(st) / SR, 2), loop=True,
                         target_lufs=target, loop_s=round(loop_s, 2),
                         note="循环点 %ss · 目标 %s LUFS" % (loop_s, target)))
        if verbose:
            print("  mus  %-22s %6.2f s  ->  %s.ogg (%.0f LUFS)" %
                  (name, len(st) / SR, name, lufs(st)))
    return made


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["sfx", "music"], default=None)
    args = ap.parse_args()

    outdir = OUT
    os.makedirs(outdir, exist_ok=True)
    print("输出目录: %s" % outdir)

    sfx_meta = music_meta = []
    if args.only in (None, "sfx"):
        print("\n[1/2] 渲染音效 (%d 个) ..." % len(build_sfx_spec()))
        sfx_meta = render_sfx(outdir)
    if args.only in (None, "music"):
        print("\n[2/2] 渲染音乐与环境层 (%d 个，较慢) ..." % len(MUSIC))
        music_meta = render_music(outdir)

    # 清单（供接入时读取）。分阶段渲染时与已有清单合并，
    # 否则 --only music 会把上一轮已经渲染好的音效条目冲掉。
    mpath = os.path.join(os.path.dirname(os.path.abspath(__file__)), "audio_manifest.json")
    old = {}
    if os.path.exists(mpath):
        try:
            old = json.load(open(mpath, encoding="utf-8"))
        except (ValueError, OSError):
            old = {}
    if sfx_meta:
        old["sfx"] = sfx_meta
    if music_meta:
        old["music"] = music_meta
    old["sample_rate"] = SR
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(old, f, ensure_ascii=False, indent=2)
    print("\n清单: %s" % mpath)
    print("完成。")


if __name__ == "__main__":
    main()
