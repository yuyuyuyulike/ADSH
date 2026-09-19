#!/usr/bin/env python3
"""等设备真的闲下来（最近 20 秒没人碰屏幕 + 应用在前台），再跑一条很短的活，
验证：本轮用量（轮尾「用量 N tok」）与 PTC 子调用是逐行长出来的。"""
import os
import re
import subprocess
import sys
import time

ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
OUT = "/mnt/d/WSN2005/Android1/App/ADSH/build/tmp"
APP = "com.adsh.app.debug/com.adsh.app.MainActivity"
PROMPT = "use%srun_code%stosecall%sbash('pwd')%sand%sprint%sthe%sresult,%sthen%sone%sChinese%ssentence"


def run(*args, timeout=120):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def text_of(*args):
    return run(*args).stdout.decode("utf-8", errors="replace")


def focus():
    for line in text_of("shell", "dumpsys", "window").splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return ""


def awake():
    return "mWakefulness=Awake" in text_of("shell", "dumpsys", "power")


def idle_seconds():
    """最近一次触摸距今多少秒：只看第一个 RecentQueue（主触摸屏）里最新的一条"""
    out = text_of("shell", "dumpsys", "input")
    parts = out.split("RecentQueue")
    block = parts[1] if len(parts) > 1 else out
    ages = [int(m.group(1)) for m in re.finditer(r"age=(\d+)ms", block)]
    return min(ages) / 1000.0 if ages else 1e9


def shot(name):
    path = os.path.join(OUT, name)
    with open(path, "wb") as handle:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=handle, timeout=180)
    print("shot " + name, flush=True)


def main():
    wait = int(sys.argv[1]) if len(sys.argv) > 1 else 1800
    deadline = time.time() + wait
    while time.time() < deadline:
        if awake() and idle_seconds() > 20:
            break
        time.sleep(5)
    print("idle for %.1fs, focus=%s" % (idle_seconds(), focus()), flush=True)

    run("shell", "am", "start", "-n", APP)
    time.sleep(4)
    run("shell", "input", "tap", "640", "2436")
    time.sleep(0.8)
    run("shell", "input", "text", PROMPT)
    time.sleep(0.8)
    run("shell", "input", "tap", "1160", "1669")
    print("sent", flush=True)

    marks = [2.0, 4.0, 6.0, 9.0, 12.0, 16.0, 20.0, 25.0]
    started = time.time()
    for index, mark in enumerate(marks):
        while time.time() - started < mark:
            time.sleep(0.2)
        shot("r13b-%02d.png" % index)
    run("shell", "input", "keyevent", "111")
    time.sleep(1.5)
    shot("r13b-tail.png")
    run("shell", "input", "keyevent", "3")
    print("done", flush=True)


main()
