#!/usr/bin/env python3
"""第十一轮的现场自检：不抢前台 —— 先等用户离开别的应用，再做一轮真实对话并抓帧。

对齐 scripts/ui-smoke.py 的做法：设备被占用时等待，用户还在用就不打扰。
"""
import os
import subprocess
import sys
import time

ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
OUT = "/mnt/d/WSN2005/Android1/App/ADSH/build/tmp"
APP = "com.adsh.app.debug/com.adsh.app.MainActivity"
BUSY = ("aweme", "douyin", "tencent", "wechat", "bilibili", "youtube", "chrome")


def run(*args, timeout=120, binary=False):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def text_of(*args):
    r = run(*args)
    return r.stdout.decode("utf-8", errors="replace")


def focus():
    for line in text_of("shell", "dumpsys", "window").splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return ""


def awake():
    return "mWakefulness=Awake" in text_of("shell", "dumpsys", "power")


def ime_shown():
    return "mInputShown=true" in text_of("shell", "dumpsys", "input_method")


def shot(name):
    path = os.path.join(OUT, name)
    with open(path, "wb") as handle:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=handle, timeout=180)
    return path


def main():
    wait = int(sys.argv[1]) if len(sys.argv) > 1 else 600
    deadline = time.time() + wait
    while time.time() < deadline:
        current = focus()
        busy = any(word in current.lower() for word in BUSY)
        if not busy and awake():
            break
        print("waiting; focus=" + current, flush=True)
        time.sleep(10)
    print("free: " + focus(), flush=True)

    run("shell", "am", "start", "-n", APP)
    time.sleep(3)
    shot("r11-00-enter.png")

    # 输入框 → 打字 → 发送（键盘弹出时发送键在右上）
    run("shell", "input", "tap", "640", "2436")
    time.sleep(0.8)
    run("shell", "input", "text", "use%srun_code%sto%sprint%s1..20,sthen%swrite%sa%s150-word%ssummary")
    time.sleep(0.8)
    shot("r11-01-typed.png")
    if ime_shown():
        run("shell", "input", "tap", "1160", "1669")
    else:
        run("shell", "input", "tap", "1166", "2619")
    print("sent", flush=True)

    marks = [0.8, 2.0, 3.5, 5.0, 7.0, 9.0, 12.0, 16.0, 21.0, 27.0, 34.0, 42.0]
    started = time.time()
    for index, mark in enumerate(marks):
        while time.time() - started < mark:
            time.sleep(0.2)
        shot("r11-10-%02d.png" % index)
        print("frame %d at %.1fs" % (index, mark), flush=True)

    run("shell", "input", "keyevent", "3")  # HOME：把设备还回去，不再占前台
    print("done", flush=True)


main()
