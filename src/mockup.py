# Renders HTML mockups to PNG with headless Firefox, then opens the first one.
# Mockups live in the gitignored mockups/ folder, which survives across design rounds.
# Pages load the cached fonts as url(fonts/inter.woff2) and url(fonts/symbols.woff2)
# (Material Symbols Rounded, ligatures: <i style="font-family:Sym">music_note</i>).
# The app's real styles (mockup_base.css) are copied next to each page as app.css: link it and
# build tool screens from its classes rather than a look of your own.
import argparse
import shutil
import subprocess
import urllib.request
from pathlib import Path

MOCKUPS = Path(__file__).resolve().parent.parent / "mockups"
BASE_CSS = Path(__file__).resolve().parent / "mockup_base.css"
FONTS = {
    "inter.woff2": "https://fonts.gstatic.com/s/inter/v20/UcC73FwrK3iLTeHuS_nVMrMxCp50SjIa1ZL7.woff2",
    "symbols.woff2": "https://fonts.gstatic.com/s/materialsymbolsrounded/v383/"
    "syl7-zNym6YjUruM-QrEh7-nyTnjDwKNJ_190FjpZIvLgyidOK7BDB_Qb9vUdV6_gjDK-P3JuF_Zs-obHph2-jOcZTKPqw.woff2",
}


def ensure_base(folder: Path):
    shutil.copy(BASE_CSS, folder / "app.css")


def ensure_fonts(folder: Path):
    fonts = folder / "fonts"
    fonts.mkdir(parents=True, exist_ok=True)
    for name, url in FONTS.items():
        target = fonts / name
        if not target.exists():
            source = MOCKUPS / "fonts" / name
            if source.exists() and source != target:
                shutil.copy(source, target)
            else:
                urllib.request.urlretrieve(url, target)


def render(page: Path, size: str, profile: Path) -> Path:
    out = page.resolve().with_suffix(".png")
    out.unlink(missing_ok=True)
    result = subprocess.run(
        [
            "firefox",
            "--headless",
            "--no-remote",
            "--profile",
            str(profile),
            "--window-size",
            size,
            "--screenshot",
            str(out),
            page.resolve().as_uri(),
        ],
        capture_output=True,
        text=True,
    )
    if not out.exists():
        raise SystemExit(f"firefox wrote no {out}:\n{result.stdout}{result.stderr}")
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pages", nargs="+", type=Path)
    parser.add_argument("--size", default="940,920", help="window width,height in CSS px")
    parser.add_argument("--no-open", action="store_true")
    args = parser.parse_args()

    profile = MOCKUPS / ".ffprofile"
    profile.mkdir(parents=True, exist_ok=True)
    ensure_fonts(MOCKUPS)
    shots = []
    for i, page in enumerate(args.pages, 1):
        ensure_fonts(page.resolve().parent)
        ensure_base(page.resolve().parent)
        shots.append(render(page, args.size, profile))
        print(f"rendered {i}/{len(args.pages)} {shots[-1]}", flush=True)
    if not args.no_open:
        subprocess.Popen(
            ["setsid", "-f", "xdg-open", str(shots[0])], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )


if __name__ == "__main__":
    main()
