# Imports a flashcards JSON bundle into the app on the phone through FlashcardImportReceiver, a
# broadcast only the adb shell can send. The import upserts by id, so a bundle of corrected cards
# with zeroed progress fixes them. The phone may stay locked.
import sys
from pathlib import Path

from phone import FILES_DIR, PACKAGE, adb

INCLUDE_STOPPED_PACKAGES = "32"


def main():
    path = Path(sys.argv[1])
    adb("push", str(path), FILES_DIR)
    print(f"pushed {path.name}", flush=True)
    out = adb(
        "shell",
        "am",
        "broadcast",
        "-f",
        INCLUDE_STOPPED_PACKAGES,
        "-n",
        f"{PACKAGE}/.flashcards.FlashcardImportReceiver",
        "--es",
        "file",
        path.name,
    )
    print(out.strip(), flush=True)
    if 'data="imported"' not in out:
        sys.exit("import failed")
    print("imported", flush=True)


if __name__ == "__main__":
    main()
