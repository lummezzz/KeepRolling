import os
import numpy as np
import soundfile as sf

ROOT = r"C:\Users\Jiakai Zhang\Desktop\Keep Rolling"
SRC = os.path.join(ROOT, "KeepRolling", "BGM-01.wav")
DST = os.path.join(ROOT, "KeepRolling", "app", "src", "main", "res", "raw", "bgm_day_view.ogg")

x, sr = sf.read(SRC, dtype="float32", always_2d=True)
print("source: %d frames @ %d Hz = %.2fs" % (len(x), sr, len(x) / sr))

# ---------- 1. 找到尾部"连续近乎静音"的起点 ----------
mono = x.mean(axis=1)
w = int(0.05 * sr)
n = len(mono) // w
env = np.array([np.sqrt((mono[i * w:(i + 1) * w] ** 2).mean()) for i in range(n)])
i = n - 1
while i > 0 and env[i] < 0.02:
    i -= 1
end = int((i + 1) * 0.05 * sr)
print("tail silence starts ~%.2fs -> keep %.2fs" % ((i + 1) * 0.05, end / sr))

y = x[:end].copy()

# ---------- 2. 0.6s 等功率交叉淡化（尾 ← 头）----------
tc = int(0.6 * sr)
t = np.linspace(0.0, np.pi / 2.0, tc)[:, None]
y[-tc:] = y[-tc:].copy() * np.cos(t) + x[:tc] * np.sin(t)

# ---------- 3. 端头 2ms 余弦微淡（消除相位差造成的接缝"咔"） ----------
tf = int(0.002 * sr)
r = np.linspace(0.0, np.pi / 2.0, tf)[:, None]
y[:tf] *= np.sin(r)
y[-tf:] *= np.cos(r)

print("final: %.2fs  peak %.3f  rms %.4f" % (len(y) / sr, np.max(np.abs(y)), np.sqrt((y ** 2).mean())))

# ---------- 4. 分块写 OGG（一次性写会被环境打断）----------
with sf.SoundFile(DST, "w", samplerate=sr, channels=2, format="OGG", subtype="VORBIS") as f:
    step = int(4 * sr)
    for i0 in range(0, len(y), step):
        f.write(np.ascontiguousarray(y[i0:i0 + step]))

print("written %.2f MB -> %s" % (os.path.getsize(DST) / 1048576, DST))

# ---------- 5. 回读校验 ----------
z, zr = sf.read(DST, dtype="float32", always_2d=True)
print("readback: %.2fs @ %d Hz  ch=%d" % (len(z) / zr, zr, z.shape[1]))
print("seam  first %s  last %s" % (np.round(z[0], 4), np.round(z[-1], 4)))
