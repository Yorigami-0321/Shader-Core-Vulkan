#!/usr/bin/env python3
"""GAP-011 闪烁判据：中心区**黑色像素占比**（h22 校准口径的反向复刻 + 校准证据）。

【为什么需要这个工具】
`evidence/h22-criterion-calibration.md` 定下了判据，但**口径本身没有被工具化** ——
h25/h26 两轮的数字是临时算的，无法复算。07-CONSTRAINTS T10「数字要能复现」
在这里就是指本脚本。

【口径是怎么定出来的（不是猜的）】
`h22` 文档写「地形区采样 64000 px」。用**前缀和反解**（`inverse2.py` 的思路）在
h21/h25/h26 三组共 12 张已入库截图上搜索，能同时复现四个已公布数字的采样区是：
中心矩形 **x∈[45%,65%] / y∈[15%,75%]**（930x577 下 = 64,356 px ≈ 文档所说的 64000）。
阈值取 **RGB 各通道 ≤ 8**。复现精度 ±0.25pp：

    组           本脚本      h2x 文档公布
    h25 packfrag-off-1   0.39%      0.38%
    h26 att8-1           0.39%      0.38%
    h21 黑相位           99.86%     99.89%
    h21 有地形但天空黑    52.58%     52.81%

【判读】（阈值来自 h22：对照组上界 ~20% vs 实验组下界 52.8%，区间内无样本）
    > 90%  ⇒ **全黑相位**（地形整帧消失）
    < 60%  ⇒ **有内容相位**
    🔴 闪烁 = 同一组 6 帧里**两种相位交替出现**。单帧看不出闪烁（h22 §〇）。

【为什么不能用精确哈希】
MC 画面逐帧在变（云/水/动画/光照插值）：模组全关的对照组连拍 6 帧 = 6 种不同哈希
（`evidence/h22-criterion-calibration.md` §一）。⇒ 只看「语义级」属性是否稳定。

用法：
    python3 tools/vulkan-local/flicker_ratio.py evidence/h27-images/*.png
    python3 tools/vulkan-local/flicker_ratio.py --selfcheck     # 拿已入库证据回归
"""
import sys

FX0, FY0, FX1, FY1 = 0.45, 0.15, 0.65, 0.75
THR = 8

BLACK_PHASE = 90.0    # > ⇒ 全黑相位
CONTENT_PHASE = 60.0  # < ⇒ 有内容相位


def decode_png(path):
    """stdlib-only PNG 解码（8bit，ctype 2/6）。"""
    import struct, zlib
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos, idat, ihdr = 8, b"", None
    while pos < len(data):
        ln, typ = struct.unpack(">I4s", data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", chunk)
        elif typ == b"IDAT":
            idat += chunk
        pos += 12 + ln
    w, h, depth, ctype = ihdr[0], ihdr[1], ihdr[2], ihdr[3]
    assert depth == 8 and ctype in (2, 6), (depth, ctype)
    ch = 3 if ctype == 2 else 4
    raw = zlib.decompress(idat)
    stride = w * ch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, ch, bytes(out)


def black_ratio(path, thr=THR):
    """中心区黑色像素占比（%）。"""
    w, h, ch, px = decode_png(path)
    stride = w * ch
    x0, x1 = int(round(w * FX0)), int(round(w * FX1))
    y0, y1 = int(round(h * FY0)), int(round(h * FY1))
    n = black = 0
    for y in range(y0, y1):
        row = y * stride
        for x in range(x0, x1):
            i = row + x * ch
            n += 1
            if px[i] <= thr and px[i + 1] <= thr and px[i + 2] <= thr:
                black += 1
    return (black / n * 100 if n else 0.0), n


def verdict(ratios):
    """一组帧 → 判定串。"""
    black = [r for r in ratios if r > BLACK_PHASE]
    content = [r for r in ratios if r < CONTENT_PHASE]
    mid = [r for r in ratios if CONTENT_PHASE <= r <= BLACK_PHASE]
    if black and content:
        return "闪烁（两相交替：全黑 %d 帧 + 有内容 %d 帧）" % (len(black), len(content))
    if black:
        return "全黑相位（%d/%d 帧）—— 地形整帧消失" % (len(black), len(ratios))
    if content:
        return "无闪烁（%d/%d 帧全在有内容相位）" % (len(content), len(ratios))
    return "中间带（%d/%d 帧落在 60%%–90%%，判读需看图）" % (len(mid), len(ratios))


SELFCHECK = [
    # (路径, 文档公布值)
    ("evidence/h25-images/packfrag-off-1.png", 0.38),
    ("evidence/h26-images/att8-1.png", 0.38),
    ("evidence/h21-images/h21-U-black-phase.png", 99.89),
    ("evidence/h21-images/h21-T-after-both-fixes.png", 52.81),
]


def selfcheck():
    print("口径回归（复现 h22/h25/h26 已公布数字，容差 0.5pp）")
    ok = True
    for path, pub in SELFCHECK:
        try:
            got, n = black_ratio(path)
        except FileNotFoundError:
            print(f"  SKIP  {path}（未入库）")
            continue
        good = abs(got - pub) <= 0.5
        ok &= good
        print(f"  {'OK  ' if good else 'FAIL'} {path}: {got:.2f}%  (公布 {pub}%, n={n})")
    print("SELFCHECK " + ("PASS" if ok else "FAIL"))
    return ok


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("-")]
    if "--selfcheck" in argv:
        return 0 if selfcheck() else 1
    if not args:
        print(__doc__)
        return 2
    ratios = []
    for p in args:
        try:
            r, n = black_ratio(p)
        except FileNotFoundError:
            print(f"{p}: 缺失")
            continue
        ratios.append(r)
        print(f"{p}  black={r:6.2f}%  n={n}")
    if ratios:
        print("判定：" + verdict(ratios))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
