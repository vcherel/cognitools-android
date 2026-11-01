# Frame times per navigation step, from the app's gfxinfo counters reset between steps.
# Runs a fixed journey (Notes opened and scrolled down and up, then the flashcard lists, "All",
# a list, swipes on its review, each undone) so two builds compare; compare them within one
# session, frame times swing up to 4x between sessions. `--only notes` or `--only flashcards`
# runs one half: the flashcards half answers real reviews and undoes them, so skip it unless
# the flashcards are what is measured.
#
# The janky count alone hides a slow screen: the phone runs at 90 Hz and pipelines the UI and
# render threads, so each step also prints its per stage times (from the last 120 frames):
# ui is the app's own work (input, composition, layout, recording), draw the render thread
# issuing the draw commands, swap the wait on the buffer and the GPU.
import argparse
import re
import sys

from phone import (
    PACKAGE,
    adb,
    back,
    countdown,
    desc,
    drag,
    fling,
    open_app_at_menu,
    swipe_left,
    tap,
    text,
    wait_until_unlocked,
)

COUNTERS = (r"Total frames rendered: (\d+)", r"Janky frames: (\d+)", r"Number Slow UI thread: (\d+)")

totals = {}


def undo_enabled(node):
    return node.get("content-desc") == "Undo" and node.get("enabled") == "true"


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, len(s) * p // 100)] if s else 0.0


def stages(out):
    ui, draw, swap = [], [], []
    header = None
    for line in out.splitlines():
        if line.startswith("Flags,"):
            header = line.rstrip(",").split(",")
        elif header and re.match(r"^\d+,", line):
            row = dict(zip(header, map(int, line.rstrip(",").split(",")), strict=False))
            if row["Flags"] == 0:
                ui.append((row["SyncQueued"] - row["IntendedVsync"]) / 1e6)
                draw.append((row["SwapBuffers"] - row["IssueDrawCommandsStart"]) / 1e6)
                swap.append((row["FrameCompleted"] - row["SwapBuffers"]) / 1e6)
        else:
            header = None
    return ui, draw, swap


def step(name):
    out = adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    counts = [int(re.search(pattern, out).group(1)) for pattern in COUNTERS]
    total = totals.setdefault(name, [0, 0, 0])
    for i, c in enumerate(counts):
        total[i] += c
    ui, draw, swap = stages(out)
    times = (
        f" | ui p50 {pct(ui, 50):.1f} p90 {pct(ui, 90):.1f} ms, draw p50 {pct(draw, 50):.1f} ms,"
        f" swap p50 {pct(swap, 50):.1f} ms"
        if ui
        else ""
    )
    print(f"    {name}: {counts[1]} janky, {counts[2]} slow UI, of {counts[0]} frames{times}", flush=True)


def notes():
    if not tap(text("Notes"), "Notes"):
        return
    step("open notes")
    for _ in range(6):
        drag(up=True)
    step("scroll notes down")
    for _ in range(6):
        drag(up=False)
    step("scroll notes up")
    back()


def flashcards():
    if not tap(text("Flashcards"), "Flashcards"):
        return
    step("open lists")
    if tap(desc("Search everything"), "All"):
        step("open All")
        fling()
        back()
        step("scroll All and back")
    if tap(lambda n: n.get("text", "").endswith("to review"), "a list"):
        step("open a list")
        fling()
        step("scroll list")
        if tap(text("Play"), "Play"):
            step("open review")
            # Each answer undone, so the real cards keep their progress.
            for _ in range(3):
                swipe_left()
                if not tap(undo_enabled, "Undo"):
                    print("  WARNING: an answer was left recorded on a real card", flush=True)
                    break
            step("swipe and undo")
            back()
        back()
        step("back to lists")
    back()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("rounds", nargs="?", type=int, default=3)
    parser.add_argument("--only", choices=("notes", "flashcards"))
    args = parser.parse_args()
    journeys = [j for j in (notes, flashcards) if args.only in (None, j.__name__)]

    wait_until_unlocked()
    countdown()
    if not open_app_at_menu():
        sys.exit("main menu not reached")
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")

    for r in range(1, args.rounds + 1):
        print(f"round {r}/{args.rounds}", flush=True)
        for journey in journeys:
            journey()
            open_app_at_menu()
            step("back to menu")

    print("totals over all rounds:", flush=True)
    for name, (frames, janky, slow) in totals.items():
        print(f"  {name}: {janky} janky, {slow} slow UI, of {frames} frames", flush=True)
    frames, janky = sum(t[0] for t in totals.values()), sum(t[1] for t in totals.values())
    print(f"  all: {janky} janky of {frames} frames", flush=True)


if __name__ == "__main__":
    main()
