import math

DT = 1.0 / 60.0
HARD = 8.0
COMFY = 2.5
MAXA = 2.0
MINGAP = 2.0
HEADWAY = 1.2
DIST = 45.0
V0 = 22.2


def idm_phantom(v, gap):
    """旧：把停止线当成一辆静止的车"""
    free = 1.0 - (v / 22.2) ** 4
    s_star = MINGAP + max(0.0, v * HEADWAY + v * v / (2 * math.sqrt(MAXA * COMFY)))
    acc = MAXA * (free - (s_star / max(gap, 0.5)) ** 2)
    return max(-HARD, min(MAXA, acc))


def kin(v, gap):
    """新：运动学约束，够就完全不干预；贴线时直接按住"""
    if gap < 0.35:
        return -HARD
    g = max(gap, 0.05)
    vmax = math.sqrt(2 * COMFY * g)
    if v <= vmax:
        return MAXA
    return -min(HARD, v * v / (2 * g))


def simulate(accel_fn, release_at=None):
    s, v, t = 0.0, V0, 0.0
    brake_start = None
    stopped_at = None
    v_at_release = None
    while t < 60 and s < DIST:
        a = accel_fn(v, DIST - s) if (release_at is None or s < release_at) else MAXA
        if brake_start is None and a < -0.05:
            brake_start = s
        v = max(0.0, v + a * DT)
        s += v * DT
        t += DT
        if release_at is not None and v_at_release is None and s >= release_at:
            v_at_release = v
        if stopped_at is None and v < 0.02:
            stopped_at = s
    return brake_start, stopped_at, v, v_at_release


for label, fn in (("旧 IDM-幽灵车", idm_phantom), ("新 运动学", kin)):
    b, st, v, _ = simulate(fn)
    print("%-14s A: 起刹 %5.1fm | 停于 %5.1fm (线前 %4.1fm) | 末速 %4.2f m/s" % (
        label,
        b if b is not None else -1,
        st if st is not None else -1,
        DIST - (st if st is not None else DIST),
        v,
    ))

print()
for label, fn in (("旧 IDM-幽灵车", idm_phantom), ("新 运动学", kin)):
    b, st, v, vr = simulate(fn, release_at=20.0)
    print("%-14s B: 约束在 20m 处解除 → 解除瞬间 %5.1f km/h，终速 %5.1f km/h%s" % (
        label,
        (vr or 0) * 3.6,
        v * 3.6,
        "（已被迫停死）" if st is not None and st < 20.0 else "",
    ))
