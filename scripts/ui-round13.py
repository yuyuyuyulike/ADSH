#!/usr/bin/env python3
"""第十三轮现场自检：等设备空闲 → 发一条会组合调用 bash/写入的活 → 抓帧。

看这几件事：代码行摘要是不是 description、子调用是不是带竖线逐行长出来、
行上还有没有「0秒」、轮尾四个动作（复制/分支/用量/用时）在不在、滚动有没有跳。
"""
import os
import subprocess
import sys
import time

ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
OUT = "/mnt/d/WSN2005/Android1/App/ADSH/build/tmp"
APP = "com.adsh.app.debug/com.adsh.app.MainActivity"
BUSY = ("aweme", "douyin", "tencent", "wechat", "bilibili", "youtube", "chrome")
PROMPT = ("use%srun_code%sto%scall%sbash%s(pwd),%sthen%swrite%snotes/r13.md%swith%s3%slines,"
          "%sthen%sread%sit%sback%sand%sprint%sit,%sfinally%sgive%sa%sshort%sChinese%ssummary")


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


def shot(name):
    path = os.path.join(OUT, name)
    with open(path, "wb") as handle:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=handle, timeout=180)
    print("shot " + name, flush=True)


def main():
    wait = int(sys.argv[1]) if len(sys.argv) > 1 else 900
    deadline = time.time() + wait
    while time.time() < deadline:
        current = focus()
        if not any(word in current.lower() for word in BUSY) and awake():
            break
        time.sleep(10)
    print("free: " + focus(), flush=True)

    run("shell", "am", "start", "-n", APP)
    time.sleep(5)
    run("shell", "input", "tap", "640", "2436")
    time.sleep(0.8)
    run("shell", "input", "text", PROMPT)
    time.sleep(0.8)
    run("shell", "input", "tap", "1160", "1669")
    print("sent", flush=True)

    marks = [1.5, 3.0, 4.5, 6.0, 8.0, 10.0, 13.0, 16.0, 20.0, 25.0, 30.0, 36.0]
    started = time.time()
    for index, mark in enumerate(marks):
        while time.time() - started < mark:
            time.sleep(0.2)
        shot("r13-%02d.png" % index)
    # 关掉输入法，把轮尾那一排动作露出来
    run("shell", "input", "keyevent", "111")
    time.sleep(1.2)
    shot("r13-tail.png")
    run("shell", "input", "keyevent", "3")
    print("done", flush=True)


main()
