import re, subprocess, time
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
PKG = "com.adsh.app.debug"
ROOT = "/mnt/d/WSN2005/Android1/App/ADSH"

def sh(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True).stdout.decode("utf-8", "ignore")

def focus():
    m = re.search(r"mCurrentFocus=(\S+)\s+(\S+)\s+([^\s}]+)", sh("dumpsys", "window"))
    return m.group(3) if m else "?"

def shot(name):
    png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
    open(ROOT + "/build/tmp/" + name, "wb").write(png)
    return len(png)

# 点「添加工作区…」（预览 (110,919) → 实际 (259,2166)）
print("点添加工作区…")
sh("input", "tap", "259", "2166")
time.sleep(3.0)
print("焦点=" + focus())
print("截图 " + str(shot("v2-picker.png")))
