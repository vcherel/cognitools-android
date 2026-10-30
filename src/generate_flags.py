import hashlib
import io
import json
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw

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
# Flags that print their own name, painted over (boxes in the w640 image, with the background they
# sit on) so the card isn't given away. Guam's letters are split around the palm trunk.
NAME_BOXES = {
    "yt": [((145, 32, 495, 102), "white")],
    "gu": [((283, 159, 314, 178), "#94dbfc"), ((323, 159, 358, 178), "#94dbfc")],
}
# Look-alikes whose official proportions would give the answer away, all stretched to 3:2 so only
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


def hide_name(image, code):
    for box, fill in NAME_BOXES.get(code, []):
        ImageDraw.Draw(image).rectangle(box, fill=fill)
    return image


def reshape(image, code):
    return image.resize((640, 427)) if code in SAME_SHAPE else image


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "cognitools-flags"})
    with urllib.request.urlopen(request) as response:
        return response.read()


def main():
    names = json.loads(fetch("https://flagcdn.com/fr/codes.json"))
    codes = sorted(c for c in names if "-" not in c and c not in NOT_COUNTRIES | SAME_FLAG_AS_SOVEREIGN)
    codes.sort(key=lambda c: c not in SOVEREIGN_FIRST)
    FLAGS_DIR.mkdir(parents=True, exist_ok=True)

    seen = {}
    cards = []
    for i, code in enumerate(codes, 1):
        image = Image.open(io.BytesIO(fetch(f"https://flagcdn.com/w640/{code}.png"))).convert("RGBA")
        digest = hashlib.sha1(image.tobytes()).hexdigest()
        name = RENAMES.get(code, names[code])
        if digest in seen:
            print(f"{i}/{len(codes)} {code} skipped, same flag as {seen[digest]}", flush=True)
            continue
        seen[digest] = code
        reshape(hide_name(image, code), code).save(FLAGS_DIR / f"{code}.webp", "WEBP", quality=88, method=6)
        cards.append(card(code, name))
        print(f"{i}/{len(codes)} {code} {name}", flush=True)
    for code, (url, name) in EXTRA_FLAGS.items():
        extra_flag(url).save(FLAGS_DIR / f"{code}.webp", "WEBP", quality=88, method=6)
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
