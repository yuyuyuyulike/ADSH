import re, subprocess, time, xml.etree.ElementTree as ET
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

def find(want):
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

# 1) 取消系统选择器
sh("input", "keyevent", "4")
time.sleep(1.2)
print("取消选择器后截图 " + str(shot("v3-back.png")))

# 2) 打开抽屉
sh("input", "swipe", "260", "1500", "1180", "1500", "300")
time.sleep(1.3)

# 3) 点会话「⋯」看菜单宽度和位置
hits = find("的操作")
print("会话行: " + str([(h[0], h[1], h[2]) for h in hits]))
if hits:
    _, x, y = hits[0]
    sh("input", "tap", str(x), str(y))
    time.sleep(1.2)
    print("菜单截图 " + str(shot("v4-menu.png")))
    sh("input", "keyevent", "4")
    time.sleep(0.8)
