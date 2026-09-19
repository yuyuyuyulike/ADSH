#!/usr/bin/env python3
"""第十三轮 b：轮尾裁剪、模型分组、工具定义、回到底部按钮的现场核对。"""
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
shot("r14-00-chat.png")

# 往上滑开 → 应该出现「回到底部」按钮
run("shell", "input", "swipe", "640", "900", "640", "1800", "300")
time.sleep(1.0)
shot("r14-01-scrolled.png")

# 输入框里的模型图标（分组标题）与上下文圆环
run("shell", "input", "tap", "837", "2543")
time.sleep(1.0)
shot("r14-02-model-menu.png")
run("shell", "input", "keyevent", "4")
time.sleep(0.6)
run("shell", "input", "tap", "957", "2543")
time.sleep(1.0)
shot("r14-03-context.png")
run("shell", "input", "keyevent", "4")
time.sleep(0.6)
run("shell", "input", "keyevent", "3")
print("done", flush=True)
