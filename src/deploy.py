# Builds the release APK, installs it on the connected phone and compiles it with its baseline
# profile. Stops before building when no phone is connected. --no-build installs the last build.
# The commit installed is written to build/deployed_commit, "dirty" when the tree had changes.
import subprocess
import sys
from pathlib import Path

from gradle import gradle
from phone import PACKAGE, adb

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "app/build/outputs/apk/release/app-release.apk"
BUILT = APK.with_name("built_commit")
DEPLOYED = ROOT / "build/deployed_commit"


def head_commit():
    def git(*args):
        return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout.strip()

    commit = git("rev-parse", "HEAD")
    dirty = git("status", "--porcelain", "--untracked-files=no")
    return commit + (" dirty" if dirty else "")


def run(step, *cmd):
    print(step, flush=True)
    result = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout + result.stderr, flush=True)
        sys.exit(f"{step} failed")
    return result.stdout


def main():
    devices = [line for line in adb("devices").splitlines()[1:] if line.strip().endswith("device")]
    if not devices:
        sys.exit("no phone connected")
    if "--no-build" not in sys.argv:
        commit = head_commit()
        gradle("building 1/4", "assembleRelease")
        BUILT.write_text(commit + "\n")
    run("installing 2/4", "adb", "install", "-r", str(APK))
    DEPLOYED.parent.mkdir(exist_ok=True)
    DEPLOYED.write_text(BUILT.read_text() if BUILT.exists() else "unknown\n")
    # Without the broadcast first, the compile below compiles an empty profile.
    run(
        "installing the profile 3/4",
        "adb",
        "shell",
        "am",
        "broadcast",
        "-a",
        "androidx.profileinstaller.action.INSTALL_PROFILE",
        f"{PACKAGE}/androidx.profileinstaller.ProfileInstallReceiver",
    )
    run("compiling 4/4", "adb", "shell", "cmd", "package", "compile", "-m", "speed-profile", "-f", PACKAGE)
    print("deployed", flush=True)


if __name__ == "__main__":
    main()
