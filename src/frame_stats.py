# Janky frames per navigation step, from the app's gfxinfo counters reset between steps.
# Runs a fixed journey (Notes, the flashcard lists, "Tout", a list) so two builds compare;
# compare them within one session, frame times swing up to 4x between sessions.
import re
import sys

from phone import (
    PACKAGE,
    adb,
    back,
    countdown,
    desc,
    fling,
    open_app_at_menu,
    tap,
    text,
    wait_until_unlocked,
)

COUNTERS = (r"Total frames rendered: (\d+)", r"Janky frames: (\d+)", r"Number Slow UI thread: (\d+)")

totals = {}


def step(name):
    out = adb("shell", "dumpsys", "gfxinfo", PACKAGE)
    counts = [int(re.search(pattern, out).group(1)) for pattern in COUNTERS]
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    total = totals.setdefault(name, [0, 0, 0])
    for i, c in enumerate(counts):
        total[i] += c
    print(f"    {name}: {counts[1]} janky, {counts[2]} slow UI, of {counts[0]} frames", flush=True)


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    wait_until_unlocked()
    countdown()
    if not open_app_at_menu():
        sys.exit("main menu not reached")
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")

    for r in range(1, rounds + 1):
        print(f"round {r}/{rounds}", flush=True)
        if tap(text("Notes"), "Notes"):
            fling()
            back()
        open_app_at_menu()
        step("notes")
        if tap(text("Flashcards"), "Flashcards"):
            step("open lists")
            if tap(desc("Rechercher dans tout"), "Tout"):
                step("open Tout")
                fling()
                back()
                step("scroll Tout and back")
            if tap(lambda n: n.get("text", "").endswith("à réviser"), "a list"):
                step("open a list")
                fling()
                back()
                step("scroll list and back")
            back()
        open_app_at_menu()
        step("back to menu")

    print("totals over all rounds:", flush=True)
    for name, (frames, janky, slow) in totals.items():
        print(f"  {name}: {janky} janky, {slow} slow UI, of {frames} frames", flush=True)
    frames, janky = sum(t[0] for t in totals.values()), sum(t[1] for t in totals.values())
    print(f"  all: {janky} janky of {frames} frames", flush=True)


if __name__ == "__main__":
    main()
