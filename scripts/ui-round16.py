#!/usr/bin/env python3
"""第十四轮自检：present 的 files 形状、工具失败时的报错信息（工具名/参数/原始错误）。"""
import os
import re
import subprocess
import sys
import time

ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
OUT = "/mnt/d/WSN2005/Android1/App/ADSH/build/tmp"
APP = "com.adsh.app.debug/com.adsh.app.MainActivity"
PROMPT = ("use%srun_code: (1)%swrite%snotes/r15.txt%s(one%sline), "
          "(2)%scall%spresent%swith%sthe%sdeclared%sshape%s(files%sarray), "
          "(3)%sthen%scall%sread%swith%sno%spath%sand%sDO%sNOT%scatch it, "
          "and%spaste%sthe%sexact%serror%stext%syou%sgot")


def run(*args, timeout=120):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def text_of(*args):
    return run(*args).stdout.decode("utf-8", errors="replace")


def idle_seconds():
    out = text_of("shell", "dumpsys", "input")
    parts = out.split("RecentQueue")
    block = parts[1] if len(parts) > 1 else out
    ages = [int(m.group(1)) for m in re.finditer(r"age=(\d+)ms", block)]
    return min(ages) / 1000.0 if ages else 1e9


def awake():
    return "mWakefulness=Awake" in text_of("shell", "dumpsys", "power")


def shot(name):
    with open(os.path.join(OUT, name), "wb") as handle:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=handle, timeout=180)
    print("shot " + name, flush=True)


wait = int(sys.argv[1]) if len(sys.argv) > 1 else 900
deadline = time.time() + wait
while time.time() < deadline:
    if awake() and idle_seconds() > 20:
        break
    time.sleep(5)
print("idle %.1fs" % idle_seconds(), flush=True)

run("shell", "am", "start", "-n", APP)
time.sleep(5)
run("shell", "input", "tap", "640", "2436")
time.sleep(0.8)
run("shell", "input", "text", PROMPT)
time.sleep(0.8)
run("shell", "input", "tap", "1160", "1669")
print("sent", flush=True)

for index, mark in enumerate([6.0, 10.0, 15.0, 20.0, 26.0, 32.0, 40.0, 48.0]):
    time.sleep(max(0.0, mark - (0 if index == 0 else 0)))
shot("r16-00.png")
run("shell", "input", "keyevent", "111")
time.sleep(1.5)
shot("r16-tail.png")
run("shell", "input", "keyevent", "3")
print("done", flush=True)
