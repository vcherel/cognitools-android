# Builds the city lists on top of generate_maps.py: Préfectures (carte), Villes du monde (carte)
# (the capitals and the big cities together, so a country does not give its capital away),
# Préfectures US and its map list, and Villes importantes (France), each map card a dot on the city.
import argparse
import json
import math
import unicodedata

import numpy as np
from PIL import Image, ImageDraw

from generate_maps import (
    ASSETS,
    MAPS_DIR,
    OVERSEAS_DEPTS,
    SUPERSAMPLE,
    H,
    Laea,
    W,
    city_dot,
    draw_map,
    encode,
    load_data,
    mainland_states,
    mainland_view,
    metro_view,
    near_paris,
    paris_inset,
    read_shapes,
    render_city,
    render_country,
    source,
    target_pts,
)

FLAGS_SEED = ASSETS / "seed_flags.json"
CAPITALS_SEED = ASSETS / "seed_capitals.json"
PREFECTURES_SEED = ASSETS / "seed_prefectures.json"
SHEET_PATH = "mockups/cities_sheet.png"
# Places Natural Earth lacks, as (lon, lat).
MISSING_CAPITALS = {"nr": (166.921, -0.547), "xk": (21.166, 42.667)}
PARIS_SUBURBS = {"75", "92", "93", "94"}
FRANCE_CITIES = 40
# A city this close to one already asked would share its dot.
FRANCE_GAP_KM = 25
# Well known cities that are neither a capital nor a US state capital, those having lists of their own.
WORLD_CITIES = [
    ("New York", "us"),
    ("Los Angeles", "us"),
    ("Chicago", "us"),
    ("Houston", "us"),
    ("San Francisco", "us"),
    ("Miami", "us"),
    ("Seattle", "us"),
    ("Las Vegas", "us"),
    ("New Orleans", "us"),
    ("Philadelphia", "us"),
    ("Detroit", "us"),
    ("Toronto", "ca"),
    ("Montréal", "ca"),
    ("Vancouver", "ca"),
    ("Guadalajara", "mx"),
    ("Monterrey", "mx"),
    ("Cancún", "mx"),
    ("São Paulo", "br"),
    ("Rio de Janeiro", "br"),
    ("Salvador", "br"),
    ("Manaus", "br"),
    ("Medellín", "co"),
    ("Guayaquil", "ec"),
    ("Valparaíso", "cl"),
    ("Córdoba", "ar"),
    ("Milan", "it"),
    ("Naples", "it"),
    ("Venice", "it"),
    ("Florence", "it"),
    ("Barcelona", "es"),
    ("Seville", "es"),
    ("Valencia", "es"),
    ("Munich", "de"),
    ("Hamburg", "de"),
    ("Frankfurt", "de"),
    ("Cologne", "de"),
    ("Manchester", "gb"),
    ("Liverpool", "gb"),
    ("Edinburgh", "gb"),
    ("Geneva", "ch"),
    ("Zürich", "ch"),
    ("Porto", "pt"),
    ("Rotterdam", "nl"),
    ("Antwerpen", "be"),
    ("Kraków", "pl"),
    ("Thessaloniki", "gr"),
    ("Istanbul", "tr"),
    ("St. Petersburg", "ru"),
    ("Novosibirsk", "ru"),
    ("Vladivostok", "ru"),
    ("Odessa", "ua"),
    ("Casablanca", "ma"),
    ("Marrakesh", "ma"),
    ("Alexandria", "eg"),
    ("Lagos", "ng"),
    ("Abidjan", "ci"),
    ("Mombasa", "ke"),
    ("Johannesburg", "za"),
    ("Cape Town", "za"),
    ("Durban", "za"),
    ("Dubai", "ae"),
    ("Jeddah", "sa"),
    ("Almaty", "kz"),
    ("Karachi", "pk"),
    ("Mumbai", "in"),
    ("Kolkata", "in"),
    ("Bengaluru", "in"),
    ("Chennai", "in"),
    ("Ho Chi Minh City", "vn"),
    ("Shanghai", "cn"),
    ("Guangzhou", "cn"),
    ("Chengdu", "cn"),
    ("Hong Kong", "hk"),
    ("Ōsaka", "jp"),
    ("Busan", "kr"),
    ("Sydney", "au"),
    ("Melbourne", "au"),
    ("Perth", "au"),
    ("Auckland", "nz"),
    ("Dallas", "us"),
    ("San Diego", "us"),
    ("Calgary", "ca"),
    ("Québec", "ca"),
    ("Tijuana", "mx"),
    ("Belo Horizonte", "br"),
    ("Recife", "br"),
    ("Porto Alegre", "br"),
    ("Fortaleza", "br"),
    ("Cali", "co"),
    ("Cartagena", "co"),
    ("Maracaibo", "ve"),
    ("La Paz", "bo"),
    ("Santa Cruz", "bo"),
    ("Cusco", "pe"),
    ("Rosario", "ar"),
    ("Mendoza", "ar"),
    ("Birmingham", "gb"),
    ("Glasgow", "gb"),
    ("Stuttgart", "de"),
    ("Düsseldorf", "de"),
    ("Turin", "it"),
    ("Palermo", "it"),
    ("Bologna", "it"),
    ("Bilbao", "es"),
    ("Málaga", "es"),
    ("Gdańsk", "pl"),
    ("Wrocław", "pl"),
    ("Göteborg", "se"),
    ("Bergen", "no"),
    ("Basel", "ch"),
    ("Kharkiv", "ua"),
    ("Lviv", "ua"),
    ("Yekaterinburg", "ru"),
    ("Kazan", "ru"),
    ("İzmir", "tr"),
    ("Antalya", "tr"),
    ("Tangier", "ma"),
    ("Fez", "ma"),
    ("Oran", "dz"),
    ("Banghazi", "ly"),
    ("Kano", "ng"),
    ("Douala", "cm"),
    ("Cotonou", "bj"),
    ("Dar es Salaam", "tz"),
    ("Lubumbashi", "cd"),
    ("Tel Aviv", "il"),
    ("Makkah", "sa"),
    ("Basra", "iq"),
    ("Isfahan", "ir"),
    ("Mashhad", "ir"),
    ("Samarkand", "uz"),
    ("Lahore", "pk"),
    ("Hyderabad", "in"),
    ("Ahmedabad", "in"),
    ("Chattogram", "bd"),
    ("Colombo", "lk"),
    ("Yangon", "mm"),
    ("Chiang Mai", "th"),
    ("Da Nang", "vn"),
    ("Surabaya", "id"),
    ("Denpasar", "id"),
    ("Cebu", "ph"),
    ("Shenzhen", "cn"),
    ("Chongqing", "cn"),
    ("Wuhan", "cn"),
    ("Xian", "cn"),
    ("Harbin", "cn"),
    ("Kyoto", "jp"),
    ("Sapporo", "jp"),
    ("Hiroshima", "jp"),
    ("Brisbane", "au"),
    ("Adelaide", "au"),
]
# A capital whose whole country lies this close to it would show the same thing as its Pays card.
TINY_COUNTRY_KM = 100
WORLD_LIST = ("seed_capital_maps.json", "builtin-capital-maps-v1", "Villes du monde (carte)", 11)

LISTS = {
    "pref": ("seed_prefecture_maps.json", "builtin-prefecture-maps-v1", "Préfectures (carte)", 10),
    "cap": WORLD_LIST,
    "uscap": ("seed_state_capital_maps.json", "builtin-state-capital-maps-v1", "Préfectures US (carte)", 13),
    "cityfr": ("seed_city_maps_fr.json", "builtin-cities-fr-v1", "Villes importantes (France)", 14),
    "cityw": WORLD_LIST,
}
STATE_CAPITALS = ("seed_state_capitals.json", "builtin-state-capitals-v1", "Préfectures US", 12)


def fold(text):
    text = unicodedata.normalize("NFD", text.lower())
    return "".join(c for c in text if not unicodedata.combining(c)).replace("-", " ").replace("'", " ")


def km(a, b):
    (lon1, lat1), (lon2, lat2) = (map(math.radians, p) for p in (a, b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def far_from(point, others, gap):
    return all(km(point, other) > gap for other in others)


def prefectures(communes):
    """{dept code: (prefecture, (lon, lat))} from the Préfectures list and the communes' centres."""
    by_dept = {}
    for commune in communes:
        by_dept.setdefault(commune["codeDepartement"], {})[fold(commune["nom"])] = commune
    found = {}
    for card in json.loads(PREFECTURES_SEED.read_text())["cards"]:
        dept = card["id"].removeprefix("pref-").upper().lstrip("0").zfill(2)
        commune = by_dept.get(dept, {}).get(fold(card["definition"]))
        if commune is None:
            print(f"no commune for {card['definition']} ({dept})", flush=True)
            continue
        found[dept] = (card["definition"], tuple(commune["centre"]["coordinates"]))
    return found


def capitals(places, world):
    """{country code: (capital, (lon, lat))} for the Capitales list, matched in the country's places."""
    codes = {
        c["definition"]: c["id"].removeprefix("flag-") for c in json.loads(FLAGS_SEED.read_text())["cards"]
    }
    codes["Afghanistan"] = "af"
    found = {}
    for card in json.loads(CAPITALS_SEED.read_text())["cards"]:
        code = codes.get(card["name"])
        if code not in world:
            print(f"no country shape for {card['name']}", flush=True)
            continue
        if code in MISSING_CAPITALS:
            found[code] = (card["definition"], MISSING_CAPITALS[code])
            continue
        own = [p for p in places if p["ISO_A2"].lower() == code]
        wanted = fold(card["definition"])
        named = [p for p in own if wanted in (fold(p["NAME_FR"] or ""), fold(p["NAME"]))]
        capital = named or [p for p in own if p["ADM0CAP"]]
        if not capital:
            print(f"no place for {card['definition']} ({code})", flush=True)
            continue
        best = max(capital, key=lambda p: p["POP_MAX"])
        found[code] = (card["definition"], (best["LONGITUDE"], best["LATITUDE"]))
    return found


def state_capitals(places, states):
    """{state code: (state name, capital, (lon, lat))} from the Admin-1 capitals of the United States."""
    english = {
        rec["name"]: f"us-{rec['postal'].lower()}"
        for rec, _ in read_shapes("ne_50m_admin_1_states_provinces_lakes.zip")
        if rec["iso_a2"] == "US"
    }
    found = {}
    for p in places:
        if p["ADM0_A3"] != "USA" or not p["FEATURECLA"].startswith("Admin-1 capital"):
            continue
        code = english.get(p["ADM1NAME"])
        if code in states:
            name = " ".join(p["NAME"].replace("St. ", "Saint ").split())
            found[code] = (states[code][0], name, (p["LONGITUDE"], p["LATITUDE"]))
    return found


def france_cities(communes, prefs):
    """The biggest mainland communes that are no prefecture, away from Paris and from each other."""
    taken = [point for _, point in prefs.values()]
    picked = {}
    for commune in sorted(communes, key=lambda c: -(c.get("population") or 0)):
        dept = commune["codeDepartement"]
        if len(dept) > 2 or dept in PARIS_SUBURBS or "centre" not in commune:
            continue
        point = tuple(commune["centre"]["coordinates"])
        if not far_from(point, taken, FRANCE_GAP_KM):
            continue
        picked[commune["code"]] = (commune["nom"], point)
        taken.append(point)
        if len(picked) == FRANCE_CITIES:
            break
    return picked


def world_cities(places, world):
    """[WORLD_CITIES] found in Natural Earth by English name and country, under their French name."""
    picked = {}
    for name, iso in WORLD_CITIES:
        found = [p for p in places if " ".join(p["NAME"].split()) == name and p["ISO_A2"].lower() == iso]
        if not found or iso not in world:
            print(f"no place for {name} ({iso})", flush=True)
            continue
        p = max(found, key=lambda p: p["POP_MAX"])
        picked[str(p["NE_ID"])] = (p["NAME_FR"] or p["NAME"], iso, (p["LONGITUDE"], p["LATITUDE"]))
    return picked


def render_france(point, data):
    """A dot on the map of metropolitan France, in the Paris zoom when it sits next to Paris."""
    metro = {c: polys for c, (_, polys) in data["depts"].items() if c not in OVERSEAS_DEPTS}
    view = metro_view(data["depts"])
    img = draw_map(view, [], data["world"], data["lakes"], [metro]).resize((W, H), Image.LANCZOS)
    city_dot(img, view, point)
    ((x, y),) = view.km_to_px(view.proj.forward(np.array([point]))) / SUPERSAMPLE
    if near_paris(view, (x, y)):
        return paris_inset(img, view, [], metro, data, point)
    return img


def render_us(code, point, data):
    if code in ("us-ak", "us-hi"):
        return render_country(data["states"][code][1], data, point)
    view = mainland_view(data["states"])
    img = draw_map(view, [], data["world"], data["lakes"], [mainland_states(data["states"])])
    img = img.resize((W, H), Image.LANCZOS)
    city_dot(img, view, point)
    return img


def catalog(data):
    """{list key: {card key: (answer, render)}} of every map card, plus the Préfectures US text cards."""
    places = [rec for rec, _ in read_shapes("ne_10m_populated_places.zip")]
    communes = json.loads(source("communes.json").read_text())
    world = data["world"]
    prefs = prefectures(communes)
    caps = capitals(places, world)
    us = state_capitals(places, data["states"])
    cards = {
        "pref": {
            dept.lower(): (
                name,
                lambda p=point, d=dept: (
                    render_country(world[OVERSEAS_DEPTS[d]], data, p)
                    if d in OVERSEAS_DEPTS
                    else render_france(p, data)
                ),
            )
            for dept, (name, point) in prefs.items()
        },
        "cap": {
            code: (name, lambda p=point, c=code: render_city(world[c], data, p))
            for code, (name, point) in caps.items()
            if np.hypot(*target_pts(world[code], Laea(point[1], point[0])).T).max() >= TINY_COUNTRY_KM
        },
        "uscap": {
            code[3:]: (capital, lambda p=point, c=code: render_us(c, p, data))
            for code, (_, capital, point) in us.items()
        },
        "cityfr": {
            insee: (name, lambda p=point: render_france(p, data))
            for insee, (name, point) in france_cities(communes, prefs).items()
        },
        "cityw": {
            key: (name, lambda p=point, c=code: render_city(world[c], data, p))
            for key, (name, code, point) in world_cities(places, world).items()
        },
    }
    return cards, us


def image_name(kind, key):
    return f"{kind}-{key}"


def write_bundle(filename, list_id, name, order, cards, fixed_side=True):
    bundle = {
        "version": 1,
        "lists": [{"id": list_id, "name": name, "order": order, "fixedSide": fixed_side}],
        "cards": cards,
    }
    (ASSETS / filename).write_text(json.dumps(bundle, ensure_ascii=False, indent=1) + "\n")


def write_seeds(cards, us):
    seeds = {}
    for kind, entries in cards.items():
        seeds.setdefault(LISTS[kind], []).extend(
            {
                "id": f"{kind}map-{key}",
                "listId": LISTS[kind][1],
                "name": "",
                "definition": answer,
                "image": f"asset:maps/{image_name(kind, key)}.webp",
                "lastReview": 0,
                "randomSide": False,
            }
            for key, (answer, _) in entries.items()
        )
    for (filename, list_id, name, order), seed in seeds.items():
        write_bundle(filename, list_id, name, order, sorted(seed, key=lambda c: fold(c["definition"])))
    filename, list_id, name, order = STATE_CAPITALS
    text = [
        {
            "id": f"uscap-{code[3:]}",
            "listId": list_id,
            "name": state,
            "definition": capital,
            "lastReview": 0,
            "randomSide": True,
        }
        for code, (state, capital, _) in sorted(us.items(), key=lambda item: fold(item[1][0]))
    ]
    write_bundle(filename, list_id, name, order, text, fixed_side=False)
    print(
        ", ".join(f"{len(e)} {k}" for k, e in cards.items()) + f", {len(text)} uscap text written", flush=True
    )


def parse(code):
    kind, key = code.split(":")
    return kind, key


def sheet(codes, cards):
    cols = 3
    rows = math.ceil(len(codes) / cols)
    out = Image.new("RGB", (cols * (W + 20), rows * (H + 40)), "#888888")
    for i, code in enumerate(codes):
        kind, key = parse(code)
        answer, render = cards[kind][key]
        x, y = (i % cols) * (W + 20) + 10, (i // cols) * (H + 40) + 10
        out.paste(render(), (x, y))

        ImageDraw.Draw(out).text((x, y + H + 8), f"{code} {answer}", fill="black")
        print(f"{i + 1}/{len(codes)} {code}", flush=True)
    out.save(SHEET_PATH)
    print(SHEET_PATH, flush=True)


def write_maps(items):
    MAPS_DIR.mkdir(parents=True, exist_ok=True)
    for i, (kind, key, render) in enumerate(items, 1):
        (MAPS_DIR / f"{image_name(kind, key)}.webp").write_bytes(encode(render()))
        print(f"{i}/{len(items)} {kind}:{key}", flush=True)


def list_changed(cards):
    """The cards whose fresh render differs from the shipped picture, the ids RESETS needs."""
    items = [(kind, key, render) for kind, entries in cards.items() for key, (_, render) in entries.items()]
    changed = []
    for i, (kind, key, render) in enumerate(items, 1):
        shipped = MAPS_DIR / f"{image_name(kind, key)}.webp"
        differs = not shipped.exists() or shipped.read_bytes() != encode(render())
        if differs:
            changed.append(f"{kind}map-{key}")
        print(f"{i}/{len(items)} {kind}:{key}{' changed' if differs else ''}", flush=True)
    print(" ".join(changed) or "nothing changed", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Build the city flashcard lists.")
    parser.add_argument("--sheet", nargs="+", metavar="KIND:KEY", help="only render these cards side by side")
    parser.add_argument("--list", action="store_true", help="only print every card key and answer")
    parser.add_argument("--seeds", action="store_true", help="only write the seed files")
    parser.add_argument("--only", choices=list(LISTS), help="only write the pictures of this list")
    parser.add_argument(
        "--changed", action="store_true", help="only list the cards whose picture would change"
    )
    args = parser.parse_args()
    data = load_data()
    cards, us = catalog(data)
    if args.changed:
        list_changed(cards)
    elif args.only:
        write_maps([(args.only, key, render) for key, (_, render) in cards[args.only].items()])
    elif args.list:
        for kind, entries in cards.items():
            print(kind, " ".join(f"{k}={a}" for k, (a, _) in entries.items()), flush=True)
    elif args.sheet:
        sheet(args.sheet, cards)
    elif args.seeds:
        write_seeds(cards, us)
    else:
        write_maps(
            [(kind, key, render) for kind, entries in cards.items() for key, (_, render) in entries.items()]
        )
        write_seeds(cards, us)


if __name__ == "__main__":
    main()
