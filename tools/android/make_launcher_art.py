#!/usr/bin/env python3
"""Make the launcher's background and app icon from a screenshot of YOUR running game.

  make_launcher_art.py <screenshot.png> [--icon X,Y,SIZE] [--fill-from Y] [--icon-src PICTURE]

  --icon X,Y,SIZE  square region of the (cleaned) screenshot used for the app icon
                   (default: the centred square)
  --fill-from Y    replace everything from row Y down with the rows above it, mirrored
                   (hides on-screen text such as "PRESS START" / rating notices)

Writes android/art-res/ (git-ignored; Gradle adds it as a resource folder and switches the app icon
to it only when it exists): drawable-nodpi/launcher_bg.jpg and mipmap-*/ic_launcher.png. The art is
the game's own picture, so it stays on this machine like the game files: never commit it.
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "android", "art-res")
ICON_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

ap = argparse.ArgumentParser()
ap.add_argument("shot")
ap.add_argument("--icon")
ap.add_argument("--fill-from", type=int)
ap.add_argument("--icon-src", help="take the icon from this picture instead (e.g. the disc's nxeslot art)")
a = ap.parse_args()

img = Image.open(a.shot).convert("RGB")
w, h = img.size
if a.fill_from:
    y = a.fill_from
    strip = ImageOps.flip(img.crop((0, 2 * y - h, w, y)))
    img.paste(strip, (0, y))

bg_dir = os.path.join(OUT, "drawable-nodpi")
os.makedirs(bg_dir, exist_ok=True)
img.resize((min(w, 1920), int(h * min(w, 1920) / w)), Image.LANCZOS).save(
    os.path.join(bg_dir, "launcher_bg.jpg"), quality=88)

src = Image.open(a.icon_src).convert("RGB") if a.icon_src else img
if a.icon:
    x, y, s = (int(v) for v in a.icon.split(","))
else:
    s = min(src.size)
    x, y = (src.size[0] - s) // 2, (src.size[1] - s) // 2
square = src.crop((x, y, x + s, y + s))
for density, px in ICON_SIZES.items():
    d = os.path.join(OUT, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)
    icon = square.resize((px, px), Image.LANCZOS).convert("RGBA")
    mask = Image.new("L", (px * 4, px * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, px * 4 - 1, px * 4 - 1), radius=px * 4 // 5, fill=255)
    icon.putalpha(mask.resize((px, px), Image.LANCZOS))
    icon.save(os.path.join(d, "ic_launcher.png"))
print(f"launcher art written to {os.path.normpath(OUT)} (icon from {x},{y} size {s})")
