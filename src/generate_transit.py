# Builds the Transports (Île-de-France) list on top of generate_maps.py: one map per metro, RER,
# Transilien or tram line, the line in red over the gray lines of its mode, on a frame holding that
# whole mode so the line's place in it is the only clue.
import argparse
import json
import math
import urllib.parse

import numpy as np
from PIL import Image, ImageDraw

from generate_maps import (
    ASSETS,
    DATA,
    LAND,
    MAPS_DIR,
    OVERSEAS_DEPTS,
    PARIS_BORDER,
    PARIS_EDGE,
    RIVER,
    SOURCES,
    SUBURBS,
    SUPERSAMPLE,
    TARGET,
    H,
    Laea,
    Ring,
    View,
    W,
    encode,
    fill,
    load_arrondissements,
    load_french,
    load_paris_water,
    load_rivers,
    outline,
    source,
)

SHEET_PATH = "mockups/transit_sheet.png"
LIST = ("seed_transit_lines.json", "builtin-transit-lines-v1", "Transports (Île-de-France)", 18)
IDF_BOX = "(48.1,1.4,49.25,3.6)"
NETWORK = (150, 150, 150)
ARRONDISSEMENT_EDGE = (200, 193, 180)
MARGIN = 0.06
# Past this many km per pixel the arrondissements are a blur and the OSM water of Paris too small.
CITY_SCALE_KM = 0.1


def overpass(query):
    return "https://overpass-api.de/api/interpreter?data=" + urllib.parse.quote(
        f"[out:json][timeout:180];{query}out geom;"
    )


def lines_of(prefix, answer, refs):
    """{osm ref: (card key, answer)} for one network."""
    return {ref: (f"{prefix}-{ref.lower()}", answer.format(ref)) for ref in refs}


MODES = {
    "metro": {
        "source": "paris_metro.json",
        "query": f'relation["route"="subway"]["network"="Métro de Paris"]{IDF_BOX};',
        "lines": {
            "Métro de Paris": lines_of(
                "metro",
                "Métro {}",
                ["1", "2", "3", "3bis", "4", "5", "6", "7", "7bis"]
                + ["8", "9", "10", "11", "12", "13", "14"],
            )
        },
    },
    "rail": {
        "source": "paris_rail.json",
        "query": f'relation["route"="train"]["network"~"^(RER|Transilien)$"]{IDF_BOX};',
        "lines": {
            "RER": lines_of("rer", "RER {}", ["A", "B", "C", "D", "E"]),
            "Transilien": lines_of("transilien", "Ligne {}", ["H", "J", "K", "L", "N", "P", "R", "U", "V"]),
        },
    },
    "tram": {
        "source": "paris_tram.json",
        "query": f'relation["route"~"^(tram|light_rail)$"]["network"="Tramway d\'Île de France"]{IDF_BOX};',
        "lines": {
            "Tramway d'Île de France": lines_of(
                "tram",
                "{}",
                ["T1", "T2", "T3a", "T3b", "T4", "T5", "T6", "T7"]
                + ["T8", "T9", "T10", "T11", "T12", "T13", "T14"],
            )
        },
    },
}
for mode in MODES.values():
    SOURCES[mode["source"]] = overpass(mode["query"])


def load_lines(mode):
    """{card key: (answer, [Ring])} of each line's tracks, every route relation of the line merged."""
    lines = {}
    for rel in json.loads(source(mode["source"]).read_text())["elements"]:
        tags = rel.get("tags", {})
        known = mode["lines"].get(tags.get("network"), {})
        ref = next((r for r in known if r.lower() == tags.get("ref", "").lower().replace(" ", "")), None)
        if ref is None:
            continue
        key, answer = known[ref]
        for member in rel["members"]:
            if member["type"] == "way" and member.get("role", "") == "" and "geometry" in member:
                coords = [(p["lon"], p["lat"]) for p in member["geometry"]]
                if len(coords) > 1:
                    lines.setdefault(key, (answer, []))[1].append(Ring(coords))
    missing = [key for network in mode["lines"].values() for key, _ in network.values() if key not in lines]
    if missing:
        print(f"no tracks for {', '.join(missing)}", flush=True)
    return lines


def frame(lines):
    proj = Laea(48.86, 2.35)
    pts = np.concatenate([proj.forward(r.lonlat) for _, rings in lines.values() for r in rings])
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    s = max(x1 - x0, y1 - y0) / ((1 - 2 * MARGIN) * W)
    return View(proj, (x0 + x1) / 2, (y0 + y1) / 2, s, W, H)


def draw_tracks(draw, view, rings, color, width):
    for ring in rings:
        draw.line([tuple(p) for p in view.px(ring)], fill=color, width=width, joint="curve")


def draw_water(img, view, data):
    if view.s > CITY_SCALE_KM:
        draw = ImageDraw.Draw(img)
        for river in data["rivers"]:
            draw.line([tuple(p) for p in view.px(river)], fill=RIVER, width=6, joint="curve")
        return
    water = Image.new("L", img.size, 0)
    water_draw = ImageDraw.Draw(water)
    for ext, holes in data["paris_water"]:
        water_draw.polygon(view.px(ext).ravel().tolist(), fill=255)
        for hole in holes:
            water_draw.polygon(view.px(hole).ravel().tolist(), fill=0)
    img.paste(RIVER, (0, 0, *img.size), water)


def render_line(key, lines, data):
    view = frame(lines)
    img = Image.new("RGB", (W * SUPERSAMPLE, H * SUPERSAMPLE), SUBURBS)
    draw = ImageDraw.Draw(img)
    for code, (_, polys) in data["depts"].items():
        if code not in OVERSEAS_DEPTS:
            for ext, _ in polys:
                outline(draw, ext, view.px(ext), PARIS_BORDER, 5)
    fill(draw, view, data["paris"], LAND)
    if view.s <= CITY_SCALE_KM:
        for polys in data["arrondissements"].values():
            for ext, _ in polys:
                outline(draw, ext, view.px(ext), ARRONDISSEMENT_EDGE, 3)
    for ext, _ in data["paris"]:
        outline(draw, ext, view.px(ext), PARIS_EDGE, 8)
    draw_water(img, view, data)
    for other, (_, rings) in lines.items():
        if other != key:
            draw_tracks(draw, view, rings, NETWORK, 6)
    draw_tracks(draw, view, lines[key][1], TARGET, 16)
    return img.resize((W, H), Image.LANCZOS)


def load_data():
    DATA.mkdir(parents=True, exist_ok=True)
    arrondissements, paris = load_arrondissements()
    return {
        "modes": {name: load_lines(mode) for name, mode in MODES.items()},
        "depts": load_french("departements.geojson"),
        "arrondissements": arrondissements,
        "paris": paris,
        "paris_water": load_paris_water(),
        "rivers": load_rivers(),
    }


def write_seed(data):
    filename, list_id, name, order = LIST
    cards = [
        {
            "id": key,
            "listId": list_id,
            "name": "",
            "definition": answer,
            "image": f"asset:maps/{key}.webp",
            "lastReview": 0,
            "randomSide": False,
        }
        for mode_name, mode in MODES.items()
        for network in mode["lines"].values()
        for key, answer in network.values()
        if key in data["modes"][mode_name]
    ]
    bundle = {
        "version": 1,
        "lists": [{"id": list_id, "name": name, "order": order, "fixedSide": True}],
        "cards": cards,
    }
    (ASSETS / filename).write_text(json.dumps(bundle, ensure_ascii=False, indent=1) + "\n")
    print(f"{len(cards)} {name} written", flush=True)


def find(key, data):
    return next(lines for lines in data["modes"].values() if key in lines)


def sheet(keys, data):
    cols = 4
    rows = math.ceil(len(keys) / cols)
    out = Image.new("RGB", (cols * (W + 20), rows * (H + 40)), "#888888")
    for i, key in enumerate(keys):
        lines = find(key, data)
        x, y = (i % cols) * (W + 20) + 10, (i // cols) * (H + 40) + 10
        out.paste(render_line(key, lines, data), (x, y))
        ImageDraw.Draw(out).text((x, y + H + 8), f"{key} {lines[key][0]}", fill="black")
        print(f"{i + 1}/{len(keys)} {key}", flush=True)
    out.save(SHEET_PATH)
    print(SHEET_PATH, flush=True)


def main():
    parser = argparse.ArgumentParser(description="Build the Île-de-France transit flashcard list.")
    parser.add_argument("--sheet", nargs="+", metavar="KEY", help="only render these lines (rer-a, tram-t3a)")
    args = parser.parse_args()
    data = load_data()
    if args.sheet:
        sheet(args.sheet, data)
        return
    MAPS_DIR.mkdir(parents=True, exist_ok=True)
    for name in MODES:
        lines = data["modes"][name]
        for i, key in enumerate(lines, 1):
            (MAPS_DIR / f"{key}.webp").write_bytes(encode(render_line(key, lines, data)))
            print(f"{name} {i}/{len(lines)} {key}", flush=True)
    write_seed(data)


if __name__ == "__main__":
    main()
