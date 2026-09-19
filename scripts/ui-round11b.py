#!/usr/bin/env python3
"""切页往返：工作区文件 -> 返回，验证对话页回来就在最底部、且不闪。"""
import os
import subprocess
import time

ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
OUT = "/mnt/d/WSN2005/Android1/App/ADSH/build/tmp"
APP = "com.adsh.app.debug/com.adsh.app.MainActivity"


def run(*args):
    return subprocess.run([ADB, *args], capture_output=True, timeout=120)


def shot(name):
    with open(os.path.join(OUT, name), "wb") as handle:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=handle, timeout=180)
    print("shot", name, flush=True)


run("shell", "am", "start", "-n", APP)
time.sleep(3.5)
shot("r11-20-a-bottom.png")
run("shell", "input", "tap", "1204", "221")
time.sleep(1.5)
shot("r11-20-b-panel.png")
run("shell", "input", "keyevent", "4")
shot("r11-20-c-back.png")
time.sleep(0.35)
shot("r11-20-d-back.png")
time.sleep(0.7)
shot("r11-20-e-back.png")
run("shell", "input", "keyevent", "3")
print("done", flush=True)
