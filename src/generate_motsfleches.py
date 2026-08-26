"""Build the Mots fleches clue dictionary from Lexique + the French Wiktionary.

Words come from data/lexique.tsv (lemmas, ranked by frequency); their definitions come
from fr.wiktionary.org. Each kept sense yields a short clue and the full definition used
as the explanation when checking an answer.

The same pages also give their synonyms and antonyms, which turn into "Synonyme de ..." /
"Contraire de ..." clues: that is how a real mots fleches grid reads, where a dictionary
definition reads like a dictionary.

Run with:
    uv run --no-project src/generate_motsfleches.py
"""

import csv
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

from motsfleches_common import (
    CLUE_MAX,
    MAX_LENGTH,
    MAX_SENSES,
    MAX_WORDS,
    MIN_LENGTH,
    cut_at_semicolon,
    full_definition,
    gives_it_away,
    shorten,
    strip_accents,
    write_asset,
)

LEXIQUE_PATH = "data/lexique.tsv"
CACHE_PATH = "data/motsfleches_senses.json"
OUTPUT_PATH = "app/src/main/assets/motsfleches_dict_fr.txt"

API = "https://fr.wiktionary.org/w/api.php"
USER_AGENT = "CogniTools/1.0 (personal offline crossword app; valentin.cherel22@yahoo.com)"

# A synonym nobody has heard of is a worse clue than the definition it replaces (Lexique, per million).
MIN_RELATED_FREQ = 1.0
# How many synonyms and how many antonyms a word keeps.
MAX_RELATED = 2

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


def grid_form(word):
    """The A-Z form a word takes inside a grid, or None if it cannot go in one."""
    ascii_word = strip_accents(word).upper().replace("Œ", "OE").replace("Æ", "AE")
    if not re.fullmatch(r"[A-Z]+", ascii_word):
        return None
    return ascii_word


def load_candidates():
    """Lemmas from Lexique, most frequent first and one per grid form, plus every lemma's
    frequency, which is what ranks the synonyms a page offers."""
    seen = {}
    frequencies = {}
    with open(LEXIQUE_PATH, encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            if row["islem"] != "1":
                continue
            if row["cgram"] not in ("NOM", "ADJ", "VER", "ADV"):
                continue
            word = row["ortho"]
            try:
                freq = float(row["freqlemfilms2"]) + float(row["freqlemlivres"])
            except ValueError:
                continue
            key = strip_accents(word).lower()
            frequencies[key] = max(frequencies.get(key, 0.0), freq)
            if not MIN_LENGTH <= len(word) <= MAX_LENGTH:
                continue
            form = grid_form(word)
            if form is None or len(form) > MAX_LENGTH:
                continue
            previous = seen.get(form)
            if previous is None or freq > previous[1]:
                seen[form] = (word, freq)

    ranked = sorted(seen.items(), key=lambda item: -item[1][1])
    return [(form, word) for form, (word, _) in ranked[:MAX_WORDS]], frequencies


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
RELATED_SECTIONS = {"synonymes": "syn", "antonymes": "ant"}
# A synonym is one word or one phrase, sometimes followed by the sense it applies to.
RELATED_WORD = re.compile(r"^[^\W\d_][\w' -]*$", re.UNICODE)


def parse_related(block):
    """The words a synonyms or antonyms section lists, one per bullet."""
    found = []
    for line in block.splitlines():
        if not line.startswith("*") or line.startswith("**"):
            continue
        text = clean_wikitext(line.lstrip("* ").strip())
        text = re.split(r"[(,:;]", text)[0].strip()
        if text and RELATED_WORD.fullmatch(text):
            found.append(text)
    return found


def parse_page(wikitext):
    """What the French part of a page holds: its definitions in page order, its synonyms and its
    antonyms."""
    page = {"senses": [], "syn": [], "ant": []}
    start = wikitext.find("== {{langue|fr}} ==")
    if start < 0:
        return page
    end = wikitext.find("\n== {{langue|", start + 1)
    french = wikitext[start : end if end > 0 else len(wikitext)]

    marks = list(SECTION.finditer(french))
    for index, mark in enumerate(marks):
        name = mark.group(1).strip().lower()
        stop = marks[index + 1].start() if index + 1 < len(marks) else len(french)
        block = french[mark.end() : stop]
        if name in RELATED_SECTIONS:
            page[RELATED_SECTIONS[name]].extend(parse_related(block))
            continue
        pos = POS_SECTIONS.get(name)
        if pos is None:
            continue
        for line in block.splitlines():
            if line.startswith("# ") or (line.startswith("#") and line[1:2] not in ("*", ":", "#")):
                definition = clean_wikitext(line.lstrip("# ").strip())
                if definition:
                    page["senses"].append((pos, definition))
    return page


def usable_senses(senses, word):
    kept = []
    for rank, (pos, raw) in enumerate(senses):
        definition = cut_at_semicolon(raw)
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
            # A definition that has to be ellipsized to fit a cell is what makes a grid read like a
            # dictionary, so a shorter sense wins over the main one by a wide margin.
            + (45 if len(definition) > CLUE_MAX else 0)
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


def entry_of(cache, word):
    """A cached page. Entries written before the synonyms were kept are plain sense lists."""
    entry = cache.get(word)
    if isinstance(entry, dict):
        return entry
    return {"senses": entry or [], "syn": [], "ant": []}


def collect(candidates, cache):
    # An entry that is not a dict predates the synonym sections and has to be fetched again.
    missing = [word for _, word in candidates if not isinstance(cache.get(word), dict)]
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


def related_clues(entry, word, frequencies):
    """The crossword idiom: the most common synonyms and antonyms the page offers.

    A grid reads like a grid when its clues are these, not dictionary sentences, so several are
    kept per word: the more of them a word carries, the more often one comes out of the draw.
    """
    clues = []
    for kind, label in (("syn", "Synonyme"), ("ant", "Contraire")):
        options = {
            other
            for other in entry.get(kind, [])
            if 2 < len(other) < 25
            and not gives_it_away(other, word)
            and not gives_it_away(word, other)
            and frequencies.get(strip_accents(other).lower(), 0) >= MIN_RELATED_FREQ
        }
        best = sorted(options, key=lambda other: -frequencies[strip_accents(other).lower()])
        for other in best[:MAX_RELATED]:
            elided = strip_accents(other[0]).lower() in "aeiou"
            clues.append(f"{label} d'{other}" if elided else f"{label} de {other}")
    return clues


def build_rows(candidates, cache, frequencies):
    """One row per clue: the grid form, its frequency rank, the part of speech, the clue and the
    full definition behind it."""
    rows = []
    for rank, (form, word) in enumerate(candidates):
        entry = entry_of(cache, word)
        senses = usable_senses(entry["senses"], word)
        for pos, definition in senses:
            # Rank is the frequency order: the grid generator uses it to pick a difficulty.
            clue = shorten(definition, (" : ", ", "))
            rows.append(f"{form}\t{rank}\t{pos}\t{clue}\t{full_definition(definition)}")
        # A synonym clue stands on its own: it is how a grid reads, and a word whose definitions
        # were all thrown out still deserves to be in one.
        pos = senses[0][0] if senses else "n"
        for clue in related_clues(entry, word, frequencies):
            rows.append(f"{form}\t{rank}\t{pos}\t{clue}\t{clue}")
    return rows


def main():
    candidates, frequencies = load_candidates()
    cache = collect(candidates, load_cache())
    write_asset(build_rows(candidates, cache, frequencies), OUTPUT_PATH)


if __name__ == "__main__":
    sys.exit(main())
