# -*- coding: utf-8 -*-
"""成品质检（含循环接缝连续性检查）"""
import os
import numpy as np, soundfile as sf

RAW = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    "..", "app", "src", "main", "res", "raw"))
def db(v): return -99.0 if v <= 1e-9 else 20*np.log10(v)

loops, oneshots, total = [], [], 0
print("%-20s %5s %8s %7s %9s %6s %8s" % ("文件","声道","时长","峰值dB","RMSdB","kbps","接缝"))
for f in sorted(os.listdir(RAW)):
    if not f.endswith((".wav", ".ogg")): continue
    p = os.path.join(RAW, f)
    x, sr = sf.read(p, always_2d=True)
    size = os.path.getsize(p); total += size
    dur = len(x)/sr
    kbps = size*8/dur/1000
    seam = ""
    if f.startswith(("bgm_", "amb_")) or f == "sfx_draw_loop.wav":
        for c in range(x.shape[1]):
            d = np.abs(np.diff(x[:, c]))
            jump = abs(x[0, c] - x[-1, c])
            ratio = jump / (d.mean() + 1e-12)
            if ratio > 8: seam += "CH%d跳变×%.0f " % (c, ratio)
        loops.append(f)
    else:
        oneshots.append(f)
    print("%-20s %5d %7.2fs %7.1f %9.1f %6.0f %s" % (
        f, x.shape[1], dur, db(np.abs(x).max()), db(np.sqrt(np.mean(x*x))),
        kbps, seam if seam else "OK"))
print("\n一次性音效 %d 个 · 循环素材 %d 个 · 合计 %.2f MB" % (
    len(oneshots), len(loops), total/1048576))
