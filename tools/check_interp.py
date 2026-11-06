"""验证 FrameMixer 的插值在 5x 倍速 + 120Hz 屏幕下是否等步长、不倒退。"""

FRAME_MS = 1000.0 / 120.0     # 屏幕帧间隔
SNAP_MS = 16.0                # 仿真快照间隔（delay(16) 实际略长）
SPEED = 22.2                  # m/s（80 km/h）
MULT = 5.0                    # 倍速

step_per_snap = SPEED * MULT * (SNAP_MS / 1000.0)   # 每份快照车前进多少米

# 快照到达时刻（真实时间 ms）与对应世界位置
snaps = []
t = 0.0
n = 0
while t < 200:
    snaps.append((t, n * step_per_snap))
    t += SNAP_MS
    n += 1

frames = []
t = 0.0
while t < 160:
    frames.append(t)
    t += FRAME_MS


def sim(interpolate):
    out = []
    cur = 0                      # 当前快照下标
    prev = 0
    t0 = t1 = 0.0
    lag = 0.0
    last_arrival = None
    for ft in frames:
        # 推进快照（可能一帧内到达多份）
        while cur + 1 < len(snaps) and snaps[cur + 1][0] <= ft:
            prev = cur
            cur += 1
            t0, t1 = snaps[prev][0], snaps[cur][0]
            if last_arrival is not None:
                d = max(4.0, min(40.0, snaps[cur][0] - last_arrival))
                lag = d if lag == 0 else (lag * 3 + d) / 4
            last_arrival = snaps[cur][0]
        if not interpolate or t1 <= t0:
            out.append(snaps[cur][1])
        else:
            span = t1 - t0
            lg = lag if 0 < lag < span else span
            k = (ft - lg - t0) / span
            k = 0.0 if k < 0 else (1.0 if k > 1 else k)
            out.append(snaps[prev][1] + (snaps[cur][1] - snaps[prev][1]) * k)
    return out


for label, interp in (("不插值", False), ("插值", True)):
    xs = sim(interp)
    d = [xs[i + 1] - xs[i] for i in range(len(xs) - 1)]
    neg = sum(1 for x in d if x < -0.01)
    d_sorted = sorted(d)
    print("%-6s 每帧位移: 最小 %+.3f m  最大 %+.3f m  倒退帧 %d/%d" %
          (label, d_sorted[0], d_sorted[-1], neg, len(d)))
    zeros = sum(1 for x in d if abs(x) < 0.02)
    print("        位移≈0 的帧: %d/%d" % (zeros, len(d)))
