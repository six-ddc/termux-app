#!/usr/bin/env python3
"""
ghostty_render_bench.py — Ghostty 渲染能力综合压测 / 演示脚本

零外部依赖（stdlib only；PIL/Pillow 可选，用于 Kitty PNG 测试）。
适配 Termux on Android、ghostty.app、kitty、wezterm 等 Ghostty 协议兼容终端。

使用方式：

    # 一屏综合 showcase（最直观，截图证据用）
    python3 ghostty_render_bench.py showcase

    # 全套自动跑：先跑动态/性能项，最后在报告里嵌入静态渲染样张
    python3 ghostty_render_bench.py all --report bench.json

    # 单测
    python3 ghostty_render_bench.py fps_scroll --duration 5
    python3 ghostty_render_bench.py latency --samples 200
    python3 ghostty_render_bench.py kitty
    python3 ghostty_render_bench.py visual --duration 5
    python3 ghostty_render_bench.py neon_tunnel --duration 5

    # 分组 / 列表
    python3 ghostty_render_bench.py perf --duration 5
    python3 ghostty_render_bench.py list

测试维度：
    协议正确性: truecolor / palette / styles / underlines / wide / osc8 / kitty
    渲染性能:   fps_static / fps_scroll / throughput / stress_rgba / stress_glyph
    视觉压测:   disco / plasma / matrix / fireworks / neon_tunnel
                braille_particles / emoji_storm / dashboard
    延迟:       latency (DSR 6 round-trip)
    一屏综合:   showcase

按 q / Ctrl-C 可中断当前测试，菜单内输入 a 跑全套。
"""

from __future__ import annotations

import argparse
import colorsys
import json
import math
import os
import random
import select
import signal
import struct
import sys
import termios
import time
import tty
from contextlib import contextmanager
from dataclasses import dataclass, field, asdict
from typing import Callable, Optional


# ─────────────────────────────────────────────────────────────────────────────
# 终端 IO 工具层
# ─────────────────────────────────────────────────────────────────────────────

ESC = "\x1b"
CSI = ESC + "["
APC = ESC + "_"
ST = ESC + "\\"
OSC = ESC + "]"
BEL = "\x07"


def w(s: str) -> None:
    """直接写到 stdout，不缓冲。"""
    sys.stdout.write(s)


def flush() -> None:
    sys.stdout.flush()


def wf(s: str) -> None:
    w(s)
    flush()


def term_size() -> tuple[int, int]:
    """(cols, rows)。"""
    try:
        sz = os.get_terminal_size(sys.stdout.fileno())
        return sz.columns, sz.lines
    except OSError:
        return 80, 24


def clear_screen() -> None:
    wf(CSI + "2J" + CSI + "H")


def move(row: int, col: int) -> None:
    w(f"{CSI}{row};{col}H")


def hide_cursor() -> None:
    wf(CSI + "?25l")


def show_cursor() -> None:
    wf(CSI + "?25h")


def reset() -> str:
    return CSI + "0m"


def sgr(*codes: int | str) -> str:
    return CSI + ";".join(str(c) for c in codes) + "m"


def rgb_fg(r: int, g: int, b: int) -> str:
    return f"{CSI}38;2;{r};{g};{b}m"


def rgb_bg(r: int, g: int, b: int) -> str:
    return f"{CSI}48;2;{r};{g};{b}m"


def fit_text(s: str, width: int) -> str:
    """Clip/pad mostly-ASCII dashboard labels to a fixed cell width."""
    if width <= 0:
        return ""
    if len(s) > width:
        return s[:max(width - 1, 0)] + "…"
    return s + " " * (width - len(s))


def palette_bg(idx: int) -> str:
    return f"{CSI}48;5;{idx}m"


def palette_fg(idx: int) -> str:
    return f"{CSI}38;5;{idx}m"


def osc8(uri: str, label: str, style: str = "") -> str:
    return f"{OSC}8;;{uri}{BEL}{style}{label}{reset()}{OSC}8;;{BEL}"


def underline(kind: int, color_rgb: Optional[tuple[int, int, int]] = None) -> str:
    """kind: 0=off 1=single 2=double 3=curly 4=dotted 5=dashed."""
    parts = [f"4:{kind}"]
    if color_rgb is not None:
        r, g, b = color_rgb
        parts.append(f"58:2::{r}:{g}:{b}")
    return CSI + ";".join(parts) + "m"


@contextmanager
def raw_stdin():
    """Put stdin into cbreak mode so DSR/CPR responses can be read."""
    fd = sys.stdin.fileno()
    if not os.isatty(fd):
        yield None
        return
    old = termios.tcgetattr(fd)
    try:
        tty.setcbreak(fd, termios.TCSADRAIN)
        yield fd
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)


def read_pty(fd: int, terminator: bytes, timeout: float) -> bytes:
    """Read from fd until we see `terminator` or `timeout` elapses."""
    buf = b""
    deadline = time.perf_counter() + timeout
    while True:
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            return buf
        r, _, _ = select.select([fd], [], [], remaining)
        if not r:
            return buf
        try:
            chunk = os.read(fd, 256)
        except OSError:
            return buf
        if not chunk:
            return buf
        buf += chunk
        if terminator in buf:
            return buf


@contextmanager
def alt_screen():
    wf(CSI + "?1049h")
    hide_cursor()
    try:
        yield
    finally:
        show_cursor()
        wf(CSI + "?1049l")


# ─────────────────────────────────────────────────────────────────────────────
# 数据结构
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Result:
    name: str
    ok: bool = True
    elapsed_s: float = 0.0
    metrics: dict = field(default_factory=dict)
    note: str = ""


TESTS: dict[str, Callable[["Bench"], Result]] = {}


def register(name: str):
    def deco(fn):
        TESTS[name] = fn
        fn.__test_name__ = name
        return fn
    return deco


# ─────────────────────────────────────────────────────────────────────────────
# Bench 主类
# ─────────────────────────────────────────────────────────────────────────────

class Bench:
    def __init__(self, args):
        self.args = args
        self.cols, self.rows = term_size()
        self.results: list[Result] = []
        try:
            signal.signal(signal.SIGWINCH, lambda *_: self.refresh_size())
        except (ValueError, OSError):
            pass

    def refresh_size(self) -> tuple[int, int]:
        """Re-read TIOCGWINSZ. Call before each test so IME / rotation /
        toolbar toggles that change viewport mid-run get picked up."""
        try:
            sz = os.get_terminal_size(sys.stdout.fileno())
            self.cols, self.rows = sz.columns, sz.lines
        except OSError:
            pass
        return self.cols, self.rows

    # ── helpers ─────────────────────────────────────────────────────────────

    def header(self, title: str):
        bar = "═" * max(self.cols - 4, 10)
        wf(f"\n{sgr(1, '38;2;120;180;255')}╔ {title} {bar[len(title) + 2:]}╗{reset()}\n")

    def note(self, s: str):
        wf(f"{rgb_fg(150, 150, 170)}{s}{reset()}\n")

    def good(self, s: str):
        wf(f"{rgb_fg(120, 220, 140)}✓ {s}{reset()}\n")

    def bad(self, s: str):
        wf(f"{rgb_fg(255, 110, 110)}✗ {s}{reset()}\n")

    # ── public ──────────────────────────────────────────────────────────────

    def run_one(self, name: str) -> Result:
        fn = TESTS.get(name)
        if fn is None:
            r = Result(name=name, ok=False, note="unknown test")
            self.results.append(r)
            return r
        self.refresh_size()
        t0 = time.perf_counter()
        try:
            r = fn(self)
        except KeyboardInterrupt:
            r = Result(name=name, ok=False, note="interrupted")
        except Exception as e:
            r = Result(name=name, ok=False, note=f"{type(e).__name__}: {e}")
        r.elapsed_s = time.perf_counter() - t0
        self.results.append(r)
        return r

    def run_all(self, exclude: set[str] = frozenset()):
        for name in TESTS:
            if name in exclude:
                continue
            self.run_one(name)

    def report(self) -> dict:
        return {
            "terminal": {"cols": self.cols, "rows": self.rows},
            "results": [asdict(r) for r in self.results],
        }


# ─────────────────────────────────────────────────────────────────────────────
# 协议测试：truecolor / palette / styles / underlines / wide / osc8
# ─────────────────────────────────────────────────────────────────────────────

@register("truecolor")
def t_truecolor(b: Bench) -> Result:
    b.header("24-bit TrueColor")
    width = max(b.cols - 2, 16)
    # 4 行 HSV 渐变（V=1/0.85/0.7/0.55，覆盖不同亮度）
    for v in (1.0, 0.85, 0.7, 0.55):
        for x in range(width):
            h = x / width
            r, g, b_ = colorsys.hsv_to_rgb(h, 0.95, v)
            w(rgb_bg(int(r * 255), int(g * 255), int(b_ * 255)) + " ")
        w(reset() + "\n")
    # 灰阶
    for x in range(width):
        v = int(x / max(width - 1, 1) * 255)
        w(rgb_bg(v, v, v) + " ")
    w(reset() + "\n")
    flush()
    return Result(name="truecolor",
                  metrics={"cells_emitted": width * 5, "rows": 5})


@register("palette")
def t_palette(b: Bench) -> Result:
    b.header("256-color palette")
    per_row = max(8, min(64, b.cols - 4))
    cells = 0
    for i in range(256):
        w(palette_bg(i) + " ")
        cells += 1
        if (i + 1) % per_row == 0:
            w(reset() + "\n")
    if cells % per_row:
        w(reset() + "\n")
    flush()
    return Result(name="palette",
                  metrics={"cells_emitted": 256, "per_row": per_row})


@register("styles")
def t_styles(b: Bench) -> Result:
    b.header("SGR text styles")
    items = [
        (sgr(1) + "Bold" + reset(), "1"),
        (sgr(3) + "Italic" + reset(), "3"),
        (sgr(2) + "Dim" + reset(), "2"),
        (sgr(4) + "Under" + reset(), "4"),
        (sgr(9) + "Strike" + reset(), "9"),
        (sgr(7) + "Inverse" + reset(), "7"),
        (sgr(5) + "Blink" + reset(), "5"),
        (sgr(1, 3, 4) + "B+I+U" + reset(), "1;3;4"),
        (sgr(1, 3, 4, 7) + "All" + reset(), "1;3;4;7"),
    ]
    line = "  ".join(s for s, _ in items)
    wf(line + "\n")
    # 颜色 + 样式组合
    combo = []
    for fg in (1, 2, 3, 4, 5, 6, 7):
        combo.append(palette_fg(fg) + sgr(1) + "■" + reset())
    wf("fg ramp bold: " + " ".join(combo) + "\n")
    return Result(name="styles", metrics={"variants": len(items)})


@register("underlines")
def t_underlines(b: Bench) -> Result:
    b.header("SGR 4:N exotic underlines + 58 colored underline")
    samples = [
        (1, None, "single"),
        (1, (255, 80, 80), "single-red"),
        (2, (255, 200, 80), "double-amber"),
        (3, (140, 220, 255), "curly-cyan"),
        (4, (180, 255, 140), "dotted-lime"),
        (5, (255, 140, 220), "dashed-pink"),
    ]
    for kind, color, label in samples:
        w(underline(kind, color) + label + reset() + "  ")
    w("\n")
    flush()
    return Result(name="underlines", metrics={"variants": len(samples)})


@register("wide")
def t_wide(b: Bench) -> Result:
    b.header("Wide-cell / CJK / emoji / box drawing")
    lines = [
        "ASCII baseline  0123456789",
        "CJK 中文       你好，世界！终端 GPU 测试。",
        "Hiragana       こんにちは、世界！",
        "Hangul         안녕하세요, 세계!",
        "Emoji          🚀 🎨 🐧 ☕ ⭐ ✅ ❌ 🌍",
        "Box drawing    ┌──┬──┐  │a │b │  ├──┼──┤  └──┴──┘",
        "Arrows / sym   ↑ ← → ↓ ◆ ● ▲ ▼ ★ ✦ ✩ ∑ ∫ √ π ∞ ≠ ≈",
    ]
    for line in lines:
        wf(line + "\n")
    return Result(name="wide", metrics={"lines": len(lines)})


@register("osc8")
def t_osc8(b: Bench) -> Result:
    b.header("OSC 8 hyperlinks")
    links = [
        ("https://ghostty.org", "ghostty.org", "4;38;2;120;180;255"),
        ("https://github.com/termux", "github/termux", "4;38;2;180;255;180"),
        ("https://example.org", "example.org", "4;38;2;255;200;120"),
        ("mailto:test@example.org", "mailto:test", "4;38;2;255;160;220"),
    ]
    parts = []
    for uri, label, sty in links:
        parts.append(osc8(uri, label, CSI + sty + "m"))
    wf("  ".join(parts) + "\n")
    return Result(name="osc8", metrics={"links": len(links)})


# ─────────────────────────────────────────────────────────────────────────────
# Kitty 图形协议（PIL 可选；无 PIL 时退化成纯 base64-PNG inline）
# ─────────────────────────────────────────────────────────────────────────────

_BUNDLED_PNG_B64 = (
    # 64×40 RGBA PNG with a gradient + colored dots (precomputed offline).
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAoCAIAAAB99ePgAAAB6klEQVR4nO3Wv0pCQRTH8d97D"
    "fIPpJZQ+QO0aQuCNiGoLagXqLapJyhoCJoaegQfwLJBSCgoSgwUE0VRMfHHvfeebnf3nrkz"
    "Lyb0e5jOzGGGc34zZ45zPgT//YIvg7eD9wMu8GMRn1Eo9TgKHvDQg3rFCY30HuVcZJEAFTU"
    "x5xkVNBKlIxRyo/yFhPx5wpV5xR/8H/EsAQuMASoFnLNJOcVdGImCnB7gO5XGNwY8q5gJxV"
    "WhJzixOQE2QXBONeBxhDOiYRkRZeOmuVKMc5XdRzgYNcEPRTBWeENMrtmEqsxX5Y4aTbnT8"
    "f8WG3wWO+yJOZ6m3kFZ9ApY8KsfCOC8DRSDcAH3QC5kCJXLrFy1lYRZ7nb0Fy/8x5lc+5/y"
    "lZqQUmQHFvP3O7CcrXp3vP5wTmRTGN/wm9PdvKzdJxKJZyNcSf2eY//WBwIIgkAxRywZ8vd"
    "DPlnxgHKMCEZHO5LQgY6n38pwSTBZJ8MOpUHELi1JJJwBcLnz0iIvPDOQ+yhvqsBQO0AZ5"
    "lh/J7ZAaG/8YBC1MqGZK+1xQLEnvNYNzh9SoIQZsmK9aN/+S5HmYLuKxnzLpZIKKBgpAhh"
    "MUv1mlNDvkOY3VFkGgPmgxgAAAABJRU5ErkJggg=="
).replace("\n", "")


def _make_or_load_png_bytes() -> bytes:
    """Return PNG bytes. Prefer PIL-generated, fall back to bundled."""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        import base64 as _b64
        return _b64.b64decode(_BUNDLED_PNG_B64)
    W, H = 200, 60
    img = Image.new("RGBA", (W, H), (10, 12, 18, 255))
    d = ImageDraw.Draw(img)
    for x in range(W):
        r = int(255 * x / W)
        g = int(140 + 80 * math.sin(x * math.pi / 40))
        b_ = int(255 * (W - x) / W)
        d.line([(x, 0), (x, 8)], fill=(r, g, b_, 255))
    d.rectangle([(0, 12), (W, 42)], fill=(40, 70, 120, 255))
    try:
        from PIL import ImageFont
        font = ImageFont.truetype(
            "/system/fonts/Roboto-Bold.ttf", 18)
    except (ImportError, OSError):
        from PIL import ImageFont
        font = ImageFont.load_default()
    d.text((6, 14), "Ghostty / Termux", fill=(255, 255, 255, 255), font=font)
    for i, c in enumerate([(220, 80, 80), (80, 200, 120),
                           (80, 140, 255), (240, 180, 40)]):
        d.ellipse([(8 + i * 45, 46), (38 + i * 45, 58)], fill=c + (255,))
    import io
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def kitty_emit(png_bytes: bytes) -> None:
    """Emit Kitty graphics escape, chunked per spec (<=4096 b64 chars/chunk)."""
    import base64 as _b64
    b64 = _b64.b64encode(png_bytes).decode("ascii")
    CHUNK = 4096
    chunks = [b64[i:i + CHUNK] for i in range(0, len(b64), CHUNK)]
    for i, ch in enumerate(chunks):
        m = 0 if i == len(chunks) - 1 else 1
        if i == 0:
            w(f"{APC}Ga=T,f=100,m={m};{ch}{ST}")
        else:
            w(f"{APC}Gm={m};{ch}{ST}")
    flush()


@register("kitty")
def t_kitty(b: Bench) -> Result:
    b.header("Kitty graphics: PNG → AImageDecoder → GL texture")
    png = _make_or_load_png_bytes()
    t0 = time.perf_counter()
    kitty_emit(png)
    elapsed = time.perf_counter() - t0
    wf("\n")
    return Result(name="kitty",
                  metrics={"png_bytes": len(png),
                           "emit_ms": round(elapsed * 1000, 2)})


# ─────────────────────────────────────────────────────────────────────────────
# 性能测试：fps_static / fps_scroll / throughput / stress_rgba / stress_glyph
# ─────────────────────────────────────────────────────────────────────────────

@register("fps_static")
def t_fps_static(b: Bench) -> Result:
    """固定位置每帧只刷一个 cell。测 dirty-row 触发到屏的 throughput 上限。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    clear_screen()
    b.header("fps_static (counter at fixed position)")
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    while time.perf_counter() < deadline:
        w(f"{CSI}3;1H" + sgr(1, "38;2;120;220;160") +
          f"frame {frames:09d}" + reset())
        flush()
        frames += 1
    elapsed = time.perf_counter() - t0
    fps = frames / elapsed
    move(5, 1)
    b.note(f"frames={frames}  elapsed={elapsed:.2f}s  fps={fps:.1f}")
    return Result(name="fps_static",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(fps, 1)})


@register("fps_scroll")
def t_fps_scroll(b: Bench) -> Result:
    """连续 print 行，测可持续滚屏率（行/秒）。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    clear_screen()
    b.header("fps_scroll (continuous newlines)")
    rows = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    # 提前生成一条彩色行，避开 hotpath 里 colorsys 计算
    line = (rgb_fg(120, 200, 255) + "scroll-row "
            + rgb_fg(200, 200, 200) + "0123456789 ABCDEF "
            + rgb_bg(40, 80, 60) + " green-band " + reset())
    while time.perf_counter() < deadline:
        w(f"{line} #{rows}\n")
        if rows % 64 == 0:
            flush()
        rows += 1
    flush()
    elapsed = time.perf_counter() - t0
    rps = rows / elapsed
    b.note(f"rows={rows}  elapsed={elapsed:.2f}s  rows/s={rps:.1f}")
    return Result(name="fps_scroll",
                  metrics={"rows": rows, "elapsed_s": elapsed,
                           "rows_per_s": round(rps, 1)})


@register("throughput")
def t_throughput(b: Bench) -> Result:
    """字节吞吐：一次 dump 已知大小的 ASCII，测时间。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    clear_screen()
    b.header("throughput (ascii bytes/s)")
    block = ("the quick brown fox jumps over the lazy dog 0123456789\n" * 64)
    bblock = block.encode("ascii")
    bytes_sent = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    fd = sys.stdout.buffer
    while time.perf_counter() < deadline:
        fd.write(bblock)
        bytes_sent += len(bblock)
    fd.flush()
    elapsed = time.perf_counter() - t0
    mbps = bytes_sent / elapsed / (1024 * 1024)
    b.note(f"\nbytes={bytes_sent}  elapsed={elapsed:.2f}s  "
           f"throughput={mbps:.2f} MiB/s")
    return Result(name="throughput",
                  metrics={"bytes": bytes_sent, "elapsed_s": elapsed,
                           "mib_per_s": round(mbps, 2)})


# ─────────────────────────────────────────────────────────────────────────────
# 视觉花哨：disco / plasma / matrix / fireworks / rainbow / gradient_2d
# 全部尊重当前 b.cols / b.rows（run_one 之前已 refresh_size）。
# ─────────────────────────────────────────────────────────────────────────────

@register("disco")
def t_disco(b: Bench) -> Result:
    """全屏 HSV 色相环旋转 — 视觉冲击 + 大面积 24-bit 重绘。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    palette_n = 96
    bg_table = []
    for i in range(palette_n):
        h = i / palette_n
        rr, gg, bb = colorsys.hsv_to_rgb(h, 0.95, 1.0)
        bg_table.append(
            f"\033[48;2;{int(rr*255)};{int(gg*255)};{int(bb*255)};"
            f"38;2;15;15;25m")
    chars = "█▓▒░◆●▲▼★✦◢◣◤◥"
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        while time.perf_counter() < deadline:
            parts = ["\033[H"]
            for y in range(rows):
                row = []
                base = frames * 3 + y * 5
                for x in range(cols):
                    row.append(bg_table[(x + base) % palette_n])
                    row.append(chars[(x ^ y ^ frames) % len(chars)])
                row.append("\033[0m\n")
                parts.append("".join(row))
            wf("".join(parts))
            frames += 1
    finally:
        show_cursor()
    elapsed = time.perf_counter() - t0
    cells_per_s = frames * cols * rows / elapsed
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"cells/s={cells_per_s:.0f}")
    return Result(name="disco",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "cells_per_s": int(cells_per_s),
                           "cols": cols, "rows": rows})


@register("plasma")
def t_plasma(b: Bench) -> Result:
    """2D 正弦 plasma —— 平滑色场 + 浮点驱动渲染流。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    n = 256
    sin_t = [math.sin(2 * math.pi * i / n) for i in range(n)]
    cos_t = [math.cos(2 * math.pi * i / n) for i in range(n)]
    palette_n = 128
    sgr_table = []
    for i in range(palette_n):
        h = i / palette_n
        rr, gg, bb = colorsys.hsv_to_rgb(h, 0.85, 1.0)
        sgr_table.append(
            f"\033[48;2;{int(rr*255)};{int(gg*255)};{int(bb*255)}m ")
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        while time.perf_counter() < deadline:
            parts = ["\033[H"]
            t = frames * 4
            for y in range(rows):
                row = []
                ys = sin_t[(y * 16 + t) % n]
                yc = cos_t[(y * 12 + t * 2) % n]
                for x in range(cols):
                    xs = sin_t[(x * 8 + t) % n]
                    xc = cos_t[(x * 6 + y * 5 + t) % n]
                    v = (xs + ys + xc + yc) / 4.0
                    idx = int((v + 1) * 0.5 * (palette_n - 1))
                    row.append(sgr_table[idx])
                row.append("\033[0m\n")
                parts.append("".join(row))
            wf("".join(parts))
            frames += 1
    finally:
        show_cursor()
    elapsed = time.perf_counter() - t0
    cells_per_s = frames * cols * rows / elapsed
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"cells/s={cells_per_s:.0f}")
    return Result(name="plasma",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "cells_per_s": int(cells_per_s)})


@register("matrix")
def t_matrix(b: Bench) -> Result:
    """Matrix 风格绿色字符雨 —— 局部 dirty cell 高频刷新。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    rng = random.Random(42)
    glyphs = "ｦｱｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾅﾆﾇﾈﾉﾊﾋﾌﾍ01:#&%@$"
    heads = [rng.randint(-rows, 0) for _ in range(cols)]
    speeds = [rng.randint(1, 3) for _ in range(cols)]
    trail_len = max(rows // 2, 6)
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        wf("\033[48;2;0;0;0m\033[H"
           + ("".join([" " * cols + "\n" for _ in range(rows)]))
           + "\033[0m")
        while time.perf_counter() < deadline:
            parts = []
            step = max(1, 180 // trail_len)
            for x in range(cols):
                hr = heads[x]
                if 1 <= hr <= rows:
                    parts.append(
                        f"\033[{hr};{x+1}H\033[1;38;2;220;255;220m"
                        f"{rng.choice(glyphs)}\033[0m")
                for k in range(1, trail_len):
                    tr = hr - k
                    if 1 <= tr <= rows:
                        fade = max(30, 200 - k * step)
                        parts.append(
                            f"\033[{tr};{x+1}H\033[38;2;0;{fade};0m"
                            f"{rng.choice(glyphs)}\033[0m")
                er = hr - trail_len
                if 1 <= er <= rows:
                    parts.append(f"\033[{er};{x+1}H \033[0m")
                heads[x] = hr + speeds[x]
                if heads[x] - trail_len > rows:
                    heads[x] = rng.randint(-rows, 0)
                    speeds[x] = rng.randint(1, 3)
            wf("".join(parts))
            frames += 1
    finally:
        show_cursor()
        wf("\033[0m")
    elapsed = time.perf_counter() - t0
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  trail={trail_len}")
    return Result(name="matrix",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "cols": cols, "rows": rows,
                           "trail_len": trail_len})


@register("fireworks")
def t_fireworks(b: Bench) -> Result:
    """24-bit 粒子烟花 —— 稀疏移动 cell + 全屏增量重绘。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    rng = random.Random(7)
    bursts: list[list] = []
    glyphs = "*+•·◦✦✧✩"
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        while time.perf_counter() < deadline:
            if len(bursts) < 10 and rng.random() < 0.35:
                bursts.append([rng.randint(3, max(cols - 3, 4)),
                               rng.randint(3, max(rows - 3, 4)),
                               0, rng.randint(8, 16),
                               rng.random()])
            parts = ["\033[2J\033[H"]
            new_bursts = []
            for fw in bursts:
                cx, cy, age, max_age, hue = fw
                if age >= max_age:
                    continue
                r_rad = age + 1
                bright = 1.0 - age / (max_age * 1.4)
                rr, gg, bb = colorsys.hsv_to_rgb(hue, 0.9, max(0.25, bright))
                color = (f"\033[38;2;{int(rr*255)};"
                         f"{int(gg*255)};{int(bb*255)}m")
                for k in range(20):
                    ang = 2 * math.pi * k / 20
                    px = int(cx + math.cos(ang) * r_rad * 1.6)
                    py = int(cy + math.sin(ang) * r_rad * 0.8)
                    if 1 <= px <= cols and 1 <= py <= rows:
                        parts.append(
                            f"\033[{py};{px}H{color}"
                            f"{glyphs[age % len(glyphs)]}\033[0m")
                fw[2] += 1
                new_bursts.append(fw)
            bursts = new_bursts
            wf("".join(parts))
            frames += 1
            time.sleep(0.04)
    finally:
        show_cursor()
    elapsed = time.perf_counter() - t0
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"bursts_alive={len(bursts)}")
    return Result(name="fireworks",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1)})


@register("rainbow")
def t_rainbow(b: Bench) -> Result:
    """彩虹文本一次性铺满：fg 24-bit + bold/italic/underline/strike/inverse 叠加。"""
    b.refresh_size()
    clear_screen()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    line = ("the quick brown fox jumps over the lazy dog · "
            "ghostty render bench · 终端 GPU 加速渲染 · ")
    line = (line * ((cols // max(len(line), 1)) + 2))[:cols]
    overlays = ["\033[1m", "\033[3m", "\033[4m", "\033[9m",
                "\033[1;3m", "\033[7m", "\033[1;4m", "\033[3;4m"]
    palette_n = 360
    fg_table = []
    for i in range(palette_n):
        h = i / palette_n
        rr, gg, bb = colorsys.hsv_to_rgb(h, 0.9, 1.0)
        fg_table.append(
            f"\033[38;2;{int(rr*255)};{int(gg*255)};{int(bb*255)}m")
    t0 = time.perf_counter()
    for y in range(rows):
        parts = []
        ov = overlays[y % len(overlays)]
        for x in range(cols):
            ch = line[x]
            phase = (x * 3 + y * 7) % palette_n
            if (x + y) % 11 == 0:
                parts.append(ov + fg_table[phase] + ch + "\033[0m")
            else:
                parts.append(fg_table[phase] + ch)
        parts.append("\033[0m\n")
        wf("".join(parts))
    elapsed = time.perf_counter() - t0
    b.note(f"rows={rows}  cols={cols}  elapsed_ms={elapsed*1000:.1f}")
    return Result(name="rainbow",
                  metrics={"rows": rows, "cols": cols,
                           "elapsed_ms": round(elapsed * 1000, 2)})


@register("gradient_2d")
def t_gradient_2d(b: Bench) -> Result:
    """整屏 2D HSV gradient 静态铺满 —— 截图友好，验证全屏 24-bit。"""
    b.refresh_size()
    clear_screen()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    t0 = time.perf_counter()
    parts = []
    for y in range(rows):
        for x in range(cols):
            h = (x / max(cols - 1, 1) + y / max(rows - 1, 1)) * 0.5
            rr, gg, bb = colorsys.hsv_to_rgb(h, 0.85, 1.0)
            parts.append(
                f"\033[48;2;{int(rr*255)};{int(gg*255)};{int(bb*255)}m ")
        parts.append("\033[0m\n")
    wf("".join(parts))
    elapsed = time.perf_counter() - t0
    b.note(f"cells={cols*rows}  elapsed_ms={elapsed*1000:.1f}")
    return Result(name="gradient_2d",
                  metrics={"cols": cols, "rows": rows,
                           "elapsed_ms": round(elapsed * 1000, 2)})


@register("neon_tunnel")
def t_neon_tunnel(b: Bench) -> Result:
    """霓虹透视隧道 —— 全屏 24-bit + 几何动画 + 高频整屏重绘。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    aspect = cols / max(rows * 2.0, 1.0)
    coords = []
    for y in range(rows):
        ny = (y - rows / 2.0) / max(rows / 2.0, 1.0)
        for x in range(cols):
            nx = ((x - cols / 2.0) / max(cols / 2.0, 1.0)) * aspect
            dist = math.sqrt(nx * nx + ny * ny) + 0.03
            ang = math.atan2(ny, nx)
            coords.append((18.0 / dist, ang * 6.0, ang * 10.0))
    glyphs = " .·:-=+*#%@"
    palette_n = 192
    bg_table = []
    for i in range(palette_n):
        h = (0.58 + i / palette_n * 0.45) % 1.0
        rr, gg, bb = colorsys.hsv_to_rgb(h, 0.92, 0.92)
        bg_table.append(
            f"\033[48;2;{int(rr*80)};{int(gg*80)};{int(bb*80)};"
            f"38;2;{int(rr*255)};{int(gg*255)};{int(bb*255)}m")
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        while time.perf_counter() < deadline:
            t = frames * 0.14
            parts = ["\033[H"]
            idx = 0
            for y in range(rows):
                row = []
                for x in range(cols):
                    tunnel, wave_a, wave_b = coords[idx]
                    idx += 1
                    ring = math.sin(tunnel - t * 9.0)
                    twist = math.sin(wave_a + t * 2.3) * 0.35
                    stripe = math.sin(wave_b - t * 4.0) * 0.25
                    level = max(0.0, min(1.0, 0.5 + ring * 0.45
                                          + twist + stripe))
                    color_idx = int((tunnel * 3.0 + frames * 5 + y * 2)
                                    % palette_n)
                    char_idx = min(len(glyphs) - 1,
                                   int(level * (len(glyphs) - 1)))
                    row.append(bg_table[color_idx] + glyphs[char_idx])
                row.append("\033[0m\n")
                parts.append("".join(row))
            wf("".join(parts))
            frames += 1
    finally:
        show_cursor()
        wf("\033[0m")
    elapsed = time.perf_counter() - t0
    cells_per_s = frames * cols * rows / elapsed
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"cells/s={cells_per_s:.0f}")
    return Result(name="neon_tunnel",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "cells_per_s": int(cells_per_s),
                           "cols": cols, "rows": rows})


@register("braille_particles")
def t_braille_particles(b: Bench) -> Result:
    """Braille 子像素粒子场 —— Unicode glyph atlas + 密集动态 bitmask。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    subcols = cols * 2
    subrows = rows * 4
    rng = random.Random(1234)
    count = min(max(cols * rows // 2, 260), 1800)
    particles = []
    for i in range(count):
        angle = rng.random() * math.tau
        speed = 0.45 + rng.random() * 1.75
        particles.append([
            rng.random() * max(subcols - 1, 1),
            rng.random() * max(subrows - 1, 1),
            math.cos(angle) * speed,
            math.sin(angle) * speed,
            rng.random(),
        ])
    dot_bits = [0x01, 0x08, 0x02, 0x10, 0x04, 0x20, 0x40, 0x80]
    fg_table = []
    for i in range(160):
        rr, gg, bb = colorsys.hsv_to_rgb(i / 160.0, 0.82, 1.0)
        fg_table.append(
            f"\033[38;2;{int(rr*255)};{int(gg*255)};{int(bb*255)}m")
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        while time.perf_counter() < deadline:
            grid = [0] * (cols * rows)
            hues = [0] * (cols * rows)
            for p in particles:
                p[0] += p[2]
                p[1] += p[3]
                if p[0] < 0 or p[0] >= subcols:
                    p[2] *= -1
                    p[0] = max(0, min(subcols - 1, p[0]))
                if p[1] < 0 or p[1] >= subrows:
                    p[3] *= -1
                    p[1] = max(0, min(subrows - 1, p[1]))
                sx = int(p[0])
                sy = int(p[1])
                cx = sx // 2
                cy = sy // 4
                cell = cy * cols + cx
                grid[cell] |= dot_bits[(sy % 4) * 2 + (sx % 2)]
                hues[cell] = int((p[4] * 160 + frames * 2) % 160)
            parts = ["\033[H"]
            idx = 0
            for _ in range(rows):
                row = []
                for _ in range(cols):
                    bits = grid[idx]
                    if bits:
                        row.append(fg_table[hues[idx]] + chr(0x2800 + bits))
                    else:
                        row.append(" ")
                    idx += 1
                row.append("\033[0m\n")
                parts.append("".join(row))
            wf("".join(parts))
            frames += 1
    finally:
        show_cursor()
        wf("\033[0m")
    elapsed = time.perf_counter() - t0
    cells_per_s = frames * cols * rows / elapsed
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"particles={count}  cells/s={cells_per_s:.0f}")
    return Result(name="braille_particles",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "particles": count,
                           "cells_per_s": int(cells_per_s)})


@register("emoji_storm")
def t_emoji_storm(b: Bench) -> Result:
    """Emoji/ZWJ/组合字符风暴 —— font fallback、宽字符和复杂 glyph 压测。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    tokens = [
        "🚀", "🎨", "🔥", "⚡", "✨", "🌈", "🧪", "🧠", "🛠️", "📱",
        "👩‍💻", "👨‍🔬", "🧑🏽‍🚀", "🏳️‍🌈", "🇨🇳", "🇺🇸", "👨‍👩‍👧‍👦",
        "a\u0301", "e\u0308", "n\u0303", "Z\u0351", "क", "क्", "क्ष",
        "中文", "かな", "한글", "🟩", "🟦", "🟪", "🟥", "◌̸", "◌͜",
    ]
    fg_table = []
    for i in range(96):
        rr, gg, bb = colorsys.hsv_to_rgb(i / 96.0, 0.82, 1.0)
        fg_table.append(
            f"\033[38;2;{int(rr*255)};{int(gg*255)};{int(bb*255)}m")
    frames = 0
    token_count = 0
    rng = random.Random(2026)
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        while time.perf_counter() < deadline:
            parts = ["\033[H"]
            for y in range(rows):
                row = []
                # Complex emoji do not map cleanly to len(); intentionally
                # overfill each row so wrapping/fallback paths are exercised.
                for x in range(max(cols // 2, 18)):
                    token = tokens[(x * 5 + y * 7 + frames * 3
                                    + rng.randrange(len(tokens)))
                                   % len(tokens)]
                    row.append(fg_table[(x + y * 3 + frames) % len(fg_table)])
                    row.append(token)
                    if (x + frames) % 5 == 0:
                        row.append("\033[1m")
                    if (x + y) % 7 == 0:
                        row.append("\033[4:3m")
                    token_count += 1
                row.append("\033[0m\n")
                parts.append("".join(row))
            wf("".join(parts))
            frames += 1
    finally:
        show_cursor()
        wf("\033[0m")
    elapsed = time.perf_counter() - t0
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"tokens/s={token_count/elapsed:.0f}")
    return Result(name="emoji_storm",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "tokens": token_count,
                           "tokens_per_s": int(token_count / elapsed)})


@register("dashboard")
def t_dashboard(b: Bench) -> Result:
    """动态 TUI 仪表盘 —— 混合 box drawing、sparklines、日志和进度条。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    b.refresh_size()
    clear_screen()
    hide_cursor()
    cols = b.cols
    rows = max(b.rows - 2, 10)
    spark = "▁▂▃▄▅▆▇█"
    logs = [
        "decode: APC kitty image chunk accepted",
        "renderer: atlas page recycled without fallback",
        "damage: merged 13 dirty rows into 3 rectangles",
        "input: CPR latency sample queued",
        "gpu: texture upload batched with glyph quads",
        "term: wide cell pair resolved after resize",
        "style: colored underline + italic span cached",
    ]
    sections = ["parser", "shape", "atlas", "blend", "upload", "swap"]
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    try:
        while time.perf_counter() < deadline:
            parts = ["\033[H"]
            line_count = 0

            def emit(line: str = "") -> None:
                nonlocal line_count
                parts.append(line + "\033[0m\n")
                line_count += 1

            title = (f" Ghostty Render Ops  frame={frames:05d}  "
                     f"viewport={cols}x{rows} ")
            emit(sgr(1, "48;2;20;26;42", "38;2;235;245;255")
                 + fit_text(title, cols))

            heat_w = max(cols - 2, 1)
            heat = [" "]
            for x in range(heat_w):
                h = (x / max(heat_w, 1) + frames * 0.013) % 1.0
                rr, gg, bb = colorsys.hsv_to_rgb(h, 0.88, 0.95)
                heat.append(rgb_bg(int(rr * 255), int(gg * 255),
                                   int(bb * 255)) + " ")
            emit("".join(heat))

            chart_w = max(cols - 24, 10)
            points = []
            for i in range(chart_w):
                v = (math.sin((i + frames) * 0.24)
                     + math.sin((i * 0.37) - frames * 0.13)) * 0.5
                points.append(spark[int((v + 1.0) * 0.5
                                        * (len(spark) - 1))])
            emit(rgb_fg(130, 210, 255) + "frame-time "
                 + rgb_fg(225, 245, 255) + "".join(points))

            bar_w = max(cols - 22, 8)
            for i, name in enumerate(sections):
                phase = frames * 0.18 + i * 0.9
                ratio = 0.5 + math.sin(phase) * 0.36
                fill = max(0, min(bar_w, int(ratio * bar_w)))
                ms = 0.3 + ratio * (7.0 + i * 0.4)
                bar = (rgb_fg(90, 230, 150) + "█" * fill
                       + rgb_fg(45, 65, 85) + "░" * (bar_w - fill))
                emit(rgb_fg(170, 180, 210) + f"{name:<7} "
                     + bar + rgb_fg(235, 210, 150) + f" {ms:5.2f}ms")

            log_rows = max(1, rows - line_count - 2)
            emit(rgb_fg(120, 130, 160) + "─ logs " + "─" * max(cols - 8, 0))
            for i in range(log_rows):
                msg = logs[(frames + i) % len(logs)]
                sev = ["INFO", "WARN", "GPU ", "VT  "][(frames + i) % 4]
                hue = (frames * 3 + i * 19) % 96
                rr, gg, bb = colorsys.hsv_to_rgb(hue / 96.0, 0.6, 1.0)
                prefix = f"{frames+i:05d} {sev} "
                emit(rgb_fg(int(rr * 255), int(gg * 255), int(bb * 255))
                     + fit_text(prefix + msg, cols))

            while line_count < rows:
                emit(" " * cols)
            wf("".join(parts))
            frames += 1
    finally:
        show_cursor()
        wf("\033[0m")
    elapsed = time.perf_counter() - t0
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}")
    return Result(name="dashboard",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "cols": cols, "rows": rows})


@register("stress_rgba")
def t_stress_rgba(b: Bench) -> Result:
    """每帧整屏背景换 RGB —— GPU 大面积重绘压测。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    clear_screen()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    blank_row = " " * cols
    frames = 0
    t0 = time.perf_counter()
    deadline = t0 + duration
    while time.perf_counter() < deadline:
        r = (frames * 7) % 256
        g = (frames * 13) % 256
        bl = (frames * 19) % 256
        prefix = rgb_bg(r, g, bl)
        out = [CSI + "H"]
        for _ in range(rows):
            out.append(prefix + blank_row + "\n")
        out.append(reset())
        sys.stdout.write("".join(out))
        flush()
        frames += 1
    elapsed = time.perf_counter() - t0
    cells_per_s = frames * cols * rows / elapsed
    move(rows + 1, 1)
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"cells/s={cells_per_s:.0f}")
    return Result(name="stress_rgba",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "cells_per_s": int(cells_per_s)})


@register("stress_glyph")
def t_stress_glyph(b: Bench) -> Result:
    """每帧整屏字符随机化 —— glyph atlas 命中率压测。"""
    duration = float(getattr(b.args, "duration", 3) or 3)
    clear_screen()
    cols = b.cols
    rows = max(b.rows - 2, 8)
    # 用 ASCII 可打印字符循环，atlas 命中率高；后段加入 Unicode 块字符压力更大
    pool = list("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
    pool_box = ["█", "▓", "▒", "░", "▌", "▐", "▄", "▀"]
    frames = 0
    rng = random.Random(0)
    t0 = time.perf_counter()
    deadline = t0 + duration
    while time.perf_counter() < deadline:
        out = [CSI + "H"]
        for _ in range(rows):
            row_chars = []
            for _ in range(cols):
                if rng.random() < 0.15:
                    row_chars.append(rng.choice(pool_box))
                else:
                    row_chars.append(rng.choice(pool))
            out.append("".join(row_chars) + "\n")
        sys.stdout.write("".join(out))
        flush()
        frames += 1
    elapsed = time.perf_counter() - t0
    cells_per_s = frames * cols * rows / elapsed
    b.note(f"\nframes={frames}  fps={frames/elapsed:.1f}  "
           f"cells/s={cells_per_s:.0f}")
    return Result(name="stress_glyph",
                  metrics={"frames": frames, "elapsed_s": elapsed,
                           "fps": round(frames / elapsed, 1),
                           "cells_per_s": int(cells_per_s)})


# ─────────────────────────────────────────────────────────────────────────────
# 延迟：DSR 6 (CPR) round-trip
# ─────────────────────────────────────────────────────────────────────────────

@register("latency")
def t_latency(b: Bench) -> Result:
    """DSR 6 (\\033[6n) → CPR (\\033[r;cR) round-trip, p50/p95/p99."""
    samples = int(getattr(b.args, "samples", 100) or 100)
    b.header(f"latency (DSR 6 → CPR round-trip, n={samples})")
    if not sys.stdin.isatty():
        b.bad("stdin is not a tty; cannot measure CPR latency")
        return Result(name="latency", ok=False, note="stdin not a tty")
    times_ms: list[float] = []
    with raw_stdin() as fd:
        if fd is None:
            return Result(name="latency", ok=False, note="raw stdin unavailable")
        # warmup
        for _ in range(3):
            w(CSI + "6n"); flush()
            read_pty(fd, b"R", 0.5)
        for _ in range(samples):
            t0 = time.perf_counter()
            w(CSI + "6n"); flush()
            buf = read_pty(fd, b"R", 0.5)
            t1 = time.perf_counter()
            if buf.endswith(b"R"):
                times_ms.append((t1 - t0) * 1000)
    if not times_ms:
        b.bad("no responses received")
        return Result(name="latency", ok=False, note="no responses")
    times_ms.sort()

    def pct(p: float) -> float:
        idx = max(0, min(len(times_ms) - 1, int(len(times_ms) * p)))
        return times_ms[idx]

    p50, p95, p99 = pct(0.50), pct(0.95), pct(0.99)
    mn, mx = times_ms[0], times_ms[-1]
    mean = sum(times_ms) / len(times_ms)
    b.note(f"n={len(times_ms)}  min={mn:.2f}  p50={p50:.2f}  "
           f"p95={p95:.2f}  p99={p99:.2f}  max={mx:.2f}  mean={mean:.2f} (ms)")
    return Result(name="latency",
                  metrics={"n": len(times_ms),
                           "min_ms": round(mn, 2),
                           "p50_ms": round(p50, 2),
                           "p95_ms": round(p95, 2),
                           "p99_ms": round(p99, 2),
                           "max_ms": round(mx, 2),
                           "mean_ms": round(mean, 2)})


# ─────────────────────────────────────────────────────────────────────────────
# 一屏综合 showcase
# ─────────────────────────────────────────────────────────────────────────────

@register("showcase")
def t_showcase(b: Bench) -> Result:
    """一屏全特性渲染（截图证据用）。"""
    clear_screen()
    width = max(b.cols - 2, 40)

    # title
    title = " Ghostty Render Showcase — libghostty-vt + OpenGL ES 2 "
    pad = max(width - len(title) - 2, 0)
    w(sgr(1, "48;2;25;30;55", "38;2;230;230;255")
      + " " + title + " " * pad + " "
      + reset() + "\n")

    # truecolor
    w(rgb_fg(160, 160, 200) + "── TrueColor + Grayscale " + "─" *
      max(width - 26, 0) + reset() + "\n")
    for x in range(width):
        h = x / width
        r, g, bl = colorsys.hsv_to_rgb(h, 0.95, 1.0)
        w(rgb_bg(int(r * 255), int(g * 255), int(bl * 255)) + " ")
    w(reset() + "\n")
    for x in range(width):
        v = int(x / max(width - 1, 1) * 255)
        w(rgb_bg(v, v, v) + " ")
    w(reset() + "\n")

    # palette band
    w(rgb_fg(160, 160, 200) + "── 256-color palette " + "─" *
      max(width - 22, 0) + reset() + "\n")
    per_row = min(64, width)
    for r in range(0, 256, per_row):
        for i in range(r, min(r + per_row, 256)):
            w(palette_bg(i) + " ")
        w(reset() + "\n")

    # styles
    w(rgb_fg(160, 160, 200) + "── SGR styles + exotic underlines " +
      "─" * max(width - 35, 0) + reset() + "\n")
    w(sgr(1) + "Bold" + reset() + " "
      + sgr(3) + "Italic" + reset() + " "
      + sgr(2) + "Dim" + reset() + " "
      + sgr(9) + "Strike" + reset() + " "
      + sgr(7) + "Inv" + reset() + " "
      + sgr(5) + "Blink" + reset() + "\n")
    w(underline(1, None) + "single" + reset() + "  "
      + underline(2, (255, 80, 80)) + "double-red" + reset() + "  "
      + underline(3, (240, 200, 60)) + "curly-amber" + reset() + "  "
      + underline(4, (100, 200, 255)) + "dotted-cyan" + reset() + "  "
      + underline(5, (140, 255, 120)) + "dashed-lime" + reset() + "\n")

    # wide / emoji / box
    w(rgb_fg(160, 160, 200) + "── Wide-cell / CJK / emoji / box " + "─" *
      max(width - 34, 0) + reset() + "\n")
    w("CJK 中文  ひらがな  한글  Emoji 🚀🎨🐧☕  Box ┌─┐│└┘├┤\n")
    w("Arrows ↑←→↓ ◆●▲▼  Math ∑∫√π∞≠≈  Stars ★✦✩\n")

    # osc8
    w(rgb_fg(160, 160, 200) + "── OSC 8 hyperlinks (tap any) " + "─" *
      max(width - 31, 0) + reset() + "\n")
    w(osc8("https://ghostty.org", "ghostty.org",
           CSI + "4;38;2;120;180;255m") + "  ")
    w(osc8("https://github.com/termux", "github/termux",
           CSI + "4;38;2;180;255;180m") + "  ")
    w(osc8("https://example.org", "example.org",
           CSI + "4;38;2;255;200;120m") + "\n")

    # kitty
    w(rgb_fg(160, 160, 200) + "── Kitty graphics PNG → GPU " + "─" *
      max(width - 29, 0) + reset() + "\n")
    try:
        kitty_emit(_make_or_load_png_bytes())
        w("\n")
    except Exception as e:
        b.bad(f"kitty emit failed: {e}")

    # hint
    w(rgb_fg(110, 110, 130) +
      "logcat (Android):  adb logcat | rg 'Frame ghostty-render-state'" +
      reset() + "\n")
    flush()
    return Result(name="showcase",
                  metrics={"width": width, "rows": b.rows})


# ─────────────────────────────────────────────────────────────────────────────
# 汇总 + 报告
# ─────────────────────────────────────────────────────────────────────────────

def print_render_samples(b: Bench) -> None:
    """Append compact static render samples to the final visible report."""
    b.refresh_size()
    width = max(b.cols - 2, 24)
    sample_width = min(width, 80)

    w("\n" + sgr(1, "38;2;120;180;255")
      + "═══ Render Samples "
      + "═" * max(b.cols - 20, 10) + reset() + "\n")

    w(rgb_fg(160, 160, 200) + "── TrueColor / grayscale "
      + "─" * max(sample_width - 23, 0) + reset() + "\n")
    for x in range(sample_width):
        h = x / max(sample_width - 1, 1)
        r, g, bl = colorsys.hsv_to_rgb(h, 0.95, 1.0)
        w(rgb_bg(int(r * 255), int(g * 255), int(bl * 255)) + " ")
    w(reset() + "\n")
    for x in range(sample_width):
        v = int(x / max(sample_width - 1, 1) * 255)
        w(rgb_bg(v, v, v) + " ")
    w(reset() + "\n")

    w(rgb_fg(160, 160, 200) + "── 256-color palette excerpt "
      + "─" * max(sample_width - 29, 0) + reset() + "\n")
    cells = min(128, sample_width * 2)
    per_row = max(8, sample_width)
    for i in range(cells):
        w(palette_bg(i) + " ")
        if (i + 1) % per_row == 0:
            w(reset() + "\n")
    if cells % per_row:
        w(reset() + "\n")

    w(rgb_fg(160, 160, 200) + "── SGR / highlights / underline "
      + "─" * max(sample_width - 31, 0) + reset() + "\n")
    w(sgr(1) + "Bold" + reset() + "  "
      + sgr(3) + "Italic" + reset() + "  "
      + sgr(2) + "Dim" + reset() + "  "
      + sgr(7) + "Inverse" + reset() + "  "
      + sgr(9) + "Strike" + reset() + "  "
      + sgr(1, "48;2;40;90;65", "38;2;220;255;230")
      + "Highlighted" + reset() + "\n")
    w(underline(1, (255, 120, 120)) + "single-red" + reset() + "  "
      + underline(2, (255, 210, 90)) + "double-amber" + reset() + "  "
      + underline(3, (120, 220, 255)) + "curly-cyan" + reset() + "  "
      + underline(4, (170, 255, 130)) + "dotted-lime" + reset() + "  "
      + underline(5, (255, 150, 225)) + "dashed-pink" + reset() + "\n")

    w(rgb_fg(160, 160, 200) + "── Wide / emoji / OSC 8 "
      + "─" * max(sample_width - 23, 0) + reset() + "\n")
    w("CJK 中文  ひらがな  한글  Emoji 🚀🎨🐧☕  Box ┌─┐│└┘├┤\n")
    w(osc8("https://ghostty.org", "ghostty.org",
           CSI + "4;38;2;120;180;255m") + "  "
      + osc8("https://github.com/termux", "github/termux",
             CSI + "4;38;2;180;255;180m") + "\n")

    w(rgb_fg(160, 160, 200) + "── Kitty graphics PNG "
      + "─" * max(sample_width - 21, 0) + reset() + "\n")
    try:
        kitty_emit(_make_or_load_png_bytes())
        w("\n")
    except Exception as e:
        w(rgb_fg(255, 160, 120) + f"kitty sample failed: {e}"
          + reset() + "\n")
    flush()


def print_summary(b: Bench,
                  results: Optional[list[Result]] = None,
                  title: str = "Summary",
                  include_render_samples: bool = False) -> None:
    rows = results if results is not None else b.results
    w("\n" + sgr(1, "38;2;120;180;255")
      + f"═══ {title} " + "═" * max(b.cols - len(title) - 6, 10)
      + reset() + "\n")
    name_w = max((len(r.name) for r in rows), default=8) + 2
    for r in rows:
        status = (rgb_fg(120, 220, 140) + "✓"
                  if r.ok else rgb_fg(255, 110, 110) + "✗")
        line = f"{status} {r.name:<{name_w}}{reset()}"
        line += f" {r.elapsed_s*1000:7.1f} ms  "
        if r.metrics:
            kv = "  ".join(f"{k}={v}" for k, v in r.metrics.items())
            line += rgb_fg(160, 160, 180) + kv + reset()
        if r.note:
            line += "  " + rgb_fg(255, 180, 120) + r.note + reset()
        w(line + "\n")
    if include_render_samples:
        print_render_samples(b)
    flush()


def write_report(b: Bench, path: str) -> None:
    with open(path, "w") as f:
        json.dump(b.report(), f, ensure_ascii=False, indent=2)


# ─────────────────────────────────────────────────────────────────────────────
# main
# ─────────────────────────────────────────────────────────────────────────────

STATIC_ORDER = [
    "showcase",
    "truecolor",
    "palette",
    "styles",
    "underlines",
    "wide",
    "osc8",
    "kitty",
]

PERF_ORDER = [
    "fps_static",
    "fps_scroll",
    "throughput",
    "stress_rgba",
    "stress_glyph",
]

VISUAL_ORDER = [
    "gradient_2d",
    "rainbow",
    "disco",
    "plasma",
    "matrix",
    "fireworks",
    "neon_tunnel",
    "braille_particles",
    "emoji_storm",
    "dashboard",
]

PERF_VISUAL_ORDER = PERF_ORDER + VISUAL_ORDER + ["latency"]
DEFAULT_ALL_ORDER = PERF_VISUAL_ORDER
LIST_ORDER = PERF_VISUAL_ORDER + STATIC_ORDER


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description="Ghostty render capability + perf bench",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=("Groups: all, perf, visual, static\n"
                "Tests: " + ", ".join(LIST_ORDER)),
    )
    p.add_argument("test", nargs="?", default="all",
                   help=("test name, 'all', 'perf', 'visual', 'static', "
                         "'list', or 'showcase' (default: all)"))
    p.add_argument("--duration", type=float, default=3.0,
                   help="seconds for perf tests (default 3)")
    p.add_argument("--samples", type=int, default=100,
                   help="latency samples (default 100)")
    p.add_argument("--report", type=str, default="",
                   help="write JSON report to this path")
    p.add_argument("--no-summary", action="store_true")
    args = p.parse_args(argv)

    bench = Bench(args)

    if args.test == "list":
        w("groups:\n")
        w("  all\n")
        w("  perf\n")
        w("  visual\n")
        w("  static\n")
        w("tests:\n")
        seen = set()
        for name in LIST_ORDER:
            seen.add(name)
            w(f"  {name}\n")
        for name in TESTS:
            if name not in seen:
                w(f"  {name}\n")
        flush()
        return 0

    try:
        if args.test == "all":
            for name in DEFAULT_ALL_ORDER:
                bench.run_one(name)
        elif args.test == "perf":
            for name in PERF_ORDER + ["latency"]:
                bench.run_one(name)
        elif args.test == "visual":
            for name in VISUAL_ORDER:
                bench.run_one(name)
        elif args.test == "static":
            for name in STATIC_ORDER:
                bench.run_one(name)
        elif args.test == "showcase":
            bench.run_one("showcase")
        elif args.test in TESTS:
            bench.run_one(args.test)
        else:
            sys.stderr.write(f"unknown test: {args.test}\n"
                             "use 'list' to see available tests.\n")
            return 2
    except KeyboardInterrupt:
        w("\ninterrupted\n")
    finally:
        show_cursor()
        wf(reset())
        if not args.no_summary and len(bench.results) > 1:
            print_summary(
                bench,
                title=("Performance Report" if args.test == "all"
                       else "Summary"),
                include_render_samples=(args.test == "all"),
            )
        if args.report:
            write_report(bench, args.report)
            sys.stderr.write(f"\nreport written to {args.report}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
