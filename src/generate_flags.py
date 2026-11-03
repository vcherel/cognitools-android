import argparse
import hashlib
import io
import json
import math
import random
import string
import tempfile
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ASSETS = Path("app/src/main/assets")
FLAGS_DIR = ASSETS / "flags"
SEED_PATH = ASSETS / "seed_flags.json"
LIST_ID = "builtin-flags-v1"

NOT_COUNTRIES = {"eu", "un"}
# Australia's flag in a slightly different blue, so the duplicate check below misses it.
SAME_FLAG_AS_SOVEREIGN = {"hm"}
# Matched to the names the Capitales list already uses.
RENAMES = {
    "cd": "RD Congo",
    "sv": "El Salvador",
    "af": "Afghanistan (avant 2021)",
    "sx": "Saint-Martin (partie néerlandaise)",
    "bn": "Brunéi",
    "cf": "Centrafrique",
    "cz": "République tchèque",
    "lr": "Libéria",
    "st": "São Tomé-et-Príncipe",
    "sz": "Éswatini",
    # flagcdn files Bonaire's flag under the Caribbean Netherlands, which fly the Dutch one.
    "bq": "Bonaire",
    "eh": "Sahara occidental",
}
# Territories flying their sovereign's flag would be the same card with two answers.
SOVEREIGN_FIRST = ["fr", "no", "us", "au", "gb", "nl"]
# Flags that print their own name, painted over and written again in random letters, so the card
# isn't given away but the flag still looks like itself. Boxes in the w640 image (after reshape),
# with the background they sit on, the ink and the letter height. Guam's letters are split around
# the palm trunk.
NAME_BOXES = {
    "yt": [((145, 32, 495, 102), "white", "#d42e12", 48)],
    "gu": [
        ((283, 159, 314, 182), "#94dbfc", "#bd0728", 12),
        ((323, 159, 358, 182), "#94dbfc", "#bd0728", 12),
    ],
}
# Same for names written around a seal: center, inner and outer radius of the ring, background, ink,
# letter height, and the arcs the text ran along (from and to angle, in degrees clockwise from
# 3 o'clock; read clockwise the letters stand outward, read anticlockwise they stand inward).
NAME_RINGS = {
    "ni": ((319.5, 213), 54.5, 67, "white", "#cfb022", 7, [(180, 375), (150, 35)]),
    "py": ((319.5, 176), 28.6, 36.4, "white", "black", 5, [(145, 395)]),
    "sv": ((319, 216), 57, 69, "white", "#ffc900", 9, [(112, 428)]),
}
FONT = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
# Drawn this many times bigger, then scaled down, so the small letters keep smooth edges.
SUPERSAMPLE = 4
# Look-alikes whose official proportions would give the answer away, all brought to 3:2 so only
# their colors and emblems tell them apart. Indonesia and Monaco keep theirs: it is their only
# difference.
SAME_SHAPE = {
    "ie",  # Côte d'Ivoire
    "md",  # Andorra, Romania, Chad
    "ad",
    "lu",  # Netherlands
    "si",  # Slovakia, Russia
    "qa",  # Bahrain
    "bh",
    "lv",  # Austria
    "hn",  # El Salvador, Nicaragua
    "sv",
    "ni",
    "cu",  # Puerto Rico
    "cr",  # Thailand
    "lt",  # Bolivia
    "bo",
    "ga",  # Sierra Leone
}


# Flags flagcdn lacks, each its own card, from Wikimedia Commons. Outlined, since a white flag
# disappears on the card.
EXTRA_FLAGS = {
    "af-taliban": (
        "https://commons.wikimedia.org/wiki/Special:FilePath/Flag_of_the_Taliban.svg?width=640",
        "Afghanistan (talibans)",
    ),
}


def extra_flag(url):
    image = Image.open(io.BytesIO(fetch(url))).convert("RGBA")
    image = image.resize((640, round(640 * image.height / image.width)))
    ImageDraw.Draw(image).rectangle((0, 0, image.width - 1, image.height - 1), outline="#bbbbbb", width=2)
    return image


def card(code, name):
    return {
        "id": f"flag-{code}",
        "listId": LIST_ID,
        "name": "",
        "definition": name,
        "image": f"asset:flags/{code}.webp",
        "lastReview": 0,
        "randomSide": False,
    }


def font_for(height):
    """The font whose capitals stand [height] pixels tall, with that cap height."""
    probe = ImageFont.truetype(FONT, 100)
    top, bottom = probe.getbbox("H", anchor="ls")[1::2]
    return ImageFont.truetype(FONT, round(100 * height / (bottom - top))), height


def gibberish(rng, font, width, spaced):
    """Random capitals filling [width], in words of a few letters when [spaced]."""
    text, word = "", rng.randint(2, 8)
    while True:
        if spaced and word == 0:
            char, word = " ", rng.randint(2, 8)
        else:
            char, word = rng.choice(string.ascii_uppercase), word - 1
        if font.getlength(text + char) > width:
            return text.strip()
        text += char


def write_on_arc(layer, rng, center, radius, arc, height, ink):
    font, cap = font_for(height * SUPERSAMPLE)
    start, end = arc
    clockwise = end > start
    text = gibberish(rng, font, math.radians(abs(end - start)) * radius * SUPERSAMPLE, spaced=True)
    total = font.getlength(text)
    middle = (start + end) / 2
    side = 2 * font.size
    for i, char in enumerate(text):
        offset = font.getlength(text[:i]) + font.getlength(char) / 2 - total / 2
        angle = middle + math.degrees(offset / (radius * SUPERSAMPLE)) * (1 if clockwise else -1)
        glyph = Image.new("RGBA", (side, side))
        ImageDraw.Draw(glyph).text((side / 2, side / 2 + cap / 2), char, font=font, fill=ink, anchor="ms")
        glyph = glyph.rotate((270 if clockwise else 90) - angle, Image.BICUBIC)
        x = SUPERSAMPLE * (center[0] + radius * math.cos(math.radians(angle)))
        y = SUPERSAMPLE * (center[1] + radius * math.sin(math.radians(angle)))
        layer.alpha_composite(glyph, (round(x - side / 2), round(y - side / 2)))


def hide_name(image, code):
    """Paints over the name a flag prints and writes random letters in its place."""
    if code not in NAME_BOXES and code not in NAME_RINGS:
        return image
    rng = random.Random(code)
    layer = Image.new("RGBA", (image.width * SUPERSAMPLE, image.height * SUPERSAMPLE))
    for box, fill, ink, height in NAME_BOXES.get(code, []):
        ImageDraw.Draw(image).rectangle(box, fill=fill)
        font, cap = font_for(height * SUPERSAMPLE)
        text = gibberish(rng, font, 0.92 * (box[2] - box[0]) * SUPERSAMPLE, spaced=False)
        middle = (SUPERSAMPLE * (box[0] + box[2]) / 2, SUPERSAMPLE * (box[1] + box[3]) / 2 + cap / 2)
        ImageDraw.Draw(layer).text(middle, text, font=font, fill=ink, anchor="ms")
    if code in NAME_RINGS:
        (cx, cy), inner, outer, fill, ink, height, arcs = NAME_RINGS[code]
        mask = Image.new("L", layer.size, 0)
        draw = ImageDraw.Draw(mask)
        for start, end in arcs:
            low, high = min(start, end) - 4, max(start, end) + 4
            draw.pieslice(
                [
                    SUPERSAMPLE * (cx - outer),
                    SUPERSAMPLE * (cy - outer),
                    SUPERSAMPLE * (cx + outer),
                    SUPERSAMPLE * (cy + outer),
                ],
                low,
                high,
                fill=255,
            )
        draw.ellipse(
            [
                SUPERSAMPLE * (cx - inner),
                SUPERSAMPLE * (cy - inner),
                SUPERSAMPLE * (cx + inner),
                SUPERSAMPLE * (cy + inner),
            ],
            fill=0,
        )
        image.paste(fill, (0, 0, image.width, image.height), mask.resize(image.size, Image.LANCZOS))
        for arc in arcs:
            write_on_arc(layer, rng, (cx, cy), (inner + outer) / 2, arc, height, ink)
    return Image.alpha_composite(image, layer.resize(image.size, Image.LANCZOS))


def plain_lines(image, axis):
    """Indices of the columns (axis 0) or rows (axis 1) identical to the one before: plain stripe."""
    count = image.width if axis == 0 else image.height
    span = image.height if axis == 0 else image.width

    def line(i):
        box = (i, 0, i + 1, span) if axis == 0 else (0, i, span, i + 1)
        return image.crop(box).tobytes()

    previous = line(0)
    plain = []
    for i in range(1, count):
        current = line(i)
        if current == previous:
            plain.append(i)
        previous = current
    return plain


def lines_to_keep(count, plain, target):
    """Which of [count] lines to keep so that [target] remain, dropping only [plain] ones.

    Kept so that every line lands as close as possible to where a plain stretch would put it: the
    stripes keep their proportions, and the room an emblem cannot give up is shared on both sides.
    Searches the smallest tolerance a valid choice exists for, then walks back through it.
    """
    scale = target / count

    def reachable(tolerance):
        bounds = [(0, 0)]
        for x in range(count):
            low, high = bounds[-1]
            low += 0 if x in plain else 1
            high += 1
            ideal = scale * (x + 1)
            low, high = max(low, math.ceil(ideal - tolerance)), min(high, math.floor(ideal + tolerance))
            if low > high:
                return None
            bounds.append((low, high))
        return bounds if bounds[-1][0] <= target <= bounds[-1][1] else None

    low, high = 0.0, float(count)
    while high - low > 0.25:
        middle = (low + high) / 2
        low, high = (low, middle) if reachable(middle) else (middle, high)
    bounds = reachable(high)
    kept, current = [], target
    for x in range(count - 1, -1, -1):
        before_low, before_high = bounds[x]
        can_drop = x in plain and before_low <= current <= before_high
        can_keep = before_low <= current - 1 <= before_high
        if can_drop and (not can_keep or abs(current - scale * x) <= abs(current - 1 - scale * x)):
            continue
        kept.append(x)
        current -= 1
    return kept[::-1]


def reshape(image):
    """Brings a flag to 3:2 by dropping plain stripe columns or rows, so its emblem keeps its shape.

    When the stripes run out first, the flag stays as close to 3:2 as they allow.
    """
    axis = 0 if image.width > image.height * 3 / 2 else 1
    count = image.width if axis == 0 else image.height
    plain = set(plain_lines(image, axis))
    target = round(image.height * 3 / 2) if axis == 0 else round(image.width * 2 / 3)
    kept = lines_to_keep(count, plain, max(target, count - len(plain)))
    out = Image.new(image.mode, (len(kept), image.height) if axis == 0 else (image.width, len(kept)))
    for j, i in enumerate(kept):
        if axis == 0:
            out.paste(image.crop((i, 0, i + 1, image.height)), (j, 0))
        else:
            out.paste(image.crop((0, i, image.width, i + 1)), (0, j))
    return out.resize((640, round(640 * out.height / out.width)), Image.LANCZOS)


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "cognitools-flags"})
    with urllib.request.urlopen(request) as response:
        return response.read()


def flagcdn(code, width):
    return Image.open(io.BytesIO(fetch(f"https://flagcdn.com/w{width}/{code}.png"))).convert("RGBA")


def render(code, image):
    """The card picture for [code], from its w640 [image]."""
    if code in EXTRA_FLAGS:
        return extra_flag(EXTRA_FLAGS[code][0])
    if code in SAME_SHAPE:
        # From the bigger render: dropping stripes then scaling back down keeps the emblem sharp.
        image = reshape(flagcdn(code, 1280))
    return hide_name(image, code)


def save(image, code):
    image.save(FLAGS_DIR / f"{code}.webp", "WEBP", quality=88, method=6)


def sheet(codes):
    """Shipped picture on the left, a fresh render on the right; nothing written to the assets."""
    tile_w, tile_h = 660, 470
    out = Image.new("RGB", (tile_w * 2, tile_h * len(codes)), "#888888")
    draw = ImageDraw.Draw(out)
    for i, code in enumerate(codes):
        shipped = FLAGS_DIR / f"{code}.webp"
        fresh = render(code, None if code in EXTRA_FLAGS else flagcdn(code, 640))
        for column, image in enumerate([Image.open(shipped) if shipped.exists() else None, fresh]):
            x, y = column * tile_w, i * tile_h
            label = "shipped" if column == 0 else "regenerated"
            if image is None:
                draw.text((x + 12, y + 12), f"{code}: no shipped picture", fill="black")
                continue
            out.paste(image.convert("RGB"), (x + 10, y + 10))
            draw.text((x + 12, y + 450), f"{code} {label} {image.width}x{image.height}", fill="black")
        print(f"{i + 1}/{len(codes)} {code}", flush=True)
    path = Path(tempfile.mkdtemp(prefix="flags_sheet_")) / "sheet.png"
    out.save(path)
    print(path, flush=True)


def main():
    parser = argparse.ArgumentParser(description="Build the Drapeaux flashcards list from flagcdn.")
    parser.add_argument("--sheet", nargs="+", metavar="CODE", help="only render these flags side by side")
    parser.add_argument("--write", nargs="+", metavar="CODE", help="only write these flags' pictures")
    args = parser.parse_args()
    if args.sheet:
        sheet(args.sheet)
        return
    if args.write:
        for i, code in enumerate(args.write, 1):
            save(render(code, None if code in EXTRA_FLAGS else flagcdn(code, 640)), code)
            print(f"{i}/{len(args.write)} {code}", flush=True)
        return

    names = json.loads(fetch("https://flagcdn.com/fr/codes.json"))
    codes = sorted(c for c in names if "-" not in c and c not in NOT_COUNTRIES | SAME_FLAG_AS_SOVEREIGN)
    codes.sort(key=lambda c: c not in SOVEREIGN_FIRST)
    FLAGS_DIR.mkdir(parents=True, exist_ok=True)

    seen = {}
    cards = []
    for i, code in enumerate(codes, 1):
        image = flagcdn(code, 640)
        digest = hashlib.sha1(image.tobytes()).hexdigest()
        name = RENAMES.get(code, names[code])
        if digest in seen:
            print(f"{i}/{len(codes)} {code} skipped, same flag as {seen[digest]}", flush=True)
            continue
        seen[digest] = code
        save(render(code, image), code)
        cards.append(card(code, name))
        print(f"{i}/{len(codes)} {code} {name}", flush=True)
    for code, (url, name) in EXTRA_FLAGS.items():
        save(extra_flag(url), code)
        cards.append(card(code, name))
        print(f"{code} {name}", flush=True)

    cards.sort(key=lambda c: c["definition"])
    bundle = {
        "version": 1,
        "lists": [{"id": LIST_ID, "name": "Drapeaux", "order": 3, "fixedSide": True}],
        "cards": cards,
    }
    SEED_PATH.write_text(json.dumps(bundle, ensure_ascii=False, indent=1) + "\n")
    print(f"{len(cards)} flags written", flush=True)


if __name__ == "__main__":
    main()
