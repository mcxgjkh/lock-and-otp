#!/usr/bin/env python3
"""生成两个应用的启动图标（纯标准库，无 Pillow 依赖）。

不引入矢量图/自适应图标：minSdk 19 只需要 mipmap 位图，
用 4x 超采样 + 盒式降采样得到平滑边缘，输出 48/72/96/144/192 五档。
"""
import math
import os
import struct
import zlib

SS = 4  # 超采样倍数
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def write_png(path, w, h, rgba):
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def rrect(x, y, l, t, r, b, br):
    """圆角矩形内部判定（归一化坐标）。"""
    if x < l or x > r or y < t or y > b:
        return False
    cx = min(max(x, l + br), r - br)
    cy = min(max(y, t + br), b - br)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= br * br


def annulus_top(x, y, cx, cy, rad, thick):
    """上半圆弧（锁梁）。"""
    if y > cy:
        return False
    d = math.hypot(x - cx, y - cy)
    return abs(d - rad) <= thick / 2.0


def ring(x, y, cx, cy, rad, thick):
    d = math.hypot(x - cx, y - cy)
    return abs(d - rad) <= thick / 2.0


def disc(x, y, cx, cy, rad):
    return math.hypot(x - cx, y - cy) <= rad


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def render(size, kind):
    W = size * SS
    buf = bytearray(W * W * 4)
    for py in range(W):
        for px in range(W):
            x = (px + 0.5) / W
            y = (py + 0.5) / W
            if not rrect(x, y, 0.02, 0.02, 0.98, 0.98, 0.22):
                continue
            if kind == "lock":
                top, bottom = (0x2B, 0x3F, 0x5E), (0x10, 0x18, 0x27)
            else:
                top, bottom = (0x24, 0x5A, 0x63), (0x0C, 0x25, 0x2B)
            r, g, b = lerp(top, bottom, y)
            fg = None
            if kind == "lock":
                if annulus_top(x, y, 0.5, 0.468, 0.152, 0.064) \
                        or rrect(x, y, 0.255, 0.445, 0.745, 0.79, 0.058):
                    fg = (255, 255, 255)
                if disc(x, y, 0.5, 0.598, 0.038) or rrect(x, y, 0.481, 0.598, 0.519, 0.7, 0.013):
                    fg = None
            else:
                if ring(x, y, 0.5, 0.5, 0.245, 0.06):
                    fg = (255, 255, 255)
                if rrect(x, y, 0.472, 0.315, 0.528, 0.51, 0.014) \
                        or rrect(x, y, 0.5, 0.472, 0.66, 0.528, 0.014):
                    fg = (255, 255, 255)
                if disc(x, y, 0.5, 0.5, 0.032):
                    fg = (255, 255, 255)
            if fg is not None:
                r, g, b = fg
            i = (py * W + px) * 4
            buf[i] = r
            buf[i + 1] = g
            buf[i + 2] = b
            buf[i + 3] = 255

    # 盒式降采样
    out = bytearray(size * size * 4)
    for y in range(size):
        for x in range(size):
            ar = ag = ab = aa = 0
            for sy in range(SS):
                for sx in range(SS):
                    i = ((y * SS + sy) * W + (x * SS + sx)) * 4
                    ar += buf[i]
                    ag += buf[i + 1]
                    ab += buf[i + 2]
                    aa += buf[i + 3]
            n = SS * SS
            o = (y * size + x) * 4
            out[o] = ar // n
            out[o + 1] = ag // n
            out[o + 2] = ab // n
            out[o + 3] = aa // n
    return out


def emit(project_dir, kind):
    for bucket, size in SIZES.items():
        data = render(size, kind)
        d = os.path.join(project_dir, "app", "src", "main", "res", "mipmap-" + bucket)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "ic_launcher.png")
        write_png(path, size, size, data)
        print("wrote", path)


if __name__ == "__main__":
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    emit(os.path.join(base, "lockscreen-root"), "lock")
    emit(os.path.join(base, "otp-vault"), "otp")
