import re, subprocess, sys, time
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
ROOT = "/mnt/d/WSN2005/Android1/App/ADSH"

def sh(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True).stdout.decode("utf-8", "ignore")

def win():
    out = sh("dumpsys", "window", "windows")
    return [l.strip() for l in out.splitlines() if "Window{" in l and "adsh" in l]

print("窗口列表：")
for w in win()[:8]:
    print("  " + w[:150])

# 关掉可能开着的菜单 / 弹窗，再关抽屉
sh("input", "keyevent", "4")
time.sleep(0.8)
png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
open(ROOT + "/build/tmp/after-back.png", "wb").write(png)
print("back 后截图 " + str(len(png)))
