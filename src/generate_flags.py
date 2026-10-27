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
    "sx": "Saint-Martin (partie néerlandaise)",
}
# Territories flying their sovereign's flag would be the same card with two answers.
SOVEREIGN_FIRST = ["fr", "no", "us", "au", "gb", "nl"]
# Flags that print their own name, painted over (box in the w640 image) so the card isn't given away.
NAME_BOXES = {"yt": (145, 32, 495, 102)}


def hide_name(image, code):
    if code in NAME_BOXES:
        ImageDraw.Draw(image).rectangle(NAME_BOXES[code], fill="white")
    return image


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
        hide_name(image, code).save(FLAGS_DIR / f"{code}.webp", "WEBP", quality=88, method=6)
        cards.append(
            {
                "id": f"flag-{code}",
                "listId": LIST_ID,
                "name": "",
                "definition": name,
                "image": f"asset:flags/{code}.webp",
                "lastReview": 0,
                "randomSide": False,
            }
        )
        print(f"{i}/{len(codes)} {code} {name}", flush=True)

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
