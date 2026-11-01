# Runs Gradle and prints one progress line per task, counted against the same build's last total.
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COUNTS = ROOT / "build/gradle_task_counts.json"


def load_counts():
    try:
        return json.loads(COUNTS.read_text())
    except (OSError, ValueError):
        return {}


def gradle(label, *args):
    key = " ".join(args)
    counts = load_counts()
    total = counts.get(key)
    cmd = [str(ROOT / "gradlew"), "-p", str(ROOT), *args, "--console=plain"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    output = []
    done = 0
    for line in proc.stdout:
        output.append(line)
        if line.startswith("> Task "):
            done += 1
            task = line.split()[2].rsplit(":", 1)[-1]
            count = f"{done}/{max(total, done)}" if total else str(done)
            print(f"{label}: {task} ({count})", flush=True)
    if proc.wait() != 0:
        print("".join(output), flush=True)
        sys.exit(f"{label} failed")
    counts[key] = done
    COUNTS.parent.mkdir(exist_ok=True)
    COUNTS.write_text(json.dumps(counts, indent=1))
