import re, subprocess, sys, time
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
ROOT = "/mnt/d/WSN2005/Android1/App/ADSH"

def sh(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True).stdout.decode("utf-8", "ignore")

def shot(name):
    png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
    open(ROOT + "/build/tmp/" + name, "wb").write(png)
    return len(png)

# 点 adsh-ws 的垃圾桶 → 应该弹出删除确认
sh("input", "tap", "696", "670")
time.sleep(1.3)
print("确认弹窗截图 " + str(shot("confirm.png")))

# 取消 → 关抽屉 → 回到会话
sh("input", "keyevent", "4")
time.sleep(0.8)
sh("input", "keyevent", "4")
time.sleep(1.0)
print("恢复后截图 " + str(shot("restored.png")))
