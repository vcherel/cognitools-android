import argparse
import gzip
import io
import json
import math
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
import shapefile
from PIL import Image, ImageDraw
from shapely.geometry import LineString, Polygon, shape
from shapely.ops import polygonize, unary_union

ASSETS = Path("app/src/main/assets")
MAPS_DIR = ASSETS / "maps"
FLAGS_SEED = ASSETS / "seed_flags.json"
COUNTRY_SEED = ASSETS / "seed_maps.json"
DEPT_SEED = ASSETS / "seed_dept_maps.json"
REGION_SEED = ASSETS / "seed_region_maps.json"
ARRONDISSEMENT_SEED = ASSETS / "seed_arrondissement_maps.json"
DATA = Path("data/maps")
SHEET_PATH = Path("mockups/maps_sheet.png")
SOURCES = {
    "ne_10m_admin_0_map_units.zip": "https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_0_map_units.zip",
    "ne_50m_lakes.zip": "https://naciscdn.org/naturalearth/50m/physical/ne_50m_lakes.zip",
    "departements.geojson": "https://raw.githubusercontent.com/gregoiredavid/france-geojson/master/departements-avec-outre-mer.geojson",
    "regions.geojson": "https://raw.githubusercontent.com/gregoiredavid/france-geojson/master/regions-avec-outre-mer.geojson",
    "arrondissements.geojson": "https://opendata.paris.fr/api/explore/v2.1/catalog/datasets/arrondissements/exports/geojson",
    "paris_water.json": "https://overpass-api.de/api/interpreter?data="
    + urllib.parse.quote(
        '[out:json][timeout:60];(way["natural"="water"]["water"="river"](48.65,2.05,49.05,2.68);'
        'relation["natural"="water"]["water"="river"](48.65,2.05,49.05,2.68););out geom;'
    ),
}

R = 6371.0
W, H = 800, 800
SS = 3
SEA = (214, 230, 240)
LAND = (228, 224, 214)
BORDER = (130, 124, 114)
TARGET = (214, 64, 46)
TARGET_EDGE = (150, 36, 24)
INSET_EDGE = (110, 110, 110)
SUBURBS = (244, 242, 237)
PARIS_BORDER = (176, 169, 156)
PARIS_EDGE = (120, 113, 102)
RIVER = (140, 188, 222)
# The fixed map a place is shown on, (lat, lon, width in km): the first holding all of it, so every
# country of a continent sits on the same page of the atlas.
FRAMES = (
    (17, -74, 4600),  # Caribbean
    (18, 80, 5000),  # South Asia
    (54, 8, 5000),  # Europe
    (34, 52, 6000),  # Middle East
    (22, 110, 8400),  # East Asia
    (-22, -60, 8800),  # South America
    (50, -98, 9000),  # North America
    (0, 18, 10000),  # Africa
    (-20, 75, 8000),  # Indian Ocean
    (-42, -45, 8000),  # South Atlantic
    (-10, -178, 12000),  # Pacific
)
# A place no page holds gets its own map, its extent filling this share of it, never narrower.
MAX_FILL = 0.7
OWN_FRAME_MIN_KM = 3000
# A target at least this many pixels across reads on the page, a smaller one gets a lens.
READS_PX = 90
LENS = 280
# The lens never spans less, so it holds a coast or a border and not a lone blob.
LENS_MIN_KM = 220
LENS_FILL = 0.55
# Pixels across under which a target gets a circle around it, a Paris department the zoom inset.
TINY_PX = 16
PARIS_INSET_PX = 22

# Map units not drawn as anyone's: disputed spots, bases, reefs. They stay plain land.
PLAIN_UNITS = {
    "KAS",
    "ESB",
    "WSB",
    "USG",
    "BRI",
    "SPI",
    "BRT",
    "PGA",
    "BJN",
    "SER",
    "SCR",
    "CNM",
    "PFA",
    "CLP",
    "CSI",
    "ATC",
}
# Map units counted with the country that claims them.
UNIT_CODES = {"CYN": "cy", "SOL": "so", "KNX": "kr", "KNZ": "kp", "SYU": "sy"}
NAMES = {"af": "Afghanistan", "bq": "Pays-Bas caribéens"}
SKIPPED_FLAGS = {"af-taliban"}
OVERSEAS_DEPTS = {"971": "gp", "972": "mq", "973": "gf", "974": "re", "976": "yt"}
OVERSEAS_REGIONS = {"01": "gp", "02": "mq", "03": "gf", "04": "re", "06": "yt"}
PARIS = (2.35, 48.86)
PARIS_HEIGHT = 540


def to_xyz(lonlat):
    lon, lat = np.radians(lonlat[:, 0]), np.radians(lonlat[:, 1])
    return np.column_stack([np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)])


class Ring:
    def __init__(self, coords):
        self.lonlat = np.asarray(coords, dtype=float)[:, :2]
        xyz = to_xyz(self.lonlat)
        center = xyz.mean(0)
        norm = np.linalg.norm(center)
        self.center = center / norm if norm > 1e-9 else xyz[0]
        self.radius = np.arccos(np.clip(xyz @ self.center, -1, 1)).max()
        lat = np.radians(self.lonlat[:, 1].mean())
        x, y = self.lonlat[:, 0] * math.cos(lat), self.lonlat[:, 1]
        self.area = abs(np.dot(x, np.roll(y, 1)) - np.dot(y, np.roll(x, 1))) / 2

    def latlon(self):
        x, y, z = self.center
        return math.degrees(math.asin(z)), math.degrees(math.atan2(y, x))


def polygons(geom):
    """[(exterior, holes)] of a shapely (multi)polygon, as Rings."""
    parts = (
        [geom]
        if geom.geom_type == "Polygon"
        else [g for g in getattr(geom, "geoms", []) if g.geom_type == "Polygon"]
    )
    return [(Ring(p.exterior.coords), [Ring(i.coords) for i in p.interiors]) for p in parts if not p.is_empty]


class Laea:
    """Lambert azimuthal equal area centered on (lat, lon), in km."""

    def __init__(self, lat, lon):
        self.lat, self.lon = lat, lon
        self.sin1, self.cos1 = math.sin(math.radians(lat)), math.cos(math.radians(lat))
        self.center = to_xyz(np.array([[lon, lat]]))[0]

    def forward(self, lonlat):
        lam = np.radians(lonlat[:, 0] - self.lon)
        phi = np.radians(lonlat[:, 1])
        cosc = self.sin1 * np.sin(phi) + self.cos1 * np.cos(phi) * np.cos(lam)
        k = np.sqrt(2 / np.maximum(1 + cosc, 1e-9))
        x = R * k * np.cos(phi) * np.sin(lam)
        y = R * k * (self.cos1 * np.sin(phi) - self.sin1 * np.cos(phi) * np.cos(lam))
        return np.column_stack([x, y])

    def inverse(self, xy):
        x, y = xy[:, 0], xy[:, 1]
        rho = np.hypot(x, y)
        c = 2 * np.arcsin(np.clip(rho / (2 * R), 0, 1))
        sinc, cosc = np.sin(c), np.cos(c)
        safe = np.where(rho > 0, rho, 1)
        lat = np.arcsin(np.clip(cosc * self.sin1 + np.where(rho > 0, y * sinc * self.cos1 / safe, 0), -1, 1))
        lon = math.radians(self.lon) + np.arctan2(x * sinc, rho * self.cos1 * cosc - y * self.sin1 * sinc)
        return np.column_stack([(np.degrees(lon) + 180) % 360 - 180, np.degrees(lat)])


class View:
    """A [w] x [h] pixel window on [proj], centered on (cx, cy) km, [s] km per pixel."""

    def __init__(self, proj, cx, cy, s, w=W, h=H):
        self.proj, self.cx, self.cy, self.s, self.w, self.h = proj, cx, cy, s, w, h
        center = proj.inverse(np.array([[cx, cy]]))
        self.center = to_xyz(center)[0]
        self.reach = math.hypot(w, h) / 2 * s / R + 0.02

    def shows(self, ring):
        angle = math.acos(max(-1.0, min(1.0, float(ring.center @ self.center))))
        far = math.acos(max(-1.0, min(1.0, float(ring.center @ self.proj.center))))
        return angle - ring.radius < self.reach and far + ring.radius < math.radians(150)

    def km_to_px(self, xy):
        return np.column_stack(
            [
                ((xy[:, 0] - self.cx) / self.s + self.w / 2) * SS,
                (self.h / 2 - (xy[:, 1] - self.cy) / self.s) * SS,
            ]
        )

    def px(self, ring):
        return self.km_to_px(self.proj.forward(ring.lonlat))

    def on_canvas(self, pts):
        return (
            pts[:, 0].max() >= 0
            and pts[:, 1].max() >= 0
            and pts[:, 0].min() <= self.w * SS
            and pts[:, 1].min() <= self.h * SS
        )


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "cognitools-maps"})
    with urllib.request.urlopen(request) as response:
        body = response.read()
    # Paris open data gzips its export even to a client that never asked for it.
    return gzip.decompress(body) if body[:2] == b"\x1f\x8b" else body


def source(name):
    path = DATA / name
    if not path.exists():
        DATA.mkdir(parents=True, exist_ok=True)
        print(f"downloading {name}", flush=True)
        path.write_bytes(fetch(SOURCES[name]))
    return path


def read_shapes(name):
    archive = zipfile.ZipFile(source(name))
    stem = name.removesuffix(".zip")
    reader = shapefile.Reader(
        shp=io.BytesIO(archive.read(f"{stem}.shp")),
        dbf=io.BytesIO(archive.read(f"{stem}.dbf")),
        shx=io.BytesIO(archive.read(f"{stem}.shx")),
        encoding="utf-8",
    )
    return [
        (rec.as_dict(), shape(shp.__geo_interface__))
        for rec, shp in zip(reader.records(), reader.shapes(), strict=True)
    ]


def unit_code(rec):
    unit = rec["GU_A3"]
    if unit in PLAIN_UNITS:
        return None
    if unit in UNIT_CODES:
        return UNIT_CODES[unit]
    if rec["ADM0_A3"] == "UMI":
        return "um"
    code = rec["ISO_A2_EH"]
    return None if code == "-99" else code.lower()


def load_world():
    """{code: [(exterior, holes)]} with every map unit of a country merged, plain land under None keys."""
    grouped, plain = {}, []
    for rec, geom in read_shapes("ne_10m_admin_0_map_units.zip"):
        code = unit_code(rec)
        if code is None:
            plain.append(geom)
        else:
            grouped.setdefault(code, []).append(geom)
    world = {}
    for code, geoms in grouped.items():
        geom = geoms[0] if len(geoms) == 1 else unary_union([g.buffer(0) for g in geoms])
        world[code] = polygons(geom)
    world[None] = [p for g in plain for p in polygons(g)]
    return world


def load_lakes():
    return [
        Ring(p.exterior.coords) for _, g in read_shapes("ne_50m_lakes.zip") for p in getattr(g, "geoms", [g])
    ]


def load_french(name):
    """{code: (name, polygons)} of the departments or regions file."""
    data = json.loads(source(name).read_text())
    return {
        f["properties"]["code"]: (f["properties"]["nom"], polygons(shape(f["geometry"])))
        for f in data["features"]
    }


def load_arrondissements():
    """{number: polygons} of the Paris arrondissements, and the polygons of Paris as a whole."""
    data = json.loads(source("arrondissements.geojson").read_text())
    shapes = {f["properties"]["c_ar"]: shape(f["geometry"]) for f in data["features"]}
    # The buffer closes the slivers between neighbours, which would draw as an inner edge.
    whole = unary_union([s.buffer(1e-5) for s in shapes.values()])
    return {n: polygons(s) for n, s in shapes.items()}, polygons(whole)


def load_paris_water():
    """The polygons of the rivers around Paris, from OpenStreetMap's ways and multipolygons."""
    shapes = []
    for element in json.loads(source("paris_water.json").read_text())["elements"]:
        if element["type"] == "way":
            coords = [(p["lon"], p["lat"]) for p in element["geometry"]]
            if len(coords) > 3 and coords[0] == coords[-1]:
                shapes.append(Polygon(coords).buffer(0))
            continue
        rings = {"outer": [], "inner": []}
        for member in element["members"]:
            if member["type"] == "way" and member.get("role") in rings and "geometry" in member:
                rings[member["role"]].append(LineString([(p["lon"], p["lat"]) for p in member["geometry"]]))
        outer = unary_union(list(polygonize(rings["outer"])))
        shapes.append(outer.difference(unary_union(list(polygonize(rings["inner"])))))
    # Shrunk then grown back, which drops the canals: thin stray lines at this scale.
    water = polygons(unary_union(shapes).buffer(-2e-4).buffer(2e-4))
    return [(ext, holes) for ext, holes in water if ext.area > 2e-6]


def outline(draw, ring, pts, color, width):
    """The ring's edge, minus the seams Natural Earth cuts along the antimeridian and the pole."""
    lon, lat = ring.lonlat[:, 0], ring.lonlat[:, 1]
    seam = (np.abs(lon) > 179.99) | (lat < -89.99)
    cut = seam[:-1] & seam[1:]
    start = 0
    for i in list(np.nonzero(cut)[0]) + [len(pts) - 1]:
        if i - start >= 1:
            draw.line(pts[start : i + 1].ravel().tolist(), fill=color, width=width, joint="curve")
        start = i + 1


def draw_map(view, target, world, lakes, layers=(), border=2 * SS):
    """The view supersampled, every land gray, [target] red."""
    img = Image.new("RGB", (view.w * SS, view.h * SS), SEA)
    draw = ImageDraw.Draw(img)
    edges = []
    for features in [world, *layers]:
        for polys in features.values():
            for ext, holes in polys:
                if not view.shows(ext):
                    continue
                pts = view.px(ext)
                if not view.on_canvas(pts):
                    continue
                draw.polygon(pts.ravel().tolist(), fill=LAND)
                edges.append((ext, pts))
                edges += [(h, view.px(h)) for h in holes if view.shows(h)]
    lake_pts = [view.px(lake) for lake in lakes if view.shows(lake)]
    lake_pts = [pts for pts in lake_pts if view.on_canvas(pts)]
    for pts in lake_pts:
        draw.polygon(pts.ravel().tolist(), fill=SEA)
    for ring, pts in edges:
        outline(draw, ring, pts, BORDER, border)

    mask = Image.new("L", img.size, 0)
    mask_draw = ImageDraw.Draw(mask)
    target_edges = []
    for ext, holes in target:
        if not view.shows(ext):
            continue
        pts = view.px(ext)
        mask_draw.polygon(pts.ravel().tolist(), fill=255)
        target_edges.append((ext, pts))
        for hole in holes:
            mask_draw.polygon(view.px(hole).ravel().tolist(), fill=0)
    for pts in lake_pts:
        mask_draw.polygon(pts.ravel().tolist(), fill=0)
    img.paste(TARGET, (0, 0, *img.size), mask)
    for ring, pts in target_edges:
        outline(draw, ring, pts, TARGET_EDGE, SS)
    return img


def target_pts(target, proj):
    """The km points of the target's biggest piece and the pieces near it, far territories left out."""
    main = max((ext for ext, _ in target), key=lambda r: r.area)
    main_pts = proj.forward(main.lonlat)
    origin = main_pts.mean(0)
    reach = max(800.0, 2 * np.hypot(*(main_pts - origin).T).max())
    pts = [proj.forward(ext.lonlat) for ext, _ in target]
    return np.concatenate([p for p in pts if np.hypot(*(p.mean(0) - origin)) <= reach])


def box_km(pts):
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    return x0, y0, x1, y1


def box_px(view, pts):
    px = view.km_to_px(pts) / SS
    return (*px.min(0), *px.max(0))


def page_view(target):
    """The first page of the atlas holding the target, else a map of its own."""
    for lat, lon, width in FRAMES:
        proj = Laea(lat, lon)
        if np.abs(target_pts(target, proj)).max() <= width * 0.47:
            return View(proj, 0, 0, width / W)
    main = max((ext for ext, _ in target), key=lambda r: r.area)
    proj = Laea(*main.latlon())
    x0, y0, x1, y1 = box_km(target_pts(target, proj))
    s = max((x1 - x0) / (MAX_FILL * W), (y1 - y0) / (MAX_FILL * H), OWN_FRAME_MIN_KM / W)
    return View(proj, (x0 + x1) / 2, (y0 + y1) / 2, s)


def lens_spot(img, center, radius):
    """Where the lens hides the least land, clear of the target, out of the score badge's corner."""
    land = np.any(np.abs(np.asarray(img).astype(int) - SEA) > 6, axis=2)
    pad = 14
    near, mid, far = LENS // 2 + pad, W // 2, W - LENS // 2 - pad
    yy, xx = np.ogrid[0:H, 0:W]
    best = None
    for x, y in ((near, near), (near, far), (far, far), (mid, far), (near, mid), (far, mid), (mid, near)):
        gap = math.hypot(x - center[0], y - center[1]) - LENS / 2 - radius
        covered = (xx - x) ** 2 + (yy - y) ** 2 <= (LENS / 2) ** 2
        # The leaders cross the map, so the nearer of two equal spots wins.
        score = land[covered].mean() + gap / W * 0.25 + (100 if gap < 40 else 0)
        if best is None or score < best[0]:
            best = (score, x, y)
    return best[1], best[2]


def draw_leaders(draw, a, ra, b, rb):
    """The two outer tangents between the circles around [a] and [b]."""
    base = math.atan2(b[1] - a[1], b[0] - a[0])
    offset = math.acos(max(-1, min(1, (ra - rb) / math.dist(a, b))))
    for sign in (1, -1):
        t = base + sign * offset
        cos, sin = math.cos(t), math.sin(t)
        draw.line(
            (a[0] + ra * cos, a[1] + ra * sin, b[0] + rb * cos, b[1] + rb * sin), fill=TARGET_EDGE, width=SS
        )


def render_lens(target, data):
    main = max((ext for ext, _ in target), key=lambda r: r.area)
    proj = Laea(*main.latlon())
    pts = target_pts(target, proj)
    box = box_km(pts)
    x0, y0, x1, y1 = box
    span = max(max(x1 - x0, y1 - y0) / LENS_FILL, LENS_MIN_KM)
    view = View(proj, (x0 + x1) / 2, (y0 + y1) / 2, span / LENS, LENS, LENS)
    img = draw_map(view, target, data["world"], data["lakes"]).resize((LENS, LENS), Image.LANCZOS)
    dot_specks(img, view, target)
    ring_circle(img, view, box)
    return img


def disc_mask(diameter):
    mask = Image.new("L", (diameter * SS, diameter * SS), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, diameter * SS - 1, diameter * SS - 1), fill=255)
    return mask.resize((diameter, diameter), Image.LANCZOS)


def ring_circle(img, view, box_km):
    x0, y0, x1, y1 = box_km
    size = max(x1 - x0, y1 - y0) / view.s
    if size >= TINY_PX:
        return
    cx, cy = view.km_to_px(np.array([[(x0 + x1) / 2, (y0 + y1) / 2]]))[0] / SS
    r = size / 2 + 14
    ImageDraw.Draw(img).ellipse((cx - r, cy - r, cx + r, cy + r), outline=TARGET_EDGE, width=3)


def dot_specks(img, view, target):
    """A dot on every island of the target too small to see, so an archipelago shows up."""
    draw = ImageDraw.Draw(img)
    shown = [view.px(ext) / SS for ext, _ in target if view.shows(ext)]
    shown = [pts for pts in shown if view.on_canvas(pts * SS)]
    big = [(pts.min(0) - 20, pts.max(0) + 20) for pts in shown if (pts.max(0) - pts.min(0)).max() >= 6]
    for pts in shown:
        if (pts.max(0) - pts.min(0)).max() >= 6:
            continue
        x, y = pts.mean(0)
        if any(lo[0] <= x <= hi[0] and lo[1] <= y <= hi[1] for lo, hi in big):
            continue
        draw.ellipse((x - 3.5, y - 3.5, x + 3.5, y + 3.5), fill=TARGET, outline=TARGET_EDGE)


def render_country(target, data):
    """The target on its page of the atlas, with a lens on it when it is too small to read there."""
    view = page_view(target)
    big = draw_map(view, target, data["world"], data["lakes"], border=SS)
    img = big.resize((W, H), Image.LANCZOS)
    x0, y0, x1, y1 = box_px(view, target_pts(target, view.proj))
    size = max(x1 - x0, y1 - y0)
    if size >= READS_PX:
        dot_specks(img, view, target)
        return img

    center = ((x0 + x1) / 2, (y0 + y1) / 2)
    radius = size / 2 + 11
    x, y = lens_spot(img, center, radius)
    draw = ImageDraw.Draw(big)
    scaled = (center[0] * SS, center[1] * SS)
    draw_leaders(draw, scaled, radius * SS, (x * SS, y * SS), LENS / 2 * SS)
    rx0, ry0 = scaled[0] - radius * SS, scaled[1] - radius * SS
    draw.ellipse((rx0, ry0, rx0 + 2 * radius * SS, ry0 + 2 * radius * SS), outline=TARGET_EDGE, width=SS)
    img = big.resize((W, H), Image.LANCZOS)
    dot_specks(img, view, target)

    left, top = x - LENS // 2, y - LENS // 2
    edge = 5
    img.paste(
        (255, 255, 255),
        (left - edge, top - edge, left + LENS + edge, top + LENS + edge),
        disc_mask(LENS + 2 * edge),
    )
    img.paste(render_lens(target, data), (left, top), disc_mask(LENS))
    return img


def metro_view(depts):
    proj = Laea(46.6, 2.45)
    pts = np.concatenate(
        [proj.forward(ext.lonlat) for code, (_, polys) in depts.items() if len(code) == 2 for ext, _ in polys]
    )
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    s = max((x1 - x0) / (0.96 * W), (y1 - y0) / (0.96 * H))
    return View(proj, (x0 + x1) / 2, (y0 + y1) / 2, s)


def paris_view(outline_polys):
    """Paris large enough to read its arrondissements, the departments around it cut by the frame."""
    proj = Laea(PARIS[1], PARIS[0])
    pts = np.concatenate([proj.forward(ext.lonlat) for ext, _ in outline_polys])
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    s = (x1 - x0) / (0.76 * W)
    return View(proj, (x0 + x1) / 2, (y0 + y1) / 2, s, W, PARIS_HEIGHT)


def fill(draw, view, polys, color):
    for ext, _ in polys:
        draw.polygon(view.px(ext).ravel().tolist(), fill=color)


def render_arrondissement(number, data):
    """An arrondissement on the map of Paris, the river over everything so it cuts the shapes."""
    features, paris = data["arrondissements"], data["paris"]
    target = features[number]
    view = paris_view(paris)
    img = Image.new("RGB", (view.w * SS, view.h * SS), SUBURBS)
    draw = ImageDraw.Draw(img)
    for code, (_, polys) in data["depts"].items():
        if code not in OVERSEAS_DEPTS:
            for ext, _ in polys:
                outline(draw, ext, view.px(ext), PARIS_BORDER, 5)
    fill(draw, view, paris, LAND)
    fill(draw, view, target, TARGET)
    for polys in features.values():
        for ext, _ in polys:
            outline(draw, ext, view.px(ext), PARIS_BORDER, 5)
    for ext, _ in paris:
        outline(draw, ext, view.px(ext), PARIS_EDGE, 10)
    for ext, _ in target:
        outline(draw, ext, view.px(ext), TARGET_EDGE, 7)
    water = Image.new("L", img.size, 0)
    water_draw = ImageDraw.Draw(water)
    for ext, holes in data["paris_water"]:
        water_draw.polygon(view.px(ext).ravel().tolist(), fill=255)
        for hole in holes:
            water_draw.polygon(view.px(hole).ravel().tolist(), fill=0)
    img.paste(RIVER, (0, 0, *img.size), water)
    return img.resize((view.w, view.h), Image.LANCZOS)


def render_french(code, features, overseas, data):
    """A department or region on the map of metropolitan France, an overseas one on its own map."""
    if code in overseas:
        return render_country(data["world"][overseas[code]], data)
    target = features[code][1]
    metro = {c: polys for c, (_, polys) in features.items() if c not in overseas}
    view = metro_view(data["depts"])
    img = draw_map(view, target, data["world"], data["lakes"], [metro]).resize((W, H), Image.LANCZOS)

    paris = view.proj.forward(np.array([PARIS]))[0]
    tx0, ty0, tx1, ty1 = box_px(view, target_pts(target, view.proj))
    ((px, py),) = view.km_to_px(np.array([paris])) / SS
    if (
        max(tx1 - tx0, ty1 - ty0) >= PARIS_INSET_PX
        or math.hypot((tx0 + tx1) / 2 - px, (ty0 + ty1) / 2 - py) > 50
    ):
        return img
    zoom = View(view.proj, paris[0], paris[1], 0.95, 170, 170)
    inset = draw_map(zoom, target, data["world"], data["lakes"], [metro]).resize(
        (zoom.w, zoom.h), Image.LANCZOS
    )
    half = zoom.w * zoom.s / 2
    (ax, ay), (bx, by) = (
        view.km_to_px(np.array([[paris[0] - half, paris[1] + half], [paris[0] + half, paris[1] - half]])) / SS
    )
    draw = ImageDraw.Draw(img)
    draw.rectangle((ax, ay, bx, by), outline=INSET_EDGE, width=2)
    # Over the Bay of Biscay and Spain, the one corner no department reaches.
    x, y = 12, H - inset.height - 12
    draw.rectangle((x - 4, y - 4, x + inset.width + 3, y + inset.height + 3), fill=(255, 255, 255))
    img.paste(inset, (x, y))
    draw.rectangle((x - 1, y - 1, x + inset.width, y + inset.height), outline=INSET_EDGE, width=2)
    return img


def country_names():
    cards = json.loads(FLAGS_SEED.read_text())["cards"]
    names = {}
    for card in cards:
        code = card["id"].removeprefix("flag-")
        if code not in SKIPPED_FLAGS:
            names[code] = NAMES.get(code, card["definition"])
    return names


def load_data():
    world = load_world()
    print("world loaded", flush=True)
    arrondissements, paris = load_arrondissements()
    return {
        "world": world,
        "lakes": load_lakes(),
        "depts": load_french("departements.geojson"),
        "regions": load_french("regions.geojson"),
        "arrondissements": arrondissements,
        "paris": paris,
        "paris_water": load_paris_water(),
    }


def is_dept(code):
    return code[:1].isdigit()


def is_region(code):
    """A region is asked for as r plus its code (r53 for Bretagne), its code alone being a department's."""
    return code[:1] == "r" and code[1:].isdigit()


def is_arrondissement(code):
    """An arrondissement is asked for as a plus its number (a7 for the 7th)."""
    return code[:1] == "a" and code[1:].isdigit()


def arrondissement_name(number):
    return "1er" if number == 1 else f"{number}e"


def render(code, data):
    if is_arrondissement(code):
        return render_arrondissement(int(code[1:]), data)
    if is_dept(code):
        return render_french(code, data["depts"], OVERSEAS_DEPTS, data)
    if is_region(code):
        return render_french(code[1:], data["regions"], OVERSEAS_REGIONS, data)
    return render_country(data["world"][code], data)


def image_name(code):
    if is_dept(code):
        return f"dep-{code.lower()}"
    if is_region(code):
        return f"reg-{code[1:]}"
    if is_arrondissement(code):
        return f"arr-{code[1:]}"
    return code


def save(img, code):
    MAPS_DIR.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(MAPS_DIR / f"{image_name(code)}.webp", "WEBP", quality=82, method=6)


def sheet(codes, data):
    cols = 3
    rows = math.ceil(len(codes) / cols)
    out = Image.new("RGB", (cols * (W + 20), rows * (H + 40)), "#888888")
    draw = ImageDraw.Draw(out)
    for i, code in enumerate(codes):
        x, y = (i % cols) * (W + 20) + 10, (i // cols) * (H + 40) + 10
        out.paste(render(code, data), (x, y))
        draw.text((x, y + H + 8), code, fill="black")
        print(f"{i + 1}/{len(codes)} {code}", flush=True)
    SHEET_PATH.parent.mkdir(exist_ok=True)
    out.save(SHEET_PATH)
    print(SHEET_PATH.resolve(), flush=True)


def list_pages(data):
    """Every country by the page it lands on, in capitals when it reads there without a lens."""
    pages = {}
    for code in country_names():
        target = data["world"].get(code)
        if target is None:
            continue
        view = page_view(target)
        x0, y0, x1, y1 = box_px(view, target_pts(target, view.proj))
        label = code.upper() if max(x1 - x0, y1 - y0) >= READS_PX else code
        is_page = any((view.proj.lat, view.proj.lon) == frame[:2] for frame in FRAMES)
        key = f"{view.proj.lat:.0f},{view.proj.lon:.0f}" if is_page else "own map"
        pages.setdefault(key, []).append(label)
    for key, codes in pages.items():
        print(f"{key}: {len(codes)}: {' '.join(codes)}", flush=True)


def card(prefix, list_id, code, name):
    key = code[1:] if is_region(code) or is_arrondissement(code) else code.lower()
    return {
        "id": f"{prefix}-{key}",
        "listId": list_id,
        "name": "",
        "definition": name,
        "image": f"asset:maps/{image_name(code)}.webp",
        "lastReview": 0,
        "randomSide": False,
    }


def write_seed(path, list_id, list_name, order, cards):
    bundle = {
        "version": 1,
        "lists": [{"id": list_id, "name": list_name, "order": order, "fixedSide": True}],
        "cards": cards,
    }
    path.write_text(json.dumps(bundle, ensure_ascii=False, indent=1) + "\n")


def list_codes(data):
    """The codes of the countries, departments, regions and arrondissements, in their lists' order."""
    names = country_names()
    missing = sorted(code for code in names if code not in data["world"])
    if missing:
        print(f"no shape for {', '.join(missing)}", flush=True)
    codes = [c for c in names if c in data["world"]]
    depts = sorted(data["depts"])
    regions = [f"r{c}" for c in sorted(data["regions"], key=lambda c: data["regions"][c][0])]
    arrondissements = [f"a{n}" for n in sorted(data["arrondissements"])]
    return codes, depts, regions, arrondissements


def build_all(data):
    everything = [code for codes in list_codes(data) for code in codes]
    for i, code in enumerate(everything, 1):
        save(render(code, data), code)
        print(f"{i}/{len(everything)} {code}", flush=True)
    write_seeds(data)


def write_seeds(data):
    names = country_names()
    codes, depts, regions, arrondissements = list_codes(data)
    country_cards = sorted(
        (card("map", "builtin-maps-v1", c, names[c]) for c in codes), key=lambda c: c["definition"]
    )
    dept_cards = [card("deptmap", "builtin-dept-maps-v1", c, data["depts"][c][0]) for c in depts]
    region_cards = [
        card("regionmap", "builtin-region-maps-v1", c, data["regions"][c[1:]][0]) for c in regions
    ]
    arrondissement_cards = [
        card("arrmap", "builtin-arrondissement-maps-v1", c, arrondissement_name(int(c[1:])))
        for c in arrondissements
    ]
    write_seed(COUNTRY_SEED, "builtin-maps-v1", "Pays (carte)", 4, country_cards)
    write_seed(DEPT_SEED, "builtin-dept-maps-v1", "Départements (carte)", 5, dept_cards)
    write_seed(REGION_SEED, "builtin-region-maps-v1", "Régions (carte)", 6, region_cards)
    write_seed(
        ARRONDISSEMENT_SEED,
        "builtin-arrondissement-maps-v1",
        "Arrondissements (carte)",
        7,
        arrondissement_cards,
    )
    counts = (
        f"{len(country_cards)} countries, {len(dept_cards)} departments, "
        f"{len(region_cards)} regions, {len(arrondissement_cards)} arrondissements"
    )
    print(f"{counts} written", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Build the map flashcards lists from Natural Earth.")
    parser.add_argument("--sheet", nargs="+", metavar="CODE", help="only render these maps side by side")
    parser.add_argument("--write", nargs="+", metavar="CODE", help="only write these maps' pictures")
    parser.add_argument("--pages", action="store_true", help="only list the page each country lands on")
    parser.add_argument("--seeds", action="store_true", help="only write the seed files")
    args = parser.parse_args()
    data = load_data()
    if args.pages:
        list_pages(data)
    elif args.seeds:
        write_seeds(data)
    elif args.sheet:
        sheet(args.sheet, data)
    elif args.write:
        for i, code in enumerate(args.write, 1):
            save(render(code, data), code)
            print(f"{i}/{len(args.write)} {code}", flush=True)
    else:
        build_all(data)


if __name__ == "__main__":
    main()
