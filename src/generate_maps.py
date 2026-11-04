import argparse
import io
import json
import math
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
import shapefile
from PIL import Image, ImageDraw
from shapely.geometry import shape
from shapely.ops import unary_union

ASSETS = Path("app/src/main/assets")
MAPS_DIR = ASSETS / "maps"
FLAGS_SEED = ASSETS / "seed_flags.json"
COUNTRY_SEED = ASSETS / "seed_maps.json"
DEPT_SEED = ASSETS / "seed_dept_maps.json"
REGION_SEED = ASSETS / "seed_region_maps.json"
DATA = Path("data/maps")
SHEET_PATH = Path("mockups/maps_sheet.png")
SOURCES = {
    "ne_10m_admin_0_map_units.zip": "https://naciscdn.org/naturalearth/10m/cultural/ne_10m_admin_0_map_units.zip",
    "ne_50m_lakes.zip": "https://naciscdn.org/naturalearth/50m/physical/ne_50m_lakes.zip",
    "departements.geojson": "https://raw.githubusercontent.com/gregoiredavid/france-geojson/master/departements-avec-outre-mer.geojson",
    "regions.geojson": "https://raw.githubusercontent.com/gregoiredavid/france-geojson/master/regions-avec-outre-mer.geojson",
}

R = 6371.0
W, H = 800, 600
SS = 3
SEA = (214, 230, 240)
LAND = (228, 224, 214)
BORDER = (130, 124, 114)
TARGET = (214, 64, 46)
TARGET_EDGE = (150, 36, 24)
GLOBE_SEA = (176, 205, 226)
GLOBE_LAND = (204, 198, 184)
INSET_EDGE = (110, 110, 110)
# Frame width in km by the square root of the target's area: a speck of an island zoomed in
# until its coast shows, a middle size country zoomed out until its neighbours do.
FRAME_CURVE = ((4, 35), (20, 200), (600, 2600), (3000, 6000))
# The share of the card a target spread wide (a big country, a long archipelago) may fill.
MAX_FILL = 0.7
# Past this width the map already shows where it is on Earth, the globe would only hide some of it.
GLOBE_MAX_FRAME_KM = 7000
GLOBE_DIAMETER = 170
# A target smaller than this many pixels gets a circle around it.
TINY_PX = 16

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
        return response.read()


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


def draw_map(view, target, world, lakes, layers=()):
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
        outline(draw, ring, pts, BORDER, 2 * SS)

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


class Globe:
    """The small orthographic globe in a card's corner, sampled from an equirectangular raster."""

    EQ_W, EQ_H = 4096, 2048

    def __init__(self, world):
        self.land = self.raster([p for polys in world.values() for p in polys])

    def raster(self, polys):
        img = Image.new("L", (self.EQ_W, self.EQ_H), 0)
        draw = ImageDraw.Draw(img)
        for ext, _ in polys:
            draw.polygon(self.eq_px(ext.lonlat).ravel().tolist(), fill=255)
        return np.asarray(img) > 0

    def eq_px(self, lonlat):
        return np.column_stack(
            [(lonlat[:, 0] + 180) / 360 * self.EQ_W, (90 - lonlat[:, 1]) / 180 * self.EQ_H]
        )

    def render(self, lat0, lon0, target, frame_lonlat, diameter=GLOBE_DIAMETER):
        n = diameter * SS
        half = n / 2
        yy, xx = np.mgrid[0:n, 0:n]
        u, v = (xx + 0.5 - half) / half, (half - yy - 0.5) / half
        rho = np.hypot(u, v)
        inside = rho <= 1
        c = np.arcsin(np.clip(rho, 0, 1))
        sin1, cos1 = math.sin(math.radians(lat0)), math.cos(math.radians(lat0))
        safe = np.where(rho > 0, rho, 1)
        lat = np.arcsin(np.clip(np.cos(c) * sin1 + np.where(rho > 0, v * np.sin(c) * cos1 / safe, 0), -1, 1))
        lon = math.radians(lon0) + np.arctan2(u * np.sin(c), rho * cos1 * np.cos(c) - v * sin1 * np.sin(c))
        col = (((np.degrees(lon) + 180) % 360) / 360 * self.EQ_W).astype(int) % self.EQ_W
        row = np.clip(((90 - np.degrees(lat)) / 180 * self.EQ_H).astype(int), 0, self.EQ_H - 1)
        hit = self.raster(target)[row, col]
        land = self.land[row, col]
        rgb = np.empty((n, n, 3))
        rgb[:] = GLOBE_SEA
        rgb[land] = GLOBE_LAND
        rgb[hit] = TARGET
        rgb *= (0.78 + 0.22 * np.cos(c))[..., None]
        alpha = np.where(inside, 255, 0)
        img = Image.fromarray(np.dstack([rgb, alpha]).astype(np.uint8), "RGBA")

        draw = ImageDraw.Draw(img)
        lam = np.radians(frame_lonlat[:, 0] - lon0)
        phi = np.radians(frame_lonlat[:, 1])
        visible = sin1 * np.sin(phi) + cos1 * np.cos(phi) * np.cos(lam) >= 0
        fx = half + half * np.cos(phi) * np.sin(lam)
        fy = half - half * (cos1 * np.sin(phi) - sin1 * np.cos(phi) * np.cos(lam))
        pts = np.column_stack([fx, fy])[visible]
        if len(pts) > 1:
            draw.line(pts.ravel().tolist(), fill=TARGET_EDGE, width=2 * SS, joint="curve")
        draw.ellipse((SS, SS, n - SS, n - SS), outline=INSET_EDGE, width=SS)
        return img.resize((diameter, diameter), Image.LANCZOS)


def frame_lonlat(view):
    """The view's border as lon/lat points, for the globe's locator outline."""
    half_w, half_h = view.w / 2 * view.s, view.h / 2 * view.s
    t = np.linspace(-1, 1, 40)
    xs = np.concatenate([t * half_w, np.full(40, half_w), -t * half_w, np.full(40, -half_w)])
    ys = np.concatenate([np.full(40, half_h), -t * half_h, np.full(40, -half_h), t * half_h])
    return view.proj.inverse(np.column_stack([xs + view.cx, ys + view.cy]))


def corner(img, size, avoid, margin=12):
    """The corner of [img] where an inset of [size] hides the least land, never over [avoid]."""
    pixels = np.asarray(img)
    land = np.any(np.abs(pixels.astype(int) - SEA) > 6, axis=2)
    w, h = size
    best = None
    for x in (margin, img.width - w - margin):
        for y in (margin, img.height - h - margin):
            score = land[y : y + h, x : x + w].sum()
            ax0, ay0, ax1, ay1 = avoid
            if x < ax1 + 8 and ax0 - 8 < x + w and y < ay1 + 8 and ay0 - 8 < y + h:
                score += 10**9
            if best is None or score < best[0]:
                best = (score, x, y)
    return best[1], best[2]


def paste_globe(img, globe, view, target, avoid):
    lat, lon = view.proj.lat, view.proj.lon
    disc = globe.render(lat, lon, target, frame_lonlat(view))
    x, y = corner(img, disc.size, avoid)
    backing = Image.new("L", (disc.width + 8, disc.height + 8), 0)
    ImageDraw.Draw(backing).ellipse((0, 0, backing.width - 1, backing.height - 1), fill=255)
    img.paste((255, 255, 255), (x - 4, y - 4, x - 4 + backing.width, y - 4 + backing.height), backing)
    img.paste(disc, (x, y), disc)


def target_box(view, target):
    """The target's pixel bounding box on the final image."""
    pts = [view.px(ext) / SS for ext, _ in target if view.shows(ext)]
    pts = [p for p in pts if view.on_canvas(p * SS)]
    if not pts:
        return (view.w / 2, view.h / 2, view.w / 2, view.h / 2)
    allpts = np.concatenate(pts)
    return (*allpts.min(0), *allpts.max(0))


def polygon_km2(ext, holes):
    km_per_degree = math.pi * R / 180
    return (ext.area - sum(h.area for h in holes)) * km_per_degree**2


def country_view(target):
    """Centered on the country's biggest piece and the pieces near it, with room for its neighbours."""
    main = max((ext for ext, _ in target), key=lambda r: r.area)
    proj = Laea(*main.latlon())
    main_pts = proj.forward(main.lonlat)
    reach = max(800.0, 2 * np.hypot(main_pts[:, 0], main_pts[:, 1]).max())
    near = [proj.forward(ext.lonlat) for ext, _ in target]
    near = [p for p in near if np.hypot(*p.mean(0)) <= reach]
    pts = np.concatenate(near)
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    size, frame = np.log(np.array(FRAME_CURVE, dtype=float)).T
    side = math.sqrt(sum(polygon_km2(ext, holes) for ext, holes in target))
    frame_km = math.exp(np.interp(math.log(max(side, 1e-3)), size, frame))
    s = max((x1 - x0) / (MAX_FILL * W), (y1 - y0) / (MAX_FILL * H), frame_km / W)
    return View(proj, (x0 + x1) / 2, (y0 + y1) / 2, s), (x0, y0, x1, y1)


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
    world, lakes, globe = data["world"], data["lakes"], data["globe"]
    view, box_km = country_view(target)
    img = draw_map(view, target, world, lakes).resize((W, H), Image.LANCZOS)
    dot_specks(img, view, target)
    ring_circle(img, view, box_km)
    if view.w * view.s < GLOBE_MAX_FRAME_KM:
        paste_globe(img, globe, view, target, target_box(view, target))
    return img


def metro_view(depts):
    proj = Laea(46.6, 2.45)
    pts = np.concatenate(
        [proj.forward(ext.lonlat) for code, (_, polys) in depts.items() if len(code) == 2 for ext, _ in polys]
    )
    (x0, y0), (x1, y1) = pts.min(0), pts.max(0)
    s = max((x1 - x0) / (0.96 * W), (y1 - y0) / (0.96 * H))
    return View(proj, (x0 + x1) / 2, (y0 + y1) / 2, s)


def render_french(code, features, overseas, data):
    """A department or region on the map of metropolitan France, an overseas one on its own map."""
    if code in overseas:
        return render_country(data["world"][overseas[code]], data)
    target = features[code][1]
    metro = {c: polys for c, (_, polys) in features.items() if c not in overseas}
    view = metro_view(data["depts"])
    img = draw_map(view, target, data["world"], data["lakes"], [metro]).resize((W, H), Image.LANCZOS)

    paris = view.proj.forward(np.array([PARIS]))[0]
    tx0, ty0, tx1, ty1 = target_box(view, target)
    ((px, py),) = view.km_to_px(np.array([paris])) / SS
    if max(tx1 - tx0, ty1 - ty0) >= TINY_PX or math.hypot((tx0 + tx1) / 2 - px, (ty0 + ty1) / 2 - py) > 40:
        return img
    zoom = View(view.proj, paris[0], paris[1], 0.95, 190, 190)
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
    return {
        "world": world,
        "lakes": load_lakes(),
        "depts": load_french("departements.geojson"),
        "regions": load_french("regions.geojson"),
        "globe": Globe(world),
    }


def is_dept(code):
    return code[:1].isdigit()


def is_region(code):
    """A region is asked for as r plus its code (r53 for Bretagne), its code alone being a department's."""
    return code[:1] == "r" and code[1:].isdigit()


def render(code, data):
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
    return code


def save(img, code):
    MAPS_DIR.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(MAPS_DIR / f"{image_name(code)}.webp", "WEBP", quality=82, method=6)


def sheet(codes, data):
    cols = 2
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


def card(prefix, list_id, code, name):
    key = code[1:] if is_region(code) else code.lower()
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


def build_all(data):
    names = country_names()
    missing = sorted(code for code in names if code not in data["world"])
    if missing:
        print(f"no shape for {', '.join(missing)}", flush=True)
    codes = [c for c in names if c in data["world"]]
    depts = sorted(data["depts"])
    regions = [f"r{c}" for c in sorted(data["regions"], key=lambda c: data["regions"][c][0])]
    everything = codes + depts + regions
    for i, code in enumerate(everything, 1):
        save(render(code, data), code)
        print(f"{i}/{len(everything)} {code}", flush=True)
    country_cards = sorted(
        (card("map", "builtin-maps-v1", c, names[c]) for c in codes), key=lambda c: c["definition"]
    )
    dept_cards = [card("deptmap", "builtin-dept-maps-v1", c, data["depts"][c][0]) for c in depts]
    region_cards = [
        card("regionmap", "builtin-region-maps-v1", c, data["regions"][c[1:]][0]) for c in regions
    ]
    write_seed(COUNTRY_SEED, "builtin-maps-v1", "Pays (carte)", 4, country_cards)
    write_seed(DEPT_SEED, "builtin-dept-maps-v1", "Départements (carte)", 5, dept_cards)
    write_seed(REGION_SEED, "builtin-region-maps-v1", "Régions (carte)", 6, region_cards)
    counts = f"{len(country_cards)} countries, {len(dept_cards)} departments, {len(region_cards)} regions"
    print(f"{counts} written", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Build the map flashcards lists from Natural Earth.")
    parser.add_argument("--sheet", nargs="+", metavar="CODE", help="only render these maps side by side")
    parser.add_argument("--write", nargs="+", metavar="CODE", help="only write these maps' pictures")
    args = parser.parse_args()
    data = load_data()
    if args.sheet:
        sheet(args.sheet, data)
    elif args.write:
        for i, code in enumerate(args.write, 1):
            save(render(code, data), code)
            print(f"{i}/{len(args.write)} {code}", flush=True)
    else:
        build_all(data)


if __name__ == "__main__":
    main()
