"""The parts of the mots fleches clue dictionary that are the same in both languages.

Shared by generate_motsfleches.py (French) and generate_motsfleches_en.py (English): the size
limits, the shortening rules and the asset file format. What differs between the two, where the
words and the definitions come from and what a grid form looks like, stays in each script.
"""

import os
import re
import unicodedata

MIN_LENGTH = 3
MAX_LENGTH = 11
MAX_WORDS = 34000
MAX_SENSES = 2
# How long a clue can be before it has to be cut to fit a grid cell.
CLUE_MAX = 48
# How long the full definition kept for the answer check can be.
FULL_MAX = 240
# What is left of a definition once it is cut at its first semicolon has to still say something.
MIN_SEGMENT = 12


def strip_accents(text):
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn")


def gives_it_away(definition, word):
    """True when the definition contains the answer (or an obvious inflection of it)."""
    plain = strip_accents(definition).lower()
    stem = strip_accents(word).lower()[: max(4, len(word) - 2)]
    return any(token.startswith(stem) for token in re.findall(r"[a-z]+", plain))


def cut_at_semicolon(definition):
    """A definition that goes on after a semicolon is really several: the first one is the clue.

    Applied to the whole definition, not only to the ones too long for a cell, so the clue bar
    never shows a sentence with three senses strung together either.
    """
    parts = definition.split(";")
    if len(parts) == 1:
        return definition
    kept = ""
    for part in parts:
        kept = f"{kept};{part}" if kept else part
        if len(kept.strip()) >= MIN_SEGMENT:
            break
    return kept.strip(" ,;:")


def shorten(definition, separators=(": ", ", ")):
    """The definition cut down to a clue that fits a cell, at a separator when there is one."""
    if len(definition) <= CLUE_MAX:
        return definition
    for separator in separators:
        cut = definition.find(separator)
        if 18 <= cut <= CLUE_MAX:
            return definition[:cut]
    return definition[:CLUE_MAX].rsplit(" ", 1)[0] + "…"


def full_definition(definition):
    """The whole definition shown when checking an answer, capped on a word boundary."""
    if len(definition) <= FULL_MAX:
        return definition
    return definition[:FULL_MAX].rsplit(" ", 1)[0]


def write_asset(rows, output_path):
    # Plain text on purpose: the Android build unpacks a .gz asset at packaging time, and the APK
    # deflates whatever it ships anyway.
    with open(output_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(rows))
    words = len({row.split("\t", 1)[0] for row in rows})
    size = os.path.getsize(output_path)
    print(f"Wrote {len(rows)} clues for {words} words to {output_path} ({size / 1e6:.1f} MB)")
