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
                out.append((d or t, x1, y1, x2, y2))
    return out

def shot(name):
    png = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
    open(ROOT + "/build/tmp/" + name, "wb").write(png)
    return len(png)

def focus():
    m = re.search(r"mCurrentFocus=(\S+)\s+(\S+)\s+([^\s}]+)", sh("dumpsys", "window"))
    return m.group(3) if m else "?"

sh("am", "start", "-n", PKG + "/com.adsh.app.MainActivity")
time.sleep(3)
print("0) 当前会话（应该是空会话，chip 可见）: " + str(shot("v0-chat.png")))

print("1) 点工作区 chip（截图里的位置：预览 (95,985) → 实际 (224,2321)）")
sh("input", "tap", "224", "2321")
time.sleep(1.2)
print("   chip 菜单: " + str(shot("v1-chipmenu.png")))

hits = [h for h in find("添加工作区")]
print("   菜单项: " + str([(h[0], h[1], h[2]) for h in hits]))
if hits:
    _, x1, y1, x2, y2 = hits[0]
    sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(2.5)
    print("2) 系统文件夹选择器焦点=" + focus())
    print("   选择器: " + str(shot("v2-picker.png")))
    sh("input", "keyevent", "4")
    time.sleep(1.0)
