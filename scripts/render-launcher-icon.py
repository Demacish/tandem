#!/usr/bin/env python3
"""Generates Tandem's launcher icon from one geometry definition.

Writes the adaptive icon layers as VectorDrawables and renders the 512x512 Play Store PNG
with the same paths, so the vector and the PNG can never drift apart.

    python3 scripts/render-launcher-icon.py

Outputs (relative to the repo root):
  app/src/main/res/drawable/ic_launcher_background.xml   tomato gradient, full 108 dp canvas
  app/src/main/res/drawable/ic_launcher_foreground.xml   cream pot, heart, steam (also the splash icon)
  app/src/main/res/drawable/ic_launcher_monochrome.xml   one-colour silhouette for themed icons
  app/src/main/ic_launcher-playstore.png                 512x512 Play Store icon (not packaged)

Needs only pycairo (python3-cairo) and Pillow. Pure drawing: no Android SDK, no Gradle.

Geometry: adaptive icons use a 108x108 dp canvas. Launchers show the inner 72 dp and may mask
it to any shape; everything important stays inside the 66 dp safe circle around (54, 54).
Paths use only absolute M/L/C/Z so they map 1:1 to VectorDrawable pathData and cairo calls.
"""

from __future__ import annotations

import math
import pathlib
import re

import cairo
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/drawable"
PLAYSTORE_PNG = ROOT / "app/src/main/ic_launcher-playstore.png"

CANVAS = 108.0
SAFE_RADIUS = 33.0
CENTER = 54.0

# Brand colours (docs/design/stitch-export/recept_varm_k_k/DESIGN.md).
TOMATO = "#C2410C"
TOMATO_LIGHT = "#CC4D16"
TOMATO_DARK = "#9A3412"
BODY_TOP = "#FFF3E6"
BODY_BOTTOM = "#FCD3A6"
LID = "#FDE2C4"
KNOB = "#FCD9B4"
RIM = "#FFFFFF"
HANDLE = "#FFFFFF"
STEAM = "#FAD5BA"
HEART_DISC = "#F5C49E"


def circle(cx: float, cy: float, r: float) -> str:
    """Circle as four cubic Béziers (kappa approximation)."""
    k = 0.5522847498 * r
    return (
        f"M{cx},{cy - r} "
        f"C{cx + k},{cy - r} {cx + r},{cy - k} {cx + r},{cy} "
        f"C{cx + r},{cy + k} {cx + k},{cy + r} {cx},{cy + r} "
        f"C{cx - k},{cy + r} {cx - r},{cy + k} {cx - r},{cy} "
        f"C{cx - r},{cy - k} {cx - k},{cy - r} {cx},{cy - r} Z"
    )


def ellipse(cx: float, cy: float, rx: float, ry: float) -> str:
    kx, ky = 0.5522847498 * rx, 0.5522847498 * ry
    return (
        f"M{cx},{cy - ry} "
        f"C{cx + kx},{cy - ry} {cx + rx},{cy - ky} {cx + rx},{cy} "
        f"C{cx + rx},{cy + ky} {cx + kx},{cy + ry} {cx},{cy + ry} "
        f"C{cx - kx},{cy + ry} {cx - rx},{cy + ky} {cx - rx},{cy} "
        f"C{cx - rx},{cy - ky} {cx - kx},{cy - ry} {cx},{cy - ry} Z"
    )


def rounded_rect(x0: float, y0: float, x1: float, y1: float, r: float) -> str:
    k = 0.5522847498 * r
    return (
        f"M{x0 + r},{y0} L{x1 - r},{y0} "
        f"C{x1 - r + k},{y0} {x1},{y0 + r - k} {x1},{y0 + r} "
        f"L{x1},{y1 - r} "
        f"C{x1},{y1 - r + k} {x1 - r + k},{y1} {x1 - r},{y1} "
        f"L{x0 + r},{y1} "
        f"C{x0 + r - k},{y1} {x0},{y1 - r + k} {x0},{y1 - r} "
        f"L{x0},{y0 + r} "
        f"C{x0},{y0 + r - k} {x0 + r - k},{y0} {x0 + r},{y0} Z"
    )


def body(top: float) -> str:
    return (
        f"M36.5,{top} L71.5,{top} "
        "C71.5,63 71,70.5 65.5,74 "
        "C62,76.3 58,77 54,77 "
        "C50,77 46,76.3 42.5,74 "
        f"C37,70.5 36.5,63 36.5,{top} Z"
    )


HEART = (
    "M54,68.8 C53.6,68.8 53.2,68.6 52.9,68.3 "
    "C50.2,65.9 48.5,64.3 48.5,62.2 "
    "C48.5,60.6 49.8,59.3 51.4,59.3 "
    "C52.4,59.3 53.4,59.8 54,60.6 "
    "C54.6,59.8 55.6,59.3 56.6,59.3 "
    "C58.2,59.3 59.5,60.6 59.5,62.2 "
    "C59.5,64.3 57.8,65.9 55.1,68.3 "
    "C54.8,68.6 54.4,68.8 54,68.8 Z"
)
HEART_DISC_PATH = circle(54, 64, 7)
KNOB_PATH = ellipse(54, 45.6, 5.4, 2.5)
LID_PATH = "M37,50.5 C38,47.2 45,46.4 54,46.4 C63,46.4 70,47.2 71,50.5 Z"
RIM_PATH = rounded_rect(32.5, 49.5, 75.5, 54.2, 2.35)
HANDLE_LEFT = "M38,57.6 C32.2,57.6 30.6,59.6 30.6,62.1 C30.6,64.6 32.2,66.6 38,66.6"
HANDLE_RIGHT = "M70,57.6 C75.8,57.6 77.4,59.6 77.4,62.1 C77.4,64.6 75.8,66.6 70,66.6"
STEAM_PATHS = [
    "M44.2,40 C41.6,37.4 41.6,35.2 44.2,32.6 C46.8,30 46.8,27.8 44.2,25.8",
    "M54,39.6 C51.2,36.6 51.2,34 54,31 C56.8,28 56.8,25.6 54,23.4",
    "M63.8,40 C61.2,37.4 61.2,35.2 63.8,32.6 C66.4,30 66.4,27.8 63.8,25.8",
]
HANDLE_STROKE = 3.4
STEAM_STROKE = 3.2

# ---------------------------------------------------------------------------------------------
# Layers: (kind, path, paint) where paint is a hex colour or a vertical gradient tuple.
# ---------------------------------------------------------------------------------------------
BODY_GRADIENT = ("linear", 54, 54, 54, 77, BODY_TOP, BODY_BOTTOM)
BACKGROUND_GRADIENT = ("linear", 0, 0, 108, 108, TOMATO_LIGHT, TOMATO_DARK)

FOREGROUND = (
    [("stroke", p, STEAM, STEAM_STROKE, 0.95) for p in STEAM_PATHS]
    + [
        ("stroke", HANDLE_LEFT, HANDLE, HANDLE_STROKE, 1.0),
        ("stroke", HANDLE_RIGHT, HANDLE, HANDLE_STROKE, 1.0),
        ("fill", body(53.5), BODY_GRADIENT),
        ("fill", KNOB_PATH, KNOB),
        ("fill", LID_PATH, LID),
        ("fill", RIM_PATH, RIM),
        ("fill", HEART_DISC_PATH, HEART_DISC),
        ("fill", HEART, TOMATO),
    ]
)

# Monochrome: the system tints it with the wallpaper colour and only uses alpha. The heart is a
# hole in the pot (evenOdd) and a 1.2 dp gap separates rim and body so the silhouette still
# reads as "pot with lid" at 24 dp.
MONO = "#FFFFFF"
MONOCHROME = (
    [("stroke", p, MONO, STEAM_STROKE, 1.0) for p in STEAM_PATHS]
    + [
        ("stroke", HANDLE_LEFT, MONO, HANDLE_STROKE, 1.0),
        ("stroke", HANDLE_RIGHT, MONO, HANDLE_STROKE, 1.0),
        ("fill", KNOB_PATH, MONO),
        ("fill", LID_PATH, MONO),
        ("fill", RIM_PATH, MONO),
        ("fill_evenodd", body(55.4) + " " + HEART, MONO),
    ]
)

BACKGROUND = [("fill", "M0,0 L108,0 L108,108 L0,108 Z", BACKGROUND_GRADIENT)]


# ---------------------------------------------------------------------------------------------
# Path parsing (absolute M/L/C/Z only)
# ---------------------------------------------------------------------------------------------
TOKEN = re.compile(r"[MLCZ]|-?\d+(?:\.\d+)?")


def parse(path: str):
    tokens = TOKEN.findall(path)
    ops, i = [], 0
    while i < len(tokens):
        cmd = tokens[i]
        i += 1
        if cmd == "Z":
            ops.append(("Z", []))
            continue
        n = {"M": 2, "L": 2, "C": 6}[cmd]
        ops.append((cmd, [float(v) for v in tokens[i : i + n]]))
        i += n
    return ops


def points(path: str):
    for cmd, args in parse(path):
        for j in range(0, len(args), 2):
            yield args[j], args[j + 1]


def check_safe_zone() -> None:
    """Every foreground point (plus half a stroke) must sit inside the 66 dp safe circle."""
    worst = 0.0
    for layer in FOREGROUND + MONOCHROME:
        extra = layer[3] / 2 if layer[0] == "stroke" else 0.0
        for x, y in points(layer[1]):
            worst = max(worst, math.hypot(x - CENTER, y - CENTER) + extra)
    if worst > SAFE_RADIUS:
        raise SystemExit(f"icon leaves the safe zone: {worst:.2f} dp > {SAFE_RADIUS} dp")
    print(f"safe zone ok: furthest point {worst:.2f} dp from centre (limit {SAFE_RADIUS})")


# ---------------------------------------------------------------------------------------------
# VectorDrawable output
# ---------------------------------------------------------------------------------------------
def fmt(v: float) -> str:
    return f"{v:.3f}".rstrip("0").rstrip(".")


def path_data(path: str) -> str:
    out = []
    for cmd, args in parse(path):
        out.append(cmd + (" " + " ".join(fmt(a) for a in args) if args else ""))
    return " ".join(out)


def argb(hex_colour: str) -> str:
    return "#FF" + hex_colour.lstrip("#").upper()


def vector_layer(layer) -> str:
    kind, path, paint = layer[0], layer[1], layer[2]
    data = path_data(path)
    if kind == "stroke":
        width, alpha = layer[3], layer[4]
        alpha_attr = f'\n        android:strokeAlpha="{fmt(alpha)}"' if alpha < 1 else ""
        return (
            "    <path\n"
            f'        android:pathData="{data}"\n'
            f'        android:strokeColor="{paint}"\n'
            f'        android:strokeWidth="{fmt(width)}"{alpha_attr}\n'
            '        android:strokeLineCap="round"\n'
            '        android:strokeLineJoin="round" />\n'
        )
    fill_type = '\n        android:fillType="evenOdd"' if kind == "fill_evenodd" else ""
    if isinstance(paint, tuple):
        _, x0, y0, x1, y1, c0, c1 = paint
        return (
            f'    <path android:pathData="{data}"{fill_type}>\n'
            '        <aapt:attr name="android:fillColor">\n'
            "            <gradient\n"
            '                android:type="linear"\n'
            f'                android:startX="{fmt(x0)}"\n'
            f'                android:startY="{fmt(y0)}"\n'
            f'                android:endX="{fmt(x1)}"\n'
            f'                android:endY="{fmt(y1)}"\n'
            f'                android:startColor="{argb(c0)}"\n'
            f'                android:endColor="{argb(c1)}" />\n'
            "        </aapt:attr>\n"
            "    </path>\n"
        )
    return (
        "    <path\n"
        f'        android:pathData="{data}"{fill_type}\n'
        f'        android:fillColor="{paint}" />\n'
    )


def write_vector(name: str, layers, comment: str) -> None:
    uses_aapt = any(isinstance(layer[2], tuple) for layer in layers)
    ns = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if uses_aapt else ""
    body_xml = "".join(vector_layer(layer) for layer in layers)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment}\n"
        "     GENERATED by scripts/render-launcher-icon.py - edit the script, not this file. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"'
        f"{ns}\n"
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        f"{body_xml}"
        "</vector>\n"
    )
    (RES / f"{name}.xml").write_text(xml, encoding="utf-8")
    print(f"wrote {RES / name}.xml")


# ---------------------------------------------------------------------------------------------
# PNG output (cairo)
# ---------------------------------------------------------------------------------------------
def rgb(hex_colour: str):
    h = hex_colour.lstrip("#")
    return tuple(int(h[i : i + 2], 16) / 255 for i in (0, 2, 4))


def trace(ctx: cairo.Context, path: str) -> None:
    for cmd, a in parse(path):
        if cmd == "M":
            ctx.move_to(*a)
        elif cmd == "L":
            ctx.line_to(*a)
        elif cmd == "C":
            ctx.curve_to(*a)
        else:
            ctx.close_path()


def paint_layer(ctx: cairo.Context, layer) -> None:
    kind, path, paint = layer[0], layer[1], layer[2]
    ctx.new_path()
    trace(ctx, path)
    alpha = layer[4] if kind == "stroke" else 1.0
    if isinstance(paint, tuple):
        _, x0, y0, x1, y1, c0, c1 = paint
        source = cairo.LinearGradient(x0, y0, x1, y1)
        source.add_color_stop_rgb(0, *rgb(c0))
        source.add_color_stop_rgb(1, *rgb(c1))
        ctx.set_source(source)
    else:
        ctx.set_source_rgba(*rgb(paint), alpha)
    if kind == "stroke":
        ctx.set_line_width(layer[3])
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        ctx.set_line_join(cairo.LINE_JOIN_ROUND)
        ctx.stroke()
    else:
        ctx.set_fill_rule(
            cairo.FILL_RULE_EVEN_ODD if kind == "fill_evenodd" else cairo.FILL_RULE_WINDING
        )
        ctx.fill()


def render_png(out: pathlib.Path, size: int, crop: float, layers) -> None:
    """Renders the centred `crop` x `crop` dp square of the canvas at size x size px."""
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surface)
    ctx.set_antialias(cairo.ANTIALIAS_BEST)
    scale = size / crop
    ctx.scale(scale, scale)
    ctx.translate(-(CANVAS - crop) / 2, -(CANVAS - crop) / 2)
    for layer in layers:
        paint_layer(ctx, layer)
    surface.flush()
    tmp = out.with_suffix(".tmp.png")
    surface.write_to_png(str(tmp))
    # Play Store: 512x512, 32-bit PNG, full square (Play applies its own mask and shadow).
    Image.open(tmp).convert("RGBA").save(out, optimize=True)
    tmp.unlink()
    print(f"wrote {out}")


def main() -> None:
    check_safe_zone()
    RES.mkdir(parents=True, exist_ok=True)
    write_vector(
        "ic_launcher_background",
        BACKGROUND,
        "Adaptive launcher icon background: tomato gradient (#CC4D16 -> #9A3412).",
    )
    write_vector(
        "ic_launcher_foreground",
        FOREGROUND,
        "Adaptive launcher icon foreground: cream pot with lid, handles, tomato heart and steam,\n"
        "     inside the 66 dp safe circle. Also used as the splash screen icon.",
    )
    write_vector(
        "ic_launcher_monochrome",
        MONOCHROME,
        "Monochrome layer for themed icons (Android 13+): only alpha is used.",
    )
    # 84 dp of the 108 dp canvas: the pot fills about 62 % of the Play icon, like the Stitch
    # reference (docs/design/stitch-export/tandem_appikon_512x512).
    render_png(PLAYSTORE_PNG, 512, 84.0, BACKGROUND + FOREGROUND)


if __name__ == "__main__":
    main()
