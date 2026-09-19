import re, subprocess, sys, time
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
PKG = "com.adsh.app.debug"
ROOT = "/mnt/d/WSN2005/Android1/App/ADSH"

def sh(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True).stdout.decode("utf-8", "ignore")

def focus():
    m = re.search(r"mCurrentFocus=(\S+)\s+(\S+)\s+([^\s}]+)", sh("dumpsys", "window"))
    return m.group(3) if m else "?"

sh("am", "start", "-n", PKG + "/com.adsh.app.MainActivity")
time.sleep(3.0)
if PKG not in focus():
    print("应用没起来：" + focus())
    sys.exit(0)

# 从屏幕中间往右滑：避开系统返回手势的边缘区（左边缘 30px 会触发返回）
sh("input", "swipe", "260", "1500", "1180", "1500", "300")
time.sleep(1.3)
png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
open(ROOT + "/build/tmp/drawer.png", "wb").write(png)
print("抽屉截图 " + str(len(png)) + " 字节")
