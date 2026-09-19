import re, subprocess, sys, time, xml.etree.ElementTree as ET
ADB = "/mnt/d/WSN2005/Android1/platform-tools/adb.exe"
PKG = "com.adsh.app.debug"
ROOT = "/mnt/d/WSN2005/Android1/App/ADSH"

def sh(*a):
    return subprocess.run([ADB, "shell"] + list(a), capture_output=True).stdout.decode("utf-8", "ignore")

def dump():
    for _ in range(3):
        sh("rm", "-f", "/sdcard/ui.xml")
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        xml = subprocess.run([ADB, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8", "ignore")
        if xml.strip().startswith("<?xml"):
            try:
                return ET.fromstring(xml)
            except Exception:
                pass
        time.sleep(1.0)
    return None

def nodes(want):
    r = dump()
    out = []
    if r is None:
        return out
    for n in r.iter("node"):
        d = n.get("content-desc") or ""
        t = n.get("text") or ""
        if want in d or want in t:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                out.append((d or t, (x1 + x2) // 2, (y1 + y2) // 2))
    return out

def shot(name):
    png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
    open(ROOT + "/build/tmp/" + name, "wb").write(png)
    return len(png)

def tap(want, label):
    hits = nodes(want)
    if not hits:
        print("  !! 找不到 " + want)
        return False
    d, x, y = hits[0]
    sh("input", "tap", str(x), str(y))
    time.sleep(1.2)
    print("  点了 " + label + " " + d[:24] + " @(" + str(x) + "," + str(y) + ")")
    return True

sh("am", "start", "-n", PKG + "/com.adsh.app.MainActivity")
time.sleep(3)
print("1) 打开抽屉（右滑，避开边缘）")
sh("input", "swipe", "260", "1500", "1180", "1500", "300")
time.sleep(1.3)
print("2) 点会话的 ⋯")
tap("的操作", "会话菜单")
print("   菜单截图 " + str(shot("m1-menu.png")))
sh("input", "keyevent", "4")
time.sleep(0.8)
print("3) 点「新会话」")
tap("新会话", "新会话")
print("   新会话截图 " + str(shot("m2-new.png")))
print("4) 点工作区 chip")
if tap("选择工作区", "工作区 chip"):
    print("   chip 菜单截图 " + str(shot("m3-chipmenu.png")))
    print("5) 点「添加工作区…」")
    if tap("添加工作区", "添加工作区"):
        time.sleep(1.5)
        print("   系统选择器截图 " + str(shot("m4-picker.png")))
        print("   当前焦点: " + (re.search(r"mCurrentFocus=(\S+)\s+(\S+)\s+([^\s}]+)", sh("dumpsys", "window")).group(3) if True else "?"))
