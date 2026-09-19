#!/usr/bin/env python3
"""核对：上下文面板的「工具定义」、模型菜单的分组标题、回到底部按钮。"""
import os
import subprocess
import time

ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
OUT = "/mnt/d/WSN2005/Android1/App/ADSH/build/tmp"
APP = "com.adsh.app.debug/com.adsh.app.MainActivity"


def run(*args, timeout=120):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def shot(name):
    with open(os.path.join(OUT, name), "wb") as handle:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=handle, timeout=180)
    print("shot " + name, flush=True)


run("shell", "am", "start", "-n", APP)
time.sleep(5)
# 模型菜单：先点触发键 → 再点「模型」行
run("shell", "input", "tap", "837", "2543")
time.sleep(0.9)
run("shell", "input", "tap", "400", "2258")
time.sleep(0.9)
shot("r14-10-model-list.png")
run("shell", "input", "keyevent", "4")
time.sleep(0.6)
# 上下文面板
run("shell", "input", "tap", "957", "2543")
time.sleep(0.9)
shot("r14-11-context.png")
run("shell", "input", "keyevent", "4")
time.sleep(0.6)
# 往上滑，看「回到底部」
run("shell", "input", "swipe", "640", "1200", "640", "700", "300")
time.sleep(0.4)
run("shell", "input", "swipe", "640", "1200", "640", "700", "300")
time.sleep(0.8)
shot("r14-12-scrolled.png")
run("shell", "input", "keyevent", "3")
print("done", flush=True)
