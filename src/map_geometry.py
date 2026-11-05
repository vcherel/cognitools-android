# The sphere and projection side of generate_maps.py: outline rings, the equal area projection and
# the pixel window maps are drawn through.
import math

import numpy as np

EARTH_RADIUS_KM = 6371.0
W, H = 800, 800
SUPERSAMPLE = 3


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


def main_ring(target):
    """The exterior of the target's biggest piece."""
    return max((ext for ext, _ in target), key=lambda r: r.area)


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
        x = EARTH_RADIUS_KM * k * np.cos(phi) * np.sin(lam)
        y = EARTH_RADIUS_KM * k * (self.cos1 * np.sin(phi) - self.sin1 * np.cos(phi) * np.cos(lam))
        return np.column_stack([x, y])

    def inverse(self, xy):
        x, y = xy[:, 0], xy[:, 1]
        rho = np.hypot(x, y)
        c = 2 * np.arcsin(np.clip(rho / (2 * EARTH_RADIUS_KM), 0, 1))
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
        self.reach = math.hypot(w, h) / 2 * s / EARTH_RADIUS_KM + 0.02

    def shows(self, ring):
        angle = math.acos(max(-1.0, min(1.0, float(ring.center @ self.center))))
        far = math.acos(max(-1.0, min(1.0, float(ring.center @ self.proj.center))))
        return angle - ring.radius < self.reach and far + ring.radius < math.radians(150)

    def km_to_px(self, xy):
        return np.column_stack(
            [
                ((xy[:, 0] - self.cx) / self.s + self.w / 2) * SUPERSAMPLE,
                (self.h / 2 - (xy[:, 1] - self.cy) / self.s) * SUPERSAMPLE,
            ]
        )

    def px(self, ring):
        return self.km_to_px(self.proj.forward(ring.lonlat))

    def on_canvas(self, pts):
        return (
            pts[:, 0].max() >= 0
            and pts[:, 1].max() >= 0
            and pts[:, 0].min() <= self.w * SUPERSAMPLE
            and pts[:, 1].min() <= self.h * SUPERSAMPLE
        )
