"""Build the English Mots fleches clue dictionary from WordNet + wordfreq.

The English twin of generate_motsfleches.py, and it writes the exact same format. Words are
ranked by wordfreq's frequency list; their definitions are WordNet glosses, which already
read like dictionary definitions, so no crawl and no wikitext parsing is needed.

Run with:
    uv run --no-project --with nltk --with wordfreq src/generate_motsfleches_en.py
"""

import os
import re
import sys

import nltk
from nltk.corpus import wordnet
from wordfreq import top_n_list

OUTPUT_PATH = "app/src/main/assets/motsfleches_dict_en.txt"

MIN_LENGTH = 3
MAX_LENGTH = 11
# Read down the frequency list until this many usable words are found.
MAX_WORDS = 34000
FREQUENCY_POOL = 120000
MAX_SENSES = 2
CLUE_MAX = 48
FULL_MAX = 240

POS_NAMES = {"n": "n", "a": "adj", "s": "adj", "v": "v", "r": "adv"}

# A gloss that only points at another form, or needs the sentence it came from, makes a useless clue.
USELESS_DEF = re.compile(
    r"^(a |an |the )?("
    r"used to|used as|used in|used with|of or |relating to|pertaining to|being |having |"
    r"(the )?(act|state|quality|condition|property) of$"
    r")",
    re.IGNORECASE,
)


def grid_form(word):
    """The A-Z form a word takes inside a grid, or None if it cannot go in one."""
    upper = word.upper()
    if not re.fullmatch(r"[A-Z]+", upper):
        return None
    return upper


def gives_it_away(definition, word):
    """True when the definition contains the answer (or an obvious inflection of it)."""
    stem = word.lower()[: max(4, len(word) - 2)]
    return any(token.startswith(stem) for token in re.findall(r"[a-z]+", definition.lower()))


def shorten(definition):
    if len(definition) <= CLUE_MAX:
        return definition
    for separator in ("; ", ": ", ", "):
        cut = definition.find(separator)
        if 18 <= cut <= CLUE_MAX:
            return definition[:cut]
    return definition[:CLUE_MAX].rsplit(" ", 1)[0] + "…"


def clean(gloss):
    """WordNet glosses carry their usage examples after a semicolon, in quotes."""
    gloss = re.split(r';\s*"', gloss)[0]
    gloss = re.sub(r"\s+", " ", gloss).strip(" ;,")
    gloss = re.sub(r"^\(([^)]{1,20})\)\s*", r"(\1) ", gloss)
    return gloss[:1].upper() + gloss[1:] if gloss else gloss


def usable_senses(word):
    """Up to MAX_SENSES clues for [word], main senses first, or [] if none can be used."""
    kept = []
    for rank, synset in enumerate(wordnet.synsets(word)):
        pos = POS_NAMES.get(synset.pos())
        if pos is None:
            continue
        # morphy maps inflections onto their lemma ("running" -> "run"): only keep the real lemma,
        # and drop proper nouns, whose lemma stays capitalised in WordNet.
        names = synset.lemma_names()
        if word not in names or any(name[0].isupper() for name in names if name.lower() == word):
            continue
        definition = clean(synset.definition())
        if len(definition) < 12 or USELESS_DEF.match(definition):
            continue
        if gives_it_away(definition, word):
            continue
        # Main senses first, short ones first: a definition that has to be cut to fit a grid cell
        # loses most of its meaning, so a short later sense beats a long winded first one.
        score = rank * 6 + len(definition) / 6 + (25 if len(definition) > CLUE_MAX else 0)
        kept.append((score, pos, definition))
    kept.sort(key=lambda item: item[0])
    return [(pos, definition) for _, pos, definition in kept[:MAX_SENSES]]


def build_rows():
    rows = []
    words = 0
    seen = set()
    for word in top_n_list("en", FREQUENCY_POOL):
        if words >= MAX_WORDS:
            break
        if not MIN_LENGTH <= len(word) <= MAX_LENGTH:
            continue
        form = grid_form(word)
        if form is None or form in seen:
            continue
        senses = usable_senses(word)
        if not senses:
            continue
        seen.add(form)
        for pos, definition in senses:
            full = definition[:FULL_MAX].rsplit(" ", 1)[0] if len(definition) > FULL_MAX else definition
            # Rank is the frequency order: the grid generator uses it to pick a difficulty.
            rows.append(f"{form}\t{words}\t{pos}\t{shorten(definition)}\t{full}")
        words += 1
        if words % 5000 == 0:
            print(f"  {words} words", flush=True)
    return rows, words


def main():
    nltk.download("wordnet", quiet=True)
    rows, words = build_rows()
    # Plain text on purpose: the Android build unpacks a .gz asset at packaging time, and the APK
    # deflates whatever it ships anyway.
    with open(OUTPUT_PATH, "w", encoding="utf-8") as handle:
        handle.write("\n".join(rows))
    size = os.path.getsize(OUTPUT_PATH)
    print(f"Wrote {len(rows)} clues for {words} words to {OUTPUT_PATH} ({size / 1e6:.1f} MB)")


if __name__ == "__main__":
    sys.exit(main())
