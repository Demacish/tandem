#!/usr/bin/env python3
"""Builds the demo recipe images in core/data/src/main/assets/demo/images/ (plan 6.2 "Demoläge").

Two kinds of images, both 640 x 480 WebP (quality ~80, no metadata, each <= 70 KB):

1. Photos: the AI-generated food images already in docs/design/stitch-export/ (made with Stitch
   for the design mockups), cropped to 4:3 and scaled down.
2. Illustrations: drawn here with pycairo in the brand palette (core/designsystem TandemBrand)
   around a Material Symbols Rounded icon (Apache-2.0), downloaded from github.com/google/
   material-design-icons at the commit the app's icons are pinned to and checked against the
   git blob hash GitHub publishes for that file. Two dishes without a fitting icon (meatballs,
   tacos) are drawn from plain shapes.

Usage (from the repository root, needs Pillow with WebP and pycairo):
    python3 scripts/demo-content/build_images.py
"""
import hashlib
import io
import math
import os
import random
import sys
import urllib.request

import cairo
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svgpath import draw_path, path_data, view_box  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "core", "data", "src", "main", "assets", "demo", "images")
STITCH = os.path.join(ROOT, "docs", "design", "stitch-export")
WIDTH, HEIGHT = 640, 480
MAX_BYTES = 70 * 1024

# Same pinned commit as core/designsystem/src/main/assets/licenses/NOTICE.txt.
ICONS_COMMIT = "49d4db35df873165d6bd6ba09b063c7dafbac2f4"
# icon name -> git blob SHA-1 of symbols/web/<name>/materialsymbolsrounded/<name>_fill1_48px.svg
ICONS = {
    "bakery_dining": "ec0c66fc806a5f96fdd0e2a95ae0886a5c875ffe",
    "skillet": "66ff9f0b6f7751aa023005713362e86750acbc25",
    "dinner_dining": "ef807bcdd10db449a5116062a0bf3f92cf238695",
    "cake": "f788f4a9724abeaad8f7b3654ffc0603a67a15db",
    "ramen_dining": "afb2348fb61d76324512442cc4b4b86535043767",
    "rice_bowl": "fa433dbf725978f6523689f07e678f8f7c53d569",
    "nutrition": "c4b7c1d7562fe349b72039189e648c219bdcb73f",
}

# (file, stitch folder, crop box in source pixels or None for a centred 4:3 crop)
PHOTOS = [
    ("lax.webp", "appetizing_baked_salmon_with_dill_potatoes_and_lemon_on_a_warm_ceramic_plate", None),
    ("tomatsoppa.webp", "appetizing_fresh_tomato_soup_in_a_rustic_ceramic_bowl_with_sour_cream_swirl_and", None),
    ("citronkyckling.webp", "roasted_lemon_herb_chicken_with_root_vegetables_in_a_baking_dish_cozy_kitchen", None),
    ("linsdahl.webp", "warm_yellow_lentil_dahl_curry_stew_with_coconut_milk_in_a_terracotta_bowl_fresh", None),
    # The hand-drawn terracotta pot (the left part of the illustration, without the book's text).
    ("gronsaksgryta.webp", "a_charming_cozy_warm_hand_drawn_vector_style_illustration_of_a_warm_terracotta",
     (40, 310, 560, 700)),
]

# Brand palette (TandemBrand + the light colour scheme's containers).
CREAM = (0xFB, 0xF7, 0xF2)
TONES = {
    "tomato": {"bg": (0xFF, 0xDB, 0xCF), "ink": (0xC2, 0x41, 0x0C), "soft": (0xF4, 0xA0, 0x7E)},
    "saffron": {"bg": (0xFE, 0xDE, 0xA8), "ink": (0x9C, 0x4F, 0x00), "soft": (0xE8, 0xA8, 0x4A)},
    "herb": {"bg": (0xD3, 0xF8, 0x9A), "ink": (0x45, 0x6F, 0x0C), "soft": (0x9C, 0xC8, 0x5C)},
}

# (file, tone, icon name or a custom drawing)
ILLUSTRATIONS = [
    ("pannkakor.webp", "saffron", "skillet"),
    ("kardemummabullar.webp", "saffron", "bakery_dining"),
    ("havregrynsgrot.webp", "saffron", "rice_bowl"),
    ("kottbullar.webp", "tomato", "custom:meatballs"),
    ("kottfarssas.webp", "tomato", "dinner_dining"),
    ("kladdkaka.webp", "tomato", "cake"),
    ("fredagstacos.webp", "herb", "custom:taco"),
    ("wokade-nudlar.webp", "herb", "ramen_dining"),
    ("halloumisallad.webp", "herb", "nutrition"),
]


def rgb(color, alpha=1.0):
    return color[0] / 255.0, color[1] / 255.0, color[2] / 255.0, alpha


def git_blob_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def fetch_icon(name):
    path = f"symbols/web/{name}/materialsymbolsrounded/{name}_fill1_48px.svg"
    url = f"https://raw.githubusercontent.com/google/material-design-icons/{ICONS_COMMIT}/{path}"
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read()
    if git_blob_sha(data) != ICONS[name]:
        raise SystemExit(f"checksum mismatch for {name}")
    return data.decode("utf-8")


def save_webp(image, file_name):
    image = image.convert("RGB")
    for quality in (80, 76, 72, 68, 64, 60):
        buffer = io.BytesIO()
        # No exif/icc/xmp arguments: Pillow then writes no metadata chunks.
        image.save(buffer, "WEBP", quality=quality, method=6)
        if buffer.tell() <= MAX_BYTES:
            break
    else:
        raise SystemExit(f"{file_name} stays above {MAX_BYTES} bytes")
    with open(os.path.join(OUT, file_name), "wb") as f:
        f.write(buffer.getvalue())
    return buffer.tell(), quality


def photo(folder, box):
    image = Image.open(os.path.join(STITCH, folder, "screen.png")).convert("RGB")
    if box is None:
        w, h = image.size
        target_h = round(w * 3 / 4)
        if target_h <= h:
            top = (h - target_h) // 2
            box = (0, top, w, top + target_h)
        else:
            target_w = round(h * 4 / 3)
            left = (w - target_w) // 2
            box = (left, 0, left + target_w, h)
    return image.crop(box).resize((WIDTH, HEIGHT), Image.LANCZOS)


def draw_meatballs(ctx, ink, size):
    # A plate seen from above with five meatballs and a spoonful of lingonberries.
    ctx.set_source_rgba(*rgb(ink))
    ctx.set_line_width(size * 0.05)
    ctx.arc(0, 0, size * 0.48, 0, 2 * math.pi)
    ctx.stroke()
    for x, y in [(-0.17, -0.12), (0.12, -0.17), (0.2, 0.1), (-0.05, 0.18), (-0.22, 0.12)]:
        ctx.arc(x * size, y * size, size * 0.11, 0, 2 * math.pi)
        ctx.fill()
    for x, y in [(0.0, -0.01), (0.05, 0.03), (-0.04, 0.04), (0.02, -0.06)]:
        ctx.arc(x * size, y * size, size * 0.03, 0, 2 * math.pi)
        ctx.fill()


def draw_taco(ctx, ink, size):
    # A taco seen from the front: the inside of the shell, the filling heaped along the opening
    # and the shell's thick U-shaped front edge with a few toasted spots.
    ctx.rotate(-0.16)
    r = size * 0.52
    inner = r * 0.7
    top = -size * 0.02

    ctx.set_source_rgba(*rgb(ink, 0.35))
    ctx.move_to(-inner, top)
    ctx.arc_negative(0, top, inner, math.pi, 0)
    ctx.close_path()
    ctx.fill()
    rng = random.Random(7)
    for i in range(9):
        x = -r * 0.78 + i * (r * 1.56 / 8)
        ctx.set_source_rgba(*rgb(ink, 0.65 if i % 2 else 0.9))
        ctx.arc(x, top - size * 0.01, size * rng.uniform(0.07, 0.095), 0, 2 * math.pi)
        ctx.fill()
    ctx.set_source_rgba(*rgb(ink))
    ctx.move_to(-r, top)
    ctx.arc_negative(0, top, r, math.pi, 0)
    ctx.line_to(inner, top)
    ctx.arc(0, top, inner, 0, math.pi)
    ctx.close_path()
    ctx.fill()
    ctx.set_source_rgba(*rgb(CREAM, 0.85))
    for angle in (0.35, 0.8, 1.3, 1.85, 2.35, 2.8):
        rr = (r + inner) / 2 + (size * 0.02 if angle > 1.5 else -size * 0.02)
        ctx.arc(math.cos(angle) * rr, top + math.sin(angle) * rr, size * 0.02, 0, 2 * math.pi)
        ctx.fill()


def illustration(seed, tone, icon):
    colors = TONES[tone]
    surface = cairo.ImageSurface(cairo.FORMAT_RGB24, WIDTH, HEIGHT)
    ctx = cairo.Context(surface)
    gradient = cairo.LinearGradient(0, 0, WIDTH, HEIGHT)
    gradient.add_color_stop_rgba(0, *rgb(colors["bg"]))
    gradient.add_color_stop_rgba(1, *rgb(CREAM))
    ctx.set_source(gradient)
    ctx.paint()

    rng = random.Random(seed)
    # Confetti of small dots and leaves, kept away from the plate in the middle.
    for _ in range(26):
        while True:
            x, y = rng.uniform(20, WIDTH - 20), rng.uniform(20, HEIGHT - 20)
            if math.hypot(x - WIDTH / 2, y - HEIGHT / 2) > 200:
                break
        ctx.save()
        ctx.translate(x, y)
        ctx.rotate(rng.uniform(0, math.pi))
        ctx.set_source_rgba(*rgb(colors["soft"], rng.uniform(0.35, 0.7)))
        if rng.random() < 0.5:
            ctx.arc(0, 0, rng.uniform(4, 9), 0, 2 * math.pi)
        else:
            length = rng.uniform(12, 22)
            ctx.move_to(-length, 0)
            ctx.curve_to(-length / 2, -length / 2.2, length / 2, -length / 2.2, length, 0)
            ctx.curve_to(length / 2, length / 2.2, -length / 2, length / 2.2, -length, 0)
        ctx.fill()
        ctx.restore()

    cx, cy, radius = WIDTH / 2, HEIGHT / 2 + 6, 168
    # Soft shadow, then the cream plate with a thin rim.
    for i in range(12, 0, -1):
        ctx.set_source_rgba(0.35, 0.2, 0.1, 0.012 * (13 - i))
        ctx.arc(cx + 4, cy + 10, radius + i * 1.6, 0, 2 * math.pi)
        ctx.fill()
    ctx.set_source_rgba(*rgb(CREAM))
    ctx.arc(cx, cy, radius, 0, 2 * math.pi)
    ctx.fill()
    ctx.set_source_rgba(*rgb(colors["bg"], 0.9))
    ctx.set_line_width(10)
    ctx.arc(cx, cy, radius - 18, 0, 2 * math.pi)
    ctx.stroke()

    ctx.save()
    ctx.translate(cx, cy)
    size = 190.0
    if icon.startswith("custom:"):
        {"meatballs": draw_meatballs, "taco": draw_taco}[icon.split(":")[1]](ctx, colors["ink"], size)
    else:
        svg = fetch_icon(icon)
        x, y, w, h = view_box(svg)
        ctx.scale(size / w, size / h)
        ctx.translate(-x - w / 2, -y - h / 2)
        for d in path_data(svg):
            draw_path(ctx, d)
        ctx.set_source_rgba(*rgb(colors["ink"]))
        ctx.fill()
    ctx.restore()

    png = io.BytesIO()
    surface.write_to_png(png)
    png.seek(0)
    return Image.open(png)


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for file_name, folder, box in PHOTOS:
        size, quality = save_webp(photo(folder, box), file_name)
        total += size
        print(f"{file_name:24} photo         {size:6} bytes  q{quality}")
    for file_name, tone, icon in ILLUSTRATIONS:
        size, quality = save_webp(illustration(file_name, tone, icon), file_name)
        total += size
        print(f"{file_name:24} illustration  {size:6} bytes  q{quality}")
    print(f"total {total} bytes")


if __name__ == "__main__":
    main()
