# Records the baseline profile on the phone and checks the result. The APKs are built first, so the
# phone is only needed for the recording itself (about 5 minutes); the release build is not deployed.
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

from phone import adb, box, nodes, open_app_at_menu, tap, tap_node, text, wait_until_unlocked

ROOT = Path(__file__).resolve().parent.parent
PROFILE = ROOT / "app/src/release/generated/baselineProfiles/baseline-prof.txt"
TYPED_LINE = "Une_ligne_assez_longue_pour_remplir_la_largeur"
# Every screen the journey opens. A step that misses its label is skipped without any error,
# which once left every tool after Notes out of the profile for a month.
SCREENS = [
    "notes/NotesListScreenKt",
    "notes/NoteEditorScreenKt",
    "deezer/DeezerLibraryScreenKt",
    "flashcards/ListsScreenKt",
    "flashcards/DetailScreenKt",
    "flashcards/GameScreenKt",
    "gallery/AlbumsScreenKt",
    "gallery/AlbumGridScreenKt",
]


def gradle(*tasks):
    subprocess.run([str(ROOT / "gradlew"), "-p", str(ROOT), *tasks, "--console=plain"], check=True)


def turbo(state):
    if shutil.which("turbo-toggle"):
        subprocess.run(["turbo-toggle", state])


# MIUI locks the phone mid run otherwise; a harmless keypress resets the screen timeout, WAKEUP does not.
def keep_awake(stop):
    while not stop.wait(10):
        adb("shell", "input", "keyevent", "KEYCODE_SHIFT_LEFT")


def record():
    turbo("off")
    try:
        print("building the APKs", flush=True)
        gradle(":app:assembleNonMinifiedRelease", ":baselineprofile:assembleNonMinifiedRelease")
        wait_until_unlocked()
        print("recording on the phone, leave it alone for about 5 minutes", flush=True)
        stop = threading.Event()
        threading.Thread(target=keep_awake, args=(stop,), daemon=True).start()
        try:
            gradle(
                ":app:generateReleaseBaselineProfile",
                "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
            )
        finally:
            stop.set()
    finally:
        turbo("on")


def missing_screens():
    profile = PROFILE.read_text()
    return [s for s in SCREENS if f"com/example/myapp/{s}" not in profile]


def test_note_bin(ns):
    bins = [n for n in ns if n.get("content-desc") == "Supprimer" and box(n)[3] < 1500]
    for n in ns:
        if n.get("text") != TYPED_LINE:
            continue
        x1, y1, x2, _ = box(n)
        below = [b for b in bins if box(b)[1] > y1 and box(b)[0] < x2 and box(b)[2] > x1]
        if below:
            return min(below, key=lambda b: box(b)[1])
    return None


# The journey sends its note to the trash itself; this catches a pass where that failed. It only
# confirms a dialog naming the typed note, so a real note can never go.
def trash_test_notes():
    if not open_app_at_menu() or not tap(text("Notes"), "Notes"):
        print("could not open the notes, check them by hand", flush=True)
        return
    trashed = 0
    previous = None
    for _ in range(60):
        ns = nodes()
        bin_node = test_note_bin(ns)
        if bin_node is None:
            texts = [n.get("text") for n in ns]
            if texts == previous:
                break
            previous = texts
            adb("shell", "input", "swipe", "360", "1300", "360", "500", "300")
            time.sleep(1)
            continue
        tap_node(bin_node, settle=1)
        dialog = nodes()
        names_test_note = any(f"« {TYPED_LINE} »" in (n.get("text") or "") for n in dialog)
        ok = next((n for n in dialog if n.get("text") == "Ok"), None)
        if not names_test_note or ok is None:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            print("unexpected dialog, check the notes by hand", flush=True)
            return
        tap_node(ok, settle=1.2)
        trashed += 1
        print(f"test note {trashed} sent to the trash", flush=True)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    print(f"{trashed} leftover test notes sent to the trash", flush=True)


def main():
    record()
    missing = missing_screens()
    trash_test_notes()
    if missing:
        sys.exit("missing from the profile: " + ", ".join(missing))
    print("every screen is in the profile; build and deploy the release to ship it", flush=True)


if __name__ == "__main__":
    main()
