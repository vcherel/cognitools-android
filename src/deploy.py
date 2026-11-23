# Builds the release APK, installs it on the connected phone and compiles it with its baseline
# profile. Stops before building when no phone is connected. --no-build installs the last build.
# The commit installed is written to build/deployed_commit, "dirty" when the tree had changes.
# --clean builds HEAD in a temporary worktree, leaving another session's uncommitted edits off the phone.
import shutil
import subprocess
import sys
import tempfile
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


def deploy_clean():
    tree = Path(tempfile.mkdtemp(prefix="cognitools-deploy-")) / "tree"
    run("creating a clean worktree", "git", "worktree", "add", "--detach", str(tree), "HEAD")
    try:
        shutil.copy(ROOT / "local.properties", tree)
        result = subprocess.run([sys.executable, str(tree / "src/deploy.py")])
        if result.returncode != 0:
            sys.exit("clean deploy failed")
        DEPLOYED.parent.mkdir(exist_ok=True)
        shutil.copy(tree / "build/deployed_commit", DEPLOYED)
    finally:
        run("removing the worktree", "git", "worktree", "remove", "--force", str(tree))
        shutil.rmtree(tree.parent, ignore_errors=True)


def main():
    devices = [line for line in adb("devices").splitlines()[1:] if line.strip().endswith("device")]
    if not devices:
        sys.exit("no phone connected")
    if "--clean" in sys.argv:
        deploy_clean()
        return
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
