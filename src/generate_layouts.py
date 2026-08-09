"""Build the Mots fleches grid layouts bundled with the app.

A layout only says which cells hold definitions and which hold letters; the app fills the
letter cells from the clue dictionary, so one layout yields an unlimited number of grids.

A layout is valid when:
  - the first row and the first column are definition cells, so every word starts right
    after a definition cell,
  - every run of letter cells is either a single cell (covered by the other direction) or
    a word of 3 to MAX_WORD letters,
  - no letter cell is alone in both directions,
  - definition cells carry a clue: a run of 3+ starting right of them or below them. A
    couple of plain black cells per grid are tolerated, as in printed grids.

Rows are laid out one at a time by a backtracking search: each row is picked from the
horizontal patterns that are valid on their own, and the vertical rules are checked as the
grid grows, so the search never wanders into layouts it cannot finish.

Run with:
    uv run --no-project src/generate_layouts.py
"""

import random
import sys

OUTPUT_PATH = "app/src/main/assets/motsfleches_layouts.txt"

SIZES = [(8, 10), (10, 13), (12, 15)]
LAYOUTS_PER_SIZE = 60
MIN_WORD = 3
MAX_WORD = 7
NODE_BUDGET = 40000
BRANCHING = 40  # row patterns tried per step, sampled at random for variety

DEF, LETTER = "#", "."


def empty_budget(width, height):
    """How many plain black squares a grid may hold: mostly the top row's, as in print."""
    return (width + height) // 3


def runs(line):
    """(start, length) of every maximal run of letter cells in a row or column."""
    found = []
    start = None
    for index, cell in enumerate(list(line) + [DEF]):
        if cell == LETTER and start is None:
            start = index
        elif cell != LETTER and start is not None:
            found.append((start, index - start))
            start = None
    return found


def violations(grid, width, height):
    """How many rules the layout breaks; 0 means it is playable. The final safety net."""
    penalty = 0
    horizontal = [[0] * width for _ in range(height)]
    vertical = [[0] * width for _ in range(height)]

    for r in range(height):
        for start, length in runs(grid[r]):
            if not (length == 1 or MIN_WORD <= length <= MAX_WORD):
                penalty += 1
            for c in range(start, start + length):
                horizontal[r][c] = length

    for c in range(width):
        for start, length in runs([grid[r][c] for r in range(height)]):
            if not (length == 1 or MIN_WORD <= length <= MAX_WORD):
                penalty += 1
            for r in range(start, start + length):
                vertical[r][c] = length

    empty_defs = 0
    for r in range(height):
        for c in range(width):
            if grid[r][c] == LETTER:
                if horizontal[r][c] < MIN_WORD and vertical[r][c] < MIN_WORD:
                    penalty += 1
            elif (r, c) != (0, 0):
                right = horizontal[r][c + 1] if c + 1 < width and grid[r][c + 1] == LETTER else 0
                below = vertical[r + 1][c] if r + 1 < height and grid[r + 1][c] == LETTER else 0
                if right < MIN_WORD and below < MIN_WORD:
                    empty_defs += 1
    return penalty + max(0, empty_defs - empty_budget(width, height))


def row_patterns(width):
    """Every row starting with a definition cell whose letter runs are 1 or 3..MAX_WORD."""
    patterns = []

    def extend(cells):
        if len(cells) == width:
            patterns.append(cells)
            return
        remaining = width - len(cells)
        extend(cells + [DEF])
        for length in [1] + list(range(MIN_WORD, MAX_WORD + 1)):
            if length > remaining:
                break
            # A run must be closed by a definition cell or by the grid edge.
            if length < remaining:
                extend(cells + [LETTER] * length + [DEF])
            else:
                extend(cells + [LETTER] * length)

    extend([DEF])
    return patterns


def horizontal_lengths(pattern):
    lengths = [0] * len(pattern)
    for start, length in runs(pattern):
        for index in range(start, start + length):
            lengths[index] = length
    return lengths


def build(width, height, patterns, rng):
    """One valid layout, or None once the node budget runs out."""
    grid = []
    allowed = empty_budget(width, height)
    # Per column: length of the run in progress, whether it must reach MIN_WORD (because a
    # cell only has its word vertically, or because a definition cell above needs a clue).
    budget = [NODE_BUDGET]

    def close_column(length, needed, empty_defs):
        """Check a vertical run that just ended. Returns the updated empty definition count."""
        if length == 0:
            return empty_defs if not needed else None
        if not (length == 1 or MIN_WORD <= length <= MAX_WORD):
            return None
        if needed and length < MIN_WORD:
            return None
        return empty_defs

    def place(row_index, vrun, vneed, pending, empty_defs):
        """pending[c] is True when the definition cell above column c still owes a clue."""
        if budget[0] <= 0:
            return False
        budget[0] -= 1
        if row_index == height:
            for c in range(width):
                updated = close_column(vrun[c], vneed[c], empty_defs)
                if updated is None:
                    return False
                empty_defs = updated
                if pending[c]:
                    empty_defs += 1
            return empty_defs <= allowed

        # The top row is all definition cells so no word starts against the upper edge.
        if row_index == 0:
            order = [[DEF] * width]
        else:
            order = rng.sample(patterns, min(len(patterns), BRANCHING))
        for pattern in order:
            lengths = horizontal_lengths(pattern)
            next_vrun = list(vrun)
            next_vneed = list(vneed)
            next_pending = [False] * width
            empties = empty_defs
            ok = True
            for c in range(width):
                if pattern[c] == LETTER:
                    if next_vrun[c] >= MAX_WORD:
                        ok = False
                        break
                    next_vrun[c] += 1
                    if lengths[c] < MIN_WORD:
                        next_vneed[c] = True
                    if pending[c]:
                        # The definition cell above needs this run to be a real word.
                        next_vneed[c] = True
                else:
                    updated = close_column(next_vrun[c], next_vneed[c], empties)
                    if updated is None:
                        ok = False
                        break
                    empties = updated
                    if pending[c]:
                        empties += 1
                    next_vrun[c] = 0
                    next_vneed[c] = False
                    # This definition cell owes a clue unless a word starts to its right.
                    if not (row_index == 0 and c == 0):
                        next_pending[c] = not (c + 1 < width and lengths[c + 1] >= MIN_WORD)
            if not ok or empties > allowed:
                continue
            grid.append(pattern)
            if place(row_index + 1, next_vrun, next_vneed, next_pending, empties):
                return True
            grid.pop()
        return False

    if not place(0, [0] * width, [False] * width, [False] * width, 0):
        return None
    return grid


def main():
    rng = random.Random(20260809)
    blocks = []
    for width, height in SIZES:
        patterns = row_patterns(width)
        full_row = [DEF] * width
        seen = set()
        tries = 0
        while len(seen) < LAYOUTS_PER_SIZE and tries < LAYOUTS_PER_SIZE * 20:
            tries += 1
            grid = build(width, height, patterns, rng)
            if grid is None or grid[0] != full_row:
                continue
            if violations(grid, width, height) != 0:
                continue
            key = "".join("".join(row) for row in grid)
            if key in seen:
                continue
            seen.add(key)
            blocks.append(f"{width} {height}\n" + "\n".join("".join(row) for row in grid))
        print(f"{width}x{height}: {len(seen)} layouts in {tries} tries ({len(patterns)} row patterns)")

    with open(OUTPUT_PATH, "w", encoding="utf-8") as handle:
        handle.write("\n\n".join(blocks) + "\n")
    print(f"Wrote {len(blocks)} layouts to {OUTPUT_PATH}")


if __name__ == "__main__":
    sys.exit(main())
