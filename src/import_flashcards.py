# Imports a flashcards JSON bundle into the app on the phone, through the lists screen's restore
# button. The import upserts by id, so a bundle of corrected cards with zeroed progress fixes them.
import sys
import time
from pathlib import Path

from phone import (
    adb,
    back,
    countdown,
    desc,
    find,
    nodes,
    open_app_at_menu,
    tap,
    tap_node,
    text,
    wait_until_unlocked,
)


def dump():
    for n in nodes():
        if n.get("text") or n.get("content-desc"):
            print("   ", repr(n.get("text")), repr(n.get("content-desc")), n.get("bounds"), flush=True)


def pick_file(name):
    node = find(text(name), timeout=4)
    if node is None:
        # The picker may open on Recents: go to Downloads through its drawer.
        tap(desc("Show roots"), "picker drawer")
        for label in ("Downloads", "Téléchargements", "Download"):
            if find(text(label), timeout=1.5) is not None:
                tap(text(label), label)
                break
        node = find(text(name), timeout=6)
    if node is not None:
        tap_node(node)
    return node is not None


def main():
    path = Path(sys.argv[1])
    adb("push", str(path), "/sdcard/Download/")
    print(f"pushed {path.name}", flush=True)
    countdown()
    wait_until_unlocked()
    if not open_app_at_menu():
        sys.exit("main menu not reached")
    steps = [
        lambda: tap(text("Flashcards"), "Flashcards"),
        lambda: tap(desc("Restaurer"), "restore button"),
        lambda: pick_file(path.name),
        lambda: tap(text("Importer"), "import confirmation"),
    ]
    for step in steps:
        if not step():
            dump()
            sys.exit("import failed")
    time.sleep(2)
    back()
    print("imported", flush=True)


if __name__ == "__main__":
    main()
