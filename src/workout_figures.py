"""Renders every move of ExerciseFigure.kt as the app draws it.

Both ends of each move faint, the figure at three points of its loop.

Usage: uv run --no-project --with pillow python src/workout_figures.py [out.png]
"""

import math
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/java/com/example/myapp/workout/ExerciseFigure.kt"
DEFAULT_OUT = ROOT / "mockups/workout_figures.png"

GROUND = [((4, 67.5), (96, 67.5))]
PROPS = {
    "GROUND": GROUND,
    "BAR": [((28, 10), (72, 10))],
    "CHAIR": GROUND + [((16, 40), (39, 40)), ((18, 40), (18, 67.5)), ((36, 40), (36, 67.5))],
}
COLOR = (46, 158, 106)
CARD = (238, 240, 236)
S = 3
W, H = 100 * S, 72 * S
FRAMES = (0.0, 0.5, 1.0)


def mix(c, a):
    return tuple(round(CARD[i] + (c[i] - CARD[i]) * a) for i in range(3))


def lerp(a, b, t):
    return [(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t) for (x0, y0), (x1, y1) in zip(a, b, strict=True)]


def vec(body, name, default):
    m = re.search(rf"{name} = p\((-?[\d.]+)f, (-?[\d.]+)f\)", body)
    return (float(m.group(1)), float(m.group(2))) if m else default


def figure(d, pose, near, far, ox, oy):
    px = [(x * S + ox, y * S + oy) for x, y in pose]
    head, neck, hip, nk, nf, fk, ff, ne, nh, fe, fh = px
    w = round(1.9 * S)
    for a, b in [(hip, fk), (fk, ff), (neck, fe), (fe, fh)]:
        d.line([a, b], fill=far, width=w)
    d.line([neck, hip], fill=near, width=round(w * 1.9))
    for a, b in [(hip, nk), (nk, nf), (neck, ne), (ne, nh)]:
        d.line([a, b], fill=near, width=w)
    r = 6 * S
    d.ellipse([head[0] - r, head[1] - r, head[0] + r, head[1] + r], fill=near)
    return head, r


def dot(d, c, r, color):
    d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=color)


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    moves = re.findall(r"private val (\w+) = Move\((.*?)\n\)", SOURCE.read_text(), re.S)
    img = Image.new("RGB", ((W + 20) * len(FRAMES), (H + 30) * len(moves)), "white")
    d = ImageDraw.Draw(img)
    for row, (name, body) in enumerate(moves):
        pts = [(float(x), float(y)) for x, y in re.findall(r"p\((-?[\d.]+)f, (-?[\d.]+)f\)", body)]
        start, end = pts[:11], pts[11:22]
        prop = re.search(r"props = (\w+)", body)
        front = "front = true" in body
        mat = "mat = true" in body
        look = vec(body, "look", (1.0, 0.0))
        look_to = vec(body, "lookTo", look)
        for col, t in enumerate(FRAMES):
            ox, oy = col * (W + 20), row * (H + 30) + 22
            d.rectangle([ox, oy, ox + W, oy + H], fill=CARD)
            d.text((ox + 4, oy - 18), f"{name} t={t}", fill="black")
            for a, b in PROPS[prop.group(1) if prop else "GROUND"]:
                d.line(
                    [(a[0] * S + ox, a[1] * S + oy), (b[0] * S + ox, b[1] * S + oy)],
                    fill=mix(COLOR, 0.3),
                    width=round(1.5 * S),
                )
            if mat:
                d.line(
                    [(6 * S + ox, 66.5 * S + oy), (94 * S + ox, 66.5 * S + oy)],
                    fill=mix(COLOR, 0.22),
                    width=round(2.4 * S),
                )
            ghost = mix(COLOR, 0.14)
            figure(d, start, ghost, ghost, ox, oy)
            figure(d, end, ghost, ghost, ox, oy)
            head, r = figure(d, lerp(start, end, t), COLOR, COLOR if front else mix(COLOR, 0.45), ox, oy)
            if front:
                for side in (-1, 1):
                    dot(d, (head[0] + side * r * 0.38, head[1] - r * 0.1), r * 0.17, CARD)
            else:
                lx, ly = look[0] + (look_to[0] - look[0]) * t, look[1] + (look_to[1] - look[1]) * t
                n = math.hypot(lx, ly)
                lx, ly = lx / n, ly / n
                dot(d, (head[0] + lx * r * 0.5, head[1] + ly * r * 0.5), r * 0.2, CARD)
                dot(d, (head[0] + lx * r * 1.05, head[1] + ly * r * 1.05), r * 0.32, COLOR)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    print("saved", out, len(moves), "moves", flush=True)


if __name__ == "__main__":
    main()
