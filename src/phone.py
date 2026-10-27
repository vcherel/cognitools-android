# Drives the app on the connected phone through adb, finding what to tap in uiautomator dumps.
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.example.myapp"
MENU_TITLE = "Bienvenue !"


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    try:
        return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml")).iter("node"))
    except ET.ParseError:
        return []


def box(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def tap_node(node, settle=1.5):
    x1, y1, x2, y2 = box(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(settle)


# Compared to None, never by truth value: an element without children is falsy.
def find(pred, timeout=6):
    end = time.time() + timeout
    while time.time() < end:
        for n in nodes():
            if pred(n):
                return n
        time.sleep(0.3)
    return None


def tap(pred, label):
    n = find(pred)
    if n is None:
        print(f"  could not find {label}", flush=True)
        return False
    tap_node(n)
    return True


def text(t):
    return lambda n: n.get("text") == t


def desc(d):
    return lambda n: n.get("content-desc") == d


def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.2)


def fling():
    adb("shell", "input", "swipe", "540", "1800", "540", "500", "80")
    time.sleep(1)
    adb("shell", "input", "swipe", "540", "500", "540", "1800", "80")
    time.sleep(1)


def is_locked():
    return "mIsShowing=true" in adb("shell", "dumpsys", "window", "policy")


def wait_until_unlocked():
    if not is_locked():
        return
    print("the phone is locked: unlock it to continue", flush=True)
    while is_locked():
        time.sleep(2)


def open_app_at_menu():
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    time.sleep(2)
    for _ in range(6):
        if find(text(MENU_TITLE), timeout=1.5) is not None:
            return True
        back()
    return False


def countdown(seconds=5):
    for i in range(seconds, 0, -1):
        print(f"starting in {i}, leave the phone alone", flush=True)
        time.sleep(1)
