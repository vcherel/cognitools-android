"""Build the Mots fleches clue dictionary from Lexique + the French Wiktionary.

Words come from data/lexique.tsv (lemmas, ranked by frequency); their definitions come
from fr.wiktionary.org. Each kept sense yields a short clue and the full definition used
as the explanation when checking an answer.

Run with:
    uv run --no-project src/generate_motsfleches.py
"""

import csv
import json
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

LEXIQUE_PATH = "data/lexique.tsv"
CACHE_PATH = "data/motsfleches_senses.json"
OUTPUT_PATH = "app/src/main/assets/motsfleches_dict_fr.txt"

API = "https://fr.wiktionary.org/w/api.php"
USER_AGENT = "CogniTools/1.0 (personal offline crossword app; valentin.cherel22@yahoo.com)"

MIN_LENGTH = 3
MAX_LENGTH = 11
MAX_WORDS = 34000
MAX_SENSES = 2
CLUE_MAX = 48
FULL_MAX = 240

POS_SECTIONS = {
    "nom": "n",
    "adjectif": "adj",
    "verbe": "v",
    "adverbe": "adv",
}

# Definitions that only point at another form make useless clues.
REDIRECT_DEF = re.compile(
    r"^\(?[^)]*\)?\s*("
    r"pluriel|f[ée]minin|masculin|singulier|participe|première personne|deuxième personne|"
    r"troisième personne|variante|graphie|orthographe|autre nom|synonyme|abr[ée]viation|"
    r"sigle|symbole|code|initiales|nom de famille|pr[ée]nom"
    r")\b",
    re.IGNORECASE,
)

# Senses flagged like this are real, but as a clue they send you looking for the wrong word.
RARE_MARKER = re.compile(
    r"\((très rare|rare|vieilli|d[ée]suet|archa|argot|vulgaire|par plaisanterie|ironique|"
    r"n[ée]ologisme|anglicisme|proverbial|au figur[ée]|par analogie|par extension|"
    r"r[ée]gionalisme|belgique|qu[ée]bec|suisse|afrique)",
    re.IGNORECASE,
)

DOMAIN_TEMPLATES = {"info lex", "lexique", "term", "contexte", "domaine", "term/2"}
TEXT_TEMPLATES = {"lien", "l", "w", "wsp", "polytonique", "recons", "smo"}


def strip_accents(text):
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn")


def grid_form(word):
    """The A-Z form a word takes inside a grid, or None if it cannot go in one."""
    ascii_word = strip_accents(word).upper().replace("Œ", "OE").replace("Æ", "AE")
    if not re.fullmatch(r"[A-Z]+", ascii_word):
        return None
    return ascii_word


def load_candidates():
    """Lemmas from Lexique, most frequent first, one per grid form."""
    seen = {}
    with open(LEXIQUE_PATH, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            if row["islem"] != "1":
                continue
            if row["cgram"] not in ("NOM", "ADJ", "VER", "ADV"):
                continue
            word = row["ortho"]
            if not MIN_LENGTH <= len(word) <= MAX_LENGTH:
                continue
            form = grid_form(word)
            if form is None or len(form) > MAX_LENGTH:
                continue
            try:
                freq = float(row["freqlemfilms2"]) + float(row["freqlemlivres"])
            except ValueError:
                continue
            previous = seen.get(form)
            if previous is None or freq > previous[1]:
                seen[form] = (word, freq)

    ranked = sorted(seen.items(), key=lambda item: -item[1][1])
    return [(form, word) for form, (word, _) in ranked[:MAX_WORDS]]


TEMPLATE = re.compile(r"\{\{([^{}]*)\}\}")


def expand_template(match):
    parts = [part.strip() for part in match.group(1).split("|")]
    name = parts[0].lower()
    args = [part for part in parts[1:] if "=" not in part and part not in ("fr", "1")]
    if name in DOMAIN_TEMPLATES:
        return f"({args[0].capitalize()}) " if args else ""
    if name in TEXT_TEMPLATES:
        return args[0] if args else ""
    # {{felins|fr}} and friends: a bare lexical field marker.
    if len(parts) == 2 and parts[1] == "fr" and re.fullmatch(r"[a-zéèêàçûôîï \-]+", name):
        return f"({parts[0].capitalize()}) "
    return ""


def clean_wikitext(text):
    text = re.sub(r"<ref[^>]*/>", "", text)
    text = re.sub(r"<ref[^>]*>.*?</ref>", "", text, flags=re.S)
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"<[^>]+>", "", text)
    for _ in range(6):
        text, count = TEMPLATE.subn(expand_template, text)
        if not count:
            break
    text = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"\[\[([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"\[https?://\S+ ([^\]]*)\]", r"\1", text)
    text = text.replace("'''", "").replace("''", "")
    text = text.replace("’", "'").replace(" ", " ")
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"^[\s,;:.\-–—]+", "", text)
    return text.strip(" ,;:")


SECTION = re.compile(r"^===+\s*\{\{S\|([^|}]+)", re.MULTILINE)


def parse_page(wikitext):
    """The French definitions of a page, as (pos, definition) pairs in page order."""
    start = wikitext.find("== {{langue|fr}} ==")
    if start < 0:
        return []
    end = wikitext.find("\n== {{langue|", start + 1)
    french = wikitext[start : end if end > 0 else len(wikitext)]

    senses = []
    marks = list(SECTION.finditer(french))
    for index, mark in enumerate(marks):
        pos = POS_SECTIONS.get(mark.group(1).strip().lower())
        if pos is None:
            continue
        stop = marks[index + 1].start() if index + 1 < len(marks) else len(french)
        for line in french[mark.end() : stop].splitlines():
            if line.startswith("# ") or (line.startswith("#") and not line[1:2] in ("*", ":", "#")):
                definition = clean_wikitext(line.lstrip("# ").strip())
                if definition:
                    senses.append((pos, definition))
    return senses


def gives_it_away(definition, word):
    """True when the definition contains the answer (or an obvious inflection of it)."""
    plain = strip_accents(definition).lower()
    stem = strip_accents(word).lower()[: max(4, len(word) - 2)]
    return any(token.startswith(stem) for token in re.findall(r"[a-z]+", plain))


def shorten(definition):
    if len(definition) <= CLUE_MAX:
        return definition
    for separator in (" ; ", "; ", " : ", ", "):
        cut = definition.find(separator)
        if 18 <= cut <= CLUE_MAX:
            return definition[:cut]
    return definition[:CLUE_MAX].rsplit(" ", 1)[0] + "…"


def usable_senses(senses, word):
    kept = []
    for rank, (pos, definition) in enumerate(senses):
        if len(definition) < 12 or REDIRECT_DEF.match(definition):
            continue
        if gives_it_away(definition, word):
            continue
        if definition.count("(") > 2 or "()" in definition or not definition[0].isupper():
            continue
        # Main senses first, short ones first, and only fall back on the odd ones.
        # A definition that has to be cut to fit a grid cell loses most of its meaning, so short
        # ones win over the main sense when the main sense is long winded.
        score = (
            rank * 6
            + len(definition) / 6
            + (25 if len(definition) > CLUE_MAX else 0)
            + (60 if RARE_MARKER.search(definition) else 0)
        )
        kept.append((score, pos, definition))
    kept.sort(key=lambda item: item[0])
    return [(pos, definition) for _, pos, definition in kept[:MAX_SENSES]]


def fetch_batch(titles):
    query = urllib.parse.urlencode(
        {
            "action": "query",
            "format": "json",
            "formatversion": "2",
            "prop": "revisions",
            "rvprop": "content",
            "rvslots": "main",
            "titles": "|".join(titles),
        }
    )
    request = urllib.request.Request(f"{API}?{query}", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.load(response)
    pages = {}
    for page in payload.get("query", {}).get("pages", []):
        revisions = page.get("revisions")
        if not revisions:
            continue
        pages[page["title"]] = revisions[0]["slots"]["main"]["content"]
    return pages


def load_cache():
    if os.path.exists(CACHE_PATH):
        with open(CACHE_PATH, encoding="utf-8") as handle:
            return json.load(handle)
    return {}


def save_cache(cache):
    with open(CACHE_PATH, "w", encoding="utf-8") as handle:
        json.dump(cache, handle, ensure_ascii=False)


def collect(candidates, cache):
    missing = [word for _, word in candidates if word not in cache]
    print(f"{len(candidates)} candidates, {len(missing)} to fetch", flush=True)
    for start in range(0, len(missing), 50):
        batch = missing[start : start + 50]
        try:
            pages = fetch_batch(batch)
        except Exception as error:  # a single failed batch must not lose the run
            print(f"batch {start} failed: {error}", flush=True)
            time.sleep(5)
            continue
        for word in batch:
            cache[word] = parse_page(pages.get(word, ""))
        if start % 2000 == 0:
            save_cache(cache)
            print(f"  fetched {start + len(batch)}/{len(missing)}", flush=True)
        time.sleep(0.1)
    save_cache(cache)
    return cache


def write_asset(candidates, cache):
    rows = []
    for rank, (form, word) in enumerate(candidates):
        senses = usable_senses(cache.get(word, []), word)
        for pos, definition in senses:
            full = definition[:FULL_MAX].rsplit(" ", 1)[0] if len(definition) > FULL_MAX else definition
            # Rank is the frequency order: the grid generator uses it to pick a difficulty.
            rows.append(f"{form}\t{rank}\t{pos}\t{shorten(definition)}\t{full}")
    # Plain text on purpose: the Android build unpacks a .gz asset at packaging time, and the APK
    # deflates whatever it ships anyway.
    with open(OUTPUT_PATH, "w", encoding="utf-8") as handle:
        handle.write("\n".join(rows))
    words = len({row.split("\t", 1)[0] for row in rows})
    size = os.path.getsize(OUTPUT_PATH)
    print(f"Wrote {len(rows)} clues for {words} words to {OUTPUT_PATH} ({size / 1e6:.1f} MB)")


def main():
    candidates = load_candidates()
    cache = collect(candidates, load_cache())
    write_asset(candidates, cache)


if __name__ == "__main__":
    sys.exit(main())
