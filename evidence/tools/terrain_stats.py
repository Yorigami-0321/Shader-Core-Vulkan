#!/usr/bin/env python3
"""对比两臂在**地形区**的像素统计 —— GAP-008「albedo ≡ 0」判读用。

🔖 为什么不能直接用 `flicker_ratio` 判 albedo：
那个判据量的是「中心区黑色像素占比」，它分不出「**纯黑**」与「**极暗但有纹理**」。
GAP-008 要回答的是「albedo 是不是恒 0」⇒ 必须看**均值**：
均值 0 ⇒ 恒 0；有非零亮度 ⇒ 不是恒 0。

🔖 采样矩形是怎么定的（不是拍脑袋）：
先对 `viewSlot=0` 的帧打 **8×6 平均 luma 网格**，读出地平线在第 2/3 行之间、
底部第 6 行是 HUD ⇒ 取 **x∈[0, 0.50] / y∈[0.55, 0.83]** 这一块：
全是地形、**不含绿色清屏天空**、**不含 hotbar**。
⚠️ 若采样区混进天空，均值会被纯绿 (0,255,0) 的 luma≈182 抬高 ⇒ 结论作废
（本轮第一版就踩了这个：区域取 y∈[45%,80%] 混进了天空，报出 mean_G≈23 的假信号）。

用法：
  python3 evidence/tools/terrain_stats.py 臂A的帧... -- 臂B的帧...
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from flicker_ratio import decode_png  # noqa: E402

FX0, FX1 = 0.00, 0.50   # 左半边：避开右下角的手与 hotbar
FY0, FY1 = 0.55, 0.83   # 地平线以下、HUD 以上的纯地形带


def stats(path):
    w, h, ch, px = decode_png(path)
    stride = w * ch
    x0, x1 = int(w * FX0), int(w * FX1)
    y0, y1 = int(h * FY0), int(h * FY1)
    n = sr = sg = sb = 0
    mx = 0
    nonzero = 0
    for y in range(y0, y1):
        row = y * stride
        for x in range(x0, x1):
            i = row + x * ch
            r, g, b = px[i], px[i + 1], px[i + 2]
            sr += r; sg += g; sb += b
            n += 1
            if r > mx:
                mx = r
            if r or g or b:
                nonzero += 1
    return {
        "size": f"{w}x{h}",
        "n": n,
        "mean_R": sr / n,
        "mean_G": sg / n,
        "mean_B": sb / n,
        "luma": (0.2126 * sr + 0.7152 * sg + 0.0722 * sb) / n,
        "max_R": mx,
        "nonblack%": nonzero / n * 100,
    }


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    arms = [argv[:argv.index("--")], argv[argv.index("--") + 1:]] if "--" in argv else [argv]
    for arm in arms:
        if not arm:
            continue
        print(f"—— {len(arm)} 帧：{os.path.basename(arm[0])} … {os.path.basename(arm[-1])} ——")
        for p in arm:
            s = stats(p)
            print(f"  {os.path.basename(p):22s} luma={s['luma']:8.4f} "
                  f"meanRGB=({s['mean_R']:7.4f},{s['mean_G']:7.4f},{s['mean_B']:7.4f}) "
                  f"maxR={s['max_R']:3d} 非黑像素={s['nonblack%']:6.3f}%  n={s['n']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
