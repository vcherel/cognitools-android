# Builds the Mers et océans (carte) list on top of generate_maps.py: each card a map with one ocean,
# sea or gulf in blue, from Natural Earth's marine polygons.
import argparse
import json
import math
import re

import numpy as np
import shapely
from PIL import Image, ImageDraw, ImageFilter
from shapely.ops import unary_union

from generate_maps import (
    ASSETS,
    MAPS_DIR,
    SEA,
    SUPERSAMPLE,
    H,
    Laea,
    Ring,
    View,
    W,
    draw_map,
    encode,
    load_data,
    read_shapes,
)

SEED = ASSETS / "seed_sea_maps.json"
LIST_ID = "builtin-sea-maps-v1"
LIST_NAME = "Mers et océans (carte)"
ORDER = 17
SHEET_PATH = "mockups/seas_sheet.png"
# The oceans, merged from their northern and southern halves (and the Sargasso Sea, a hole in the
# Atlantic otherwise), each on a map of its own: (lat, lon, width in km).
OCEANS = {
    "atlantique": (
        "Océan Atlantique",
        ["North Atlantic Ocean", "South Atlantic Ocean", "Sargasso Sea"],
        (8, -32, 17000),
    ),
    "pacifique": ("Océan Pacifique", ["North Pacific Ocean", "South Pacific Ocean"], (2, -165, 22000)),
    "indien": ("Océan Indien", ["INDIAN OCEAN"], (-22, 78, 15000)),
    "arctique": ("Océan Arctique", ["Arctic Ocean"], (90, 0, 8500)),
    "austral": ("Océan Austral", ["SOUTHERN OCEAN"], (-90, 0, 10000)),
}
# Natural Earth cuts the Mediterranean into its smaller seas: its card shows them all.
MEDITERRANEAN = [
    "Mediterranean Sea",
    "Tyrrhenian Sea",
    "Adriatic Sea",
    "Ionian Sea",
    "Aegean Sea",
    "Balearic Sea",
    "Ligurian Sea",
    "Alboran Sea",
    "Sea of Crete",
    "Gulf of Sidra",
    "Gulf of Gabès",
    "Golfe du Lion",
]
SEA_RANK = 4
# How close, in degrees, two pieces of sea must come to count as joined.
TOUCH_DEG = 0.05
# Closed off from the oceans, so part of none.
LANDLOCKED = {"Caspian Sea", "Garabogaz Bay"}
SKIPPED_SEAS = {"Inner Seas", "Inner Sea", "Sargasso Sea", "Sea of Crete", "Ligurian Sea", "Alboran Sea"}
# The gulfs, bays and channels well known enough to ask beside the seas.
GULFS = [
    "Gulf of Mexico",
    "Persian Gulf",
    "Bay of Bengal",
    "Hudson Bay",
    "Baffin Bay",
    "Gulf of Guinea",
    "Bay of Biscay",
    "English Channel",
    "Gulf of Aden",
    "Gulf of Bothnia",
    "Gulf of Alaska",
    "Gulf of Thailand",
    "Mozambique Channel",
]
# A sea fills this share of its map, which never spans less, so a coast around it shows.
FILL = 0.6
MIN_KM = 2600
HIGHLIGHT = (46, 104, 170)
# How far, in pixels, the blue fades out where the sea ends in open water.
FADE_PX = 24


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def french(name):
    return name[:1].upper() + name[1:]


def catalog():
    """{card key: (answer, geometry, frame or None)} of every ocean, sea and gulf asked."""
    shapes = read_shapes("ne_10m_geography_marine_polys.zip")
    marine = {}
    for rec, geom in shapes:
        if rec["name"]:
            marine.setdefault(rec["name"], []).append((rec, geom))

    def merged(names):
        return unary_union([g.buffer(0) for n in names for _, g in marine[n]])

    bases = {key: merged(names) for key, (_, names, _) in OCEANS.items()}
    taken = {n for _, names, _ in OCEANS.values() for n in names} | LANDLOCKED
    # Natural Earth's oceans leave their marginal seas out, which would end them in open water
    # anywhere: each other piece of sea joins the ocean it opens onto, through its neighbours (the
    # Black Sea through the Mediterranean), the one it shares the most water with when it touches two.
    parts = {key: [geom] for key, geom in bases.items()}
    left = [geom.buffer(0) for rec, geom in shapes if rec["name"] not in taken]
    while left:
        groups = {key: unary_union(pieces) for key, pieces in parts.items()}
        joined = []
        for geom in left:
            reach = geom.buffer(TOUCH_DEG)
            shared = {key: reach.intersection(group).area for key, group in groups.items()}
            best = max(shared, key=shared.get)
            if shared[best] > 0:
                joined.append((best, geom))
        if not joined:
            break
        for key, geom in joined:
            parts[key].append(geom)
        left = [g for g in left if all(g is not j for _, j in joined)]
    for geom in left:
        point = geom.representative_point()
        parts[min(bases, key=lambda k: bases[k].distance(point))].append(geom)
    cards = {key: (name, unary_union(parts[key]), frame) for key, (name, _, frame) in OCEANS.items()}
    seas = [n for n, entries in marine.items() if any(r["featurecla"] == "sea" for r, _ in entries)]
    for name in sorted(seas) + GULFS:
        rec = marine[name][0][0]
        if name in SKIPPED_SEAS or rec["scalerank"] > SEA_RANK:
            continue
        parts = MEDITERRANEAN if name == "Mediterranean Sea" else [name]
        cards[slug(name)] = (french(rec["name_fr"]), merged(parts), None)
    return cards


def sea_view(geom, frame):
    if frame is not None:
        lat, lon, width = frame
        return View(Laea(lat, lon), 0, 0, width / W)
    coords = np.concatenate(
        [np.asarray(p.exterior.coords) for p in getattr(geom, "geoms", [geom]) if not p.is_empty]
    )
    proj = Laea(*Ring(coords).latlon())
    pts = proj.forward(coords)
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    s = max((x1 - x0) / (FILL * W), (y1 - y0) / (FILL * H), MIN_KM / W)
    return View(proj, (x0 + x1) / 2, (y0 + y1) / 2, s)


def water_mask(view, geom):
    """The pixels of the supersampled view inside [geom], tested at the view's own resolution."""
    xs = view.cx + (np.arange(view.w) + 0.5 - view.w / 2) * view.s
    ys = view.cy + (view.h / 2 - np.arange(view.h) - 0.5) * view.s
    gx, gy = np.meshgrid(xs, ys)
    km = np.column_stack([gx.ravel(), gy.ravel()])
    lonlat = view.proj.inverse(km)
    # Past the far side of the globe the projection folds back onto itself.
    inside = np.hypot(km[:, 0], km[:, 1]) < 1.99 * 6371
    shapely.prepare(geom)
    inside &= shapely.contains_xy(geom, lonlat[:, 0], lonlat[:, 1])
    mask = Image.fromarray((inside.reshape(view.h, view.w) * 255).astype(np.uint8))
    size = (view.w * SUPERSAMPLE, view.h * SUPERSAMPLE)
    return np.asarray(mask.resize(size, Image.BILINEAR)) >= 128


def render(geom, frame, data):
    """
    The sea in deep blue on a map of coasts only. Natural Earth closes a sea with straight lines across
    open water, so the blue fades out there, while the coasts cut it sharp.
    """
    view = sea_view(geom, frame)
    img = draw_map(view, [], data["world"], data["lakes"], border=SUPERSAMPLE, borders=False)
    sea = np.all(np.asarray(img) == SEA, axis=2)
    # Land counts as inside, so the blue runs right up to the coasts and fades only into open water.
    polygon = water_mask(view, geom)
    blur = ImageFilter.GaussianBlur(FADE_PX * SUPERSAMPLE)
    fade = np.asarray(Image.fromarray(((polygon | ~sea) * 255).astype(np.uint8)).filter(blur), dtype=float)
    # The lakes and seas away from this one stay plain, though land surrounds them too.
    own = np.asarray(Image.fromarray((polygon * 255).astype(np.uint8)).filter(blur), dtype=float)
    near = np.clip(own / 255 * 3, 0, 1)
    t = np.clip(fade / 255 * 1.4 - 0.4, 0, 1) * near
    alpha = np.where(sea, t * t * (3 - 2 * t) * 255, 0).astype(np.uint8)
    img.paste(HIGHLIGHT, (0, 0, *img.size), Image.fromarray(alpha))
    return img.resize((W, H), Image.LANCZOS)


def write_seed(cards):
    seed = [
        {
            "id": f"seamap-{key}",
            "listId": LIST_ID,
            "name": "",
            "definition": answer,
            "image": f"asset:maps/sea-{key}.webp",
            "lastReview": 0,
            "randomSide": False,
        }
        for key, (answer, _, _) in cards.items()
    ]
    bundle = {
        "version": 1,
        "lists": [{"id": LIST_ID, "name": LIST_NAME, "order": ORDER, "fixedSide": True}],
        "cards": sorted(seed, key=lambda c: c["definition"]),
    }
    SEED.write_text(json.dumps(bundle, ensure_ascii=False, indent=1) + "\n")
    print(f"{len(seed)} seas written", flush=True)


def sheet(keys, cards, data):
    cols = 3
    rows = math.ceil(len(keys) / cols)
    out = Image.new("RGB", (cols * (W + 20), rows * (H + 40)), "#888888")
    for i, key in enumerate(keys):
        answer, geom, frame = cards[key]
        x, y = (i % cols) * (W + 20) + 10, (i // cols) * (H + 40) + 10
        out.paste(render(geom, frame, data), (x, y))
        ImageDraw.Draw(out).text((x, y + H + 8), f"{key} {answer}", fill="black")
        print(f"{i + 1}/{len(keys)} {key}", flush=True)
    out.save(SHEET_PATH)
    print(SHEET_PATH, flush=True)


def main():
    parser = argparse.ArgumentParser(description="Build the Mers et océans (carte) list.")
    parser.add_argument("--sheet", nargs="+", metavar="KEY", help="only render these cards side by side")
    parser.add_argument("--list", action="store_true", help="only print every card key and answer")
    args = parser.parse_args()
    cards = catalog()
    if args.list:
        print(" ".join(f"{k}={a}" for k, (a, _, _) in cards.items()), flush=True)
        return
    data = load_data()
    if args.sheet:
        sheet(args.sheet, cards, data)
        return
    MAPS_DIR.mkdir(parents=True, exist_ok=True)
    for i, (key, (_, geom, frame)) in enumerate(cards.items(), 1):
        (MAPS_DIR / f"sea-{key}.webp").write_bytes(encode(render(geom, frame, data)))
        print(f"{i}/{len(cards)} {key}", flush=True)
    write_seed(cards)


if __name__ == "__main__":
    main()
