# Pulls the Musique tool's logs off the phone and groups the error and crossfade logs by kind of line.
import re
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

from phone import adb

LOG_DIR = "/sdcard/Android/data/com.example.myapp/files/"
LINE = re.compile(r"(\d\d-\d\d) (\d\d:\d\d:\d\d) (\S+) ?(.*)")
TRACK_ID = re.compile(r"\b\d{5,}\b")
NUMBER = re.compile(r"-?\b\d+\b")


def pull(out: Path) -> Path:
    adb("pull", LOG_DIR, str(out))
    files = out / "files"
    return files if files.is_dir() else out


def summarize(errors: Path) -> None:
    counts = Counter()
    span = {}
    days_per_track = defaultdict(set)
    per_day = Counter()
    for line in errors.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE.match(line)
        if not m:
            continue
        day, time, kind, rest = m.groups()
        per_day[day] += 1
        # Obfuscated class names (k:, q7:) change with every build: fold them so one failure is one group.
        key = kind + " " + re.sub(r"\b[a-z]\d?: ", "", NUMBER.sub("N", rest))[:160]
        counts[key] += 1
        first, _ = span.get(key, (f"{day} {time}", None))
        span[key] = (first, f"{day} {time}")
        if "no sufficient rights" in rest and kind == "playback":
            days_per_track[TRACK_ID.search(rest).group()].add(day)

    print(
        f"{sum(counts.values())} lines, {len(per_day)} days, last 7 days: "
        + ", ".join(f"{d} {per_day[d]}" for d in sorted(per_day)[-7:])
    )
    print()
    for key, n in counts.most_common(25):
        first, last = span[key]
        print(f"{n:4d}  {first} .. {last}  {key}")
    again = {t: sorted(d) for t, d in days_per_track.items() if len(d) > 1}
    if again:
        print(f"\nrefused on more than one day: {len(again)} of {len(days_per_track)} refused tracks")
        for track, days in sorted(again.items(), key=lambda kv: -len(kv[1]))[:10]:
            print(f"  {track}  {' '.join(days)}")


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(tempfile.mkdtemp(prefix="deezer_logs_"))
    out.mkdir(parents=True, exist_ok=True)
    files = pull(out)
    print(f"logs in {files}", flush=True)
    for name in ("deezer_errors.txt", "deezer_crossfade_log.txt"):
        log = files / name
        print(f"\n== {name}")
        if log.exists():
            summarize(log)
        else:
            print("not on the phone yet")


if __name__ == "__main__":
    main()
